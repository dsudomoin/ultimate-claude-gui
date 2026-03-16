package ru.dsudomoin.claudecodegui.ui.compose.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * CompositionLocal providing the current font scale factor.
 * Default 1.0 = 100%. Range: 0.7–2.0.
 */
val LocalFontScale = compositionLocalOf { 1.0f }

/**
 * Returns a scaled TextUnit based on the current font scale.
 * Usage: `scaledSp(13)` instead of `13.sp`
 */
@Composable
fun scaledSp(base: Int): TextUnit {
    val scale = LocalFontScale.current
    return (base * scale).sp
}

@Composable
fun scaledSp(base: Float): TextUnit {
    val scale = LocalFontScale.current
    return (base * scale).sp
}
