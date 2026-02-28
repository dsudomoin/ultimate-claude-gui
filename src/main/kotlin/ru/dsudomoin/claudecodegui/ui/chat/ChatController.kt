package ru.dsudomoin.claudecodegui.ui.chat

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.dsudomoin.claudecodegui.bridge.BridgeManager
import ru.dsudomoin.claudecodegui.bridge.NodeDetector
import ru.dsudomoin.claudecodegui.bridge.NodeState
import ru.dsudomoin.claudecodegui.bridge.SetupChecker
import ru.dsudomoin.claudecodegui.command.CommandCategory
import ru.dsudomoin.claudecodegui.command.SlashCommandRegistry
import ru.dsudomoin.claudecodegui.core.model.ContentBlock
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.Role
import ru.dsudomoin.claudecodegui.core.model.StatusTracker
import ru.dsudomoin.claudecodegui.core.model.StreamEvent
import ru.dsudomoin.claudecodegui.core.model.ToolSummaryExtractor
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.core.session.SessionManager
import ru.dsudomoin.claudecodegui.provider.claude.ClaudeProvider
import ru.dsudomoin.claudecodegui.service.SettingsService
import ru.dsudomoin.claudecodegui.ui.approval.ApprovalPanelFactory
import ru.dsudomoin.claudecodegui.ui.compose.chat.ChatViewModel
import ru.dsudomoin.claudecodegui.ui.compose.chat.ContentFlowItem
import ru.dsudomoin.claudecodegui.ui.compose.chat.ExpandableContent
import ru.dsudomoin.claudecodegui.ui.compose.chat.QueueItemDisplayData
import ru.dsudomoin.claudecodegui.ui.compose.chat.ToolCategoryType
import ru.dsudomoin.claudecodegui.ui.compose.chat.ToolGroupData
import ru.dsudomoin.claudecodegui.ui.compose.chat.ToolGroupItemData
import ru.dsudomoin.claudecodegui.ui.compose.chat.ToolStatus
import ru.dsudomoin.claudecodegui.ui.compose.chat.ToolUseData
import ru.dsudomoin.claudecodegui.ui.compose.chat.computeDiffStats
import ru.dsudomoin.claudecodegui.ui.compose.dialog.OptionData
import ru.dsudomoin.claudecodegui.ui.compose.dialog.QuestionData
import ru.dsudomoin.claudecodegui.ui.compose.input.ContextUsageData
import ru.dsudomoin.claudecodegui.ui.status.TodoItem
import ru.dsudomoin.claudecodegui.ui.status.TodoStatus
import java.io.File

/**
 * Non-UI business logic controller for the chat.
 * Manages provider communication, message streaming, session lifecycle.
 * All UI state is exposed through [viewModel] which the Compose layer observes.
 */
class ChatController(
    private val project: Project
) : Disposable {

    companion object {
        private val log = Logger.getInstance(ChatController::class.java)
        private val URL_PATTERN = Regex("https?://\\S+")
    }

    /** EDT dispatcher — avoids dependency on kotlinx-coroutines-swing (classloader conflicts). */
    private val edtDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            javax.swing.SwingUtilities.invokeLater(block)
        }
    }

    /**
     * Scope runs on EDT by default — all viewModel updates and IntelliJ API calls are safe.
     * Heavy IO operations must be explicitly wrapped in withContext(Dispatchers.IO).
     */
    private val scope = CoroutineScope(SupervisorJob() + edtDispatcher)
    private var currentJob: Job? = null
    /** Debounced rebuild of the streaming content flow (avoids O(n) work on every TextDelta). */
    private var contentFlowRebuildJob: Job? = null

    private val sessionManager = SessionManager.getInstance(project)
    var sessionId: String? = null
        private set

    var customTitle: String? = null

    private var lastUsedTokens: Int = 0

    data class QueuedMessage(
        val text: String,
        val images: List<File>,
        val fileMentions: List<ru.dsudomoin.claudecodegui.service.ProjectFileIndexService.FileEntry> = emptyList()
    )

    private val messageQueue = java.util.concurrent.ConcurrentLinkedQueue<QueuedMessage>()

    val viewModel = ChatViewModel()

    /** Called when session title changes (after first response or session load). */
    var onSessionTitleChanged: ((String) -> Unit)? = null

    private var questionResultCallback: ((JsonObject?) -> Unit)? = null
    private var questionOriginalJson: JsonArray? = null
    enum class PlanResult { APPROVED, APPROVED_COMPACT, DENIED }
    private var planResultCallback: ((PlanResult) -> Unit)? = null
    private var approvalResultCallback: ((Boolean) -> Unit)? = null

    private val provider = ClaudeProvider().apply {
        projectDir = project.basePath
    }
    private val messages = mutableListOf<Message>()
    private val statusTracker = StatusTracker()

    /** Convenience: switch to IO for heavy operations. */
    private suspend fun <T> onIO(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    init {
        // Check setup status on launch
        scope.launch { checkSetup() }

        // Preload slash commands from SDK in background
        if (!SlashCommandRegistry.isLoaded) {
            scope.launch {
                val commands = onIO { provider.fetchSlashCommands() }
                if (commands.isNotEmpty()) {
                    SlashCommandRegistry.updateSdkCommands(commands)
                }
            }
        }

        // Check for SDK updates in background
        scope.launch {
            checkSdkUpdate()
        }
    }

    private suspend fun checkSetup() {
        val status = onIO { SetupChecker.check() }

        // If detected Node is wrong version, try to find compatible LTS
        if (status.node.state == NodeState.WRONG_VERSION) {
            val compatiblePath = onIO { NodeDetector.findCompatibleLtsNode() }
            if (compatiblePath != null) {
                val version = onIO { NodeDetector.detectVersion(compatiblePath) }
                if (version != null && NodeDetector.isCompatibleVersion(version)) {
                    // Auto-save compatible Node path
                    try {
                        val settings = SettingsService.getInstance()
                        settings.state.nodePath = compatiblePath
                        log.info("Auto-selected compatible Node.js: $compatiblePath ($version)")
                    } catch (_: Exception) { }
                    // Re-check with the new path
                    val rechecked = onIO { SetupChecker.check() }
                    viewModel.setupStatus = rechecked
                    return
                }
            }
        }

        viewModel.setupStatus = status
    }

    private suspend fun checkSdkUpdate() {
        try {
            val currentVersion = onIO { BridgeManager.detectSdkVersion() }
            viewModel.sdkCurrentVersion = currentVersion

            if (currentVersion == null) return

            val latestVersion = onIO { BridgeManager.checkLatestSdkVersion() }
            viewModel.sdkLatestVersion = latestVersion
            viewModel.sdkUpdateAvailable = latestVersion != null && latestVersion != currentVersion
        } catch (e: Exception) {
            log.warn("SDK update check failed: ${e.message}")
        }
    }

    fun updateSdk() {
        scope.launch {
            viewModel.sdkUpdating = true
            viewModel.sdkUpdateError = null
            try {
                onIO { BridgeManager.updateSdk() }
                val newVersion = onIO { BridgeManager.detectSdkVersion() }
                viewModel.sdkCurrentVersion = newVersion
                viewModel.sdkUpdateAvailable = false
                viewModel.sdkLatestVersion = newVersion
                log.info("SDK updated to $newVersion")
            } catch (e: Exception) {
                log.error("SDK update failed", e)
                viewModel.sdkUpdateError = e.message ?: "Unknown error"
            } finally {
                viewModel.sdkUpdating = false
            }
        }
    }

    fun installSdk() {
        scope.launch {
            viewModel.setupInstalling = true
            viewModel.setupError = null
            try {
                val ready = onIO { BridgeManager.ensureReady() }
                if (ready) {
                    log.info("SDK installed successfully")
                    checkSetup()
                } else {
                    viewModel.setupError = "Installation failed. Check Node.js is available."
                }
            } catch (e: Exception) {
                log.error("SDK installation failed", e)
                viewModel.setupError = e.message ?: "Unknown error"
            } finally {
                viewModel.setupInstalling = false
            }
        }
    }

    fun installNode() {
        scope.launch {
            viewModel.setupInstalling = true
            viewModel.setupError = null
            try {
                val home = System.getProperty("user.home")
                val isWin = System.getProperty("os.name").lowercase().contains("win")
                val status = viewModel.setupStatus

                val process = when {
                    status?.node?.nvmAvailable == true -> {
                        if (isWin) {
                            ProcessBuilder("cmd", "/c", "nvm install lts && nvm use lts")
                        } else {
                            ProcessBuilder("bash", "-lc",
                                "source \"$home/.nvm/nvm.sh\" && nvm install --lts")
                        }
                    }
                    status?.node?.fnmAvailable == true -> {
                        ProcessBuilder("fnm", "install", "--lts")
                    }
                    status?.node?.brewAvailable == true -> {
                        ProcessBuilder("bash", "-lc",
                            "brew install node@22 && brew link --overwrite --force node@22 2>/dev/null; true")
                    }
                    else -> {
                        com.intellij.ide.BrowserUtil.browse("https://nodejs.org")
                        viewModel.setupInstalling = false
                        return@launch
                    }
                }

                val (output, exitCode) = onIO {
                    process.redirectErrorStream(true)
                    val proc = process.start()
                    val out = proc.inputStream.bufferedReader().readText()
                    out to proc.waitFor()
                }

                if (exitCode == 0) {
                    log.info("Node LTS installed via version manager")
                    val newPath = onIO { NodeDetector.findCompatibleLtsNode() }
                    if (newPath != null) {
                        try {
                            SettingsService.getInstance().state.nodePath = newPath
                            log.info("Auto-configured Node path: $newPath")
                        } catch (_: Exception) { }
                    }
                    checkSetup()
                } else {
                    val errorMsg = output.lines().takeLast(3).joinToString("\n").ifBlank {
                        "Node installation failed (exit code $exitCode)"
                    }
                    viewModel.setupError = errorMsg
                }
            } catch (e: Exception) {
                log.error("Node installation failed", e)
                viewModel.setupError = e.message ?: "Unknown error"
            } finally {
                viewModel.setupInstalling = false
            }
        }
    }

    fun runLogin() {
        scope.launch {
            viewModel.setupInstalling = true
            viewModel.setupError = null
            var process: Process? = null
            try {
                val claudePath = viewModel.setupStatus?.claudeCliPath
                if (claudePath == null) {
                    viewModel.setupError = "Claude CLI not found. Install SDK first."
                    return@launch
                }

                log.info("Running claude login: $claudePath")
                val outputLines = mutableListOf<String>()
                val exitCode = onIO {
                    val p = ProcessBuilder(claudePath, "login")
                        .redirectErrorStream(true)
                        .start()
                    process = p

                    // Read stdout line-by-line (non-blocking relative to EOF)
                    // and open any OAuth URL in the browser
                    val reader = p.inputStream.bufferedReader()
                    val readerJob = launch(Dispatchers.IO) {
                        try {
                            reader.forEachLine { line ->
                                outputLines.add(line)
                                log.info("claude login: $line")
                                // Open OAuth URL in the browser if present
                                val url = URL_PATTERN.find(line)?.value
                                if (url != null) {
                                    log.info("Opening login URL: $url")
                                    com.intellij.ide.BrowserUtil.browse(url)
                                }
                            }
                        } catch (_: java.io.IOException) {
                            // stream closed
                        }
                    }

                    // Wait with timeout (3 minutes for OAuth flow)
                    val finished = p.waitFor(180, java.util.concurrent.TimeUnit.SECONDS)
                    if (!finished) {
                        p.destroyForcibly()
                        readerJob.cancel()
                        return@onIO -1
                    }
                    readerJob.join()
                    p.exitValue()
                }

                if (exitCode == 0) {
                    log.info("Login successful")
                    try { ru.dsudomoin.claudecodegui.service.OAuthCredentialService.getInstance().clearCache() }
                    catch (_: Exception) { }
                    checkSetup()
                } else if (exitCode == -1) {
                    viewModel.setupError = UcuBundle.message("setup.loginTimeout")
                } else {
                    val errorMsg = outputLines.joinToString("\n").ifBlank { "Login failed (exit code $exitCode)" }
                    viewModel.setupError = errorMsg
                }
            } catch (e: CancellationException) {
                process?.destroyForcibly()
                throw e
            } catch (e: Exception) {
                log.error("Login failed", e)
                viewModel.setupError = e.message ?: "Unknown error"
            } finally {
                viewModel.setupInstalling = false
            }
        }
    }

    fun openNodeDownloadPage() {
        com.intellij.ide.BrowserUtil.browse("https://nodejs.org")
    }

    fun sendMessage(text: String) = onSendMessage(text)

    fun sendFromCompose() {
        val text = viewModel.inputText.trim()
        if (text.isBlank()) return
        viewModel.inputText = ""
        onSendMessage(text)
    }

    fun stopGeneration() = onStopGeneration()

    fun submitQuestion(answers: Map<String, List<String>>) {
        viewModel.questionPanelVisible = false
        val answersJson = buildJsonObject {
            for ((questionText, selected) in answers) {
                if (selected.size == 1) {
                    put(questionText, selected[0])
                } else if (selected.size > 1) {
                    put(questionText, buildJsonArray { selected.forEach { add(JsonPrimitive(it)) } })
                }
            }
        }
        val updatedInput = buildJsonObject {
            questionOriginalJson?.let { put("questions", it) }
            put("answers", answersJson)
        }
        questionResultCallback?.invoke(updatedInput)
        questionResultCallback = null
        questionOriginalJson = null
    }

    fun cancelQuestion() {
        viewModel.questionPanelVisible = false
        questionResultCallback?.invoke(null)
        questionResultCallback = null
        questionOriginalJson = null
    }

    fun approvePlan() {
        viewModel.planPanelVisible = false
        viewModel.planMarkdown = ""
        planResultCallback?.invoke(PlanResult.APPROVED)
        planResultCallback = null
    }

    fun approvePlanAndCompact() {
        viewModel.planPanelVisible = false
        viewModel.planMarkdown = ""
        planResultCallback?.invoke(PlanResult.APPROVED_COMPACT)
        planResultCallback = null
    }

    fun denyPlan() {
        viewModel.planPanelVisible = false
        viewModel.planMarkdown = ""
        planResultCallback?.invoke(PlanResult.DENIED)
        planResultCallback = null
    }

    fun approvePermission() {
        viewModel.pendingApproval = null
        approvalResultCallback?.invoke(true)
        approvalResultCallback = null
    }

    fun rejectPermission() {
        viewModel.pendingApproval = null
        approvalResultCallback?.invoke(false)
        approvalResultCallback = null
    }

    private fun getActiveFileContext(): String? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return null
        val vFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return null
        val line = editor.caretModel.logicalPosition.line + 1
        return "Currently viewing `${vFile.path}` (line $line)"
    }

    private fun onSendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.startsWith("/")) {
            val cmdName = trimmed.split(" ", limit = 2).first()
            val cmd = SlashCommandRegistry.find(cmdName)
            if (cmd != null && cmd.category == CommandCategory.LOCAL) {
                executeLocalCommand(cmd)
                return
            }
        }

        if (currentJob?.isActive == true) {
            val images = consumeImages()
            messageQueue.add(QueuedMessage(text, images))
            syncQueueToViewModel()
            return
        }
        doSendMessage(text)
    }

    private fun executeLocalCommand(cmd: ru.dsudomoin.claudecodegui.command.SlashCommand) {
        when {
            cmd.name in SlashCommandRegistry.NEW_SESSION_ALIASES -> {
                newChat()
            }
        }
    }

    private fun doSendMessage(
        text: String,
        preAttachedImages: List<File>? = null,
        preFileMentions: List<ru.dsudomoin.claudecodegui.service.ProjectFileIndexService.FileEntry>? = null
    ) {
        val images = preAttachedImages ?: consumeImages()
        val fileMentions = preFileMentions ?: emptyList()

        val sb = StringBuilder(text)

        if (!text.startsWith("/") && !text.startsWith("From `") && !text.startsWith("File: `") && !text.startsWith("I'm working in")) {
            val ctx = getActiveFileContext()
            if (ctx != null) sb.append("\n\n_${ctx}_")
        }

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

        if (images.isNotEmpty()) {
            sb.append("\n\n_Attached screenshots:_")
            images.forEach { file ->
                sb.append("\n- `${file.absolutePath}`")
            }
        }

        val enrichedText = sb.toString()

        val displayMessage = if (images.isNotEmpty()) {
            Message.user(text, images)
        } else {
            Message.user(text)
        }
        messages.add(displayMessage)
        syncMessagesToViewModel()

        viewModel.isSending = true
        viewModel.isStreaming = true
        viewModel.streamingThinkingText = ""
        viewModel.streamingResponseText = ""
        viewModel.streamingContentFlow = emptyList()
        viewModel.thinkingCollapsed = false

        messages.add(Message.assistant(""))
        syncMessagesToViewModel()
        viewModel.requestScrollToBottom()


        val thinkingText = StringBuilder()
        val responseText = StringBuilder()
        var thinkingCollapsed = false
        val toolUseBlocks = mutableListOf<ContentBlock.ToolUse>()
        val toolResults = mutableMapOf<String, Pair<String, Boolean>>()
        val textCheckpoints = mutableListOf<Int>()
        var inPlanMode = false
        var planModeStartOffset = 0
        val planTextAccumulator = StringBuilder()  // direct accumulator for plan text
        var pendingPlanMarkdown: String? = null  // plan text prepared by PlanModeExit
        var compactAfterResponse = false  // set when user approves plan with compact
        var hasError = false

        fun enterPlanMode(origin: String) {
            if (!inPlanMode) {
                inPlanMode = true
                planModeStartOffset = responseText.length
                log.info("$origin: entering plan mode, offset=$planModeStartOffset")
            } else {
                log.info("$origin: duplicate plan-mode enter ignored, accumulatorLen=${planTextAccumulator.length}, offset=$planModeStartOffset")
            }
        }

        fun addToolUseBlock(id: String, name: String, input: JsonObject) {
            val toolBlock = ContentBlock.ToolUse(id, name, input)
            toolUseBlocks.add(toolBlock)
            textCheckpoints.add(responseText.length)
            trackToolUseForStatus(id, name, input)
            syncStatusToViewModel()
            contentFlowRebuildJob?.cancel()
            rebuildStreamingContentFlow(responseText, toolUseBlocks, toolResults, textCheckpoints)
            viewModel.requestScrollToBottom()
        }

        val settings = SettingsService.getInstance()
        val selectedModel = settings.state.claudeModel
        val selectedMode = settings.state.permissionMode
        val showThinking = settings.state.thinkingEnabled
        val useStreaming = settings.state.streamingEnabled

        val apiMessages = messages.dropLast(1).toMutableList()
        if (enrichedText != text) {
            val lastUserIdx = apiMessages.indexOfLast { it.role == Role.USER }
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
                            if (!showThinking) {
                                log.info("ThinkingDelta ignored (thinking disabled)")
                                return@collect
                            }
                            thinkingText.append(event.text)
                            val text = thinkingText.toString()
                            viewModel.streamingThinkingText = text
                            log.info("ThinkingDelta: total=${thinkingText.length} chars")
                        }

                        is StreamEvent.TextDelta -> {
                            if (!thinkingCollapsed && thinkingText.isNotEmpty()) {
                                thinkingCollapsed = true
                                viewModel.thinkingCollapsed = true
                            }
                            responseText.append(event.text)
                            if (inPlanMode) {
                                planTextAccumulator.append(event.text)
                                if (planTextAccumulator.length <= 200 || planTextAccumulator.length % 500 == 0) {
                                    log.info("TextDelta in planMode: accumulatorLen=${planTextAccumulator.length}, delta='${event.text.take(80)}'")
                                }
                            } else {
                                val text = responseText.toString()
                                viewModel.streamingResponseText = text
                                if (toolUseBlocks.isNotEmpty()) {
                                    // Debounce: rebuild content flow at most every 200ms during text streaming.
                                    // ToolUse/ToolResult events still trigger immediate rebuilds.
                                    contentFlowRebuildJob?.cancel()
                                    contentFlowRebuildJob = scope.launch {
                                        delay(200)
                                        rebuildStreamingContentFlow(responseText, toolUseBlocks, toolResults, textCheckpoints)
                                    }
                                }
                            }
                        }

                        is StreamEvent.PlanModeEnter -> {
                            enterPlanMode("PlanModeEnter")
                        }

                        is StreamEvent.PlanModeExit -> {
                            log.info("PlanModeExit: preparing plan, inPlanMode=$inPlanMode, offset=$planModeStartOffset, textLen=${responseText.length}, accumulatorLen=${planTextAccumulator.length}")
                            inPlanMode = false
                            // Use direct accumulator first, fall back to offset-based extraction
                            pendingPlanMarkdown = if (planTextAccumulator.isNotEmpty()) {
                                planTextAccumulator.toString()
                            } else {
                                val effectiveOffset = if (planModeStartOffset > 0) planModeStartOffset
                                    else textCheckpoints.lastOrNull()?.coerceAtMost(responseText.length) ?: 0
                                responseText.substring(effectiveOffset).toString()
                            }
                            log.info("PlanModeExit: pendingPlanMarkdown length=${pendingPlanMarkdown?.length}")
                        }

                        is StreamEvent.ToolUse -> {
                            val lowerName = event.name.lowercase()
                            if (lowerName == "enterplanmode") {
                                // Keep plan-mode tools visible in the content flow.
                                addToolUseBlock(event.id, event.name, event.input)
                                enterPlanMode("EnterPlanMode ToolUse")
                                return@collect
                            }
                            if (lowerName == "exitplanmode") {
                                // Keep plan-mode tools visible in the content flow.
                                addToolUseBlock(event.id, event.name, event.input)
                                return@collect
                            }
                            if (!thinkingCollapsed && thinkingText.isNotEmpty()) {
                                thinkingCollapsed = true
                                viewModel.thinkingCollapsed = true
                            }
                            addToolUseBlock(event.id, event.name, event.input)
                        }

                        is StreamEvent.ToolResult -> {
                            val toolName = toolUseBlocks.find { it.id == event.id }?.name ?: "?"
                            log.info("ToolResult: id=${event.id}, tool=$toolName, isError=${event.isError}, contentLen=${event.content.length}, preview='${event.content.take(200)}'")
                            toolResults[event.id] = event.content to event.isError
                            trackToolResultForStatus(event.id, event.isError)
                            syncStatusToViewModel()
                            contentFlowRebuildJob?.cancel()
                            rebuildStreamingContentFlow(responseText, toolUseBlocks, toolResults, textCheckpoints)
                            viewModel.requestScrollToBottom()
                        }

                        is StreamEvent.Error -> {
                            hasError = true
                            messages[messages.lastIndex] =
                                Message.assistant("[Error] ${event.message}")
                            viewModel.isStreaming = false
                            viewModel.isSending = false
                            syncMessagesToViewModel()
                        }

                        is StreamEvent.TextSnapshot -> {
                            if (event.text.isNotEmpty()) {
                                if (!thinkingCollapsed && thinkingText.isNotEmpty()) {
                                    thinkingCollapsed = true
                                    viewModel.thinkingCollapsed = true
                                }
                                responseText.clear()
                                responseText.append(event.text)
                                if (!inPlanMode) {
                                    val text = responseText.toString()
                                    viewModel.streamingResponseText = text
                                    if (toolUseBlocks.isNotEmpty()) {
                                        contentFlowRebuildJob?.cancel()
                                        contentFlowRebuildJob = scope.launch {
                                            delay(200)
                                            rebuildStreamingContentFlow(responseText, toolUseBlocks, toolResults, textCheckpoints)
                                        }
                                    }
                                }
                            }
                        }

                        is StreamEvent.ThinkingSnapshot -> {
                            if (!showThinking) return@collect
                            if (event.text.isNotEmpty()) {
                                thinkingText.clear()
                                thinkingText.append(event.text)
                                val text = thinkingText.toString()
                                viewModel.streamingThinkingText = text
                            }
                        }

                        is StreamEvent.StreamEnd -> {
                            if (hasError) return@collect
                            contentFlowRebuildJob?.cancel()
                            contentFlowRebuildJob = null

                            sessionId = provider.sessionId
                            val blocks = buildAssistantBlocks(thinkingText, responseText, toolUseBlocks, toolResults, textCheckpoints)
                            log.info("StreamEnd: blocks=${blocks.size}, hasThinking=${blocks.any { it is ContentBlock.Thinking }}, thinkingLen=${thinkingText.length}")
                            messages[messages.lastIndex] = Message.assistant(blocks)
                            viewModel.isStreaming = false
                            viewModel.isSending = false
                            syncMessagesToViewModel()
                            viewModel.requestScrollToBottom()

                            // Update tab title from session content
                            if (customTitle == null) {
                                val title = sessionTitle
                                if (title != "New Chat") {
                                    onSessionTitleChanged?.invoke(title)
                                }
                            }

                            processQueue()
                        }

                        is StreamEvent.Usage -> {
                            lastUsedTokens = event.inputTokens + event.cacheCreation + event.cacheRead
                            val total = lastUsedTokens
                            val maxTokens = 200_000
                            viewModel.contextUsage = ContextUsageData(
                                usedTokens = total,
                                maxTokens = maxTokens,
                            )
                        }

                        is StreamEvent.PermissionRequest -> {
                            val lower = event.toolName.lowercase()
                            log.info("PermissionRequest: tool='${event.toolName}' lower='$lower' inputKeys=${event.input.keys}")
                            if (lower == "askuserquestion" || lower == "ask_user_question") {
                                log.info("AskUserQuestion: full input=${event.input}")
                                log.info("AskUserQuestion: input keys=${event.input.keys}, input size=${event.input.size}")
                                val questionsElement = event.input["questions"]
                                log.info("AskUserQuestion: questions element=$questionsElement, class=${questionsElement?.javaClass?.simpleName}")
                                val questionsArray = questionsElement?.jsonArray
                                if (questionsArray != null) {
                                    log.info("AskUserQuestion: ${questionsArray.size} questions, showing panel")
                                    val deferred = CompletableDeferred<JsonObject?>()
                                    showQuestionPanel(questionsArray) { result ->
                                        log.info("AskUserQuestion: user responded, result=$result")
                                        deferred.complete(result)
                                    }
                                    val result = deferred.await()
                                    if (result != null) {
                                        log.info("AskUserQuestion: sending response with updatedInput=$result")
                                        provider.sendPermissionResponseWithInput(true, result)
                                    } else {
                                        log.info("AskUserQuestion: user cancelled, denying")
                                        provider.sendPermissionResponse(false, "User cancelled")
                                    }
                                } else {
                                    log.warn("AskUserQuestion: no 'questions' array in input (keys=${event.input.keys}), auto-approving")
                                    provider.sendPermissionResponse(true)
                                }
                            } else if (lower == "enterplanmode") {
                                enterPlanMode("EnterPlanMode PermissionRequest")
                                provider.sendPermissionResponse(true)
                            } else if (lower == "exitplanmode") {
                                log.info("ExitPlanMode PermissionRequest: showing plan dialog, accumulator=${planTextAccumulator.length}, pending=${pendingPlanMarkdown?.length}")
                                inPlanMode = false
                                try {
                                    val effectiveOffset = if (planModeStartOffset > 0) {
                                        planModeStartOffset.coerceAtMost(responseText.length)
                                    } else {
                                        textCheckpoints.lastOrNull()?.coerceAtMost(responseText.length) ?: 0
                                    }
                                    val offsetCandidate = responseText.substring(effectiveOffset)
                                    val inputCandidate = extractPlanMarkdownFromInput(event.input)
                                    val planMarkdown = listOfNotNull(
                                        inputCandidate?.takeIf { it.isNotBlank() },
                                        planTextAccumulator.toString().takeIf { it.isNotBlank() },
                                        pendingPlanMarkdown?.takeIf { it.isNotBlank() },
                                        offsetCandidate.takeIf { it.isNotBlank() },
                                    ).maxByOrNull { it.length } ?: ""
                                    pendingPlanMarkdown = null
                                    log.info("ExitPlanMode: planMarkdown length=${planMarkdown.length}, preview=${planMarkdown.take(200)}")

                                    // Update displayed message with plan content
                                    val displayBlocks = mutableListOf<ContentBlock>()
                                    if (thinkingText.isNotEmpty()) {
                                        displayBlocks.add(ContentBlock.Thinking(thinkingText.toString()))
                                    }
                                    displayBlocks.addAll(toolUseBlocks)
                                    if (planMarkdown.isNotBlank()) {
                                        displayBlocks.add(ContentBlock.Text(planMarkdown))
                                    }
                                    messages[messages.lastIndex] = Message.assistant(displayBlocks)
                                    syncMessagesToViewModel()
                                    viewModel.requestScrollToBottom()

                                    // Show plan approval panel and wait
                                    val deferred = CompletableDeferred<PlanResult>()
                                    planResultCallback = { result -> deferred.complete(result) }
                                    viewModel.planMarkdown = planMarkdown
                                    viewModel.planPanelVisible = true
                                    val planResult = deferred.await()

                                    when (planResult) {
                                        PlanResult.APPROVED -> {
                                            provider.sendPermissionResponse(true)
                                        }
                                        PlanResult.APPROVED_COMPACT -> {
                                            provider.sendPermissionResponse(true)
                                            compactAfterResponse = true
                                        }
                                        PlanResult.DENIED -> {
                                            provider.sendPermissionResponse(false, "User rejected the plan")
                                        }
                                    }

                                    // Prepare for continued streaming
                                    messages.add(Message.assistant(""))
                                    thinkingText.clear()
                                    responseText.clear()
                                    thinkingCollapsed = false
                                    toolUseBlocks.clear()
                                    textCheckpoints.clear()
                                    planModeStartOffset = 0
                                    planTextAccumulator.clear()
                                    syncMessagesToViewModel()
                                    viewModel.streamingContentFlow = emptyList()
                                } catch (e: Exception) {
                                    log.error("ExitPlanMode handler failed", e)
                                    provider.sendPermissionResponse(true)
                                }
                            } else {
                                val deferred = CompletableDeferred<Boolean>()
                                val approvalRequest = ApprovalPanelFactory.classifyTool(event.toolName, event.input)
                                approvalResultCallback = { allowed -> deferred.complete(allowed) }
                                viewModel.pendingApproval = approvalRequest
                                viewModel.requestScrollToBottom()
                                val allowed = deferred.await()
                                provider.sendPermissionResponse(allowed)
                            }
                        }

                        else -> { /* ignore other events */ }
                    }
                }

                // After streaming completes, send /compact if user chose "Approve & Compact"
                if (compactAfterResponse) {
                    compactAfterResponse = false
                    doSendMessage("/compact")
                }
            } catch (_: CancellationException) {
                if (responseText.isNotBlank() || thinkingText.isNotBlank() || toolUseBlocks.isNotEmpty()) {
                    val blocks = buildAssistantBlocks(thinkingText, responseText, toolUseBlocks, toolResults, textCheckpoints, stopped = true)
                    messages[messages.lastIndex] = Message.assistant(blocks)
                } else if (messages.isNotEmpty()) {
                    messages.removeAt(messages.lastIndex)
                }
                viewModel.isStreaming = false
                viewModel.isSending = false
                syncMessagesToViewModel()
                processQueue()
            }
        }
    }

    private fun processQueue() {
        val next = messageQueue.poll() ?: return
        syncQueueToViewModel()
        doSendMessage(next.text, next.images, next.fileMentions)
    }

    fun removeFromQueue(index: Int) {
        val list = messageQueue.toMutableList()
        if (index in list.indices) {
            list.removeAt(index)
            messageQueue.clear()
            list.forEach { messageQueue.add(it) }
            syncQueueToViewModel()
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
        messages.clear()
        provider.resetSession()
        sessionId = null
        customTitle = null
        lastUsedTokens = 0
        statusTracker.clear()
        viewModel.messages = emptyList()
        viewModel.isSending = false
        viewModel.isStreaming = false
        viewModel.streamingThinkingText = ""
        viewModel.streamingResponseText = ""
        viewModel.streamingContentFlow = emptyList()
        viewModel.thinkingCollapsed = false
        viewModel.contextUsage = ContextUsageData()
        viewModel.queueItems = emptyList()
        viewModel.todos = emptyList()
        viewModel.fileChanges = emptyList()
        viewModel.agents = emptyList()
        viewModel.questionPanelVisible = false
        viewModel.planPanelVisible = false
        viewModel.pendingApproval = null
    }

    fun loadSession(loadSessionId: String) {
        onStopGeneration()
        val loaded = sessionManager.load(loadSessionId) ?: return
        messages.clear()
        messages.addAll(loaded)
        sessionId = loadSessionId
        customTitle = sessionManager.getTitle(loadSessionId)
        provider.setResumeSessionId(loadSessionId)
        syncMessagesToViewModel()
        val usage = sessionManager.getLastUsage(loadSessionId)
        if (usage != null) {
            lastUsedTokens = usage.first + usage.second + usage.third
            val total = lastUsedTokens
            val maxTokens = 200_000
            viewModel.contextUsage = ContextUsageData(
                usedTokens = total,
                maxTokens = maxTokens,
            )
        }
        viewModel.requestForceScrollToBottom()
    }

    val sessionTitle: String
        get() {
            customTitle?.let { return it }
            val firstUser = messages.firstOrNull {
                it.role == Role.USER
            } ?: return "New Chat"
            val text = firstUser.textContent
                .lineSequence()
                .firstOrNull { it.isNotBlank() && !it.startsWith("_") }
                ?.take(40) ?: return "New Chat"
            return if (text.length >= 40) "${text.take(37)}..." else text.ifBlank { "New Chat" }
        }

    private fun buildAssistantBlocks(
        thinkingText: StringBuilder,
        responseText: StringBuilder,
        toolUseBlocks: List<ContentBlock.ToolUse> = emptyList(),
        toolResults: Map<String, Pair<String, Boolean>> = emptyMap(),
        textCheckpoints: List<Int> = emptyList(),
        @Suppress("UNUSED_PARAMETER") stopped: Boolean = false
    ): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        if (thinkingText.isNotEmpty()) {
            blocks.add(ContentBlock.Thinking(thinkingText.toString()))
        }

        val fullText = responseText.toString()

        var prevIdx = 0
        for (i in toolUseBlocks.indices) {
            val checkpoint = if (i < textCheckpoints.size) textCheckpoints[i].coerceAtMost(fullText.length) else fullText.length
            val segment = fullText.substring(prevIdx, checkpoint)
            if (segment.isNotBlank()) {
                blocks.add(ContentBlock.Text(segment))
            }
            val toolUse = toolUseBlocks[i]
            blocks.add(toolUse)
            toolResults[toolUse.id]?.let { (content, isError) ->
                if (content.isNotBlank()) {
                    blocks.add(ContentBlock.ToolResult(toolUseId = toolUse.id, content = content, isError = isError))
                }
            }
            prevIdx = checkpoint
        }

        val remaining = fullText.substring(prevIdx.coerceAtMost(fullText.length))
        if (remaining.isNotBlank()) {
            blocks.add(ContentBlock.Text(remaining))
        }
        return blocks
    }

    private fun showQuestionPanel(questions: JsonArray, onResult: (JsonObject?) -> Unit) {
        questionOriginalJson = questions
        questionResultCallback = onResult

        val parsed = questions.mapNotNull { element ->
            val obj = element.jsonObject
            val question = obj["question"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val header = obj["header"]?.jsonPrimitive?.contentOrNull ?: ""
            val multiSelect = obj["multiSelect"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            val options = obj["options"]?.jsonArray?.mapNotNull { optElement ->
                val optObj = optElement.jsonObject
                val label = optObj["label"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val desc = optObj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                OptionData(label, desc)
            } ?: emptyList()
            QuestionData(question, header, options, multiSelect)
        }
        viewModel.currentQuestions = parsed
        viewModel.questionPanelVisible = true
    }

    /**
     * Tracks tool use in status tracker. Does NOT update viewModel —
     * caller must sync viewModel on EDT via [syncStatusToViewModel].
     */
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
                statusTracker.updateTodos(items)
            }
            lower in setOf("edit", "edit_file", "replace_string") -> {
                val filePath = input["file_path"]?.jsonPrimitive?.contentOrNull
                    ?: input["path"]?.jsonPrimitive?.contentOrNull ?: return
                val oldString = input["old_string"]?.jsonPrimitive?.contentOrNull
                    ?: input["old_str"]?.jsonPrimitive?.contentOrNull ?: ""
                val newString = input["new_string"]?.jsonPrimitive?.contentOrNull
                    ?: input["new_str"]?.jsonPrimitive?.contentOrNull ?: ""
                statusTracker.trackFileChange(name, filePath, oldString, newString)
            }
            lower in setOf("write", "write_to_file", "create_file") -> {
                val filePath = input["file_path"]?.jsonPrimitive?.contentOrNull
                    ?: input["path"]?.jsonPrimitive?.contentOrNull ?: return
                val content = input["content"]?.jsonPrimitive?.contentOrNull
                    ?: input["file_text"]?.jsonPrimitive?.contentOrNull ?: ""
                val isNew = !File(filePath).exists()
                statusTracker.trackFileWrite(filePath, content, isNew)
            }
            lower == "task" -> {
                val type = input["subagent_type"]?.jsonPrimitive?.contentOrNull
                    ?: input["type"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                val description = input["description"]?.jsonPrimitive?.contentOrNull
                    ?: input["prompt"]?.jsonPrimitive?.contentOrNull?.take(80) ?: ""
                statusTracker.trackSubagent(id, type, description)
            }
        }
    }

    /**
     * Tracks tool result in status tracker. Does NOT update viewModel —
     * caller must sync viewModel on EDT via [syncStatusToViewModel].
     */
    private fun trackToolResultForStatus(id: String, isError: Boolean) {
        statusTracker.completeSubagent(id, isError)
    }

    /** Syncs status tracker state to viewModel. Must be called on EDT. */
    private fun syncStatusToViewModel() {
        viewModel.todos = statusTracker.currentTodos
        viewModel.fileChanges = statusTracker.currentFileChanges
        viewModel.agents = statusTracker.currentSubagents
    }

    private fun rebuildStreamingContentFlow(
        responseText: StringBuilder,
        toolUseBlocks: List<ContentBlock.ToolUse>,
        toolResults: Map<String, Pair<String, Boolean>>,
        textCheckpoints: List<Int>,
    ) {
        if (toolUseBlocks.isEmpty()) {
            viewModel.streamingContentFlow = emptyList()
            return
        }

        // First pass: build flat list of flow items with tool data
        data class ToolEntry(
            val tool: ContentBlock.ToolUse,
            val status: ToolStatus,
            val expandable: ExpandableContent?,
            val diffAdd: Int,
            val diffDel: Int,
            val category: ToolCategoryType,
        )

        val flatFlow = mutableListOf<Any>() // TextSegment or ToolEntry
        val fullText = responseText.toString()
        var prevIdx = 0

        for (i in toolUseBlocks.indices) {
            val checkpoint = if (i < textCheckpoints.size) textCheckpoints[i].coerceAtMost(fullText.length) else fullText.length
            val segment = fullText.substring(prevIdx, checkpoint)
            if (segment.isNotBlank()) {
                flatFlow.add(ContentFlowItem.TextSegment(segment))
            }
            val tool = toolUseBlocks[i]
            val result = toolResults[tool.id]
            val status = when {
                result?.second == true -> ToolStatus.ERROR
                result != null -> ToolStatus.COMPLETED
                else -> ToolStatus.PENDING
            }
            val expandable = buildExpandableContent(tool.name, tool.input, result?.first)
            val (diffAdd, diffDel) = computeDiffStatsForTool(tool.name, tool.input)
            flatFlow.add(ToolEntry(tool, status, expandable, diffAdd, diffDel, classifyToolCategory(tool.name)))
            prevIdx = checkpoint
        }

        val remaining = fullText.substring(prevIdx.coerceAtMost(fullText.length))
        if (remaining.isNotBlank()) {
            flatFlow.add(ContentFlowItem.TextSegment(remaining))
        }

        // Second pass: group consecutive tool entries of same category
        val flow = mutableListOf<ContentFlowItem>()
        var i = 0
        while (i < flatFlow.size) {
            val item = flatFlow[i]
            if (item is ContentFlowItem.TextSegment) {
                flow.add(item)
                i++
                continue
            }
            val entry = item as ToolEntry
            // Collect consecutive tools of same category
            val groupEntries = mutableListOf(entry)
            var j = i + 1
            while (j < flatFlow.size) {
                val next = flatFlow[j]
                if (next is ToolEntry && next.category == entry.category) {
                    groupEntries.add(next)
                    j++
                } else break
            }

            if (groupEntries.size == 1) {
                // Single tool — render as individual block
                val e = groupEntries[0]
                flow.add(ContentFlowItem.ToolBlock(ToolUseData(
                    id = e.tool.id,
                    toolName = e.tool.name,
                    displayName = ToolSummaryExtractor.getToolDisplayName(e.tool.name),
                    summary = ToolSummaryExtractor.extractToolSummary(e.tool.name, e.tool.input),
                    status = e.status,
                    expandable = e.expandable,
                    diffAdditions = e.diffAdd,
                    diffDeletions = e.diffDel,
                    filePath = ToolSummaryExtractor.extractFilePath(e.tool.input),
                )))
            } else {
                // Multiple tools — render as group
                val groupItems = groupEntries.map { e ->
                    ToolGroupItemData(
                        id = e.tool.id,
                        toolName = e.tool.name,
                        summary = ToolSummaryExtractor.extractToolSummary(e.tool.name, e.tool.input),
                        status = e.status,
                        diffAdditions = e.diffAdd,
                        diffDeletions = e.diffDel,
                        isFileLink = e.category == ToolCategoryType.READ || e.category == ToolCategoryType.EDIT,
                        expandable = e.expandable,
                        filePath = ToolSummaryExtractor.extractFilePath(e.tool.input),
                    )
                }
                flow.add(ContentFlowItem.ToolGroup(ToolGroupData(
                    category = entry.category,
                    items = groupItems,
                )))
            }
            i = j
        }

        viewModel.streamingContentFlow = flow
    }

    private fun classifyToolCategory(toolName: String): ToolCategoryType {
        val lower = toolName.lowercase()
        return when {
            lower in setOf("read", "read_file") -> ToolCategoryType.READ
            lower in setOf("edit", "edit_file", "replace_string") -> ToolCategoryType.EDIT
            lower in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command") -> ToolCategoryType.BASH
            lower in setOf("grep", "search", "glob", "find", "list", "listfiles") -> ToolCategoryType.SEARCH
            lower in setOf("write", "write_to_file", "save-file", "create_file") -> ToolCategoryType.EDIT
            else -> ToolCategoryType.OTHER
        }
    }

    private fun syncMessagesToViewModel() {
        viewModel.messages = messages.toList()
    }

    private fun syncQueueToViewModel() {
        viewModel.queueItems = messageQueue.mapIndexed { index, qm ->
            QueueItemDisplayData(
                text = qm.text,
                imageCount = qm.images.size,
                index = index,
            )
        }
    }

    private fun consumeImages(): List<File> {
        val vmImages = viewModel.attachedImages
        if (vmImages.isNotEmpty()) {
            viewModel.attachedImages = emptyList()
            return vmImages.map { img -> File(img.filePath) }
        }
        return emptyList()
    }

    private fun buildExpandableContent(
        toolName: String,
        input: JsonObject,
        resultContent: String?,
    ): ExpandableContent? {
        val lower = toolName.lowercase()
        if (lower in setOf("read", "read_file")) {
            return null
        }
        if (lower == "task" || lower == "taskoutput") {
            val markdown = buildTaskExpandableMarkdown(input, resultContent)
            if (markdown.isNotBlank()) return ExpandableContent.Markdown(markdown)
        }
        // Edit tools → diff from input with file path for syntax highlighting
        if (lower in setOf("edit", "edit_file", "replace_string")) {
            val diff = ToolSummaryExtractor.extractEditDiffStrings(input)
            if (diff != null) {
                val filePath = ToolSummaryExtractor.extractFilePath(input)
                return ExpandableContent.Diff(diff.first, diff.second, filePath)
            }
        }
        // Write/Create tools → syntax-highlighted code content
        if (lower in setOf("write", "write_to_file", "save-file", "create_file")) {
            val content = ToolSummaryExtractor.extractWriteContent(input)
            if (content != null && content.isNotBlank()) {
                val filePath = ToolSummaryExtractor.extractFilePath(input)
                return ExpandableContent.Code(content, filePath)
            }
        }
        // Bash tools → command from input + result output
        if (lower in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command")) {
            val cmd = ToolSummaryExtractor.extractBashCommand(input)
            val output = resultContent?.takeIf { it.isNotBlank() }
            val text = buildString {
                if (cmd != null) { append("$ "); appendLine(cmd) }
                if (output != null) append(output)
            }.trimEnd()
            if (text.isNotBlank()) return ExpandableContent.PlainText(text)
        }
        // Search tools / Task → result content only
        if (ToolSummaryExtractor.hasUsefulResultContent(toolName) && resultContent?.isNotBlank() == true) {
            return ExpandableContent.PlainText(resultContent)
        }
        return null
    }

    private fun computeDiffStatsForTool(toolName: String, input: JsonObject): Pair<Int, Int> {
        val lower = toolName.lowercase()
        if (lower in setOf("edit", "edit_file", "replace_string")) {
            val diff = ToolSummaryExtractor.extractEditDiffStrings(input)
            if (diff != null) return computeDiffStats(diff.first, diff.second)
        }
        return 0 to 0
    }

    /**
     * Compose readable expandable markdown for agent tools (task/taskoutput).
     */
    private fun buildTaskExpandableMarkdown(input: JsonObject, resultContent: String?): String {
        val description = input["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val prompt = input["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val subagentType = input["subagent_type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val result = resultContent?.trim().orEmpty()

        return buildString {
            if (description.isNotBlank()) {
                appendLine("### Task")
                appendLine(description)
            }
            if (subagentType.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                append("**Subagent:** `")
                append(subagentType)
                appendLine("`")
            }
            if (prompt.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine("### Prompt")
                appendLine(prompt)
            }
            if (result.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine("### Result")
                appendLine(result)
            }
        }.trim()
    }

    /**
     * Extract plan markdown from ExitPlanMode tool input when SDK provides it there.
     * Keeps parsing permissive because schema may vary across SDK versions.
     */
    private fun extractPlanMarkdownFromInput(input: JsonObject): String? {
        val keys = listOf("plan", "plan_markdown", "planMarkdown", "markdown", "content", "text", "message")
        val candidates = mutableListOf<String>()

        fun addCandidate(value: String?) {
            val normalized = value?.trim().orEmpty()
            if (normalized.isNotBlank()) {
                candidates.add(normalized)
            }
        }

        fun extractFromElement(element: JsonElement) {
            when (element) {
                is JsonPrimitive -> addCandidate(element.contentOrNull)
                is JsonArray -> {
                    val joined = element.joinToString("\n") { entry ->
                        (entry as? JsonPrimitive)?.contentOrNull ?: ""
                    }
                    addCandidate(joined)
                }
                is JsonObject -> {
                    addCandidate((element["markdown"] as? JsonPrimitive)?.contentOrNull)
                    addCandidate((element["text"] as? JsonPrimitive)?.contentOrNull)
                    addCandidate((element["plan"] as? JsonPrimitive)?.contentOrNull)
                }
            }
        }

        for (key in keys) {
            input[key]?.let { extractFromElement(it) }
        }

        return candidates.maxByOrNull { it.length }
    }

    override fun dispose() {
        currentJob?.cancel()
        scope.cancel()
        provider.close()
    }
}
