package ru.dsudomoin.claudecodegui.ui.input

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.ui.AnimatedIcon
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.command.SlashCommandRegistry
import ru.dsudomoin.claudecodegui.service.ProjectFileIndexService
import ru.dsudomoin.claudecodegui.service.PromptEnhancer
import ru.dsudomoin.claudecodegui.service.SettingsService
import ru.dsudomoin.claudecodegui.ui.dialog.PromptEnhancerDialog
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*

class ChatInputPanel(
    private val project: Project,
    private val onSend: (String) -> Unit,
    private val onStop: () -> Unit = {}
) : JPanel(BorderLayout()) {

    companion object {
        private val LOG = Logger.getInstance(ChatInputPanel::class.java)
        // Model definitions: id → (shortName, descKey)
        private data class ModelDef(val id: String, val name: String, val descKey: String)
        private val MODELS = listOf(
            ModelDef("claude-sonnet-4-6", "Sonnet 4.6", "model.sonnet.desc"),
            ModelDef("claude-opus-4-6", "Opus 4.6", "model.opus.desc"),
            ModelDef("claude-opus-4-6[1m]", "Opus (1M)", "model.opus1m.desc"),
            ModelDef("claude-haiku-4-5-20251001", "Haiku 4.5", "model.haiku.desc")
        )

        // Permission mode definitions: id → (shortKey, fullKey, descKey, icon)
        private data class ModeDef(val id: String, val shortKey: String, val fullKey: String, val descKey: String, val icon: String)
        private val PERMISSION_MODES = listOf(
            ModeDef("default", "mode.default.name", "mode.default.full", "mode.default.desc", "\uD83D\uDCAC"),
            ModeDef("plan", "mode.plan.name", "mode.plan.full", "mode.plan.desc", "\u2261"),
            ModeDef("acceptEdits", "mode.agent.name", "mode.agent.full", "mode.agent.desc", "\uD83D\uDDA5"),
            ModeDef("bypassPermissions", "mode.auto.name", "mode.auto.full", "mode.auto.desc", "\u26A1")
        )
        private const val ARC = 14

        // Colors sourced from ThemeColors
        private val INPUT_BG get() = ThemeColors.surfacePrimary
        private val BORDER_NORMAL get() = ThemeColors.borderNormal
        private val BORDER_FOCUS get() = ThemeColors.borderFocus
        private val BUTTON_PRIMARY get() = ThemeColors.accent
        private val ERROR_COLOR get() = ThemeColors.statusError
        private val TEXT_PRIMARY get() = ThemeColors.textPrimary
        private val TEXT_SECONDARY get() = ThemeColors.textSecondary
        private val BUTTON_HOVER get() = ThemeColors.hoverOverlay
        private val TOOLBAR_BG get() = ThemeColors.toolbarBg
        private val DROPDOWN_BG get() = ThemeColors.dropdownBg
        private val DROPDOWN_BORDER get() = ThemeColors.dropdownBorder
        private val DROPDOWN_HOVER get() = ThemeColors.dropdownHover
        private val DROPDOWN_SELECTED get() = ThemeColors.dropdownSelected
        private val TOGGLE_ON get() = ThemeColors.toggleOn
        private val TOGGLE_OFF get() = ThemeColors.toggleOff
        private val TOGGLE_KNOB get() = ThemeColors.toggleKnob

        private val IMAGE_EXTENSIONS = arrayOf("png", "jpg", "jpeg", "gif", "bmp", "webp")
    }

    private var isSending = false
    private var isEnhancing = false
    private val slashPopup = SlashCommandPopup { cmd ->
        textArea.text = "${cmd.name} "
        textArea.caretPosition = textArea.text.length
    }
    private val enhanceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── File mentions (@-mentions) ─────────────────────────────────────────
    private val mentionedFiles = mutableListOf<ProjectFileIndexService.FileEntry>()
    private val mentionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
        isOpaque = false
    }
    private val fileMentionPopup = FileMentionPopup { entry -> insertFileMention(entry) }
    private val mentionSearchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mentionSearchJob: Job? = null

    private val settings = SettingsService.getInstance()
    private var selectedModelId: String = settings.state.claudeModel
    private var selectedModeId: String = settings.state.permissionMode
    private var streamingEnabled: Boolean = settings.state.streamingEnabled
    private var thinkingEnabled: Boolean = settings.state.thinkingEnabled
    private var isExpanded = false
    private val COLLAPSED_ROWS = 3
    private val EXPANDED_ROWS = 12

    val selectedModel: String get() = selectedModelId
    val selectedPermissionMode: String get() = selectedModeId
    val isStreamingEnabled: Boolean get() = streamingEnabled
    val isThinkingEnabled: Boolean get() = thinkingEnabled

    // ── Context percentage tracking ──────────────────────────────────────────

    private var lastInputTokens: Int = 0
    private val contextWindowSizes = mapOf(
        "claude-sonnet-4-6" to 200_000,
        "claude-opus-4-6" to 200_000,
        "claude-haiku-4-5-20251001" to 200_000,
        "claude-opus-4-6[1m]" to 1_000_000
    )

    // ── Active file context chip ────────────────────────────────────────────

    private var activeFileContext: Pair<String, Int>? = null  // (filePath, line)
    private val fileChipPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
    }

    // ── Attached images ──────────────────────────────────────────────────────

    private val attachedImages = mutableListOf<File>()
    private val attachmentsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).apply {
        isOpaque = false
    }

    // ── Text area with placeholder ──────────────────────────────────────────

    private val textArea = object : JBTextArea() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isNullOrEmpty() && attachedImages.isEmpty()) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = TEXT_SECONDARY
                g2.font = font
                g2.drawString(UcuBundle.message("chat.placeholder"), insets.left, insets.top + g2.fontMetrics.ascent)
            }
        }
    }.apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 3
        isOpaque = false
        border = JBUI.Borders.empty(6, 8, 4, 8)
        font = (UIManager.getFont("TextArea.font") ?: font).deriveFont(14f)
        foreground = TEXT_PRIMARY
    }

    // ── Toolbar buttons ─────────────────────────────────────────────────────

    private val settingsButton = createIconButton(AllIcons.General.GearPlain, UcuBundle.message("input.settings")).apply {
        addActionListener { showSettingsPopup() }
    }

    private val attachButton = createIconButton(AllIcons.General.Add, UcuBundle.message("input.attach")).apply {
        addActionListener { showAttachmentPicker() }
    }

    private val modelButton = createSelectorButton(
        "\u2728 ${MODELS.find { it.id == selectedModelId }?.name ?: selectedModelId} \u25BE"
    ).apply {
        addActionListener { showModelPopup() }
    }

    private val modeButton = createSelectorButton(
        getModeLabel()
    ).apply {
        addActionListener { showModePopup() }
    }

    private val sendButton = object : JButton() {
        private var hover = false

        init {
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
            minimumSize = preferredSize
            maximumSize = preferredSize
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = UcuBundle.message("input.send")
            addActionListener { onButtonClick() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val bg = if (isSending) ERROR_COLOR else BUTTON_PRIMARY
            g2.color = bg
            if (hover && isEnabled) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.85f)
            }
            g2.fillRoundRect(0, 0, width, height, 4, 4)
            g2.composite = AlphaComposite.SrcOver
            val icon = if (isSending) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
            val ix = (width - icon.iconWidth) / 2
            val iy = (height - icon.iconHeight) / 2
            icon.paintIcon(this, g2, ix, iy)
        }
    }

    // ── Expand / collapse toggle button ─────────────────────────────────────

    private val expandButton = createIconButton(
        AllIcons.General.ExpandComponent, UcuBundle.message("input.expand")
    ).apply {
        addActionListener { toggleExpandInput() }
    }

    private lateinit var enhanceButton: JButton

    // ── Rounded container ────────────────────────────────────────────────────

    private val roundedPanel = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUI.scale(ARC)
            val shape = RoundRectangle2D.Float(
                0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(),
                arc.toFloat(), arc.toFloat()
            )
            g2.color = INPUT_BG
            g2.fill(shape)
            val focused = textArea.isFocusOwner
            g2.color = if (focused) BORDER_FOCUS else BORDER_NORMAL
            if (focused) {
                g2.stroke = BasicStroke(2.5f)
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.3f)
                g2.draw(shape)
                g2.composite = AlphaComposite.SrcOver
            }
            g2.stroke = BasicStroke(1f)
            g2.draw(shape)
        }
    }.apply {
        isOpaque = false
    }

    // ── Context usage indicator (filled circle + percentage text) ──────────

    private val contextPercentLabel = object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)) {
        private var percentage = 0
        private var tokensUsed = 0
        private var tokensMax = 200_000

        private val circle = object : JComponent() {
            init {
                val sz = JBUI.scale(16)
                preferredSize = Dimension(sz, sz)
                minimumSize = preferredSize
                maximumSize = preferredSize
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                val pad = JBUI.scale(1)
                val sz = minOf(width, height) - pad * 2
                val x = (width - sz) / 2
                val y = (height - sz) / 2

                // Background track
                g2.color = BORDER_NORMAL
                g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                g2.drawOval(x, y, sz, sz)

                // Filled arc
                if (percentage > 0) {
                    val color = when {
                        percentage >= 80 -> ERROR_COLOR
                        percentage >= 50 -> JBColor(Color(0xFF, 0xA5, 0x00), Color(0xFF, 0xA5, 0x00))
                        else -> BUTTON_PRIMARY
                    }
                    g2.color = color
                    val angle = (percentage * 360) / 100
                    g2.fillArc(x, y, sz, sz, 90, -angle)
                }
            }
        }

        private val pctLabel = JLabel("0%").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = TEXT_SECONDARY
        }

        init {
            isOpaque = false
            add(circle)
            add(pctLabel)
            toolTipText = "Context: 0% — 0 / 200K tokens"
        }

        fun setPercentage(pct: Int, used: Int, max: Int) {
            percentage = pct.coerceIn(0, 100)
            tokensUsed = used
            tokensMax = max
            val displayPct = if (max > 0) ((used.toDouble() / max) * 100).toInt() else pct
            pctLabel.text = "$displayPct%"
            pctLabel.foreground = when {
                displayPct >= 80 -> ERROR_COLOR
                displayPct >= 50 -> JBColor(Color(0xFF, 0xA5, 0x00), Color(0xFF, 0xA5, 0x00))
                else -> TEXT_SECONDARY
            }
            toolTipText = "Context: $displayPct% — ${formatTokensShort(used)} / ${formatTokensShort(max)} tokens"
            circle.repaint()
        }

        private fun formatTokensShort(count: Int): String = when {
            count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
            count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000.0)
            else -> count.toString()
        }
    }

    // ── Header separator ──────────────────────────────────────────────────

    private fun createHeaderDivider(): JComponent = object : JComponent() {
        init {
            preferredSize = Dimension(1, JBUI.scale(18))
        }
        override fun paintComponent(g: Graphics) {
            g.color = BORDER_NORMAL
            (g as Graphics2D).composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.4f)
            g.fillRect(0, (height - JBUI.scale(14)) / 2, 1, JBUI.scale(14))
        }
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 8, 8)

        val scrollPane = JBScrollPane(textArea).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        // ── Header bar (attach + context% | file chip) ──────────────────────

        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(attachButton)
            add(contextPercentLabel)
            add(createHeaderDivider())
        }

        val headerBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 4, 0, 4)
            add(headerLeft, BorderLayout.WEST)
            add(fileChipPanel, BorderLayout.CENTER)
        }

        // ── Bottom toolbar ──────────────────────────────────────────────────

        val leftButtons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(settingsButton)
            add(modeButton)
            add(modelButton)
        }

        enhanceButton = createIconButton(AllIcons.Actions.Lightning, UcuBundle.message("enhancer.tooltip")).apply {
            addActionListener { onEnhancePrompt() }
        }

        val rightButtons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(expandButton)
            add(enhanceButton)
            add(createDivider())
            add(sendButton)
        }

        val toolbar = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                g.color = TOOLBAR_BG
                g.fillRect(0, 0, width, height)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 4, 4, 4)
            add(leftButtons, BorderLayout.WEST)
            add(rightButtons, BorderLayout.EAST)
        }

        // Content: header on top, text area center, toolbar bottom
        roundedPanel.add(headerBar, BorderLayout.NORTH)
        roundedPanel.add(scrollPane, BorderLayout.CENTER)
        roundedPanel.add(toolbar, BorderLayout.SOUTH)

        // Main layout: attachments + mention chips above the rounded input box
        val topPanels = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(attachmentsPanel)
            add(mentionsPanel)
        }
        val mainBox = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(topPanels, BorderLayout.NORTH)
            add(roundedPanel, BorderLayout.CENTER)
        }
        add(mainBox, BorderLayout.CENTER)

        textArea.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = roundedPanel.repaint()
            override fun focusLost(e: FocusEvent) {
                textArea.repaint()
                roundedPanel.repaint()
            }
        })

        textArea.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                // File mention popup navigation
                if (fileMentionPopup.isVisible()) {
                    when (e.keyCode) {
                        KeyEvent.VK_UP -> { e.consume(); fileMentionPopup.moveUp(); return }
                        KeyEvent.VK_DOWN -> { e.consume(); fileMentionPopup.moveDown(); return }
                        KeyEvent.VK_TAB -> { e.consume(); fileMentionPopup.selectCurrent(); return }
                        KeyEvent.VK_ENTER -> { e.consume(); fileMentionPopup.selectCurrent(); return }
                        KeyEvent.VK_ESCAPE -> { e.consume(); fileMentionPopup.hide(); return }
                    }
                }

                // Slash command popup navigation
                if (slashPopup.isVisible()) {
                    when (e.keyCode) {
                        KeyEvent.VK_UP -> { e.consume(); slashPopup.moveUp(); return }
                        KeyEvent.VK_DOWN -> { e.consume(); slashPopup.moveDown(); return }
                        KeyEvent.VK_TAB -> { e.consume(); slashPopup.selectCurrent(); return }
                        KeyEvent.VK_ENTER -> { e.consume(); slashPopup.selectCurrent(); return }
                        KeyEvent.VK_ESCAPE -> { e.consume(); slashPopup.hide(); return }
                    }
                }

                when {
                    e.keyCode == KeyEvent.VK_ENTER && e.isShiftDown -> {
                        e.consume()
                        textArea.insert("\n", textArea.caretPosition)
                    }
                    e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown -> {
                        e.consume()
                        sendMessage()
                    }
                    e.keyCode == KeyEvent.VK_ESCAPE && isSending -> {
                        e.consume()
                        onStop()
                    }
                }
            }
        })

        // Slash command + @-mention autocomplete triggers
        textArea.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) { checkSlashPrefix(); checkMentionTrigger() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) { checkSlashPrefix(); checkMentionTrigger() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) { checkSlashPrefix(); checkMentionTrigger() }
        })

        // Register Cmd+V / Ctrl+V through IntelliJ action system — this is the only
        // reliable way to intercept paste in IntelliJ before IdeKeyEventDispatcher
        val pasteShortcut = KeyStroke.getKeyStroke(
            KeyEvent.VK_V, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        )
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                if (!pasteImageFromClipboard()) {
                    // No image — do default text paste
                    textArea.paste()
                }
            }
        }.registerCustomShortcutSet(CustomShortcutSet(pasteShortcut), textArea)

        // Register Cmd+/ for prompt enhancement
        val enhanceShortcut = KeyStroke.getKeyStroke(
            KeyEvent.VK_SLASH, Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
        )
        object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                onEnhancePrompt()
            }
        }.registerCustomShortcutSet(CustomShortcutSet(enhanceShortcut), textArea)

        // TransferHandler for drag-and-drop support
        textArea.transferHandler = object : TransferHandler() {
            override fun canImport(support: TransferSupport): Boolean {
                return support.isDataFlavorSupported(DataFlavor.imageFlavor) ||
                        support.isDataFlavorSupported(DataFlavor.javaFileListFlavor) ||
                        support.isDataFlavorSupported(DataFlavor.stringFlavor)
            }

            override fun importData(support: TransferSupport): Boolean {
                val transferable = support.transferable
                if (importImageFromTransferable(transferable)) return true
                if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                    val text = transferable.getTransferData(DataFlavor.stringFlavor) as? String ?: return false
                    textArea.replaceSelection(text)
                    return true
                }
                return false
            }
        }

        // Listen for active editor changes to update file context chip
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    updateFileContextChip()
                }
            }
        )
        // Initialize with current editor
        updateFileContextChip()

        // Subscribe to language changes to refresh tooltips/labels
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .connect().subscribe(ru.dsudomoin.claudecodegui.service.LanguageChangeListener.TOPIC,
                ru.dsudomoin.claudecodegui.service.LanguageChangeListener { refreshLocale() })

        // Warm up project file index for @-mentions
        ProjectFileIndexService.getInstance(project).ensureIndexed()
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun sendMessage(text: String) = onSend(text)

    fun setSendingState(sending: Boolean) {
        isSending = sending
        // Keep textArea enabled so user can type next message while waiting
        sendButton.repaint()
        sendButton.toolTipText = if (sending) UcuBundle.message("input.stop") else UcuBundle.message("input.send")
        modelButton.isEnabled = !sending
        modeButton.isEnabled = !sending
    }

    fun consumeAttachedImages(): List<File> {
        val result = attachedImages.toList()
        attachedImages.clear()
        rebuildAttachmentsPanel()
        return result
    }

    /** Update context usage indicator from token usage data.
     *  Context window usage = input_tokens + cache_creation + cache_read (excludes output_tokens). */
    fun updateContextUsage(inputTokens: Int, cacheCreation: Int, cacheRead: Int) {
        val usedTokens = inputTokens + cacheCreation + cacheRead
        lastInputTokens = usedTokens
        val contextWindow = contextWindowSizes[selectedModelId] ?: 200_000
        val pct = ((usedTokens.toDouble() / contextWindow) * 100).toInt().coerceIn(0, 100)
        contextPercentLabel.setPercentage(pct, usedTokens, contextWindow)
    }

    /** Refresh all localized tooltips and labels after a language change. */
    private fun refreshLocale() {
        SwingUtilities.invokeLater {
            settingsButton.toolTipText = UcuBundle.message("input.settings")
            attachButton.toolTipText = UcuBundle.message("input.attach")
            sendButton.toolTipText = if (isSending) UcuBundle.message("input.stop") else UcuBundle.message("input.send")
            expandButton.toolTipText = UcuBundle.message(if (isExpanded) "input.collapse" else "input.expand")
            if (::enhanceButton.isInitialized) {
                enhanceButton.toolTipText = UcuBundle.message("enhancer.tooltip")
            }
            modeButton.text = getModeLabel()
            textArea.repaint() // placeholder re-reads from bundle in paintComponent
        }
    }

    // ── Private ─────────────────────────────────────────────────────────────

    private fun onButtonClick() {
        if (isSending) onStop() else sendMessage()
    }

    private fun checkSlashPrefix() {
        SwingUtilities.invokeLater {
            val raw = textArea.text
            // Only show popup while typing a command prefix (no spaces = still typing command name)
            if (raw.startsWith("/") && ' ' !in raw && '\n' !in raw) {
                val matches = SlashCommandRegistry.filter(raw.trim())
                if (matches.isNotEmpty()) {
                    slashPopup.show(matches, roundedPanel)
                } else {
                    slashPopup.hide()
                }
            } else {
                slashPopup.hide()
            }
        }
    }

    // ── @-mention trigger & insertion ─────────────────────────────────────

    private fun checkMentionTrigger() {
        SwingUtilities.invokeLater {
            val text = textArea.text
            val caret = textArea.caretPosition
            val atIndex = findMentionStart(text, caret)
            if (atIndex >= 0) {
                val query = text.substring(atIndex + 1, caret)
                // Hide slash popup if mention popup is about to show
                slashPopup.hide()
                mentionSearchJob?.cancel()
                mentionSearchJob = mentionSearchScope.launch {
                    delay(150) // debounce
                    val results = ProjectFileIndexService.getInstance(project).search(query)
                    SwingUtilities.invokeLater {
                        if (results.isNotEmpty()) {
                            fileMentionPopup.show(results, roundedPanel)
                        } else {
                            fileMentionPopup.hide()
                        }
                    }
                }
            } else {
                fileMentionPopup.hide()
            }
        }
    }

    /**
     * Finds the position of @ that starts the current mention, or -1.
     * @ must be at start of text or preceded by whitespace, and no whitespace between @ and caret.
     */
    private fun findMentionStart(text: String, caret: Int): Int {
        if (caret <= 0 || caret > text.length) return -1
        var i = caret - 1
        while (i >= 0) {
            val ch = text[i]
            if (ch == '@') {
                if (i == 0 || text[i - 1].isWhitespace()) return i
                return -1
            }
            if (ch.isWhitespace() || ch == '\n') return -1
            i--
        }
        return -1
    }

    private fun insertFileMention(entry: ProjectFileIndexService.FileEntry) {
        // Add to mentions list (skip duplicates)
        if (mentionedFiles.none { it.absolutePath == entry.absolutePath }) {
            mentionedFiles.add(entry)
            rebuildMentionsPanel()
        }

        // Remove the @query text from the text area (file is already shown as chip)
        val text = textArea.text
        val caret = textArea.caretPosition
        val atIndex = findMentionStart(text, caret)
        if (atIndex >= 0) {
            val newText = text.substring(0, atIndex) + text.substring(caret)
            textArea.text = newText
            textArea.caretPosition = atIndex.coerceAtMost(newText.length)
        }

        fileMentionPopup.hide()
    }

    private fun rebuildMentionsPanel() {
        mentionsPanel.removeAll()
        for (entry in mentionedFiles) {
            mentionsPanel.add(createMentionChip(entry))
        }
        mentionsPanel.revalidate()
        mentionsPanel.repaint()
    }

    private fun createMentionChip(entry: ProjectFileIndexService.FileEntry): JComponent {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = true
            background = DROPDOWN_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_NORMAL, 1),
                JBUI.Borders.empty(2, 6, 2, 4)
            )
            toolTipText = entry.relativePath
        }

        // File type icon
        val fileIcon = entry.virtualFile.fileType.icon
        if (fileIcon != null) chip.add(javax.swing.JLabel(fileIcon))

        // Filename label
        chip.add(javax.swing.JLabel(entry.fileName).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = TEXT_PRIMARY
        })

        // Remove button
        chip.add(javax.swing.JButton(AllIcons.Actions.Close).apply {
            preferredSize = Dimension(JBUI.scale(14), JBUI.scale(14))
            maximumSize = preferredSize
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = UcuBundle.message("input.remove")
            addActionListener {
                mentionedFiles.remove(entry)
                rebuildMentionsPanel()
            }
        })

        return chip
    }

    /**
     * Returns and clears all file mentions. Called by ChatPanel when sending a message.
     */
    fun consumeFileMentions(): List<ProjectFileIndexService.FileEntry> {
        val result = mentionedFiles.toList()
        mentionedFiles.clear()
        rebuildMentionsPanel()
        return result
    }

    private fun sendMessage() {
        slashPopup.hide()
        fileMentionPopup.hide()
        val text = textArea.text.trim()
        if (text.isNotEmpty() || attachedImages.isNotEmpty() || mentionedFiles.isNotEmpty()) {
            onSend(text)
            textArea.text = ""
        }
    }

    private fun getModeLabel(): String {
        val mode = PERMISSION_MODES.find { it.id == selectedModeId } ?: PERMISSION_MODES[0]
        return "${mode.icon} ${UcuBundle.message(mode.shortKey)} \u25BE"
    }

    // ── Expand / collapse input ────────────────────────────────────────────

    private fun toggleExpandInput() {
        isExpanded = !isExpanded
        textArea.rows = if (isExpanded) EXPANDED_ROWS else COLLAPSED_ROWS
        expandButton.icon = if (isExpanded) AllIcons.General.CollapseComponent else AllIcons.General.ExpandComponent
        expandButton.toolTipText = UcuBundle.message(if (isExpanded) "input.collapse" else "input.expand")
        roundedPanel.revalidate()
        roundedPanel.repaint()
        var p: Container? = roundedPanel.parent
        while (p != null) {
            p.revalidate()
            p.repaint()
            if (p is ChatInputPanel) break
            p = p.parent
        }
    }

    // ── Prompt enhancement ──────────────────────────────────────────────────

    private fun onEnhancePrompt() {
        val text = textArea.text.trim()
        if (text.isEmpty() || isEnhancing) return

        isEnhancing = true
        val originalText = text

        // Show loading state on button
        SwingUtilities.invokeLater {
            enhanceButton.icon = AnimatedIcon.Default()
            enhanceButton.isEnabled = false
            enhanceButton.toolTipText = UcuBundle.message("enhancer.enhancing")
        }

        enhanceScope.launch {
            val enhanced = PromptEnhancer.enhance(
                originalPrompt = originalText,
                cwd = project.basePath
            )

            SwingUtilities.invokeLater {
                isEnhancing = false
                // Restore button state
                enhanceButton.icon = AllIcons.Actions.Lightning
                enhanceButton.isEnabled = true
                enhanceButton.toolTipText = UcuBundle.message("enhancer.tooltip")

                if (enhanced != null) {
                    val dialog = PromptEnhancerDialog(project, originalText, enhanced)
                    if (dialog.showAndGet()) {
                        textArea.text = enhanced
                    }
                } else {
                    LOG.warn("Prompt enhancement returned null")
                }
            }
        }
    }

    // ── Settings popup with toggles ─────────────────────────────────────────

    private fun showSettingsPopup() {
        val popup = JPopupMenu().apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DROPDOWN_BORDER, 1),
                JBUI.Borders.empty(6, 0)
            )
            background = DROPDOWN_BG
        }

        popup.add(createToggleItem(
            icon = "\u21C4",
            label = UcuBundle.message("settings.streaming"),
            isOn = { streamingEnabled },
            onToggle = { enabled ->
                streamingEnabled = enabled
                settings.state.streamingEnabled = enabled
            }
        ))

        popup.add(createToggleItem(
            icon = "\uD83D\uDCA1",
            label = UcuBundle.message("settings.thinking"),
            isOn = { thinkingEnabled },
            onToggle = { enabled ->
                thinkingEnabled = enabled
                settings.state.thinkingEnabled = enabled
            }
        ))

        popup.show(settingsButton, 0, -popup.preferredSize.height - JBUI.scale(4))
    }

    private fun createToggleItem(
        icon: String,
        label: String,
        isOn: () -> Boolean,
        onToggle: (Boolean) -> Unit
    ): JComponent {
        val toggleWidth = JBUI.scale(36)
        val toggleHeight = JBUI.scale(18)

        return object : JPanel(BorderLayout()) {
            private var hover = false

            init {
                isOpaque = false
                preferredSize = Dimension(JBUI.scale(220), JBUI.scale(34))
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(0, 12, 0, 12)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                    override fun mouseClicked(e: MouseEvent) {
                        onToggle(!isOn())
                        repaint()
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                if (hover) {
                    g2.color = DROPDOWN_HOVER
                    g2.fillRect(0, 0, width, height)
                }

                val labelFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())
                g2.font = labelFont
                val fm = g2.fontMetrics
                val textY = (height + fm.ascent - fm.descent) / 2

                g2.color = TEXT_PRIMARY
                g2.drawString("$icon  $label", insets.left, textY)

                // Toggle switch
                val toggled = isOn()
                val tx = width - insets.right - toggleWidth
                val ty = (height - toggleHeight) / 2
                val trackArc = toggleHeight

                g2.color = if (toggled) TOGGLE_ON else TOGGLE_OFF
                g2.fillRoundRect(tx, ty, toggleWidth, toggleHeight, trackArc, trackArc)

                val knobSize = toggleHeight - JBUI.scale(4)
                val knobX = if (toggled) tx + toggleWidth - knobSize - JBUI.scale(2) else tx + JBUI.scale(2)
                val knobY = ty + JBUI.scale(2)
                g2.color = TOGGLE_KNOB
                g2.fillOval(knobX, knobY, knobSize, knobSize)
            }
        }
    }

    // ── File context chip ─────────────────────────────────────────────────

    private var currentCaretListener: CaretListener? = null
    private var currentEditorRef: com.intellij.openapi.editor.Editor? = null

    private fun updateFileContextChip() {
        val update = Runnable {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor
            val vFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

            // Re-attach CaretListener to the new editor
            if (editor !== currentEditorRef) {
                currentCaretListener?.let { currentEditorRef?.caretModel?.removeCaretListener(it) }
                currentEditorRef = editor
                if (editor != null) {
                    val listener = object : CaretListener {
                        override fun caretPositionChanged(event: CaretEvent) = rebuildFileChip()
                    }
                    currentCaretListener = listener
                    editor.caretModel.addCaretListener(listener)
                }
            }

            rebuildFileChip()
        }
        if (SwingUtilities.isEventDispatchThread()) update.run() else SwingUtilities.invokeLater(update)
    }

    private fun rebuildFileChip() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val vFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

        fileChipPanel.removeAll()
        if (editor != null && vFile != null) {
            // Only show line range if there's an actual text selection
            val selModel = editor.selectionModel
            val lineRange = if (selModel.hasSelection()) {
                val startLine = editor.document.getLineNumber(selModel.selectionStart) + 1
                val endLine = editor.document.getLineNumber(selModel.selectionEnd) + 1
                if (startLine == endLine) "#L$startLine" else "#L$startLine-L$endLine"
            } else {
                ""
            }
            activeFileContext = vFile.path to (editor.caretModel.logicalPosition.line + 1)
            fileChipPanel.add(createFileChip(vFile, lineRange))
        } else {
            activeFileContext = null
        }
        fileChipPanel.revalidate()
        fileChipPanel.repaint()
    }

    private fun createFileChip(vFile: VirtualFile, lineRange: String): JComponent {
        val label = "${vFile.name}$lineRange"
        val fileTypeIcon = vFile.fileType.icon

        val chip = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 4, 0, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    FileEditorManager.getInstance(project).openFile(vFile, true)
                }
            })
        }

        val tag = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = true
            background = DROPDOWN_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_NORMAL, 1),
                JBUI.Borders.empty(2, 6, 2, 4)
            )
        }

        if (fileTypeIcon != null) {
            tag.add(JLabel(fileTypeIcon))
        }

        tag.add(JLabel(label).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            foreground = TEXT_PRIMARY
        })

        tag.add(JButton(AllIcons.Actions.Close).apply {
            preferredSize = Dimension(JBUI.scale(14), JBUI.scale(14))
            maximumSize = preferredSize
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = UcuBundle.message("input.removeContext")
            addActionListener {
                activeFileContext = null
                fileChipPanel.removeAll()
                fileChipPanel.revalidate()
                fileChipPanel.repaint()
            }
        })

        chip.add(tag)
        return chip
    }

    // ── Image attachment ────────────────────────────────────────────────────

    private fun showAttachmentPicker() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, true)
            .withFileFilter { vf ->
                vf.extension?.lowercase() in IMAGE_EXTENSIONS
            }
            .withTitle(UcuBundle.message("input.chooseImage"))
        val files = FileChooser.chooseFiles(descriptor, project, null)
        for (vf in files) {
            val file = File(vf.path)
            LOG.info("FileChooser selected: ${file.absolutePath} exists=${file.exists()} size=${file.length()}")
            addImageAttachment(file)
        }
    }

    /**
     * Fallback clipboard paste — called from KeyAdapter when TransferHandler doesn't fire.
     */
    private fun pasteImageFromClipboard(): Boolean {
        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val contents = clipboard.getContents(null) ?: return false
            return importImageFromTransferable(contents)
        } catch (e: Exception) {
            LOG.warn("Clipboard paste failed", e)
            return false
        }
    }

    private fun importImageFromTransferable(transferable: Transferable): Boolean {
        try {
            // Try raw image data (screenshot from clipboard)
            if (transferable.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                val image = transferable.getTransferData(DataFlavor.imageFlavor) as? Image
                if (image != null) {
                    val buffered = toBufferedImage(image)
                    val tempDir = File(System.getProperty("java.io.tmpdir"), "claude-code-gui-images")
                    tempDir.mkdirs()
                    val tempFile = File(tempDir, "screenshot-${System.currentTimeMillis()}.png")
                    ImageIO.write(buffered, "png", tempFile)
                    LOG.info("Pasted image saved: ${tempFile.absolutePath} size=${tempFile.length()}")
                    addImageAttachment(tempFile)
                    return true
                }
            }

            // Try file list (image file copied from Finder / Explorer)
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                val files = transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<File>
                if (files != null) {
                    val imageFiles = files.filter { f -> f.extension.lowercase() in IMAGE_EXTENSIONS }
                    LOG.info("File list paste: ${files.size} total, ${imageFiles.size} images")
                    if (imageFiles.isNotEmpty()) {
                        imageFiles.forEach { addImageAttachment(it) }
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("importImageFromTransferable failed", e)
        }
        return false
    }

    private fun toBufferedImage(img: Image): BufferedImage {
        if (img is BufferedImage) return img
        val bi = BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_ARGB)
        val g = bi.createGraphics()
        g.drawImage(img, 0, 0, null)
        g.dispose()
        return bi
    }

    private fun addImageAttachment(file: File) {
        if (!file.exists()) {
            LOG.warn("Attachment file does not exist: ${file.absolutePath}")
            return
        }
        attachedImages.add(file)
        LOG.info("Image attached: ${file.name}, total=${attachedImages.size}")
        rebuildAttachmentsPanel()
    }

    private fun rebuildAttachmentsPanel() {
        attachmentsPanel.removeAll()
        for (file in attachedImages) {
            attachmentsPanel.add(createImageChip(file))
        }
        // Force layout recalculation up the entire hierarchy
        attachmentsPanel.revalidate()
        attachmentsPanel.repaint()

        // Walk up parent chain to force re-layout
        var p: Container? = attachmentsPanel.parent
        while (p != null) {
            p.revalidate()
            p.repaint()
            if (p is ChatInputPanel) break
            p = p.parent
        }
    }

    private fun createImageChip(file: File): JComponent {
        val thumbSize = JBUI.scale(48)
        val chip = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            isOpaque = true
            background = DROPDOWN_BG
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_NORMAL, 1),
                JBUI.Borders.empty(4)
            )
        }

        // Thumbnail
        val thumbLabel = JLabel().apply {
            horizontalAlignment = SwingConstants.CENTER
            preferredSize = Dimension(thumbSize, thumbSize)
        }
        try {
            val orig = ImageIO.read(file)
            if (orig != null) {
                val scale = minOf(thumbSize.toDouble() / orig.width, thumbSize.toDouble() / orig.height)
                val w = (orig.width * scale).toInt().coerceAtLeast(1)
                val h = (orig.height * scale).toInt().coerceAtLeast(1)
                val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val g2 = scaled.createGraphics()
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.drawImage(orig, 0, 0, w, h, null)
                g2.dispose()
                thumbLabel.icon = ImageIcon(scaled)
            } else {
                thumbLabel.icon = AllIcons.FileTypes.Image
            }
        } catch (e: Exception) {
            LOG.warn("Failed to read image for thumbnail: ${file.name}", e)
            thumbLabel.icon = AllIcons.FileTypes.Image
        }
        thumbLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        thumbLabel.toolTipText = UcuBundle.message("input.openInEditor")
        thumbLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val vf = LocalFileSystem.getInstance().findFileByIoFile(file)
                if (vf != null) {
                    FileEditorManager.getInstance(project).openFile(vf, true)
                }
            }
        })
        chip.add(thumbLabel, BorderLayout.CENTER)

        // Bottom: filename + remove button
        val bottomPanel = JPanel(BorderLayout(JBUI.scale(2), 0)).apply { isOpaque = false }
        val name = if (file.name.length > 15) file.name.take(12) + "..." else file.name
        bottomPanel.add(JLabel(name).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            foreground = TEXT_SECONDARY
        }, BorderLayout.CENTER)

        val removeBtn = JButton(AllIcons.Actions.Close).apply {
            preferredSize = Dimension(JBUI.scale(14), JBUI.scale(14))
            maximumSize = preferredSize
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = UcuBundle.message("input.remove")
            addActionListener {
                attachedImages.remove(file)
                rebuildAttachmentsPanel()
            }
        }
        bottomPanel.add(removeBtn, BorderLayout.EAST)
        chip.add(bottomPanel, BorderLayout.SOUTH)

        return chip
    }

    // ── Model / mode popups ─────────────────────────────────────────────────

    private fun showModelPopup() {
        val popup = JPopupMenu().apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DROPDOWN_BORDER, 1),
                JBUI.Borders.empty(6, 0)
            )
            background = DROPDOWN_BG
        }

        MODELS.forEach { model ->
            val isSelected = model.id == selectedModelId
            val item = createTwoLinePopupItem(
                icon = "\u2728",
                title = model.name,
                subtitle = UcuBundle.message(model.descKey),
                isSelected = isSelected,
                onClick = {
                    selectedModelId = model.id
                    settings.state.claudeModel = model.id
                    modelButton.text = "\u2728 ${model.name} \u25BE"
                    popup.isVisible = false
                }
            )
            popup.add(item)
        }

        popup.show(modelButton, 0, -popup.preferredSize.height - JBUI.scale(4))
    }

    private fun showModePopup() {
        val popup = JPopupMenu().apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(DROPDOWN_BORDER, 1),
                JBUI.Borders.empty(6, 0)
            )
            background = DROPDOWN_BG
        }

        PERMISSION_MODES.forEach { mode ->
            val isSelected = mode.id == selectedModeId
            val item = createTwoLinePopupItem(
                icon = mode.icon,
                title = UcuBundle.message(mode.fullKey),
                subtitle = UcuBundle.message(mode.descKey),
                isSelected = isSelected,
                onClick = {
                    selectedModeId = mode.id
                    settings.state.permissionMode = mode.id
                    modeButton.text = getModeLabel()
                    popup.isVisible = false
                }
            )
            popup.add(item)
        }

        popup.show(modeButton, 0, -popup.preferredSize.height - JBUI.scale(4))
    }

    /**
     * Creates a two-line popup item: icon + title on first line,
     * subtitle (gray) on second line, checkmark on right if selected.
     * Uses fixed icon column width so all text aligns.
     */
    private fun createTwoLinePopupItem(
        icon: String,
        title: String,
        subtitle: String,
        isSelected: Boolean,
        onClick: () -> Unit
    ): JComponent {
        val iconColWidth = JBUI.scale(28) // fixed column for icon alignment

        return object : JPanel(BorderLayout()) {
            private var hover = false

            init {
                isOpaque = false
                border = JBUI.Borders.empty(8, 12, 8, 12)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                    override fun mouseClicked(e: MouseEvent) { onClick() }
                })
            }

            override fun getPreferredSize(): Dimension {
                val fm1 = getFontMetrics(font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat()))
                val fm2 = getFontMetrics(font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat()))
                val titleWidth = fm1.stringWidth(title)
                val subtitleWidth = fm2.stringWidth(subtitle)
                val checkWidth = JBUI.scale(28)
                val textWidth = maxOf(titleWidth, subtitleWidth)
                val w = iconColWidth + textWidth + checkWidth + insets.left + insets.right
                val h = fm1.height + fm2.height + JBUI.scale(4) + insets.top + insets.bottom
                return Dimension(w, h)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                if (isSelected) {
                    g2.color = DROPDOWN_SELECTED
                    g2.fillRect(0, 0, width, height)
                } else if (hover) {
                    g2.color = DROPDOWN_HOVER
                    g2.fillRect(0, 0, width, height)
                }

                val titleFont = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
                val subtitleFont = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())

                // Icon (centered in fixed column)
                g2.font = titleFont
                val fmTitle = g2.fontMetrics
                val titleY = insets.top + fmTitle.ascent
                g2.color = TEXT_PRIMARY
                g2.drawString(icon, insets.left, titleY)

                // Title (bold)
                val textX = insets.left + iconColWidth
                g2.drawString(title, textX, titleY)

                // Subtitle
                g2.font = subtitleFont
                g2.color = TEXT_SECONDARY
                val fmSub = g2.fontMetrics
                val subY = titleY + fmTitle.descent + JBUI.scale(3) + fmSub.ascent
                g2.drawString(subtitle, textX, subY)

                // Checkmark
                if (isSelected) {
                    val checkIcon = AllIcons.Actions.Checked
                    checkIcon.paintIcon(this, g2,
                        width - checkIcon.iconWidth - insets.right,
                        (height - checkIcon.iconHeight) / 2)
                }
            }
        }
    }

    // ── Component factories ─────────────────────────────────────────────────

    private fun createSelectorButton(text: String): JButton = object : JButton(text) {
        private var hover = false

        init {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = TEXT_SECONDARY
            border = JBUI.Borders.empty(4, 6)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            })
        }

        override fun getPreferredSize(): Dimension {
            val fm = getFontMetrics(font)
            val textWidth = fm.stringWidth(getText())
            val ins = insets
            return Dimension(textWidth + ins.left + ins.right, fm.height + ins.top + ins.bottom)
        }

        override fun getMinimumSize(): Dimension = preferredSize
        override fun getMaximumSize(): Dimension = preferredSize

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            if (hover) {
                g2.color = BUTTON_HOVER
                g2.fillRoundRect(0, 0, width, height, 4, 4)
            }
            g2.color = if (hover) TEXT_PRIMARY else TEXT_SECONDARY
            g2.font = font
            val fm = g2.fontMetrics
            val x = insets.left
            val y = (height + fm.ascent - fm.descent) / 2
            g2.drawString(getText(), x, y)
        }
    }

    private fun createIconButton(icon: Icon, tooltip: String): JButton = object : JButton(icon) {
        private var hover = false

        init {
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
            minimumSize = preferredSize
            maximumSize = preferredSize
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = tooltip
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (hover) {
                g2.color = BUTTON_HOVER
                g2.fillRoundRect(0, 0, width, height, 4, 4)
            }
            val ico = getIcon() ?: return
            val ix = (width - ico.iconWidth) / 2
            val iy = (height - ico.iconHeight) / 2
            ico.paintIcon(this, g2, ix, iy)
        }
    }

    private fun createDivider(): JComponent = object : JComponent() {
        init {
            preferredSize = Dimension(1, JBUI.scale(16))
        }

        override fun paintComponent(g: Graphics) {
            g.color = BORDER_NORMAL
            (g as Graphics2D).composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
            g.fillRect(0, (height - JBUI.scale(16)) / 2, 1, JBUI.scale(16))
        }
    }
}
