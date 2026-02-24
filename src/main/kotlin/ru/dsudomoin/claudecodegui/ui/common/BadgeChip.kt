package ru.dsudomoin.claudecodegui.ui.common

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * Reusable rounded "pill" badge with click handler.
 *
 * Renders as a rounded rectangle with [backgroundColor], a slightly darker border,
 * and centred [text] in [textColor].
 */
class BadgeChip(
    private val text: String,
    private val backgroundColor: JBColor,
    private val onClick: () -> Unit = {},
    private val fixedHeight: Int = JBUI.scale(18),
    private val horizontalPadding: Int = JBUI.scale(8),
    private val cornerRadius: Int = JBUI.scale(8),
    private val textColor: JBColor = JBColor(0xDFE1E5, 0xDFE1E5)
) : JComponent() {

    init {
        cursor = Cursor(Cursor.HAND_CURSOR)
        toolTipText = text
        isOpaque = false
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                onClick()
            }
        })
    }

    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(font)
        val w = fm.stringWidth(text) + horizontalPadding * 2
        return Dimension(w, fixedHeight)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        // Filled rounded background
        g2.color = backgroundColor
        g2.fillRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)

        // Darker border
        g2.color = backgroundColor.darker()
        g2.drawRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)

        // Centred text
        g2.font = font
        g2.color = textColor
        drawCenteredText(g2, text, width, height)
    }
}
