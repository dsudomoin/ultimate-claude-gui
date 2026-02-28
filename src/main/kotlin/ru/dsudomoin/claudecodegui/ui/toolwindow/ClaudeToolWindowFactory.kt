package ru.dsudomoin.claudecodegui.ui.toolwindow

import ru.dsudomoin.claudecodegui.UcuBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import ru.dsudomoin.claudecodegui.service.ProjectFileIndexService
import ru.dsudomoin.claudecodegui.service.SettingsService
import ru.dsudomoin.claudecodegui.ui.chat.ChatController
import ru.dsudomoin.claudecodegui.ui.compose.input.AttachedImageData
import ru.dsudomoin.claudecodegui.ui.compose.input.FileContextData
import ru.dsudomoin.claudecodegui.ui.compose.input.MentionChipData
import ru.dsudomoin.claudecodegui.ui.compose.input.MentionSuggestionData
import ru.dsudomoin.claudecodegui.ui.compose.toolwindow.ChatCallbacks
import ru.dsudomoin.claudecodegui.ui.compose.toolwindow.createComposeChatPanel
import ru.dsudomoin.claudecodegui.ui.diff.InteractiveDiffManager
import ru.dsudomoin.claudecodegui.service.PromptEnhancer
import ru.dsudomoin.claudecodegui.ui.theme.ThemeManager
import ru.dsudomoin.claudecodegui.core.session.SessionManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JPanel
import javax.swing.SwingUtilities

class ClaudeToolWindowFactory : ToolWindowFactory {

    private val tabCounter = AtomicInteger(0)

    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        ThemeManager.initialize()
        addNewTab(project, toolWindow)

        val contentManagerListener = object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) {
                updateTabCloseableState(toolWindow)
            }
            override fun contentRemoved(event: ContentManagerEvent) {
                if (toolWindow.contentManager.contentCount == 0) {
                    addNewTab(project, toolWindow)
                }
                updateTabCloseableState(toolWindow)
            }
        }
        toolWindow.contentManager.addContentManagerListener(contentManagerListener)
        registerProjectDispose(project) {
            runCatching { toolWindow.contentManager.removeContentManagerListener(contentManagerListener) }
        }

        val titleActions = listOf(
            object : AnAction(UcuBundle.message("toolwindow.newChat"), UcuBundle.message("toolwindow.newChatDesc"), AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = addNewTab(project, toolWindow)
                override fun update(e: AnActionEvent) {
                    e.presentation.text = UcuBundle.message("toolwindow.newChat")
                    e.presentation.description = UcuBundle.message("toolwindow.newChatDesc")
                }
            },
            object : AnAction(UcuBundle.message("toolwindow.history"), UcuBundle.message("toolwindow.historyDesc"), AllIcons.Vcs.History) {
                override fun actionPerformed(e: AnActionEvent) = toggleHistory(project, toolWindow)
                override fun update(e: AnActionEvent) {
                    e.presentation.text = UcuBundle.message("toolwindow.history")
                    e.presentation.description = UcuBundle.message("toolwindow.historyDesc")
                }
            },
            object : AnAction(UcuBundle.message("toolwindow.settings"), UcuBundle.message("toolwindow.settingsDesc"), AllIcons.General.GearPlain) {
                override fun actionPerformed(e: AnActionEvent) {
                    ShowSettingsUtil.getInstance()
                        .showSettingsDialog(project, ru.dsudomoin.claudecodegui.settings.ClaudeSettingsConfigurable::class.java)
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.text = UcuBundle.message("toolwindow.settings")
                    e.presentation.description = UcuBundle.message("toolwindow.settingsDesc")
                }
            }
        )
        toolWindow.setTitleActions(titleActions)

        val renameAction = object : AnAction(UcuBundle.message("toolwindow.rename"), UcuBundle.message("toolwindow.renameDesc"), AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                renameCurrentTab(project, toolWindow)
            }
        }
        toolWindow.setAdditionalGearActions(DefaultActionGroup(renameAction))
        registerProjectDispose(project) {
            runCatching { toolWindow.setTitleActions(emptyList()) }
            runCatching { toolWindow.setAdditionalGearActions(DefaultActionGroup()) }
        }

        SwingUtilities.invokeLater {
            installDoubleClickRename(project, toolWindow)
        }
    }

    private fun addNewTab(project: Project, toolWindow: ToolWindow) {
        val num = tabCounter.incrementAndGet()
        val container = ChatContainerPanel(project, toolWindow)
        val title = if (num == 1) UcuBundle.message("chat.tab") else "${UcuBundle.message("chat.tab")} $num"
        val content = ContentFactory.getInstance().createContent(container, title, false).apply {
            isCloseable = true
            setDisposer(container)
        }
        // Update tab title when session title changes (e.g., after first response)
        container.controller.onSessionTitleChanged = { newTitle ->
            content.displayName = newTitle
        }
        toolWindow.contentManager.addContent(content)
        toolWindow.contentManager.setSelectedContent(content)
    }

    private fun updateTabCloseableState(toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val closeable = contentManager.contentCount > 1
        for (content in contentManager.contents) {
            content.isCloseable = closeable
        }
    }

    private var doubleClickInstalled = false

    private fun installDoubleClickRename(project: Project, toolWindow: ToolWindow) {
        if (doubleClickInstalled) return
        doubleClickInstalled = true

        val mouseListener = object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    renameCurrentTab(project, toolWindow)
                }
            }
        }
        toolWindow.contentManager.component.addMouseListener(mouseListener)
        registerProjectDispose(project) {
            runCatching { toolWindow.contentManager.component.removeMouseListener(mouseListener) }
        }
    }

    private fun registerProjectDispose(project: Project, onDispose: () -> Unit) {
        Disposer.register(project, Disposable { onDispose() })
    }

    private fun toggleHistory(project: Project, toolWindow: ToolWindow) {
        val selectedContent = toolWindow.contentManager.selectedContent ?: return
        val container = selectedContent.component as? ChatContainerPanel ?: return
        container.toggleHistory()
    }

    private fun renameCurrentTab(project: Project, toolWindow: ToolWindow) {
        val content = toolWindow.contentManager.selectedContent ?: return
        val container = content.component as? ChatContainerPanel ?: return
        val controller = container.controller

        val currentTitle = content.displayName ?: UcuBundle.message("chat.tab")
        val newTitle = Messages.showInputDialog(
            project,
            UcuBundle.message("toolwindow.renamePrompt"),
            UcuBundle.message("toolwindow.rename"),
            null,
            currentTitle,
            null
        ) ?: return

        if (newTitle.isNotBlank()) {
            controller.renameSession(newTitle)
        }
    }
}

/**
 * Container that switches between chat (Compose) and history (Swing) views.
 * ChatController is a non-visual business-logic controller; its viewModel drives the Compose UI.
 */
class ChatContainerPanel(
    private val project: Project,
    private val toolWindow: ToolWindow
) : JPanel(BorderLayout()), Disposable {

    val controller = ChatController(project)
    /** EDT dispatcher — avoids dependency on kotlinx-coroutines-swing (classloader conflicts). */
    private val edtDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            javax.swing.SwingUtilities.invokeLater(block)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + edtDispatcher)
    private var lastMentionResults: List<ProjectFileIndexService.FileEntry> = emptyList()

    private val callbacks = ChatCallbacks(
        onSendOrStop = {
            val hasText = controller.viewModel.inputText.trim().isNotBlank()
            if (hasText) {
                // Send or queue — sendFromCompose → onSendMessage handles queueing
                controller.sendFromCompose()
            } else if (controller.viewModel.isSending) {
                controller.stopGeneration()
            }
        },
        onAttachClick = { showFileChooser() },
        onPasteImage = { handleClipboardPaste() },
        onStreamingToggle = {
            val settings = SettingsService.getInstance()
            settings.state.streamingEnabled = !settings.state.streamingEnabled
            controller.viewModel.streamingEnabled = settings.state.streamingEnabled
        },
        onThinkingToggle = {
            val settings = SettingsService.getInstance()
            settings.state.thinkingEnabled = !settings.state.thinkingEnabled
            controller.viewModel.thinkingEnabled = settings.state.thinkingEnabled
        },
        onEffortChange = { newEffort ->
            val settings = SettingsService.getInstance()
            settings.state.effort = newEffort
            controller.viewModel.effort = newEffort
        },
        onBetaContextToggle = {
            val settings = SettingsService.getInstance()
            settings.state.betaContext1m = !settings.state.betaContext1m
            controller.viewModel.betaContext1m = settings.state.betaContext1m
        },
        onModelSelect = { modelId -> selectModel(modelId) },
        onModeSelect = { modeId -> selectMode(modeId) },
        onEnhanceClick = { launchEnhancer() },
        onFileContextClick = { path -> openFileInEditor(path) },
        onFileContextRemove = { controller.viewModel.fileContext = null },
        onImageClick = { path -> openFileInEditor(path) },
        onImageRemove = { path ->
            controller.viewModel.attachedImages = controller.viewModel.attachedImages.filter { it.filePath != path }
        },
        onMentionRemove = { absolutePath ->
            controller.viewModel.mentionedFiles = controller.viewModel.mentionedFiles.filter { it.absolutePath != absolutePath }
        },
        onMentionQuery = { query -> searchMentions(query) },
        onMentionSelect = { index -> selectMention(index) },
        onMentionDismiss = {
            controller.viewModel.mentionPopupVisible = false
            controller.viewModel.mentionSuggestions = emptyList()
        },
        onPromptSuggestionSelect = { suggestion ->
            controller.applyPromptSuggestion(suggestion)
        },
        onFileClick = { path -> openFileInEditor(path) },
        onToolShowDiff = { expandable ->
            when (expandable) {
                is ru.dsudomoin.claudecodegui.ui.compose.chat.ExpandableContent.Diff -> {
                    val filePath = expandable.filePath
                    if (filePath != null) {
                        InteractiveDiffManager.showEditDiff(project, filePath, expandable.oldString, expandable.newString)
                    }
                }
                is ru.dsudomoin.claudecodegui.ui.compose.chat.ExpandableContent.Code -> {
                    val filePath = expandable.filePath
                    if (filePath != null) {
                        InteractiveDiffManager.showWriteDiff(project, filePath, expandable.content)
                    }
                }
                else -> {}
            }
        },
        onToolRevert = { expandable ->
            when (expandable) {
                is ru.dsudomoin.claudecodegui.ui.compose.chat.ExpandableContent.Diff -> {
                    val filePath = expandable.filePath
                    if (filePath != null) {
                        InteractiveDiffManager.revertEdit(filePath, expandable.oldString, expandable.newString)
                    }
                }
                is ru.dsudomoin.claudecodegui.ui.compose.chat.ExpandableContent.Code -> {
                    val filePath = expandable.filePath
                    if (filePath != null) {
                        InteractiveDiffManager.revertWrite(project, filePath)
                    }
                }
                else -> {}
            }
        },
        onStopTask = { taskId -> controller.stopTask(taskId) },
        onStatusFileClick = { path -> openFileInEditor(path) },
        onStatusShowDiff = { summary ->
            val original = summary.operations.joinToString("\n") { it.oldString }
            val modified = summary.operations.joinToString("\n") { it.newString }
            InteractiveDiffManager.showDiff(project, summary.filePath, original, modified)
        },
        onStatusUndoFile = { summary ->
            controller.undoStatusFileChange(summary)
        },
        onStatusDiscardAll = {
            controller.discardAllStatusFileChanges()
        },
        onStatusKeepAll = {
            controller.keepAllStatusFileChanges()
        },
        onQueueRemove = { index -> controller.removeFromQueue(index) },
        onQuestionSubmit = { answers -> controller.submitQuestion(answers) },
        onQuestionCancel = { controller.cancelQuestion() },
        onPlanApprove = { controller.approvePlan() },
        onPlanApproveCompact = { controller.approvePlanAndCompact() },
        onPlanDeny = { controller.denyPlan() },
        onApprovalApprove = { controller.approvePermission() },
        onApprovalReject = { controller.rejectPermission() },
        onElicitationSubmit = { value -> controller.submitElicitation(value) },
        onElicitationCancel = { controller.cancelElicitation() },
        onInstallSdk = { controller.installSdk() },
        onInstallNode = { controller.installNode() },
        onLogin = { controller.runLogin() },
        onDownloadNode = { controller.openNodeDownloadPage() },
        onUpdateSdk = { controller.updateSdk() },
        onLoadSession = { sessionId -> loadHistorySession(sessionId) },
        onHistoryBack = { controller.viewModel.showingHistory = false },
        onHistoryRefresh = { refreshHistorySessions() },
        onTitleChange = { newTitle ->
            controller.renameSession(newTitle)
        },
    )

    private val composeChatPanel = createComposeChatPanel(controller.viewModel, callbacks)

    init {
        add(composeChatPanel, BorderLayout.CENTER)
        syncViewModelFromSettings()
        ProjectFileIndexService.getInstance(project).ensureIndexed()
        installClipboardPasteHandler()
        installEditorContextTracker()
    }

    private var keyDispatcher: java.awt.KeyEventDispatcher? = null

    /**
     * Install a global key event dispatcher that intercepts:
     * - Cmd+V / Ctrl+V for clipboard image paste
     * - Enter / Escape for plan approval/denial
     * This fires before IntelliJ's IdeKeyEventDispatcher
     * and before Compose's BasicTextField can consume the event.
     */
    private fun installClipboardPasteHandler() {
        val pasteModifier = java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        keyDispatcher = java.awt.KeyEventDispatcher { e ->
            if (e.id != java.awt.event.KeyEvent.KEY_PRESSED) return@KeyEventDispatcher false

            // Only handle if focus is within our panel
            val focusOwner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            if (focusOwner == null || !javax.swing.SwingUtilities.isDescendingFrom(focusOwner, composeChatPanel)) {
                return@KeyEventDispatcher false
            }

            // Plan approval: Enter → approve, Escape → deny
            if (controller.viewModel.planPanelVisible) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_ENTER -> {
                        com.intellij.openapi.diagnostic.Logger.getInstance(ChatContainerPanel::class.java)
                            .info("KeyDispatcher: Enter pressed while plan visible → approvePlan()")
                        controller.approvePlan()
                        return@KeyEventDispatcher true
                    }
                    java.awt.event.KeyEvent.VK_ESCAPE -> {
                        com.intellij.openapi.diagnostic.Logger.getInstance(ChatContainerPanel::class.java)
                            .info("KeyDispatcher: Escape pressed while plan visible → denyPlan()")
                        controller.denyPlan()
                        return@KeyEventDispatcher true
                    }
                }
            }

            // Approval panel: Enter → approve, Escape → reject
            if (controller.viewModel.pendingApproval != null) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_ENTER -> {
                        controller.approvePermission()
                        return@KeyEventDispatcher true
                    }
                    java.awt.event.KeyEvent.VK_ESCAPE -> {
                        controller.rejectPermission()
                        return@KeyEventDispatcher true
                    }
                }
            }

            // Clipboard paste: Cmd+V / Ctrl+V
            if (e.keyCode == java.awt.event.KeyEvent.VK_V
                && (e.modifiersEx and pasteModifier) != 0
            ) {
                val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                if (handleTransferableImage(clipboard.getContents(null))) {
                    return@KeyEventDispatcher true // consumed
                }
            }

            false // not consumed, pass through
        }
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(keyDispatcher)
    }

    private fun handleTransferableImage(transferable: java.awt.datatransfer.Transferable?): Boolean {
        if (transferable == null) return false
        try {
            // Check for image data (e.g. screenshot in clipboard)
            if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.imageFlavor)) {
                val image = transferable.getTransferData(java.awt.datatransfer.DataFlavor.imageFlavor) as java.awt.Image
                return saveImageAsAttachment(image)
            }
            // Check for file list (e.g. Finder copy)
            if (transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<java.io.File>
                val imageExts = setOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
                val imageFiles = files.filter { it.extension.lowercase() in imageExts }
                if (imageFiles.isNotEmpty()) {
                    val newAttachments = imageFiles.map { f ->
                        AttachedImageData(fileName = f.name, filePath = f.absolutePath)
                    }
                    controller.viewModel.attachedImages = controller.viewModel.attachedImages + newAttachments
                    return true
                }
            }
        } catch (_: Exception) { }
        return false
    }

    private fun saveImageAsAttachment(image: java.awt.Image): Boolean {
        try {
            val buffered = if (image is java.awt.image.BufferedImage) image
            else {
                val bimg = java.awt.image.BufferedImage(
                    image.getWidth(null), image.getHeight(null),
                    java.awt.image.BufferedImage.TYPE_INT_ARGB
                )
                val g = bimg.createGraphics()
                g.drawImage(image, 0, 0, null)
                g.dispose()
                bimg
            }
            val timestamp = System.currentTimeMillis()
            val tempDir = java.nio.file.Files.createDirectories(
                java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "claude-code-gui")
            )
            val tempFile = tempDir.resolve("screenshot-$timestamp.png")
            javax.imageio.ImageIO.write(buffered, "png", tempFile.toFile())
            controller.viewModel.attachedImages = controller.viewModel.attachedImages + AttachedImageData(
                fileName = "screenshot-$timestamp.png",
                filePath = tempFile.toString(),
            )
            return true
        } catch (_: Exception) { }
        return false
    }

    // ── Model / Mode selection (Compose popups call these) ─────────────────

    private fun selectModel(modelId: String) {
        val settings = SettingsService.getInstance()
        settings.state.claudeModel = modelId
        controller.setRuntimeModel(modelId)
        controller.viewModel.selectedModelId = modelId
        controller.viewModel.selectedModelName = when {
            modelId == "claude-opus-4-6-max" -> "Opus 4.6 (1M)"
            modelId.contains("sonnet") -> "Sonnet 4.6"
            modelId.contains("opus") -> "Opus 4.6"
            modelId.contains("haiku") -> "Haiku 4.5"
            else -> modelId
        }
    }

    private fun selectMode(modeId: String) {
        val settings = SettingsService.getInstance()
        settings.state.permissionMode = modeId
        controller.setRuntimePermissionMode(modeId)
        controller.viewModel.selectedModeId = modeId
        controller.viewModel.modeLabel = when (modeId) {
            "default" -> UcuBundle.message("mode.default.name")
            "plan" -> UcuBundle.message("mode.plan.name")
            "autoEdit" -> UcuBundle.message("mode.agent.name")
            "bypassPermissions" -> UcuBundle.message("mode.auto.name")
            else -> modeId
        }
    }

    // ── File attachment chooser ─────────────────────────────────────────────

    private fun showFileChooser() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
            .withTitle(UcuBundle.message("input.chooseImage"))
        val projectDir = project.guessProjectDir()
        FileChooser.chooseFiles(descriptor, project, projectDir) { files ->
            val images = files.map { vf ->
                AttachedImageData(fileName = vf.name, filePath = vf.path)
            }
            controller.viewModel.attachedImages = controller.viewModel.attachedImages + images
        }
    }

    // ── Clipboard paste for screenshots ──────────────────────────────────────

    private fun handleClipboardPaste(): Boolean {
        val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        if (clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
            try {
                val image = clipboard.getData(java.awt.datatransfer.DataFlavor.imageFlavor) as java.awt.Image
                val buffered = if (image is java.awt.image.BufferedImage) image
                else {
                    val bimg = java.awt.image.BufferedImage(image.getWidth(null), image.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB)
                    val g = bimg.createGraphics()
                    g.drawImage(image, 0, 0, null)
                    g.dispose()
                    bimg
                }
                // Save to temp file
                val timestamp = System.currentTimeMillis()
                val tempDir = java.nio.file.Files.createDirectories(java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "claude-code-gui"))
                val tempFile = tempDir.resolve("screenshot-$timestamp.png")
                javax.imageio.ImageIO.write(buffered, "png", tempFile.toFile())
                controller.viewModel.attachedImages = controller.viewModel.attachedImages + AttachedImageData(
                    fileName = "screenshot-$timestamp.png",
                    filePath = tempFile.toString(),
                )
                return true
            } catch (_: Exception) { }
        }
        return false
    }

    // ── @ mention search ─────────────────────────────────────────────────────

    private fun searchMentions(query: String) {
        val results = ProjectFileIndexService.getInstance(project).search(query, 10)
        lastMentionResults = results
        controller.viewModel.mentionSuggestions = results.map { entry ->
            MentionSuggestionData(
                fileName = entry.fileName,
                relativePath = entry.relativePath,
                absolutePath = entry.absolutePath,
                isLibrary = entry.source == ProjectFileIndexService.FileSource.LIBRARY,
                libraryName = entry.libraryName,
            )
        }
        controller.viewModel.mentionPopupVisible = results.isNotEmpty()
    }

    private fun selectMention(index: Int) {
        if (index !in lastMentionResults.indices) return
        val entry = lastMentionResults[index]

        // Add to mentioned files
        val alreadyMentioned = controller.viewModel.mentionedFiles.any { it.absolutePath == entry.absolutePath }
        if (!alreadyMentioned) {
            controller.viewModel.mentionedFiles = controller.viewModel.mentionedFiles + MentionChipData(
                fileName = entry.fileName,
                relativePath = entry.relativePath,
                absolutePath = entry.absolutePath,
            )
        }

        // Remove @query from input text
        val text = controller.viewModel.inputText
        val atIdx = text.lastIndexOf('@')
        if (atIdx >= 0) {
            val afterAt = text.substring(atIdx + 1)
            val queryLen = afterAt.takeWhile { it != ' ' && it != '\n' }.length
            controller.viewModel.inputText = text.substring(0, atIdx) + text.substring(atIdx + 1 + queryLen)
        }

        // Close popup
        controller.viewModel.mentionPopupVisible = false
        controller.viewModel.mentionSuggestions = emptyList()
    }

    // ── Prompt enhancer ───────────────────────────────────────────────────────

    private fun launchEnhancer() {
        val vm = controller.viewModel
        val text = vm.inputText.trim()
        if (text.isEmpty()) return

        vm.enhancerOriginalText = text
        vm.enhancerEnhancedText = ""
        vm.enhancerLoading = true
        vm.enhancerError = null
        vm.enhancerVisible = true

        val cwd = project.guessProjectDir()?.path
        val model = vm.selectedModelId

        scope.launch {
            try {
                val result = PromptEnhancer.enhance(text, model, cwd)
                if (result != null) {
                    vm.enhancerEnhancedText = result
                } else {
                    vm.enhancerError = ru.dsudomoin.claudecodegui.UcuBundle.message("enhancer.failed")
                }
            } catch (e: Exception) {
                vm.enhancerError = e.message ?: ru.dsudomoin.claudecodegui.UcuBundle.message("enhancer.failed")
            } finally {
                vm.enhancerLoading = false
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun syncViewModelFromSettings() {
        val settings = SettingsService.getInstance()
        val modelId = settings.state.claudeModel
        controller.viewModel.selectedModelId = modelId
        controller.viewModel.selectedModelName = when {
            modelId == "claude-opus-4-6-max" -> "Opus 4.6 (1M)"
            modelId.contains("sonnet") -> "Sonnet 4.6"
            modelId.contains("opus") -> "Opus 4.6"
            modelId.contains("haiku") -> "Haiku 4.5"
            else -> modelId
        }
        val modeId = settings.state.permissionMode
        controller.viewModel.selectedModeId = modeId
        controller.viewModel.modeLabel = when (modeId) {
            "default" -> UcuBundle.message("mode.default.name")
            "plan" -> UcuBundle.message("mode.plan.name")
            "autoEdit" -> UcuBundle.message("mode.agent.name")
            "bypassPermissions" -> UcuBundle.message("mode.auto.name")
            else -> modeId
        }
        controller.viewModel.streamingEnabled = settings.state.streamingEnabled
        controller.viewModel.thinkingEnabled = settings.state.thinkingEnabled
        controller.viewModel.effort = settings.state.effort
        controller.viewModel.betaContext1m = settings.state.betaContext1m
    }

    // ── History toggle ───────────────────────────────────────────────────────

    fun toggleHistory() {
        if (controller.viewModel.showingHistory) {
            controller.viewModel.showingHistory = false
        } else {
            refreshHistorySessions()
            controller.viewModel.showingHistory = true
        }
    }

    private fun refreshHistorySessions() {
        val sessions = SessionManager.getInstance(project).listSessions()
        controller.viewModel.historySessions = sessions
    }

    private fun loadHistorySession(sessionId: String) {
        controller.loadSession(sessionId)
        toolWindow.contentManager.selectedContent?.displayName = controller.sessionTitle
        controller.viewModel.showingHistory = false
    }

    private fun openFileInEditor(path: String) {
        val vFile = LocalFileSystem.getInstance().findFileByPath(path) ?: return
        FileEditorManager.getInstance(project).openFile(vFile, true)
    }

    // ── Editor context tracker ───────────────────────────────────────────────

    private var currentCaretListener: com.intellij.openapi.editor.event.CaretListener? = null
    private var currentTrackedEditor: com.intellij.openapi.editor.Editor? = null

    private fun installEditorContextTracker() {
        // Set initial file context
        updateFileContextFromEditor()

        // Listen for file selection changes
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    updateFileContextFromEditor()
                }

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    // If the closed file was the context file, clear
                    val currentPath = controller.viewModel.fileContext?.filePath
                    if (currentPath == file.path) {
                        controller.viewModel.fileContext = null
                    }
                }
            }
        )
    }

    /**
     * Attach a caret listener to the given editor to track cursor/selection changes.
     * Removes any previous listener from the previously tracked editor.
     */
    private fun attachCaretListener(editor: com.intellij.openapi.editor.Editor) {
        // Remove old listener if tracking a different editor
        if (currentTrackedEditor != null && currentTrackedEditor !== editor) {
            currentCaretListener?.let { listener ->
                currentTrackedEditor?.caretModel?.removeCaretListener(listener)
            }
        }

        if (currentTrackedEditor === editor) return // already tracking

        val listener = object : com.intellij.openapi.editor.event.CaretListener {
            override fun caretPositionChanged(event: com.intellij.openapi.editor.event.CaretEvent) {
                updateFileContextFromEditor()
            }
        }
        editor.caretModel.addCaretListener(listener)
        currentCaretListener = listener
        currentTrackedEditor = editor
    }

    private fun updateFileContextFromEditor() {
        val editorManager = FileEditorManager.getInstance(project)
        val editor = editorManager.selectedTextEditor
        val vFile = editorManager.selectedFiles.firstOrNull()

        if (editor == null || vFile == null) {
            controller.viewModel.fileContext = null
            return
        }

        // Attach caret listener so we track selection/cursor changes within the same file
        attachCaretListener(editor)

        val selection = editor.selectionModel
        if (!selection.hasSelection()) {
            controller.viewModel.fileContext = null
            return
        }

        val startLine = editor.document.getLineNumber(selection.selectionStart) + 1
        val endLine = editor.document.getLineNumber(selection.selectionEnd) + 1
        val lineRange = if (startLine == endLine) "#L$startLine" else "#L$startLine-L$endLine"

        controller.viewModel.fileContext = FileContextData(
            fileName = vFile.name,
            lineRange = lineRange,
            filePath = vFile.path,
        )
    }

    override fun dispose() {
        scope.cancel()
        keyDispatcher?.let {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(it)
        }
        currentCaretListener?.let { listener ->
            currentTrackedEditor?.caretModel?.removeCaretListener(listener)
        }
        currentCaretListener = null
        currentTrackedEditor = null
        controller.dispose()
    }

}
