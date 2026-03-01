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
import ru.dsudomoin.claudecodegui.bridge.ProcessEnvironment.withNodeEnvironment
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
import ru.dsudomoin.claudecodegui.ui.compose.chat.CompactBoundaryData
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
import ru.dsudomoin.claudecodegui.ui.diff.InteractiveDiffManager
import ru.dsudomoin.claudecodegui.ui.status.FileChangeSummary
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
        private val SKILL_TOOL_NAMES = setOf("skill", "useskill", "runskill", "run_skill", "execute_skill")
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

    private data class CompactBoundaryMarker(
        val textOffset: Int,
        val trigger: String,
        val preTokens: Int,
        val order: Int,
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
    private var elicitationResultCallback: ((Boolean, JsonElement?) -> Unit)? = null

    private val provider = ClaudeProvider().apply {
        projectDir = project.basePath
    }
    private val messages = mutableListOf<Message>()
    private val statusTracker = StatusTracker()
    private var lastSdkUserMessageId: String? = null

    private fun persistCustomTitleIfPossible() {
        val sid = sessionId ?: provider.sessionId
        val title = customTitle?.trim().orEmpty()
        if (!sid.isNullOrBlank() && title.isNotBlank()) {
            sessionManager.setCustomTitle(sid, title)
        }
    }

    fun renameSession(title: String) {
        val normalized = title.trim()
        if (normalized.isBlank()) return
        customTitle = normalized
        viewModel.sessionTitle = normalized
        onSessionTitleChanged?.invoke(normalized)
        persistCustomTitleIfPossible()
    }

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

        // If detected Node is wrong version, try to find a compatible one (18+)
        if (status.node.state == NodeState.WRONG_VERSION) {
            val compatiblePath = onIO { NodeDetector.findCompatibleNode() }
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
            // Detect Node.js version
            val nodePath = onIO { NodeDetector.detect() }
            if (nodePath != null) {
                val nodeVer = onIO { NodeDetector.detectVersion(nodePath) }
                viewModel.nodeVersion = nodeVer
            }

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
                        val brewPath = listOf("/opt/homebrew/bin/brew", "/usr/local/bin/brew")
                            .firstOrNull { File(it).canExecute() } ?: "brew"
                        ProcessBuilder("bash", "-c",
                            "$brewPath install node@22 && $brewPath link --overwrite --force node@22 2>/dev/null; true")
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
                    log.info("Node installed via version manager")
                    val newPath = onIO { NodeDetector.findCompatibleNode() }
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

                val nodePath = viewModel.setupStatus?.node?.path
                log.info("Running claude login: $claudePath (node=$nodePath)")
                val outputLines = mutableListOf<String>()
                val exitCode = onIO {
                    val pb = ProcessBuilder(claudePath, "login")
                        .redirectErrorStream(true)
                    if (nodePath != null) pb.withNodeEnvironment(nodePath)
                    val p = pb.start()
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

    fun submitElicitation(value: JsonElement) {
        elicitationResultCallback?.invoke(true, value)
    }

    fun cancelElicitation() {
        elicitationResultCallback?.invoke(false, null)
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
        viewModel.promptSuggestions = emptyList()
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
        val compactBoundaries = mutableListOf<CompactBoundaryMarker>()
        val taskToToolUseId = mutableMapOf<String, String>()
        val toolProgressNotes = mutableMapOf<String, MutableList<String>>()
        val toolSummaryOverrides = mutableMapOf<String, String>()
        val toolEventOrder = mutableMapOf<String, Int>()
        val textCheckpoints = mutableListOf<Int>()
        var inPlanMode = false
        var planModeStartOffset = 0
        val planTextAccumulator = StringBuilder()  // direct accumulator for plan text
        var pendingPlanMarkdown: String? = null  // plan text prepared by PlanModeExit
        var compactAfterResponse = false  // set when user approves plan with compact
        var hasError = false
        var streamEventOrdinal = 0

        fun rebuildFlowNow() {
            contentFlowRebuildJob?.cancel()
            rebuildStreamingContentFlow(
                responseText = responseText,
                toolUseBlocks = toolUseBlocks,
                toolResults = toolResults,
                textCheckpoints = textCheckpoints,
                compactBoundaries = compactBoundaries,
                toolProgressNotes = toolProgressNotes,
                toolSummaryOverrides = toolSummaryOverrides,
                toolEventOrder = toolEventOrder,
                taskToToolUseId = taskToToolUseId,
            )
        }

        fun rebuildFlowDebounced() {
            contentFlowRebuildJob?.cancel()
            contentFlowRebuildJob = scope.launch {
                delay(200)
                rebuildStreamingContentFlow(
                    responseText = responseText,
                    toolUseBlocks = toolUseBlocks,
                    toolResults = toolResults,
                    textCheckpoints = textCheckpoints,
                    compactBoundaries = compactBoundaries,
                    toolProgressNotes = toolProgressNotes,
                    toolSummaryOverrides = toolSummaryOverrides,
                    toolEventOrder = toolEventOrder,
                    taskToToolUseId = taskToToolUseId,
                )
            }
        }

        fun appendProgressNote(toolUseId: String?, note: String) {
            if (toolUseId.isNullOrBlank()) return
            val lines = toolProgressNotes.getOrPut(toolUseId) { mutableListOf() }
            if (lines.lastOrNull() == note) return
            lines.add(note)
            if (lines.size > 40) lines.removeAt(0)
        }

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
            if (toolUseBlocks.any { it.id == id }) return
            val toolBlock = ContentBlock.ToolUse(id, name, input)
            toolUseBlocks.add(toolBlock)
            textCheckpoints.add(responseText.length)
            toolEventOrder[id] = ++streamEventOrdinal
            trackToolUseForStatus(id, name, input)
            syncStatusToViewModel()
            rebuildFlowNow()
            viewModel.requestScrollToBottom()
        }

        fun ensureSyntheticTaskTool(toolUseId: String?, description: String, taskType: String?) {
            if (toolUseId.isNullOrBlank()) return
            if (toolUseBlocks.any { it.id == toolUseId }) return
            val syntheticInput = buildJsonObject {
                if (description.isNotBlank()) put("description", JsonPrimitive(description))
                if (!taskType.isNullOrBlank()) put("subagent_type", JsonPrimitive(taskType))
            }
            addToolUseBlock(toolUseId, "Task", syntheticInput)
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
                                    rebuildFlowDebounced()
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
                            rebuildFlowNow()
                            viewModel.requestScrollToBottom()
                        }

                        is StreamEvent.ToolProgress -> {
                            if (!event.taskId.isNullOrBlank() && event.toolUseId.isNotBlank()) {
                                taskToToolUseId[event.taskId] = event.toolUseId
                            }
                            if (event.toolUseId.isNotBlank()) {
                                val elapsedSec = event.elapsedTimeSeconds.toInt().coerceAtLeast(0)
                                appendProgressNote(
                                    event.toolUseId,
                                    "Running ${event.toolName.ifBlank { "tool" }} (${elapsedSec}s)"
                                )
                                rebuildFlowNow()
                            }
                        }

                        is StreamEvent.ToolUseSummary -> {
                            val summary = event.summary.trim()
                            if (summary.isBlank()) return@collect
                            event.precedingToolUseIds.forEach { toolId ->
                                if (toolId.isNotBlank()) {
                                    toolSummaryOverrides[toolId] = summary
                                    appendProgressNote(toolId, "Summary: $summary")
                                }
                            }
                            if (event.precedingToolUseIds.isNotEmpty()) {
                                rebuildFlowNow()
                            }
                        }

                        is StreamEvent.TaskStarted -> {
                            val toolUseId = event.toolUseId ?: taskToToolUseId[event.taskId]
                            if (!event.taskId.isBlank() && !toolUseId.isNullOrBlank()) {
                                taskToToolUseId[event.taskId] = toolUseId
                            }
                            ensureSyntheticTaskTool(toolUseId, event.description, event.taskType)
                            appendProgressNote(
                                toolUseId,
                                "Started: ${event.description.ifBlank { event.taskType ?: "task" }}"
                            )
                            rebuildFlowNow()
                        }

                        is StreamEvent.TaskProgress -> {
                            val toolUseId = event.toolUseId ?: taskToToolUseId[event.taskId]
                            if (!event.taskId.isBlank() && !toolUseId.isNullOrBlank()) {
                                taskToToolUseId[event.taskId] = toolUseId
                            }
                            ensureSyntheticTaskTool(toolUseId, event.description, null)
                            val metrics = mutableListOf<String>()
                            if (event.totalTokens > 0) metrics.add("${event.totalTokens} tok")
                            if (event.toolUses > 0) metrics.add("${event.toolUses} tools")
                            if (event.durationMs > 0) metrics.add("${event.durationMs / 1000}s")
                            val metricSuffix = if (metrics.isNotEmpty()) " (${metrics.joinToString(", ")})" else ""
                            val lastToolSuffix = event.lastToolName?.takeIf { it.isNotBlank() }?.let { " · last: $it" } ?: ""
                            appendProgressNote(
                                toolUseId,
                                "${event.description.ifBlank { "In progress" }}$metricSuffix$lastToolSuffix"
                            )
                            rebuildFlowNow()
                        }

                        is StreamEvent.TaskNotification -> {
                            val toolUseId = event.toolUseId ?: taskToToolUseId[event.taskId]
                            if (!event.taskId.isBlank() && !toolUseId.isNullOrBlank()) {
                                taskToToolUseId[event.taskId] = toolUseId
                            }
                            ensureSyntheticTaskTool(toolUseId, event.summary, null)

                            val statusLabel = when (event.status.lowercase()) {
                                "failed" -> "Failed"
                                "stopped" -> "Stopped"
                                else -> "Completed"
                            }
                            val summaryText = event.summary.ifBlank { "Task ${event.status}" }
                            appendProgressNote(toolUseId, "$statusLabel: $summaryText")
                            if (event.outputFile.isNotBlank()) {
                                appendProgressNote(toolUseId, "Output: ${event.outputFile}")
                            }

                            if (!toolUseId.isNullOrBlank()) {
                                val isErrorStatus = event.status.equals("failed", ignoreCase = true)
                                trackToolResultForStatus(toolUseId, isErrorStatus)
                                syncStatusToViewModel()
                                if (toolResults[toolUseId] == null) {
                                    val resultText = buildString {
                                        append(summaryText)
                                        if (event.outputFile.isNotBlank()) {
                                            appendLine()
                                            append("Output file: ${event.outputFile}")
                                        }
                                    }
                                    toolResults[toolUseId] = resultText to isErrorStatus
                                }
                            }
                            rebuildFlowNow()
                        }

                        is StreamEvent.CompactBoundary -> {
                            compactBoundaries.add(
                                CompactBoundaryMarker(
                                    textOffset = responseText.length,
                                    trigger = event.trigger,
                                    preTokens = event.preTokens,
                                    order = ++streamEventOrdinal,
                                )
                            )
                            rebuildFlowNow()
                        }

                        is StreamEvent.RateLimit -> {
                            val details = buildString {
                                append("Rate limit: ${event.status}")
                                event.rateLimitType?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
                                event.utilization?.let { append(", ${(it * 100).toInt()}% used") }
                            }
                            log.info(details)
                        }

                        is StreamEvent.AuthStatus -> {
                            when {
                                event.error != null -> log.warn("Auth error: ${event.error}")
                                event.isAuthenticating -> log.info("Authenticating with Claude...")
                                event.output.isNotEmpty() -> log.info("Auth status: ${event.output.last()}")
                            }
                        }

                        is StreamEvent.PromptSuggestion -> {
                            val suggestion = event.suggestion.trim()
                            if (suggestion.isNotBlank()) {
                                log.info("Prompt suggestion: $suggestion")
                                val updated = (listOf(suggestion) + viewModel.promptSuggestions)
                                    .distinct()
                                    .take(5)
                                viewModel.promptSuggestions = updated
                            }
                        }

                        is StreamEvent.UserMessageId -> {
                            if (event.id.isNotBlank()) {
                                lastSdkUserMessageId = event.id
                            }
                        }

                        is StreamEvent.Status -> {
                            // compact_boundary provides the visual marker; keep status as non-rendered signal.
                            event.permissionMode?.takeIf { it.isNotBlank() }?.let {
                                viewModel.initPermissionMode = it
                            }
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
                                        rebuildFlowDebounced()
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
                            persistCustomTitleIfPossible()
                            val blocks = buildAssistantBlocks(
                                thinkingText = thinkingText,
                                responseText = responseText,
                                toolUseBlocks = toolUseBlocks,
                                toolResults = toolResults,
                                textCheckpoints = textCheckpoints,
                                compactBoundaries = compactBoundaries,
                                toolEventOrder = toolEventOrder,
                            )
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
                                    toolResults.clear()
                                    compactBoundaries.clear()
                                    taskToToolUseId.clear()
                                    toolProgressNotes.clear()
                                    toolSummaryOverrides.clear()
                                    toolEventOrder.clear()
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

                        is StreamEvent.Init -> {
                            log.info("Init: model=${event.model} version=${event.claudeCodeVersion} tools=${event.tools.size} mcpServers=${event.mcpServers.size}")
                            viewModel.initModel = event.model
                            viewModel.initClaudeCodeVersion = event.claudeCodeVersion
                            viewModel.initTools = event.tools
                            viewModel.initMcpServers = event.mcpServers.map { "${it.name} (${it.status})" }
                            viewModel.initAgents = event.agents
                            viewModel.initSkills = event.skills
                            viewModel.initPlugins = event.plugins.map { it.name.ifBlank { it.path } }
                            viewModel.initSlashCommands = event.slashCommands
                            viewModel.initApiKeySource = event.apiKeySource
                            viewModel.initPermissionMode = event.permissionMode
                            viewModel.initFastModeState = event.fastModeState.orEmpty()
                            viewModel.initCwd = event.cwd
                            viewModel.initBetas = event.betas
                        }

                        is StreamEvent.ElicitationRequest -> {
                            val deferred = CompletableDeferred<Pair<Boolean, JsonElement?>>()
                            elicitationResultCallback = { allow, value -> deferred.complete(Pair(allow, value)) }
                            viewModel.elicitationData = event
                            viewModel.elicitationPanelVisible = true
                            viewModel.requestScrollToBottom()
                            val (allow, value) = deferred.await()
                            viewModel.elicitationPanelVisible = false
                            viewModel.elicitationData = null
                            elicitationResultCallback = null
                            if (allow && value != null) {
                                provider.sendElicitationResponse(true, value)
                            } else {
                                provider.sendElicitationResponse(false)
                            }
                        }

                        is StreamEvent.ResultMeta -> {
                            viewModel.lastResultMeta = event
                        }

                        is StreamEvent.HookEvent -> {
                            val detail = buildString {
                                append("Hook: ${event.hookName}")
                                event.toolName?.let { append(" ($it)") }
                                event.progress?.let { append(" — $it") }
                            }
                            log.info(detail)
                        }

                        is StreamEvent.FilesPersisted -> {
                            if (event.files.isNotEmpty()) {
                                log.info(UcuBundle.message("system.filesPersisted", event.files.size))
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
                    val blocks = buildAssistantBlocks(
                        thinkingText = thinkingText,
                        responseText = responseText,
                        toolUseBlocks = toolUseBlocks,
                        toolResults = toolResults,
                        textCheckpoints = textCheckpoints,
                        compactBoundaries = compactBoundaries,
                        toolEventOrder = toolEventOrder,
                        stopped = true,
                    )
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

    fun stopTask(taskId: String) {
        provider.stopTask(taskId)
    }

    fun setRuntimeModel(model: String) {
        provider.setModel(model)
        viewModel.initModel = model
    }

    fun setRuntimePermissionMode(mode: String) {
        provider.setPermissionMode(mode)
        viewModel.initPermissionMode = mode
    }

    fun applyPromptSuggestion(suggestion: String) {
        viewModel.inputText = suggestion
        viewModel.promptSuggestions = emptyList()
    }

    fun undoStatusFileChange(summary: FileChangeSummary): Boolean {
        val reverted = revertFileChange(summary)
        if (reverted) {
            statusTracker.removeFileChange(summary.filePath)
            syncStatusToViewModel()
        }
        return reverted
    }

    fun discardAllStatusFileChanges(): Int {
        val rewindId = lastSdkUserMessageId
        if (!rewindId.isNullOrBlank() && provider.rewindFiles(rewindId)) {
            val count = statusTracker.currentFileChanges.size
            statusTracker.clearFileChanges()
            syncStatusToViewModel()
            return count
        }
        val snapshot = statusTracker.currentFileChanges
        var reverted = 0
        snapshot.forEach { summary ->
            if (undoStatusFileChange(summary)) {
                reverted++
            }
        }
        return reverted
    }

    fun keepAllStatusFileChanges() {
        statusTracker.clearFileChanges()
        syncStatusToViewModel()
    }

    private fun revertFileChange(summary: FileChangeSummary): Boolean {
        val operations = summary.operations.toList()
        if (operations.isEmpty()) {
            return InteractiveDiffManager.revertWrite(project, summary.filePath)
        }

        // Revert in reverse order to restore the earliest file state.
        for (op in operations.asReversed()) {
            val lower = op.toolName.lowercase()
            val ok = when {
                lower in setOf("write", "write_to_file", "save-file", "create_file") ->
                    InteractiveDiffManager.revertWrite(project, summary.filePath)
                else ->
                    InteractiveDiffManager.revertEdit(summary.filePath, op.oldString, op.newString)
            }
            if (!ok) return false
        }
        return true
    }

    fun newChat() {
        onStopGeneration()
        messageQueue.clear()
        messages.clear()
        provider.resetSession()
        sessionId = null
        lastSdkUserMessageId = null
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
        viewModel.promptSuggestions = emptyList()
        viewModel.questionPanelVisible = false
        viewModel.planPanelVisible = false
        viewModel.pendingApproval = null
        viewModel.sessionTitle = ""
        viewModel.sessionTitleVisible = false
        viewModel.initModel = ""
        viewModel.initClaudeCodeVersion = ""
        viewModel.initTools = emptyList()
        viewModel.initMcpServers = emptyList()
        viewModel.initAgents = emptyList()
        viewModel.initSkills = emptyList()
        viewModel.initPlugins = emptyList()
        viewModel.initSlashCommands = emptyList()
        viewModel.initApiKeySource = ""
        viewModel.initPermissionMode = ""
        viewModel.initFastModeState = ""
        viewModel.initCwd = ""
        viewModel.initBetas = emptyList()
    }

    fun loadSession(loadSessionId: String) {
        onStopGeneration()
        val loaded = sessionManager.load(loadSessionId) ?: return
        messages.clear()
        messages.addAll(loaded)
        sessionId = loadSessionId
        lastSdkUserMessageId = null
        customTitle = sessionManager.getTitle(loadSessionId)
        viewModel.sessionTitle = customTitle ?: ""
        viewModel.sessionTitleVisible = customTitle != null
        provider.setResumeSessionId(loadSessionId)
        viewModel.promptSuggestions = emptyList()
        viewModel.initModel = ""
        viewModel.initClaudeCodeVersion = ""
        viewModel.initTools = emptyList()
        viewModel.initMcpServers = emptyList()
        viewModel.initAgents = emptyList()
        viewModel.initSkills = emptyList()
        viewModel.initPlugins = emptyList()
        viewModel.initSlashCommands = emptyList()
        viewModel.initApiKeySource = ""
        viewModel.initPermissionMode = ""
        viewModel.initFastModeState = ""
        viewModel.initCwd = ""
        viewModel.initBetas = emptyList()
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
        compactBoundaries: List<CompactBoundaryMarker> = emptyList(),
        toolEventOrder: Map<String, Int> = emptyMap(),
        @Suppress("UNUSED_PARAMETER") stopped: Boolean = false
    ): List<ContentBlock> {
        val blocks = mutableListOf<ContentBlock>()
        if (thinkingText.isNotEmpty()) {
            blocks.add(ContentBlock.Thinking(thinkingText.toString()))
        }

        val fullText = responseText.toString()
        val fullLen = fullText.length

        data class AnchoredEvent(
            val order: Int,
            val block: ContentBlock,
            val toolUse: ContentBlock.ToolUse? = null,
        )

        val eventsByOffset = mutableMapOf<Int, MutableList<AnchoredEvent>>()

        fun addEvent(offset: Int, event: AnchoredEvent) {
            val clamped = offset.coerceIn(0, fullLen)
            eventsByOffset.getOrPut(clamped) { mutableListOf() }.add(event)
        }

        for (i in toolUseBlocks.indices) {
            val toolUse = toolUseBlocks[i]
            val checkpoint = if (i < textCheckpoints.size) {
                textCheckpoints[i].coerceIn(0, fullLen)
            } else fullLen
            val order = toolEventOrder[toolUse.id] ?: (1_000_000 + i)
            addEvent(
                checkpoint,
                AnchoredEvent(
                    order = order,
                    block = toolUse,
                    toolUse = toolUse,
                )
            )
        }

        compactBoundaries.forEachIndexed { idx, marker ->
            addEvent(
                marker.textOffset,
                AnchoredEvent(
                    order = marker.order.takeIf { it > 0 } ?: (2_000_000 + idx),
                    block = ContentBlock.CompactBoundary(
                        trigger = marker.trigger,
                        preTokens = marker.preTokens,
                    ),
                )
            )
        }

        val offsets = (eventsByOffset.keys + fullLen).distinct().sorted()
        var prevIdx = 0
        for (offset in offsets) {
            val segment = fullText.substring(prevIdx.coerceIn(0, fullLen), offset.coerceIn(0, fullLen))
            if (segment.isNotBlank()) {
                blocks.add(ContentBlock.Text(segment))
            }

            eventsByOffset[offset]
                ?.sortedBy { it.order }
                ?.forEach { anchored ->
                    blocks.add(anchored.block)
                    anchored.toolUse?.let { toolUse ->
                        toolResults[toolUse.id]?.let { (content, isError) ->
                            if (content.isNotBlank()) {
                                blocks.add(ContentBlock.ToolResult(toolUseId = toolUse.id, content = content, isError = isError))
                            }
                        }
                    }
                }

            prevIdx = offset
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
        compactBoundaries: List<CompactBoundaryMarker>,
        toolProgressNotes: Map<String, List<String>>,
        toolSummaryOverrides: Map<String, String>,
        toolEventOrder: Map<String, Int>,
        taskToToolUseId: Map<String, String> = emptyMap(),
    ) {
        if (toolUseBlocks.isEmpty() && compactBoundaries.isEmpty()) {
            viewModel.streamingContentFlow = emptyList()
            return
        }

        // Reverse mapping: toolUseId → taskId (for "stop task" button in UI)
        val toolUseIdToTaskId = taskToToolUseId.entries.associate { (k, v) -> v to k }

        // First pass: build flat list of flow items with tool data
        data class ToolEntry(
            val tool: ContentBlock.ToolUse,
            val status: ToolStatus,
            val expandable: ExpandableContent?,
            val summary: String,
            val diffAdd: Int,
            val diffDel: Int,
            val category: ToolCategoryType,
        )

        data class OffsetEvent(
            val order: Int,
            val item: Any, // ToolEntry or ContentFlowItem.CompactBoundary
        )

        val flatFlow = mutableListOf<Any>() // TextSegment | CompactBoundary | ToolEntry
        val fullText = responseText.toString()
        val fullLen = fullText.length

        val eventsByOffset = mutableMapOf<Int, MutableList<OffsetEvent>>()

        fun addEvent(offset: Int, event: OffsetEvent) {
            val clamped = offset.coerceIn(0, fullLen)
            eventsByOffset.getOrPut(clamped) { mutableListOf() }.add(event)
        }

        for (i in toolUseBlocks.indices) {
            val tool = toolUseBlocks[i]
            val result = toolResults[tool.id]
            val status = when {
                result?.second == true -> ToolStatus.ERROR
                result != null -> ToolStatus.COMPLETED
                else -> ToolStatus.PENDING
            }
            val progress = toolProgressNotes[tool.id].orEmpty()
            val expandable = buildExpandableContent(tool.name, tool.input, result?.first, progress)
            val summary = toolSummaryOverrides[tool.id]
                ?.takeIf { it.isNotBlank() }
                ?: ToolSummaryExtractor.extractToolSummary(tool.name, tool.input)
            val (diffAdd, diffDel) = computeDiffStatsForTool(tool.name, tool.input)
            val checkpoint = if (i < textCheckpoints.size) textCheckpoints[i] else fullLen
            val toolOrder = toolEventOrder[tool.id] ?: (1_000_000 + i)
            addEvent(
                checkpoint,
                OffsetEvent(
                    order = toolOrder,
                    item = ToolEntry(
                        tool = tool,
                        status = status,
                        expandable = expandable,
                        summary = summary,
                        diffAdd = diffAdd,
                        diffDel = diffDel,
                        category = classifyToolCategory(tool.name),
                    )
                )
            )
        }

        compactBoundaries.forEachIndexed { idx, marker ->
            addEvent(
                marker.textOffset,
                OffsetEvent(
                    order = marker.order.takeIf { it > 0 } ?: (2_000_000 + idx),
                    item = ContentFlowItem.CompactBoundary(
                        data = CompactBoundaryData(
                            id = "stream_${marker.order}_${idx}",
                            trigger = marker.trigger,
                            preTokens = marker.preTokens,
                        )
                    )
                )
            )
        }

        val offsets = (eventsByOffset.keys + fullLen).distinct().sorted()
        var prevIdx = 0
        for (offset in offsets) {
            val clampedOffset = offset.coerceIn(0, fullLen)
            val segment = fullText.substring(prevIdx.coerceIn(0, fullLen), clampedOffset)
            if (segment.isNotBlank()) {
                flatFlow.add(ContentFlowItem.TextSegment(segment))
            }
            eventsByOffset[offset]
                ?.sortedBy { it.order }
                ?.forEach { event -> flatFlow.add(event.item) }
            prevIdx = clampedOffset
        }

        // Second pass: group consecutive tool entries of same category
        val flow = mutableListOf<ContentFlowItem>()
        var i = 0
        while (i < flatFlow.size) {
            val item = flatFlow[i]
            if (item !is ToolEntry) {
                flow.add(item as ContentFlowItem)
                i++
                continue
            }
            val entry = item
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
                    summary = e.summary,
                    status = e.status,
                    expandable = e.expandable,
                    diffAdditions = e.diffAdd,
                    diffDeletions = e.diffDel,
                    filePath = ToolSummaryExtractor.extractFilePath(e.tool.input),
                    taskId = toolUseIdToTaskId[e.tool.id],
                )))
            } else {
                // Multiple tools — render as group
                val groupItems = groupEntries.map { e ->
                    ToolGroupItemData(
                        id = e.tool.id,
                        toolName = e.tool.name,
                        summary = e.summary,
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
            lower in SKILL_TOOL_NAMES -> ToolCategoryType.SKILL
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
        progressNotes: List<String> = emptyList(),
    ): ExpandableContent? {
        val lower = toolName.lowercase()
        if (lower in setOf("read", "read_file")) {
            return null
        }
        if (lower == "task" || lower == "taskoutput") {
            val markdown = buildTaskExpandableMarkdown(input, resultContent, progressNotes)
            if (markdown.isNotBlank()) return ExpandableContent.Markdown(markdown)
        }
        if (ToolSummaryExtractor.isSkillTool(toolName)) {
            val markdown = buildSkillExpandableMarkdown(input, resultContent, progressNotes)
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
        if (progressNotes.isNotEmpty()) {
            return ExpandableContent.PlainText(progressNotes.joinToString("\n"))
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

    private fun buildSkillExpandableMarkdown(
        input: JsonObject,
        resultContent: String?,
        progressNotes: List<String> = emptyList(),
    ): String {
        val skillName = ToolSummaryExtractor.extractSkillName(input).orEmpty()
        val inputField = listOf("input", "arguments", "args", "params", "prompt", "query", "task", "request")
            .firstNotNullOfOrNull { key -> input[key]?.let { key to it } }
        val result = resultContent?.trim().orEmpty()

        fun stringifyForMarkdown(element: JsonElement): String {
            return when (element) {
                is JsonPrimitive -> element.contentOrNull.orEmpty()
                is JsonObject, is JsonArray -> "```json\n${element}\n```"
            }
        }

        return buildString {
            if (skillName.isNotBlank()) {
                appendLine("### Skill")
                append("`")
                append(skillName)
                appendLine("`")
            }

            if (inputField != null) {
                val (key, value) = inputField
                val rendered = stringifyForMarkdown(value).trim()
                if (rendered.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    appendLine("### Input ($key)")
                    appendLine(rendered)
                }
            }

            if (result.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine("### Result")
                appendLine(result)
            }

            if (progressNotes.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("### Progress")
                progressNotes.forEach { note ->
                    append("- ")
                    appendLine(note)
                }
            }

            if (isEmpty()) {
                appendLine("### Skill input")
                appendLine("```json")
                appendLine(input.toString())
                appendLine("```")
            }
        }.trim()
    }

    /**
     * Compose readable expandable markdown for agent tools (task/taskoutput).
     */
    private fun buildTaskExpandableMarkdown(
        input: JsonObject,
        resultContent: String?,
        progressNotes: List<String> = emptyList(),
    ): String {
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
            if (progressNotes.isNotEmpty()) {
                if (isNotEmpty()) appendLine()
                appendLine("### Progress")
                progressNotes.forEach { note ->
                    append("- ")
                    appendLine(note)
                }
            }
            if (isEmpty()) {
                appendLine("### Task input")
                appendLine("```json")
                appendLine(input.toString())
                appendLine("```")
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
