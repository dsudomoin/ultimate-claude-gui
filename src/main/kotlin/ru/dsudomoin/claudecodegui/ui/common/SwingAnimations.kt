package ru.dsudomoin.claudecodegui.ui.common

import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent
import javax.swing.Timer
import kotlin.math.pow

/**
 * Centralised animation utilities for Swing components.
 */
object SwingAnimations {

    /**
     * Smoothly animate a component's preferred height from its current value
     * to [targetHeight] over [duration] milliseconds with ease-in-out-cubic easing.
     */
    fun animateHeight(
        component: JComponent,
        targetHeight: Int,
        duration: Int = 200,
        onComplete: (() -> Unit)? = null
    ) {
        val startHeight = component.height.coerceAtLeast(0)
        if (startHeight == targetHeight) {
            onComplete?.invoke()
            return
        }

        val startTime = System.currentTimeMillis()

        val timer = Timer(16, null) // ~60 fps
        timer.addActionListener {
            val elapsed = System.currentTimeMillis() - startTime
            val progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
            val eased = easeInOutCubic(progress)
            val currentHeight = (startHeight + (targetHeight - startHeight) * eased).toInt()

            component.preferredSize = Dimension(component.width, currentHeight)
            component.revalidate()
            component.repaint()

            if (progress >= 1f) {
                timer.stop()
                onComplete?.invoke()
            }
        }
        timer.start()
    }

    private fun easeInOutCubic(t: Float): Float {
        return if (t < 0.5f) {
            4f * t * t * t
        } else {
            1f - (-2f * t + 2f).pow(3f) / 2f
        }
    }
}

/**
 * Paint the component with the alpha stored in the `"alpha"` client property (0f–1f).
 * If no property is set, paints at full opacity.
 */
fun JComponent.paintWithAlpha(g: Graphics, paintAction: () -> Unit) {
    val alpha = getClientProperty("alpha") as? Float ?: 1f
    if (alpha < 1f) {
        val g2 = g.create() as Graphics2D
        try {
            g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
            paintAction()
        } finally {
            g2.dispose()
        }
    } else {
        paintAction()
    }
}
