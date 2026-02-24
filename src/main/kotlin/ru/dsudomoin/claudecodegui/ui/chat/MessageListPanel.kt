package ru.dsudomoin.claudecodegui.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.Role
import ru.dsudomoin.claudecodegui.ui.common.MarkdownRenderer
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingConstants

class MessageListPanel(private val project: Project? = null) : JPanel() {

    companion object {
        private val SEPARATOR_COLOR get() = ThemeColors.separatorColor
        private val COPY_HOVER_BG get() = ThemeColors.iconHoverBg
        private val TEXT_SECONDARY get() = ThemeColors.textSecondary
        private val WELCOME_ACCENT get() = ThemeColors.accentSecondary
        private val WELCOME_TIP_BG get() = ThemeColors.welcomeTipBg
        private val WELCOME_TIP_BORDER get() = ThemeColors.welcomeTipBorder
    }

    private var isStreaming = false

    init {
        layout = VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(9), true, false)
        border = JBUI.Borders.empty(4)
    }

    fun updateMessages(messages: List<Message>) {
        isStreaming = false
        removeAll()
        if (messages.isEmpty()) {
            add(createWelcomePanel())
            revalidate()
            repaint()
            return
        }
        var prevRole: Role? = null
        messages.forEach { message ->
            if (prevRole != null && prevRole != message.role) {
                add(createSeparator())
            }
            val bubble = MessageBubble(
                message = message,
                project = project,
                streaming = false
            )
            add(wrapBubble(bubble, message.role, message))
            prevRole = message.role
        }
        revalidate()
        repaint()
    }

    fun startStreaming(messages: List<Message>) {
        isStreaming = true
        removeAll()
        var prevRole: Role? = null
        messages.forEachIndexed { index, message ->
            if (prevRole != null && prevRole != message.role) {
                add(createSeparator())
            }
            val isLast = index == messages.lastIndex
            val bubble = MessageBubble(
                message = message,
                project = project,
                streaming = isLast && message.role == Role.ASSISTANT
            )
            add(wrapBubble(bubble, message.role, message))
            prevRole = message.role
        }
        revalidate()
        repaint()
    }

    private val timeFormat = SimpleDateFormat("HH:mm")

    /**
     * Wraps a bubble in a panel with alignment + copy button on hover.
     * User messages: time + copy icon above the bubble, right-aligned.
     * Assistant messages: small copy icon in the top-right corner, overlaid.
     */
    private fun wrapBubble(bubble: MessageBubble, role: Role, message: Message): JPanel {
        val wrapper = JPanel(BorderLayout()).apply { isOpaque = false }

        when (role) {
            Role.USER -> {
                // Top row: [spacer] [time] [copy] — right-aligned, above the bubble
                val timeStr = timeFormat.format(Date(message.timestamp))
                val timeLabel = JLabel(timeStr).apply {
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                    foreground = TEXT_SECONDARY
                }
                val copyBtn = createCopyIcon(message, alwaysVisible = true)
                val topRow = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyBottom(4)
                    add(timeLabel)
                    add(copyBtn)
                }

                // Bubble panel: bubble stays at EAST so parent.width = full width for getPreferredSize()
                val bubbleRow = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(bubble, BorderLayout.EAST)
                }

                wrapper.add(topRow, BorderLayout.NORTH)
                wrapper.add(bubbleRow, BorderLayout.CENTER)
            }
            Role.ASSISTANT -> {
                // Layered pane: bubble fills all space, copy icon floats top-right
                val copyBtn = createCopyIcon(message)
                val layered = object : JLayeredPane() {
                    override fun doLayout() {
                        // Bubble fills entire area
                        bubble.setBounds(0, 0, width, height)
                        // Copy icon at top-right
                        val btnSz = JBUI.scale(22)
                        val margin = JBUI.scale(4)
                        copyBtn.setBounds(width - btnSz - margin, margin, btnSz, btnSz)
                    }
                    override fun getPreferredSize(): Dimension = bubble.preferredSize
                    override fun getMinimumSize(): Dimension = bubble.minimumSize
                }.apply {
                    add(bubble, JLayeredPane.DEFAULT_LAYER as Integer)
                    add(copyBtn, JLayeredPane.PALETTE_LAYER as Integer)
                }

                val hoverListener = object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { copyBtn.isVisible = true }
                    override fun mouseExited(e: MouseEvent) {
                        if (!isInsideComponent(e, layered)) copyBtn.isVisible = false
                    }
                }
                layered.addMouseListener(hoverListener)
                bubble.addMouseListener(hoverListener)
                copyBtn.addMouseListener(hoverListener)

                wrapper.add(layered, BorderLayout.CENTER)
            }
        }
        return wrapper
    }

    /** Small 22px copy icon button. Hidden by default for assistant, always visible for user. */
    private fun createCopyIcon(message: Message, alwaysVisible: Boolean = false): JPanel {
        val btnSize = JBUI.scale(22)
        return object : JPanel() {
            private var hover = false
            init {
                isOpaque = false
                isVisible = alwaysVisible
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                preferredSize = Dimension(btnSize, btnSize)
                minimumSize = preferredSize
                maximumSize = preferredSize
                toolTipText = "Copy"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                    override fun mouseClicked(e: MouseEvent) {
                        val text = message.textContent
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
                    }
                })
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (hover) {
                    g2.color = COPY_HOVER_BG
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(4), JBUI.scale(4))
                }
                val icon = AllIcons.Actions.Copy
                val ix = (width - icon.iconWidth) / 2
                val iy = (height - icon.iconHeight) / 2
                icon.paintIcon(this, g2, ix, iy)
            }
        }
    }

    /** Check if mouse event is still inside the given component's bounds. */
    private fun isInsideComponent(e: MouseEvent, comp: Component): Boolean {
        return try {
            val p = e.point
            val src = e.component.locationOnScreen
            val dst = comp.locationOnScreen
            Rectangle(0, 0, comp.width, comp.height).contains(
                src.x + p.x - dst.x, src.y + p.y - dst.y
            )
        } catch (_: Exception) { false }
    }

    fun updateStreamingThinking(text: String) {
        findLastBubble()?.updateThinkingText(text)
    }

    fun updateStreamingResponse(text: String) {
        findLastBubble()?.updateResponseText(text)
    }

    fun collapseThinking() {
        findLastBubble()?.collapseThinking()
    }

    fun addToolBlock(id: String, toolName: String, summary: String, input: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())) {
        findLastBubble()?.addToolBlock(id, toolName, summary, input)
    }

    fun completeToolBlock(id: String, resultContent: String? = null) {
        findLastBubble()?.completeToolBlock(id, resultContent)
    }

    fun errorToolBlock(id: String, resultContent: String? = null) {
        findLastBubble()?.errorToolBlock(id, resultContent)
    }

    fun stopStreamingTimer() {
        findLastBubble()?.stopStreamingTimer()
    }

    fun updateStreamingText(text: String) {
        findLastBubble()?.updateText(text)
    }

    /** Welcome screen shown when there are no messages. */
    private fun createWelcomePanel(): JPanel {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(40, 24, 24, 24)

            // Title
            add(JLabel(UcuBundle.message("welcome.title")).apply {
                font = font.deriveFont(Font.BOLD, JBUI.scale(20).toFloat())
                foreground = WELCOME_ACCENT
                alignmentX = Component.CENTER_ALIGNMENT
            })

            add(Box.createVerticalStrut(JBUI.scale(6)))

            // Subtitle
            add(JLabel(UcuBundle.message("welcome.subtitle")).apply {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())
                foreground = TEXT_SECONDARY
                alignmentX = Component.CENTER_ALIGNMENT
            })

            add(Box.createVerticalStrut(JBUI.scale(24)))

            // Tip cards
            val tips = listOf(
                "welcome.tip1",
                "welcome.tip2",
                "welcome.tip3",
                "welcome.tip4"
            )
            val icons = listOf("\uD83D\uDCAC", "\u2728", "\uD83D\uDCCE", "\uD83D\uDDBC\uFE0F")
            tips.forEachIndexed { index, key ->
                add(createTipCard(icons[index], UcuBundle.message(key)))
                if (index < tips.lastIndex) {
                    add(Box.createVerticalStrut(JBUI.scale(8)))
                }
            }

            add(Box.createVerticalStrut(JBUI.scale(20)))

            // Hint
            add(JLabel(UcuBundle.message("welcome.hint")).apply {
                font = font.deriveFont(Font.ITALIC, JBUI.scale(12).toFloat())
                foreground = TEXT_SECONDARY
                alignmentX = Component.CENTER_ALIGNMENT
            })
        }

        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(content, GridBagConstraints().apply {
                anchor = GridBagConstraints.CENTER
                fill = GridBagConstraints.HORIZONTAL
                weightx = 1.0
                insets = JBUI.insets(0, 20, 0, 20)
            })
        }
    }

    /** A rounded card for a single welcome tip. */
    private fun createTipCard(icon: String, text: String): JPanel {
        return object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(10, 14)
                val label = JLabel("<html><b>$icon</b>&nbsp;&nbsp;$text</html>").apply {
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())
                    horizontalAlignment = SwingConstants.LEFT
                }
                add(label, BorderLayout.CENTER)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                alignmentX = Component.CENTER_ALIGNMENT
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(10).toFloat()
                val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc)
                g2.color = WELCOME_TIP_BG
                g2.fill(shape)
                g2.color = WELCOME_TIP_BORDER
                g2.stroke = BasicStroke(1f)
                g2.draw(shape)
            }
        }
    }

    /** Subtle horizontal line between user/assistant message groups. */
    private fun createSeparator(): JPanel {
        return object : JPanel() {
            init {
                isOpaque = false
                preferredSize = Dimension(0, JBUI.scale(18))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(18))
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = SEPARATOR_COLOR
                g2.fillRect(0, height / 2, width, 1)
            }
        }
    }

    /**
     * Adds a plan block rendered as a single HTML component.
     * This survives until the next updateMessages()/startStreaming() call.
     */
    fun addPlanBlock(planMarkdown: String) {
        val htmlPane = MarkdownRenderer.renderHtml(planMarkdown)
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 4, 8)
            add(htmlPane, BorderLayout.CENTER)
        }
        add(wrapper)
        revalidate()
        repaint()
    }

    /** Add a centered system message (e.g. "Chat cleared", help text). */
    fun addSystemMessage(text: String) {
        val label = JLabel("<html><pre style='font-family:${font.family};font-size:${JBUI.scale(12)}pt;'>${
            text.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>")
        }</pre></html>").apply {
            foreground = ThemeColors.textSecondary
            horizontalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.empty(8, 16)
        }
        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(label, BorderLayout.CENTER)
        }
        add(wrapper)
        revalidate()
        repaint()
    }

    /**
     * Insert an inline approval panel into the chat flow.
     * During streaming: adds into the last assistant bubble's content flow.
     * Otherwise: appends to the end of the panel list.
     */
    fun addInlineApprovalPanel(panel: javax.swing.JPanel) {
        val lastBubble = findLastBubble()
        if (lastBubble != null && isStreaming) {
            lastBubble.addInlineApproval(panel)
        } else {
            panel.alignmentX = LEFT_ALIGNMENT
            add(panel)
        }
        revalidate()
        repaint()
    }

    /** Finds the last MessageBubble, searching recursively inside wrapper/layered panels. */
    private fun findLastBubble(): MessageBubble? {
        for (i in componentCount - 1 downTo 0) {
            val found = findBubbleIn(getComponent(i))
            if (found != null) return found
        }
        return null
    }

    private fun findBubbleIn(comp: Component): MessageBubble? {
        if (comp is MessageBubble) return comp
        if (comp is Container) {
            for (j in 0 until comp.componentCount) {
                val found = findBubbleIn(comp.getComponent(j))
                if (found != null) return found
            }
        }
        return null
    }
}
