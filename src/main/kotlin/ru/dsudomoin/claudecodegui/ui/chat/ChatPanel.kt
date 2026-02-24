package ru.dsudomoin.claudecodegui.ui.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
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
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.command.CommandCategory
import ru.dsudomoin.claudecodegui.command.SlashCommandRegistry
import ru.dsudomoin.claudecodegui.core.model.ContentBlock
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.StreamEvent
import ru.dsudomoin.claudecodegui.core.session.SessionManager
import ru.dsudomoin.claudecodegui.provider.claude.ClaudeProvider
import ru.dsudomoin.claudecodegui.service.SettingsService
import ru.dsudomoin.claudecodegui.ui.approval.ApprovalPanelFactory
import ru.dsudomoin.claudecodegui.ui.dialog.DiffPermissionDialog
import ru.dsudomoin.claudecodegui.ui.dialog.PermissionDialog
import ru.dsudomoin.claudecodegui.ui.dialog.PlanActionPanel
import ru.dsudomoin.claudecodegui.ui.dialog.QuestionSelectionPanel
import ru.dsudomoin.claudecodegui.ui.input.ChatInputPanel
import ru.dsudomoin.claudecodegui.ui.status.StatusPanel
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import ru.dsudomoin.claudecodegui.ui.status.TodoItem
import ru.dsudomoin.claudecodegui.ui.status.TodoStatus
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import javax.swing.BoxLayout
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
    var sessionId: String? = null
        private set

    /** Custom title set by user (null = auto-generated from first message). */
    var customTitle: String? = null

    private var lastUsedTokens: Int = 0
    data class QueuedMessage(
        val text: String,
        val images: List<File>,
        val fileMentions: List<ru.dsudomoin.claudecodegui.service.ProjectFileIndexService.FileEntry> = emptyList()
    )

    private val messageQueue = java.util.concurrent.ConcurrentLinkedQueue<QueuedMessage>()

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

    private val queuePanel = QueuePanel(onRemove = ::removeFromQueue)

    /** Panel that holds the input area — swappable between inputPanel and QuestionSelectionPanel */
    private val inputContainer = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(inputPanel, BorderLayout.CENTER)
    }

    // ── Floating scroll navigation button ────────────────────────────────────

    companion object {
        private val log = Logger.getInstance(ChatPanel::class.java)
        private val NAV_BG get() = ThemeColors.navBg
        private val NAV_BORDER get() = ThemeColors.navBorder
        private val NAV_ARROW get() = ThemeColors.navArrow
        private val NAV_HOVER_BG get() = ThemeColors.navHoverBg
    }

    /** true = pointing up (scroll to top), false = pointing down (scroll to bottom) */
    private var navPointsUp = false
    private var navHover = false
    private var lastScrollValue = 0
    private var scrollAnimTimer: Timer? = null
    private var pendingUpShow: Timer? = null  // delay before showing UP button
    private var autoScrollToBottom = true
    private var programmaticScroll = false

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
            val topSection = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(statusPanel)
                add(queuePanel)
            }
            add(topSection, BorderLayout.NORTH)
            add(inputContainer, BorderLayout.CENTER)
        }
        add(southPanel, BorderLayout.SOUTH)

        // Preload slash commands from SDK in background
        if (!SlashCommandRegistry.isLoaded) {
            scope.launch {
                val commands = provider.fetchSlashCommands()
                if (commands.isNotEmpty()) {
                    SlashCommandRegistry.updateSdkCommands(commands)
                }
            }
        }

        // Track scroll direction and show/hide nav button
        // Show welcome screen on initial load
        messageListPanel.updateMessages(messages)

        scrollPane.verticalScrollBar.addAdjustmentListener { e ->
            if (scrollAnimTimer?.isRunning == true) return@addAdjustmentListener

            val bar = scrollPane.verticalScrollBar
            val atTop = bar.value <= 0
            val atBottom = bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(20)
            val contentFits = bar.maximum <= bar.visibleAmount

            // Track user scroll-away during streaming
            if (!programmaticScroll && currentJob?.isActive == true) {
                autoScrollToBottom = atBottom
            }

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
        if (navPointsUp) {
            smoothScrollTo(0)
        } else {
            autoScrollToBottom = true
            smoothScrollTo(scrollPane.verticalScrollBar.maximum - scrollPane.verticalScrollBar.visibleAmount)
        }
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
        // Slash command routing
        val trimmed = text.trim()
        if (trimmed.startsWith("/")) {
            val cmdName = trimmed.split(" ", limit = 2).first()
            val cmd = SlashCommandRegistry.find(cmdName)
            if (cmd != null && cmd.category == CommandCategory.LOCAL) {
                executeLocalCommand(cmd)
                return
            }
            // SDK commands fall through to doSendMessage
        }

        // If currently streaming, queue the message for later (capture images + mentions now)
        if (currentJob?.isActive == true) {
            val images = inputPanel.consumeAttachedImages()
            val mentions = inputPanel.consumeFileMentions()
            messageQueue.add(QueuedMessage(text, images, mentions))
            queuePanel.rebuild(messageQueue.toList())
            return
        }
        doSendMessage(text)
    }

    private fun executeLocalCommand(cmd: ru.dsudomoin.claudecodegui.command.SlashCommand) {
        when {
            cmd.name in SlashCommandRegistry.NEW_SESSION_ALIASES -> {
                newChat()
                messageListPanel.addSystemMessage(UcuBundle.message("cmd.clear.done"))
            }
            cmd.name == "/help" -> {
                val sb = StringBuilder(UcuBundle.message("cmd.help.title"))
                SlashCommandRegistry.all().forEach { c ->
                    sb.append("\n  ${c.name} — ${c.description}")
                }
                messageListPanel.addSystemMessage(sb.toString())
            }
        }
    }

    private fun doSendMessage(
        text: String,
        preAttachedImages: List<File>? = null,
        preFileMentions: List<ru.dsudomoin.claudecodegui.service.ProjectFileIndexService.FileEntry>? = null
    ) {
        autoScrollToBottom = true

        // Use pre-attached images/mentions (from queue) or consume from input panel
        val images = preAttachedImages ?: inputPanel.consumeAttachedImages()
        val fileMentions = preFileMentions ?: inputPanel.consumeFileMentions()

        // Enrich text with IDE context and image paths for the API
        val sb = StringBuilder(text)

        if (!text.startsWith("/") && !text.startsWith("From `") && !text.startsWith("File: `") && !text.startsWith("I'm working in")) {
            val ctx = getActiveFileContext()
            if (ctx != null) sb.append("\n\n_${ctx}_")
        }

        // Append referenced file contents for Claude context
        if (fileMentions.isNotEmpty()) {
            sb.append("\n\n_Referenced files:_")
            for (mention in fileMentions) {
                val label = if (mention.source == ru.dsudomoin.claudecodegui.service.ProjectFileIndexService.FileSource.LIBRARY) {
                    "${mention.fileName} (${mention.libraryName ?: mention.relativePath})"
                } else {
                    mention.relativePath
                }
                sb.append("\n\nFile: `$label`")
                try {
                    // Read via VFS to support files inside JARs/ZIPs (library sources)
                    val content = String(mention.virtualFile.contentsToByteArray(), mention.virtualFile.charset)
                    val truncated = if (content.length > 50_000) {
                        content.take(50_000) + "\n... (truncated, ${content.length} chars total)"
                    } else content
                    sb.append("\n```\n$truncated\n```")
                } catch (_: Exception) {
                    sb.append("\n(Could not read file)")
                }
            }
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
        scrollToBottom()

        val thinkingText = StringBuilder()
        val responseText = StringBuilder()
        var thinkingCollapsed = false
        val toolUseBlocks = mutableListOf<ContentBlock.ToolUse>()
        val toolResults = mutableMapOf<String, Pair<String, Boolean>>()
        val textCheckpoints = mutableListOf<Int>()
        var inPlanMode = false
        var lastPlanDecision: Boolean? = null

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
                            // In plan mode: accumulate text but don't stream to UI
                            if (!inPlanMode) {
                                val currentResponse = responseText.toString()
                                SwingUtilities.invokeLater {
                                    messageListPanel.updateStreamingResponse(currentResponse)
                                    scrollToBottom()
                                }
                            }
                        }

                        is StreamEvent.PlanModeEnter -> {
                            // SDK hides EnterPlanMode from stream events, so this rarely fires.
                            // If it does, suppress text streaming until ExitPlanMode.
                            log.info("PlanModeEnter: suppressing text streaming")
                            inPlanMode = true
                        }

                        is StreamEvent.PlanModeExit -> {
                            log.info("PlanModeExit: rendering plan")
                            inPlanMode = false
                            try {
                                val planMarkdown = responseText.toString()
                                log.info("PlanModeExit: planMarkdown length=${planMarkdown.length}")

                                // Build display blocks without text (plan rendered as single HTML block)
                                val displayBlocks = mutableListOf<ContentBlock>()
                                if (thinkingText.isNotEmpty()) {
                                    displayBlocks.add(ContentBlock.Thinking(thinkingText.toString()))
                                }
                                displayBlocks.addAll(toolUseBlocks)

                                SwingUtilities.invokeAndWait {
                                    messageListPanel.stopStreamingTimer()
                                    messages[messages.lastIndex] = Message.assistant(displayBlocks)
                                    messageListPanel.updateMessages(messages)
                                    if (planMarkdown.isNotBlank()) {
                                        messageListPanel.addPlanBlock(planMarkdown)
                                    }
                                    scrollToBottom()
                                }

                                val deferred = CompletableDeferred<Boolean>()
                                SwingUtilities.invokeLater {
                                    showPlanActionButtons { approved ->
                                        deferred.complete(approved)
                                    }
                                }
                                log.info("PlanModeExit: awaiting user decision")
                                val allowed = deferred.await()
                                lastPlanDecision = allowed
                                log.info("PlanModeExit: user decided allowed=$allowed")

                                // Restore full message content (with text) for session saving
                                SwingUtilities.invokeAndWait {
                                    if (planMarkdown.isNotBlank()) {
                                        val fullBlocks = displayBlocks.toMutableList()
                                        fullBlocks.add(ContentBlock.Text(planMarkdown))
                                        messages[messages.lastIndex] = Message.assistant(fullBlocks)
                                    }
                                    messages.add(Message.assistant(""))
                                    messageListPanel.startStreaming(messages)
                                }
                                thinkingText.clear()
                                responseText.clear()
                                thinkingCollapsed = false
                                toolUseBlocks.clear()
                                textCheckpoints.clear()
                            } catch (e: Exception) {
                                log.error("PlanModeExit handler failed", e)
                            }
                        }

                        is StreamEvent.ToolUse -> {
                            // Filter out plan mode tools — handled via PlanModeExit
                            val lowerName = event.name.lowercase()
                            if (lowerName == "exitplanmode" || lowerName == "enterplanmode") {
                                return@collect
                            }
                            if (!thinkingCollapsed && thinkingText.isNotEmpty()) {
                                thinkingCollapsed = true
                                SwingUtilities.invokeLater {
                                    messageListPanel.collapseThinking()
                                }
                            }
                            val toolBlock = ContentBlock.ToolUse(event.id, event.name, event.input)
                            toolUseBlocks.add(toolBlock)
                            textCheckpoints.add(responseText.length)
                            val summary = MessageBubble.extractToolSummary(event.name, event.input)
                            SwingUtilities.invokeLater {
                                messageListPanel.addToolBlock(event.id, event.name, summary, event.input)
                                trackToolUseForStatus(event.id, event.name, event.input)
                                scrollToBottom()
                            }
                        }

                        is StreamEvent.ToolResult -> {
                            toolResults[event.id] = event.content to event.isError
                            SwingUtilities.invokeLater {
                                if (event.isError) {
                                    messageListPanel.errorToolBlock(event.id, event.content)
                                } else {
                                    messageListPanel.completeToolBlock(event.id, event.content)
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
                                // In plan mode: accumulate but don't stream to UI
                                if (!inPlanMode) {
                                    val currentResponse = responseText.toString()
                                    SwingUtilities.invokeLater {
                                        messageListPanel.updateStreamingResponse(currentResponse)
                                        scrollToBottom()
                                    }
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
                            // Capture sessionId from provider (SDK assigns it)
                            sessionId = provider.sessionId
                            val blocks = buildAssistantBlocks(thinkingText, responseText, toolUseBlocks, toolResults, textCheckpoints)
                            SwingUtilities.invokeLater {
                                messageListPanel.stopStreamingTimer()
                                messages[messages.lastIndex] = Message.assistant(blocks)
                                messageListPanel.updateMessages(messages)
                                inputPanel.setSendingState(false)
                                scrollToBottom()
                                processQueue()
                            }
                        }

                        is StreamEvent.Usage -> {
                            lastUsedTokens = event.inputTokens + event.cacheCreation + event.cacheRead
                            SwingUtilities.invokeLater {
                                inputPanel.updateContextUsage(event.inputTokens, event.cacheCreation, event.cacheRead)
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
                            } else if (lower == "exitplanmode" || lower == "enterplanmode") {
                                // Use the user's decision from PlanModeExit handler (if available)
                                val decision = lastPlanDecision ?: true
                                lastPlanDecision = null
                                log.info("$lower: PermissionRequest received (decision=$decision)")
                                if (decision) {
                                    provider.sendPermissionResponse(true)
                                } else {
                                    provider.sendPermissionResponse(false, "User rejected the plan")
                                }
                            } else {
                                val deferred = CompletableDeferred<Boolean>()
                                val approvalRequest = ApprovalPanelFactory.classifyTool(event.toolName, event.input)
                                SwingUtilities.invokeLater {
                                    val panel = ApprovalPanelFactory.create(
                                        project = project,
                                        request = approvalRequest,
                                        onApprove = { _ -> deferred.complete(true) },
                                        onReject = { deferred.complete(false) }
                                    )
                                    messageListPanel.addInlineApprovalPanel(panel)
                                    scrollToBottom()
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
                        val blocks = buildAssistantBlocks(thinkingText, responseText, toolUseBlocks, toolResults, textCheckpoints, stopped = true)
                        messages[messages.lastIndex] = Message.assistant(blocks)
                    } else {
                        messages.removeAt(messages.lastIndex)
                    }
                    messageListPanel.updateMessages(messages)
                    inputPanel.setSendingState(false)
                    scrollToBottom()
                    processQueue()
                }
            }
        }
    }

    private fun processQueue() {
        val next = messageQueue.poll() ?: return
        queuePanel.rebuild(messageQueue.toList())
        doSendMessage(next.text, next.images, next.fileMentions)
    }

    private fun removeFromQueue(index: Int) {
        val list = messageQueue.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            messageQueue.clear()
            list.forEach { messageQueue.add(it) }
            queuePanel.rebuild(messageQueue.toList())
        }
    }

    private fun onStopGeneration() {
        provider.abort()
        currentJob?.cancel()
        currentJob = null
    }

    fun newChat() {
        onStopGeneration()
        messageQueue.clear()
        queuePanel.rebuild(emptyList())
        messages.clear()
        provider.resetSession()
        sessionId = null
        customTitle = null
        lastUsedTokens = 0
        messageListPanel.updateMessages(messages)
        inputPanel.updateContextUsage(0, 0, 0)
        statusPanel.clear()
    }

    /** Load an existing session into this panel (resume from CLI storage). */
    fun loadSession(loadSessionId: String) {
        autoScrollToBottom = true
        onStopGeneration()
        val loaded = sessionManager.load(loadSessionId) ?: return
        messages.clear()
        messages.addAll(loaded)
        sessionId = loadSessionId
        customTitle = sessionManager.getTitle(loadSessionId)
        // Set resume sessionId so the next message continues this session in SDK
        provider.setResumeSessionId(loadSessionId)
        messageListPanel.updateMessages(messages)
        // Restore context usage from last assistant message in JSONL
        val usage = sessionManager.getLastUsage(loadSessionId)
        if (usage != null) {
            lastUsedTokens = usage.first + usage.second + usage.third
            inputPanel.updateContextUsage(usage.first, usage.second, usage.third)
        }
        scrollToBottom()
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

    private fun scrollToBottom() {
        if (!autoScrollToBottom) return
        SwingUtilities.invokeLater {
            scrollPane.validate()
            programmaticScroll = true
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
            programmaticScroll = false
        }
    }

    private fun buildAssistantBlocks(
        thinkingText: StringBuilder,
        responseText: StringBuilder,
        toolUseBlocks: List<ContentBlock.ToolUse> = emptyList(),
        toolResults: Map<String, Pair<String, Boolean>> = emptyMap(),
        textCheckpoints: List<Int> = emptyList(),
        stopped: Boolean = false
    ): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        if (thinkingText.isNotEmpty()) {
            blocks.add(ContentBlock.Thinking(thinkingText.toString()))
        }

        val fullText = responseText.toString()
        val suffix = if (stopped) "\n\n[Stopped]" else ""

        // Interleave text segments with tool blocks in document order
        var prevIdx = 0
        for (i in toolUseBlocks.indices) {
            val checkpoint = if (i < textCheckpoints.size) textCheckpoints[i].coerceAtMost(fullText.length) else fullText.length
            val segment = fullText.substring(prevIdx, checkpoint)
            if (segment.isNotBlank()) {
                blocks.add(ContentBlock.Text(segment))
            }
            val toolUse = toolUseBlocks[i]
            blocks.add(toolUse)
            // Add corresponding tool result if available
            toolResults[toolUse.id]?.let { (content, isError) ->
                if (content.isNotBlank()) {
                    blocks.add(ContentBlock.ToolResult(toolUseId = toolUse.id, content = content, isError = isError))
                }
            }
            prevIdx = checkpoint
        }

        // Remaining text after last tool block
        val remaining = fullText.substring(prevIdx.coerceAtMost(fullText.length)) + suffix
        if (remaining.isNotBlank()) {
            blocks.add(ContentBlock.Text(remaining))
        } else if (suffix.isNotBlank()) {
            blocks.add(ContentBlock.Text(suffix))
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

    private fun showPlanActionButtons(onResult: (Boolean) -> Unit) {
        inputContainer.removeAll()
        val panel = PlanActionPanel(
            onApprove = {
                hidePlanPanel()
                onResult(true)
            },
            onDeny = {
                hidePlanPanel()
                onResult(false)
            }
        )
        inputContainer.add(panel, BorderLayout.CENTER)
        inputContainer.revalidate()
        inputContainer.repaint()
    }

    private fun hidePlanPanel() {
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
        statusPanel.dispose()
    }
}
