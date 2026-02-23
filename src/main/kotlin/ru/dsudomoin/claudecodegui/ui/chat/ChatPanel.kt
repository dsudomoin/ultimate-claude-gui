package ru.dsudomoin.claudecodegui.ui.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.dsudomoin.claudecodegui.core.model.ContentBlock
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.StreamEvent
import ru.dsudomoin.claudecodegui.core.session.SessionManager
import ru.dsudomoin.claudecodegui.provider.claude.ClaudeProvider
import ru.dsudomoin.claudecodegui.service.SettingsService
import ru.dsudomoin.claudecodegui.ui.dialog.DiffPermissionDialog
import ru.dsudomoin.claudecodegui.ui.dialog.PermissionDialog
import ru.dsudomoin.claudecodegui.ui.dialog.QuestionSelectionPanel
import ru.dsudomoin.claudecodegui.ui.input.ChatInputPanel
import ru.dsudomoin.claudecodegui.ui.status.StatusPanel
import ru.dsudomoin.claudecodegui.ui.status.TodoItem
import ru.dsudomoin.claudecodegui.ui.status.TodoStatus
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

class ChatPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentJob: Job? = null

    private val sessionManager = SessionManager.getInstance(project)
    var sessionId: String = sessionManager.createSession()
        private set

    /** Custom title set by user (null = auto-generated from first message). */
    var customTitle: String? = null

    private var lastUsedTokens: Int = 0
    private val messageQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()

    private val provider = ClaudeProvider().apply {
        projectDir = project.basePath
    }
    private val messages = mutableListOf<Message>()
    private val messageListPanel = MessageListPanel(project)

    private val messageListWrapper = JPanel(BorderLayout()).apply {
        add(messageListPanel, BorderLayout.NORTH)
    }

    private val scrollPane = JBScrollPane(messageListWrapper).apply {
        border = JBUI.Borders.empty()
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    private val inputPanel = ChatInputPanel(
        project = project,
        onSend = ::onSendMessage,
        onStop = ::onStopGeneration
    )

    private val statusPanel = StatusPanel(project)

    /** Panel that holds the input area — swappable between inputPanel and QuestionSelectionPanel */
    private val inputContainer = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(inputPanel, BorderLayout.CENTER)
    }

    // ── Floating scroll navigation button ────────────────────────────────────

    companion object {
        private val NAV_BG = JBColor(Color(0xFF, 0xFF, 0xFF, 0xE6), Color(0x2B, 0x2B, 0x2B, 0xE6))
        private val NAV_BORDER = JBColor(Color(0x00, 0x00, 0x00, 0x1A), Color(0xFF, 0xFF, 0xFF, 0x1A))
        private val NAV_ARROW = JBColor(Color(0x55, 0x55, 0x55), Color(0xAA, 0xAA, 0xAA))
        private val NAV_HOVER_BG = JBColor(Color(0xF0, 0xF0, 0xF0, 0xF0), Color(0x3C, 0x3C, 0x3C, 0xF0))
    }

    /** true = pointing up (scroll to top), false = pointing down (scroll to bottom) */
    private var navPointsUp = false
    private var navHover = false
    private var lastScrollValue = 0
    private var scrollAnimTimer: Timer? = null
    private var pendingUpShow: Timer? = null  // delay before showing UP button

    private val scrollNavButton = object : JPanel() {
        init {
            isOpaque = false
            isVisible = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            val sz = JBUI.scale(32)
            preferredSize = Dimension(sz, sz)
            minimumSize = preferredSize
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { navHover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { navHover = false; repaint() }
                override fun mouseClicked(e: MouseEvent) { onScrollNavClick() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val arc = JBUI.scale(8).toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), arc, arc)

            // Shadow
            g2.color = Color(0, 0, 0, 20)
            g2.fill(RoundRectangle2D.Float(1f, 2f, (width - 1).toFloat(), (height - 1).toFloat(), arc, arc))

            // Background
            g2.color = if (navHover) NAV_HOVER_BG else NAV_BG
            g2.fill(shape)

            // Border
            g2.color = NAV_BORDER
            g2.stroke = BasicStroke(1f)
            g2.draw(shape)

            // Arrow
            g2.color = NAV_ARROW
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val cx = width / 2
            val cy = height / 2
            val half = JBUI.scale(5)
            if (navPointsUp) {
                g2.drawLine(cx - half, cy + half / 2, cx, cy - half / 2)
                g2.drawLine(cx, cy - half / 2, cx + half, cy + half / 2)
            } else {
                g2.drawLine(cx - half, cy - half / 2, cx, cy + half / 2)
                g2.drawLine(cx, cy + half / 2, cx + half, cy - half / 2)
            }
        }
    }

    /** Layered pane to overlay the scroll nav button on top of scrollPane. */
    private val scrollContainer = object : JLayeredPane() {
        override fun doLayout() {
            scrollPane.setBounds(0, 0, width, height)
            val btnSize = JBUI.scale(32)
            val margin = JBUI.scale(16)
            val sbWidth = if (scrollPane.verticalScrollBar.isVisible) scrollPane.verticalScrollBar.width else 0
            scrollNavButton.setBounds(
                width - btnSize - margin - sbWidth,
                height - btnSize - margin,
                btnSize, btnSize
            )
        }

        override fun getPreferredSize(): Dimension = scrollPane.preferredSize
    }.apply {
        add(scrollPane, JLayeredPane.DEFAULT_LAYER as Integer)
        add(scrollNavButton, JLayeredPane.PALETTE_LAYER as Integer)
    }

    init {
        add(scrollContainer, BorderLayout.CENTER)
        val southPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(statusPanel, BorderLayout.NORTH)
            add(inputContainer, BorderLayout.CENTER)
        }
        add(southPanel, BorderLayout.SOUTH)

        // Track scroll direction and show/hide nav button
        scrollPane.verticalScrollBar.addAdjustmentListener { e ->
            if (scrollAnimTimer?.isRunning == true) return@addAdjustmentListener

            val bar = scrollPane.verticalScrollBar
            val atTop = bar.value <= 0
            val atBottom = bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(5)
            val contentFits = bar.maximum <= bar.visibleAmount

            if (contentFits) {
                cancelPendingUpShow()
                scrollNavButton.isVisible = false
            } else {
                val direction = e.value - lastScrollValue
                if (direction < 0 && !atTop) {
                    // User scrolled up → schedule UP arrow (don't reset if already pending)
                    if (pendingUpShow == null && (!navPointsUp || !scrollNavButton.isVisible)) {
                        pendingUpShow = Timer(400) {
                            navPointsUp = true
                            scrollNavButton.isVisible = true
                            scrollNavButton.repaint()
                            pendingUpShow = null
                        }.apply { isRepeats = false; start() }
                    }
                } else if (direction > 0) {
                    // User scrolled down
                    cancelPendingUpShow()
                    if (!atBottom) {
                        navPointsUp = false
                        scrollNavButton.isVisible = true
                    } else {
                        if (!navPointsUp) scrollNavButton.isVisible = false
                    }
                } else if (atTop) {
                    cancelPendingUpShow()
                    if (navPointsUp) scrollNavButton.isVisible = false
                }
            }
            lastScrollValue = e.value
            scrollNavButton.repaint()
        }
    }

    private fun cancelPendingUpShow() {
        pendingUpShow?.stop()
        pendingUpShow = null
    }

    private fun onScrollNavClick() {
        cancelPendingUpShow()
        val target = if (navPointsUp) 0
        else scrollPane.verticalScrollBar.maximum - scrollPane.verticalScrollBar.visibleAmount
        smoothScrollTo(target)
        scrollNavButton.isVisible = false
    }

    /** Smooth animated scroll to target Y position. */
    private fun smoothScrollTo(targetY: Int) {
        scrollAnimTimer?.stop()
        val bar = scrollPane.verticalScrollBar
        val startY = bar.value
        val distance = targetY - startY
        if (distance == 0) return

        val durationMs = 500
        val intervalMs = 8
        val startTime = System.currentTimeMillis()

        scrollAnimTimer = Timer(intervalMs) {
            val elapsed = System.currentTimeMillis() - startTime
            val progress = (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)
            // ease-in-out cubic: smoother feel
            val eased = if (progress < 0.5) {
                4.0 * progress * progress * progress
            } else {
                1.0 - (-2.0 * progress + 2.0).let { it * it * it } / 2.0
            }
            val newY = startY + (distance * eased).toInt()
            bar.value = newY
            if (progress >= 1.0) {
                scrollAnimTimer?.stop()
                scrollAnimTimer = null
                bar.value = targetY
            }
        }.apply { isRepeats = true; start() }
    }

    /**
     * Public entry point for programmatic message sending (e.g., from actions).
     */
    fun sendMessage(text: String) = onSendMessage(text)

    private fun getActiveFileContext(): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val vFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
        val line = editor.caretModel.logicalPosition.line + 1
        return "Currently viewing `${vFile.path}` (line $line)"
    }

    private fun onSendMessage(text: String) {
        // If currently streaming, queue the message for later
        if (currentJob?.isActive == true) {
            messageQueue.add(text)
            return
        }
        doSendMessage(text)
    }

    private fun doSendMessage(text: String) {
        // Consume attached images
        val images = inputPanel.consumeAttachedImages()

        // Enrich text with IDE context and image paths for the API
        val sb = StringBuilder(text)

        if (!text.startsWith("From `") && !text.startsWith("File: `") && !text.startsWith("I'm working in")) {
            val ctx = getActiveFileContext()
            if (ctx != null) sb.append("\n\n_${ctx}_")
        }

        // Append image file references for the API (so Claude knows about the files)
        if (images.isNotEmpty()) {
            sb.append("\n\n_Attached screenshots:_")
            images.forEach { file ->
                sb.append("\n- `${file.absolutePath}`")
            }
        }

        val enrichedText = sb.toString()

        // Display message uses original text (without IDE context suffix)
        val displayMessage = if (images.isNotEmpty()) {
            Message.user(text, images)
        } else {
            Message.user(text)
        }
        messages.add(displayMessage)
        messageListPanel.updateMessages(messages)
        scrollToBottom()

        inputPanel.setSendingState(true)

        // Add empty assistant message as placeholder for streaming
        messages.add(Message.assistant(""))
        messageListPanel.startStreaming(messages)

        val thinkingText = StringBuilder()
        val responseText = StringBuilder()
        var thinkingCollapsed = false
        val toolUseBlocks = mutableListOf<ContentBlock.ToolUse>()

        val selectedModel = inputPanel.selectedModel
        val selectedMode = inputPanel.selectedPermissionMode
        val showThinking = inputPanel.isThinkingEnabled
        val useStreaming = inputPanel.isStreamingEnabled

        // Build API messages with enriched text for the last user message
        val apiMessages = messages.dropLast(1).toMutableList()
        if (enrichedText != text) {
            val lastUserIdx = apiMessages.indexOfLast { it.role == ru.dsudomoin.claudecodegui.core.model.Role.USER }
            if (lastUserIdx >= 0) {
                apiMessages[lastUserIdx] = if (images.isNotEmpty()) {
                    Message.user(enrichedText, images)
                } else {
                    Message.user(enrichedText)
                }
            }
        }

        currentJob = scope.launch {
            try {
                val settings = SettingsService.getInstance()
                provider.sendMessage(
                    messages = apiMessages,
                    model = selectedModel,
                    maxTokens = settings.state.maxTokens,
                    systemPrompt = settings.state.systemPrompt.ifBlank { null },
                    permissionMode = selectedMode,
                    streaming = useStreaming
                ).collect { event ->
                    when (event) {
                        is StreamEvent.ThinkingDelta -> {
                            if (!showThinking) return@collect
                            thinkingText.append(event.text)
                            val currentThinking = thinkingText.toString()
                            SwingUtilities.invokeLater {
                                messageListPanel.updateStreamingThinking(currentThinking)
                                scrollToBottom()
                            }
                        }

                        is StreamEvent.TextDelta -> {
                            if (!thinkingCollapsed && thinkingText.isNotEmpty()) {
                                thinkingCollapsed = true
                                SwingUtilities.invokeLater {
                                    messageListPanel.collapseThinking()
                                }
                            }
                            responseText.append(event.text)
                            val currentResponse = responseText.toString()
                            SwingUtilities.invokeLater {
                                messageListPanel.updateStreamingResponse(currentResponse)
                                scrollToBottom()
                            }
                        }

                        is StreamEvent.ToolUse -> {
                            if (!thinkingCollapsed && thinkingText.isNotEmpty()) {
                                thinkingCollapsed = true
                                SwingUtilities.invokeLater {
                                    messageListPanel.collapseThinking()
                                }
                            }
                            val toolBlock = ContentBlock.ToolUse(event.id, event.name, event.input)
                            toolUseBlocks.add(toolBlock)
                            val summary = MessageBubble.extractToolSummary(event.name, event.input)
                            SwingUtilities.invokeLater {
                                messageListPanel.addToolBlock(event.id, event.name, summary, event.input)
                                trackToolUseForStatus(event.id, event.name, event.input)
                                scrollToBottom()
                            }
                        }

                        is StreamEvent.ToolResult -> {
                            SwingUtilities.invokeLater {
                                if (event.isError) {
                                    messageListPanel.errorToolBlock(event.id)
                                } else {
                                    messageListPanel.completeToolBlock(event.id)
                                }
                                trackToolResultForStatus(event.id, event.isError)
                                scrollToBottom()
                            }
                        }

                        is StreamEvent.Error -> {
                            SwingUtilities.invokeLater {
                                messageListPanel.stopStreamingTimer()
                                messages[messages.lastIndex] =
                                    Message.assistant("[Error] ${event.message}")
                                messageListPanel.updateMessages(messages)
                                inputPanel.setSendingState(false)
                                scrollToBottom()
                            }
                        }

                        is StreamEvent.TextSnapshot -> {
                            if (event.text.isNotEmpty()) {
                                if (!thinkingCollapsed && thinkingText.isNotEmpty()) {
                                    thinkingCollapsed = true
                                    SwingUtilities.invokeLater {
                                        messageListPanel.collapseThinking()
                                    }
                                }
                                responseText.clear()
                                responseText.append(event.text)
                                val currentResponse = responseText.toString()
                                SwingUtilities.invokeLater {
                                    messageListPanel.updateStreamingResponse(currentResponse)
                                    scrollToBottom()
                                }
                            }
                        }

                        is StreamEvent.ThinkingSnapshot -> {
                            if (!showThinking) return@collect
                            if (event.text.isNotEmpty()) {
                                thinkingText.clear()
                                thinkingText.append(event.text)
                                val currentThinking = thinkingText.toString()
                                SwingUtilities.invokeLater {
                                    messageListPanel.updateStreamingThinking(currentThinking)
                                    scrollToBottom()
                                }
                            }
                        }

                        is StreamEvent.StreamEnd -> {
                            val blocks = buildAssistantBlocks(thinkingText, responseText, toolUseBlocks)
                            SwingUtilities.invokeLater {
                                messageListPanel.stopStreamingTimer()
                                messages[messages.lastIndex] = Message.assistant(blocks)
                                messageListPanel.updateMessages(messages)
                                inputPanel.setSendingState(false)
                                scrollToBottom()
                                processQueue()
                            }
                            // Auto-save session
                            saveSession()
                        }

                        is StreamEvent.Usage -> {
                            lastUsedTokens = event.inputTokens + event.outputTokens + event.cacheRead
                            SwingUtilities.invokeLater {
                                inputPanel.updateContextUsage(event.inputTokens, event.outputTokens, event.cacheRead)
                            }
                        }

                        is StreamEvent.PermissionRequest -> {
                            val lower = event.toolName.lowercase()
                            if (lower == "askuserquestion" || lower == "ask_user_question") {
                                // Show inline question selection UI
                                val questionsArray = event.input["questions"]?.jsonArray
                                if (questionsArray != null) {
                                    val deferred = CompletableDeferred<JsonObject?>()
                                    SwingUtilities.invokeLater {
                                        showQuestionPanel(questionsArray) { result ->
                                            deferred.complete(result)
                                        }
                                    }
                                    val result = deferred.await()
                                    if (result != null) {
                                        provider.sendPermissionResponseWithInput(true, result)
                                    } else {
                                        provider.sendPermissionResponse(false, "User cancelled")
                                    }
                                } else {
                                    provider.sendPermissionResponse(true)
                                }
                            } else {
                                val deferred = CompletableDeferred<Boolean>()
                                val isFileTool = lower in setOf(
                                    "edit", "edit_file", "replace_string",
                                    "write", "write_to_file", "create_file"
                                )
                                SwingUtilities.invokeLater {
                                    val allowed = if (isFileTool) {
                                        DiffPermissionDialog(project, event.toolName, event.input).showAndGet()
                                    } else {
                                        PermissionDialog(project, event.toolName, event.input).showAndGet()
                                    }
                                    deferred.complete(allowed)
                                }
                                val allowed = deferred.await()
                                provider.sendPermissionResponse(allowed)
                            }
                        }

                        else -> { /* ignore other events */ }
                    }
                }
            } catch (e: CancellationException) {
                SwingUtilities.invokeLater {
                    messageListPanel.stopStreamingTimer()
                    if (responseText.isNotBlank() || thinkingText.isNotBlank() || toolUseBlocks.isNotEmpty()) {
                        val blocks = buildAssistantBlocks(thinkingText, responseText, toolUseBlocks, stopped = true)
                        messages[messages.lastIndex] = Message.assistant(blocks)
                    } else {
                        messages.removeAt(messages.lastIndex)
                    }
                    messageListPanel.updateMessages(messages)
                    inputPanel.setSendingState(false)
                    scrollToBottom()
                    processQueue()
                }
                // Save on cancel too
                saveSession()
            }
        }
    }

    private fun processQueue() {
        val next = messageQueue.poll() ?: return
        doSendMessage(next)
    }

    private fun onStopGeneration() {
        provider.abort()
        currentJob?.cancel()
        currentJob = null
    }

    fun newChat() {
        onStopGeneration()
        messages.clear()
        provider.resetSession()
        sessionId = sessionManager.createSession()
        customTitle = null
        lastUsedTokens = 0
        messageListPanel.updateMessages(messages)
        inputPanel.updateContextUsage(0, 0, 0)
        statusPanel.clear()
    }

    /** Load an existing session into this panel. */
    fun loadSession(loadSessionId: String) {
        onStopGeneration()
        val loaded = sessionManager.load(loadSessionId) ?: return
        messages.clear()
        messages.addAll(loaded)
        sessionId = loadSessionId
        customTitle = sessionManager.getTitle(loadSessionId)
        lastUsedTokens = sessionManager.getUsedTokens(loadSessionId)
        provider.resetSession()
        messageListPanel.updateMessages(messages)
        scrollToBottom()
        // Restore context usage indicator
        inputPanel.updateContextUsage(lastUsedTokens, 0, 0)
    }

    /** Get session title: custom title if set, otherwise auto-generated from first message. */
    val sessionTitle: String
        get() {
            customTitle?.let { return it }
            val firstUser = messages.firstOrNull {
                it.role == ru.dsudomoin.claudecodegui.core.model.Role.USER
            } ?: return "New Chat"
            val text = firstUser.textContent
                .lineSequence()
                .firstOrNull { it.isNotBlank() && !it.startsWith("_") }
                ?.take(40) ?: return "New Chat"
            return if (text.length >= 40) "${text.take(37)}..." else text.ifBlank { "New Chat" }
        }

    private fun saveSession() {
        if (messages.isNotEmpty()) {
            val tokens = lastUsedTokens
            scope.launch {
                sessionManager.save(sessionId, messages, customTitle, tokens)
            }
        }
    }

    private fun scrollToBottom() {
        SwingUtilities.invokeLater {
            // Reset horizontal scroll to prevent left-side text clipping
            scrollPane.viewport.viewPosition = java.awt.Point(
                0,
                scrollPane.viewport.viewSize.height - scrollPane.viewport.extentSize.height
            )
        }
    }

    private fun buildAssistantBlocks(
        thinkingText: StringBuilder,
        responseText: StringBuilder,
        toolUseBlocks: List<ContentBlock.ToolUse> = emptyList(),
        stopped: Boolean = false
    ): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        if (thinkingText.isNotEmpty()) {
            blocks.add(ContentBlock.Thinking(thinkingText.toString()))
        }
        // Add tool use blocks
        blocks.addAll(toolUseBlocks)

        val text = if (stopped && responseText.isNotEmpty()) {
            "${responseText}\n\n[Stopped]"
        } else if (stopped) {
            "[Stopped]"
        } else {
            responseText.toString()
        }
        if (text.isNotBlank()) {
            blocks.add(ContentBlock.Text(text))
        }
        return blocks
    }

    private fun showQuestionPanel(questions: JsonArray, onResult: (JsonObject?) -> Unit) {
        inputContainer.removeAll()
        val panel = QuestionSelectionPanel(
            questions = questions,
            onSubmit = { updatedInput ->
                hideQuestionPanel()
                onResult(updatedInput)
            },
            onCancel = {
                hideQuestionPanel()
                onResult(null)
            }
        )
        inputContainer.add(panel, BorderLayout.CENTER)
        inputContainer.revalidate()
        inputContainer.repaint()
    }

    private fun hideQuestionPanel() {
        inputContainer.removeAll()
        inputContainer.add(inputPanel, BorderLayout.CENTER)
        inputContainer.revalidate()
        inputContainer.repaint()
    }

    private fun trackToolUseForStatus(id: String, name: String, input: JsonObject) {
        val lower = name.lowercase()
        when {
            lower == "todowrite" || lower == "todo_write" -> {
                val todosArray = input["todos"]?.jsonArray ?: return
                val items = todosArray.mapNotNull { element ->
                    val obj = element.jsonObject
                    val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val statusStr = obj["status"]?.jsonPrimitive?.contentOrNull ?: "pending"
                    val status = when (statusStr.lowercase()) {
                        "in_progress" -> TodoStatus.IN_PROGRESS
                        "completed" -> TodoStatus.COMPLETED
                        else -> TodoStatus.PENDING
                    }
                    TodoItem(content, status)
                }
                statusPanel.updateTodos(items)
            }
            lower in setOf("edit", "edit_file", "replace_string") -> {
                val filePath = input["file_path"]?.jsonPrimitive?.contentOrNull
                    ?: input["path"]?.jsonPrimitive?.contentOrNull ?: return
                val oldString = input["old_string"]?.jsonPrimitive?.contentOrNull
                    ?: input["old_str"]?.jsonPrimitive?.contentOrNull ?: ""
                val newString = input["new_string"]?.jsonPrimitive?.contentOrNull
                    ?: input["new_str"]?.jsonPrimitive?.contentOrNull ?: ""
                statusPanel.trackFileChange(name, filePath, oldString, newString)
            }
            lower in setOf("write", "write_to_file", "create_file") -> {
                val filePath = input["file_path"]?.jsonPrimitive?.contentOrNull
                    ?: input["path"]?.jsonPrimitive?.contentOrNull ?: return
                val content = input["content"]?.jsonPrimitive?.contentOrNull
                    ?: input["file_text"]?.jsonPrimitive?.contentOrNull ?: ""
                val isNew = !java.io.File(filePath).exists()
                statusPanel.trackFileWrite(filePath, content, isNew)
            }
            lower == "task" -> {
                val type = input["subagent_type"]?.jsonPrimitive?.contentOrNull
                    ?: input["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val description = input["description"]?.jsonPrimitive?.contentOrNull
                    ?: input["prompt"]?.jsonPrimitive?.contentOrNull?.take(80) ?: ""
                statusPanel.trackSubagent(id, type, description)
            }
        }
    }

    private fun trackToolResultForStatus(id: String, isError: Boolean) {
        statusPanel.completeSubagent(id, isError)
    }

    override fun dispose() {
        scrollAnimTimer?.stop()
        cancelPendingUpShow()
        currentJob?.cancel()
        scope.cancel()
        provider.close()
    }
}
