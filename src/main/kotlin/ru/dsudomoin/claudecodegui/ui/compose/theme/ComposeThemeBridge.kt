package ru.dsudomoin.claudecodegui.ui.compose.theme

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.intellij.openapi.application.ApplicationManager
import ru.dsudomoin.claudecodegui.ui.theme.ThemeChangeListener
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors

/**
 * Compose color palette mirroring [ThemeColors].
 *
 * Snapshot of all ThemeColors properties at creation time, converted to Compose [Color].
 * A new instance is created on every theme change via [ThemeChangeListener].
 */
@Immutable
data class ClaudeColorPalette(
    // ── Accent / Brand
    val accent: Color,
    val accentSecondary: Color,
    // ── User Bubble
    val userBubbleBg: Color,
    val userBubbleFg: Color,
    // ── Text
    val textPrimary: Color,
    val textSecondary: Color,
    // ── Surfaces
    val surfacePrimary: Color,
    val surfaceSecondary: Color,
    val surfaceHover: Color,
    val surfaceTertiary: Color,
    // ── Borders
    val borderNormal: Color,
    val borderFocus: Color,
    // ── Status
    val statusSuccess: Color,
    val statusWarning: Color,
    val statusError: Color,
    // ── Diff
    val diffAddFg: Color,
    val diffDelFg: Color,
    val diffAddBg: Color,
    val diffDelBg: Color,
    val diffModifiedFg: Color,
    // ── Code
    val codeBg: Color,
    val codeFg: Color,
    // ── Approve / Deny
    val approveBg: Color,
    val approveHover: Color,
    val denyBg: Color,
    val denyHover: Color,
    val denyBorder: Color,
    // ── Derived
    val userBubbleBorder: Color,
    val separatorColor: Color,
    val hoverOverlay: Color,
    val iconHoverBg: Color,
    val toolbarBg: Color,
    // ── Chip
    val chipBg: Color,
    val chipSelectedBg: Color,
    val chipHover: Color,
    val chipBorder: Color,
    val chipSelectedBorder: Color,
    // ── Nav
    val navBg: Color,
    val navBorder: Color,
    val navArrow: Color,
    val navHoverBg: Color,
    // ── Thinking
    val thinkingBorder: Color,
    // ── Welcome
    val welcomeTipBg: Color,
    val welcomeTipBorder: Color,
    // ── Plan bar
    val planBarBg: Color,
    val planBarBorder: Color,
    // ── History
    val historyAccent: Color,
    val cardBg: Color,
    val cardHover: Color,
    // ── Toggle
    val toggleOn: Color,
    val toggleOff: Color,
    val toggleKnob: Color,
    // ── Dropdown
    val dropdownBg: Color,
    val dropdownBorder: Color,
    val dropdownHover: Color,
    val dropdownSelected: Color,
    // ── Quote
    val quoteBorder: Color,
    // ── Status panel
    val statusPending: Color,
    val statusProgress: Color,
) {
    companion object {
        /** Read all [ThemeColors] properties and snapshot them as Compose colors. */
        fun fromThemeColors(): ClaudeColorPalette = ClaudeColorPalette(
            accent = ThemeColors.accent.toComposeColor(),
            accentSecondary = ThemeColors.accentSecondary.toComposeColor(),
            userBubbleBg = ThemeColors.userBubbleBg.toComposeColor(),
            userBubbleFg = ThemeColors.userBubbleFg.toComposeColor(),
            textPrimary = ThemeColors.textPrimary.toComposeColor(),
            textSecondary = ThemeColors.textSecondary.toComposeColor(),
            surfacePrimary = ThemeColors.surfacePrimary.toComposeColor(),
            surfaceSecondary = ThemeColors.surfaceSecondary.toComposeColor(),
            surfaceHover = ThemeColors.surfaceHover.toComposeColor(),
            surfaceTertiary = ThemeColors.surfaceTertiary.toComposeColor(),
            borderNormal = ThemeColors.borderNormal.toComposeColor(),
            borderFocus = ThemeColors.borderFocus.toComposeColor(),
            statusSuccess = ThemeColors.statusSuccess.toComposeColor(),
            statusWarning = ThemeColors.statusWarning.toComposeColor(),
            statusError = ThemeColors.statusError.toComposeColor(),
            diffAddFg = ThemeColors.diffAddFg.toComposeColor(),
            diffDelFg = ThemeColors.diffDelFg.toComposeColor(),
            diffAddBg = ThemeColors.diffAddBg.toComposeColor(),
            diffDelBg = ThemeColors.diffDelBg.toComposeColor(),
            diffModifiedFg = ThemeColors.diffModifiedFg.toComposeColor(),
            codeBg = ThemeColors.codeBg.toComposeColor(),
            codeFg = ThemeColors.codeFg.toComposeColor(),
            approveBg = ThemeColors.approveBg.toComposeColor(),
            approveHover = ThemeColors.approveHover.toComposeColor(),
            denyBg = ThemeColors.denyBg.toComposeColor(),
            denyHover = ThemeColors.denyHover.toComposeColor(),
            denyBorder = ThemeColors.denyBorder.toComposeColor(),
            userBubbleBorder = ThemeColors.userBubbleBorder.toComposeColor(),
            separatorColor = ThemeColors.separatorColor.toComposeColor(),
            hoverOverlay = ThemeColors.hoverOverlay.toComposeColor(),
            iconHoverBg = ThemeColors.iconHoverBg.toComposeColor(),
            toolbarBg = ThemeColors.toolbarBg.toComposeColor(),
            chipBg = ThemeColors.chipBg.toComposeColor(),
            chipSelectedBg = ThemeColors.chipSelectedBg.toComposeColor(),
            chipHover = ThemeColors.chipHover.toComposeColor(),
            chipBorder = ThemeColors.chipBorder.toComposeColor(),
            chipSelectedBorder = ThemeColors.chipSelectedBorder.toComposeColor(),
            navBg = ThemeColors.navBg.toComposeColor(),
            navBorder = ThemeColors.navBorder.toComposeColor(),
            navArrow = ThemeColors.navArrow.toComposeColor(),
            navHoverBg = ThemeColors.navHoverBg.toComposeColor(),
            thinkingBorder = ThemeColors.thinkingBorder.toComposeColor(),
            welcomeTipBg = ThemeColors.welcomeTipBg.toComposeColor(),
            welcomeTipBorder = ThemeColors.welcomeTipBorder.toComposeColor(),
            planBarBg = ThemeColors.planBarBg.toComposeColor(),
            planBarBorder = ThemeColors.planBarBorder.toComposeColor(),
            historyAccent = ThemeColors.historyAccent.toComposeColor(),
            cardBg = ThemeColors.cardBg.toComposeColor(),
            cardHover = ThemeColors.cardHover.toComposeColor(),
            toggleOn = ThemeColors.toggleOn.toComposeColor(),
            toggleOff = ThemeColors.toggleOff.toComposeColor(),
            toggleKnob = ThemeColors.toggleKnob.toComposeColor(),
            dropdownBg = ThemeColors.dropdownBg.toComposeColor(),
            dropdownBorder = ThemeColors.dropdownBorder.toComposeColor(),
            dropdownHover = ThemeColors.dropdownHover.toComposeColor(),
            dropdownSelected = ThemeColors.dropdownSelected.toComposeColor(),
            quoteBorder = ThemeColors.quoteBorder.toComposeColor(),
            statusPending = ThemeColors.statusPending.toComposeColor(),
            statusProgress = ThemeColors.statusProgress.toComposeColor(),
        )
    }
}

/** CompositionLocal providing the current [ClaudeColorPalette]. */
val LocalClaudeColors = staticCompositionLocalOf { ClaudeColorPalette.fromThemeColors() }

/**
 * Root theme wrapper for all Claude Compose UI.
 *
 * Reads colors from [ThemeColors], subscribes to [ThemeChangeListener.TOPIC],
 * and triggers recomposition when the theme changes.
 *
 * Usage:
 * ```
 * ClaudeComposeTheme {
 *     Text("Hello", color = LocalClaudeColors.current.textPrimary)
 * }
 * ```
 */
@Composable
fun ClaudeComposeTheme(content: @Composable () -> Unit) {
    var palette by remember { mutableStateOf(ClaudeColorPalette.fromThemeColors()) }
    val defaultScrollbarStyle = LocalScrollbarStyle.current
    val scrollbarStyle: ScrollbarStyle = remember(palette, defaultScrollbarStyle) {
        val isDarkSurface = palette.surfacePrimary.luminance() < 0.5f
        val baseScrollbarColor = if (isDarkSurface) Color.White else Color.Black
        val unhoverAlpha = if (isDarkSurface) 0.26f else 0.18f
        val hoverAlpha = if (isDarkSurface) 0.42f else 0.32f
        defaultScrollbarStyle.copy(
            unhoverColor = baseScrollbarColor.copy(alpha = unhoverAlpha),
            hoverColor = baseScrollbarColor.copy(alpha = hoverAlpha),
        )
    }

    // Subscribe to theme change notifications from the IntelliJ message bus
    DisposableEffect(Unit) {
        val connection = ApplicationManager.getApplication().messageBus.connect()
        connection.subscribe(
            ThemeChangeListener.TOPIC,
            ThemeChangeListener { palette = ClaudeColorPalette.fromThemeColors() },
        )
        onDispose { connection.disconnect() }
    }

    CompositionLocalProvider(
        LocalClaudeColors provides palette,
        LocalScrollbarStyle provides scrollbarStyle,
    ) {
        content()
    }
}
