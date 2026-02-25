package ru.dsudomoin.claudecodegui.ui.toolwindow

import ru.dsudomoin.claudecodegui.UcuBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
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

        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentAdded(event: ContentManagerEvent) {
                updateTabCloseableState(toolWindow)
            }
            override fun contentRemoved(event: ContentManagerEvent) {
                if (toolWindow.contentManager.contentCount == 0) {
                    addNewTab(project, toolWindow)
                }
                updateTabCloseableState(toolWindow)
            }
        })

        toolWindow.setTitleActions(listOf(
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
        ))

        val renameAction = object : AnAction(UcuBundle.message("toolwindow.rename"), UcuBundle.message("toolwindow.renameDesc"), AllIcons.Actions.Edit) {
            override fun actionPerformed(e: AnActionEvent) {
                renameCurrentTab(project, toolWindow)
            }
        }
        toolWindow.setAdditionalGearActions(DefaultActionGroup(renameAction))

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

        toolWindow.contentManager.component.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    renameCurrentTab(project, toolWindow)
                }
            }
        })
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
            content.displayName = newTitle
            controller.customTitle = newTitle
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastMentionResults: List<ProjectFileIndexService.FileEntry> = emptyList()

    private val callbacks = ChatCallbacks(
        onSendOrStop = {
            if (controller.viewModel.isSending) controller.stopGeneration()
            else controller.sendFromCompose()
        },
        onAttachClick = { showFileChooser() },
        onPasteImage = { handleClipboardPaste() },
        onSettingsClick = { showStreamingSettingsPopup() },
        onModelClick = { showModelSelectionPopup() },
        onModeClick = { showPermissionModePopup() },
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
        onStatusFileClick = { path -> openFileInEditor(path) },
        onStatusShowDiff = { summary ->
            val original = summary.operations.joinToString("\n") { it.oldString }
            val modified = summary.operations.joinToString("\n") { it.newString }
            InteractiveDiffManager.showDiff(project, summary.filePath, original, modified)
        },
        onStatusUndoFile = { /* TODO: undo file changes */ },
        onStatusDiscardAll = { /* TODO: discard all changes */ },
        onStatusKeepAll = { /* TODO: keep all changes */ },
        onQueueRemove = { index -> controller.removeFromQueue(index) },
        onQuestionSubmit = { answers -> controller.submitQuestion(answers) },
        onQuestionCancel = { controller.cancelQuestion() },
        onPlanApprove = { controller.approvePlan() },
        onPlanDeny = { controller.denyPlan() },
        onApprovalApprove = { controller.approvePermission() },
        onApprovalReject = { controller.rejectPermission() },
        onInstallSdk = { controller.installSdk() },
        onInstallNode = { controller.installNode() },
        onLogin = { controller.runLogin() },
        onDownloadNode = { controller.openNodeDownloadPage() },
        onLoadSession = { sessionId -> loadHistorySession(sessionId) },
        onHistoryBack = { controller.viewModel.showingHistory = false },
        onHistoryRefresh = { refreshHistorySessions() },
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

    // ── Model selection popup ────────────────────────────────────────────────

    private data class PopupItem(val id: String, val name: String, val description: String, val isCurrent: Boolean)

    private fun showModelSelectionPopup() {
        val settings = SettingsService.getInstance()
        val currentModel = settings.state.claudeModel
        val items = listOf(
            PopupItem("claude-sonnet-4-6", "Sonnet 4.6", UcuBundle.message("model.sonnet.desc"), currentModel == "claude-sonnet-4-6"),
            PopupItem("claude-opus-4-6", "Opus 4.6", UcuBundle.message("model.opus.desc"), currentModel == "claude-opus-4-6"),
            PopupItem("claude-haiku-4-5-20251001", "Haiku 4.5", UcuBundle.message("model.haiku.desc"), currentModel == "claude-haiku-4-5-20251001"),
        )
        val step = object : BaseListPopupStep<PopupItem>(UcuBundle.message("settings.model"), items) {
            override fun getTextFor(value: PopupItem): String {
                val check = if (value.isCurrent) "\u2713 " else "   "
                return "$check${value.name} — ${value.description}"
            }
            override fun onChosen(selectedValue: PopupItem, finalChoice: Boolean): PopupStep<*>? {
                settings.state.claudeModel = selectedValue.id
                controller.viewModel.selectedModelId = selectedValue.id
                controller.viewModel.selectedModelName = selectedValue.name
                return FINAL_CHOICE
            }
        }
        step.defaultOptionIndex = items.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
        showPopupNearMouse(JBPopupFactory.getInstance().createListPopup(step))
    }

    // ── Permission mode popup ────────────────────────────────────────────────

    private fun showPermissionModePopup() {
        val settings = SettingsService.getInstance()
        val currentMode = settings.state.permissionMode
        val items = listOf(
            PopupItem("default", UcuBundle.message("mode.default.name"), UcuBundle.message("mode.default.desc"), currentMode == "default"),
            PopupItem("plan", UcuBundle.message("mode.plan.name"), UcuBundle.message("mode.plan.desc"), currentMode == "plan"),
            PopupItem("bypassPermissions", UcuBundle.message("mode.auto.name"), UcuBundle.message("mode.auto.desc"), currentMode == "bypassPermissions"),
        )
        val step = object : BaseListPopupStep<PopupItem>(UcuBundle.message("settings.permissionMode"), items) {
            override fun getTextFor(value: PopupItem): String {
                val check = if (value.isCurrent) "\u2713 " else "   "
                return "$check${value.name} — ${value.description}"
            }
            override fun onChosen(selectedValue: PopupItem, finalChoice: Boolean): PopupStep<*>? {
                settings.state.permissionMode = selectedValue.id
                controller.viewModel.selectedModeId = selectedValue.id
                controller.viewModel.modeLabel = selectedValue.name
                return FINAL_CHOICE
            }
        }
        step.defaultOptionIndex = items.indexOfFirst { it.isCurrent }.coerceAtLeast(0)
        showPopupNearMouse(JBPopupFactory.getInstance().createListPopup(step))
    }

    // ── Streaming / Thinking settings popup ──────────────────────────────────

    private data class ToggleItem(val id: String, val name: String, val description: String, var isEnabled: Boolean)

    private fun showStreamingSettingsPopup() {
        val settings = SettingsService.getInstance()
        val items = listOf(
            ToggleItem("streaming", UcuBundle.message("settings.streaming"), UcuBundle.message("settings.streaming.desc"), settings.state.streamingEnabled),
            ToggleItem("thinking", UcuBundle.message("settings.thinking"), UcuBundle.message("settings.thinking.desc"), settings.state.thinkingEnabled),
        )
        val step = object : BaseListPopupStep<ToggleItem>(UcuBundle.message("input.settings"), items) {
            override fun getTextFor(value: ToggleItem): String {
                val check = if (value.isEnabled) "\u2713 " else "   "
                return "$check${value.name} — ${value.description}"
            }
            override fun onChosen(selectedValue: ToggleItem, finalChoice: Boolean): PopupStep<*>? {
                selectedValue.isEnabled = !selectedValue.isEnabled
                when (selectedValue.id) {
                    "streaming" -> settings.state.streamingEnabled = selectedValue.isEnabled
                    "thinking" -> settings.state.thinkingEnabled = selectedValue.isEnabled
                }
                return FINAL_CHOICE
            }
        }
        showPopupNearMouse(JBPopupFactory.getInstance().createListPopup(step))
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

    private fun showPopupNearMouse(popup: com.intellij.openapi.ui.popup.JBPopup) {
        val mouseLocation = java.awt.MouseInfo.getPointerInfo()?.location
        if (mouseLocation != null) {
            popup.show(RelativePoint(mouseLocation))
        } else {
            popup.showInFocusCenter()
        }
    }

    private fun syncViewModelFromSettings() {
        val settings = SettingsService.getInstance()
        val modelId = settings.state.claudeModel
        controller.viewModel.selectedModelId = modelId
        controller.viewModel.selectedModelName = when {
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
            "bypassPermissions" -> UcuBundle.message("mode.auto.name")
            else -> modeId
        }
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

        val line = editor.caretModel.logicalPosition.line + 1
        val selection = editor.selectionModel
        val lineRange = if (selection.hasSelection()) {
            val startLine = editor.document.getLineNumber(selection.selectionStart) + 1
            val endLine = editor.document.getLineNumber(selection.selectionEnd) + 1
            if (startLine == endLine) "#L$startLine" else "#L$startLine-L$endLine"
        } else {
            "#L$line"
        }

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
