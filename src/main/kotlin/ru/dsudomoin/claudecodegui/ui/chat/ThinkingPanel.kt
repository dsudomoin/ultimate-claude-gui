package ru.dsudomoin.claudecodegui.ui.chat

import ru.dsudomoin.claudecodegui.MyMessageBundle
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.UIManager

/**
 * Collapsible panel that displays Claude's thinking/reasoning.
 *
 * Minimal style: subtle left border line, muted text, no background.
 */
class ThinkingPanel : JPanel(BorderLayout()) {

    companion object {
        private val LEFT_BORDER_COLOR = JBColor(Color(0x00, 0x00, 0x00, 0x20), Color(0xFF, 0xFF, 0xFF, 0x20))
        private val HEADER_COLOR = JBColor(Color(0x88, 0x88, 0x88), Color(0x70, 0x70, 0x70))
        private val TEXT_COLOR = JBColor(Color(0x88, 0x88, 0x88), Color(0x68, 0x68, 0x68))
    }

    private var collapsed = false

    private val headerLabel = JBLabel("\u25BC ${MyMessageBundle.message("thinking.active")}").apply {
        font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
        foreground = HEADER_COLOR
        border = JBUI.Borders.empty(2, 0)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }

    private val contentArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        font = UIManager.getFont("Label.font")?.deriveFont(Font.ITALIC, JBUI.scale(12).toFloat()) ?: font
        foreground = TEXT_COLOR
        border = JBUI.Borders.empty(2, 0, 4, 0)
    }

    init {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, JBUI.scale(2), 0, 0, LEFT_BORDER_COLOR),
            JBUI.Borders.empty(2, 8, 2, 0)
        )

        add(headerLabel, BorderLayout.NORTH)
        add(contentArea, BorderLayout.CENTER)

        headerLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                toggleCollapsed()
            }
        })
    }

    fun updateText(text: String) {
        contentArea.text = text
    }

    fun setCollapsed(value: Boolean) {
        collapsed = value
        contentArea.isVisible = !collapsed
        headerLabel.text = if (collapsed) {
            "\u25B6 ${MyMessageBundle.message("thinking.done")}"
        } else {
            "\u25BC ${MyMessageBundle.message("thinking.active")}"
        }
        revalidate()
        repaint()
    }

    private fun toggleCollapsed() {
        setCollapsed(!collapsed)
    }
}
