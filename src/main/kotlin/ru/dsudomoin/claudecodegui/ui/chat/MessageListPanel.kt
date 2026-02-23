package ru.dsudomoin.claudecodegui.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.Role
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel

class MessageListPanel(private val project: Project? = null) : JPanel() {

    companion object {
        private val SEPARATOR_COLOR = JBColor(Color(0x00, 0x00, 0x00, 0x12), Color(0xFF, 0xFF, 0xFF, 0x12))
        private val COPY_HOVER_BG = JBColor(Color(0x00, 0x00, 0x00, 0x15), Color(0xFF, 0xFF, 0xFF, 0x15))
        private val TEXT_SECONDARY = JBColor(Color(0x88, 0x88, 0x88), Color(0x70, 0x70, 0x70))
    }

    private var isStreaming = false

    init {
        layout = VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(9), true, false)
        border = JBUI.Borders.empty(4)
    }

    fun updateMessages(messages: List<Message>) {
        isStreaming = false
        removeAll()
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
                val copyBtn = createCopyIcon(message)
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

                // Hover: show copy on wrapper hover
                val hoverListener = object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { copyBtn.isVisible = true }
                    override fun mouseExited(e: MouseEvent) {
                        if (!isInsideComponent(e, wrapper)) copyBtn.isVisible = false
                    }
                }
                wrapper.addMouseListener(hoverListener)
                bubble.addMouseListener(hoverListener)
                bubbleRow.addMouseListener(hoverListener)
                topRow.addMouseListener(hoverListener)
                copyBtn.addMouseListener(hoverListener)
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

    /** Small 22px copy icon button, hidden by default, shown on hover. */
    private fun createCopyIcon(message: Message): JPanel {
        val btnSize = JBUI.scale(22)
        return object : JPanel() {
            private var hover = false
            init {
                isOpaque = false
                isVisible = false
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

    fun completeToolBlock(id: String) {
        findLastBubble()?.completeToolBlock(id)
    }

    fun errorToolBlock(id: String) {
        findLastBubble()?.errorToolBlock(id)
    }

    fun stopStreamingTimer() {
        findLastBubble()?.stopStreamingTimer()
    }

    fun updateStreamingText(text: String) {
        findLastBubble()?.updateText(text)
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
