package ru.dsudomoin.claudecodegui.ui.common

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JComponent

/**
 * Utility for drawing consistent rounded borders on components.
 *
 * Selected state: solid border.
 * Unselected state: dashed border with 50 % alpha.
 */
object PaintUtil {

    private const val ARC_WIDTH = 8f
    private const val ARC_HEIGHT = 8f
    private const val STROKE_WIDTH = 1f

    fun drawRoundedBackground(
        g: Graphics,
        component: JComponent,
        selected: Boolean = true
    ) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val rect = RoundRectangle2D.Float(
                STROKE_WIDTH / 2f,
                STROKE_WIDTH / 2f,
                component.width.toFloat() - STROKE_WIDTH,
                component.height.toFloat() - STROKE_WIDTH,
                ARC_WIDTH,
                ARC_HEIGHT
            )

            // Fill with editor background
            g2.color = EditorColorsManager.getInstance().globalScheme.defaultBackground
            g2.fill(rect)

            if (!selected) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.5f)
            }

            // Border
            g2.color = JBUI.CurrentTheme.Focus.defaultButtonColor()
            g2.stroke = if (selected) {
                BasicStroke(STROKE_WIDTH)
            } else {
                BasicStroke(
                    STROKE_WIDTH,
                    BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL,
                    0f,
                    floatArrayOf(10f, 5f),
                    0f
                )
            }
            g2.draw(rect)
        } finally {
            g2.dispose()
        }
    }
}
