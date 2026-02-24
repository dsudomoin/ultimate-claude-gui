package ru.dsudomoin.claudecodegui.ui.chat

import ru.dsudomoin.claudecodegui.UcuBundle
import com.intellij.ui.JBColor
import ru.dsudomoin.claudecodegui.ui.common.SwingAnimations
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
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
        private val LEFT_BORDER_COLOR get() = ThemeColors.thinkingBorder
        private val HEADER_COLOR get() = ThemeColors.textSecondary
        private val TEXT_COLOR get() = ThemeColors.textSecondary
    }

    private var collapsed = false
    private var animating = false

    private val headerLabel = JBLabel("\u25BC ${UcuBundle.message("thinking.active")}").apply {
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
        highlighter = null
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
        if (collapsed == value || animating) return
        collapsed = value
        headerLabel.text = if (collapsed) {
            "\u25B6 ${UcuBundle.message("thinking.done")}"
        } else {
            "\u25BC ${UcuBundle.message("thinking.active")}"
        }

        if (collapsed) {
            // Collapse: animate height to 0, then hide
            animating = true
            SwingAnimations.animateHeight(contentArea, 0) {
                contentArea.isVisible = false
                animating = false
                revalidate()
                repaint()
            }
        } else {
            // Expand: show, measure natural height, animate from 0
            contentArea.isVisible = true
            contentArea.preferredSize = null
            doLayout() // force layout to calculate natural height
            val targetHeight = contentArea.height.coerceAtLeast(JBUI.scale(20))
            contentArea.preferredSize = Dimension(contentArea.width.coerceAtLeast(1), 0)
            animating = true
            SwingAnimations.animateHeight(contentArea, targetHeight) {
                contentArea.preferredSize = null
                animating = false
                revalidate()
                repaint()
            }
        }
    }

    private fun toggleCollapsed() {
        setCollapsed(!collapsed)
    }
}
