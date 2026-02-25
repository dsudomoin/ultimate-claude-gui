package ru.dsudomoin.claudecodegui.ui.compose.theme

import androidx.compose.ui.graphics.Color
import com.intellij.ui.JBColor

/** Convert a [JBColor] to a Compose [Color], resolving light/dark for the current LAF. */
fun JBColor.toComposeColor(): Color {
    val resolved = this.rgb
    return Color(
        red = (resolved shr 16 and 0xFF) / 255f,
        green = (resolved shr 8 and 0xFF) / 255f,
        blue = (resolved and 0xFF) / 255f,
        alpha = (resolved shr 24 and 0xFF) / 255f,
    )
}

/** Convert an AWT [java.awt.Color] to a Compose [Color]. */
fun java.awt.Color.toComposeColor(): Color {
    return Color(
        red = red / 255f,
        green = green / 255f,
        blue = blue / 255f,
        alpha = alpha / 255f,
    )
}

/** Convert a Compose [Color] to an AWT [java.awt.Color]. */
fun Color.toAwtColor(): java.awt.Color {
    return java.awt.Color(red, green, blue, alpha)
}
