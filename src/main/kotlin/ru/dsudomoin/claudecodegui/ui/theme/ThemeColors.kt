package ru.dsudomoin.claudecodegui.ui.theme

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Centralized color registry for all Claude Code GUI UI components.
 *
 * ~20 base [var] properties are user-customizable.
 * Derived [val] properties are computed from base colors automatically.
 *
 * Components reference ThemeColors.* directly at paint time — never cache locally.
 */
object ThemeColors {

    // ── Defaults (saved for resetToDefaults) ────────────────────
    private val DEFAULTS = mapOf(
        "accent" to (0x0078D4 to 0x589DF6),
        "accentSecondary" to (0x6B5CE7 to 0xA28BFE),
        "userBubbleBg" to (0x0078D4 to 0x005FB8),
        "userBubbleFg" to (0xFFFFFF to 0xFFFFFF),
        "textPrimary" to (0x1A1A1A to 0xE0E0E0),
        "textSecondary" to (0x666666 to 0x888888),
        "surfacePrimary" to (0xFFFFFF to 0x1E1E1E),
        "surfaceSecondary" to (0xFAFAFA to 0x1E1F22),
        "surfaceHover" to (0xF3F3F3 to 0x252526),
        "borderNormal" to (0xD0D0D0 to 0x3E3E42),
        "statusSuccess" to (0x107C10 to 0x4CAF50),
        "statusWarning" to (0xF7630C to 0xFF9800),
        "statusError" to (0xE81123 to 0xF44336),
        "diffAddFg" to (0x107C10 to 0x89D185),
        "diffDelFg" to (0xE81123 to 0xFF6B6B),
        "diffAddBg" to (0xE6FFEC to 0x1A3A1A),
        "diffDelBg" to (0xFFE6E6 to 0x3A1A1A),
        "diffModifiedFg" to (0x1565C0 to 0x90CAF9),
        "codeBg" to (0xF0F0F0 to 0x2A2D2E),
        "codeFg" to (0xC7254E to 0xE06C75),
        "approveBg" to (0x168B32 to 0x2E9E48),
        "denyBg" to (0xEBEEF2 to 0x2C3036),
        "surfaceTertiary" to (0xF0F0F0 to 0x2A2D30),
    )

    // ── Accent / Brand ──────────────────────────────────────────
    var accent = jbColor(0x0078D4, 0x589DF6)
    var accentSecondary = jbColor(0x6B5CE7, 0xA28BFE)

    // ── User Message ────────────────────────────────────────────
    var userBubbleBg = jbColor(0x0078D4, 0x005FB8)
    var userBubbleFg = jbColor(0xFFFFFF, 0xFFFFFF)

    // ── Text ────────────────────────────────────────────────────
    var textPrimary = jbColor(0x1A1A1A, 0xE0E0E0)
    var textSecondary = jbColor(0x666666, 0x888888)

    // ── Surfaces ────────────────────────────────────────────────
    var surfacePrimary = jbColor(0xFFFFFF, 0x1E1E1E)
    var surfaceSecondary = jbColor(0xFAFAFA, 0x1E1F22)
    var surfaceHover = jbColor(0xF3F3F3, 0x252526)
    var surfaceTertiary = jbColor(0xF0F0F0, 0x2A2D30)

    // ── Borders ─────────────────────────────────────────────────
    var borderNormal = jbColor(0xD0D0D0, 0x3E3E42)

    // ── Status ──────────────────────────────────────────────────
    var statusSuccess = jbColor(0x107C10, 0x4CAF50)
    var statusWarning = jbColor(0xF7630C, 0xFF9800)
    var statusError = jbColor(0xE81123, 0xF44336)

    // ── Diff ────────────────────────────────────────────────────
    var diffAddFg = jbColor(0x107C10, 0x89D185)
    var diffDelFg = jbColor(0xE81123, 0xFF6B6B)
    var diffAddBg = jbColor(0xE6FFEC, 0x1A3A1A)
    var diffDelBg = jbColor(0xFFE6E6, 0x3A1A1A)
    var diffModifiedFg = jbColor(0x1565C0, 0x90CAF9)

    // ── Code / Markdown ─────────────────────────────────────────
    var codeBg = jbColor(0xF0F0F0, 0x2A2D2E)
    var codeFg = jbColor(0xC7254E, 0xE06C75)

    // ── Plan Approve/Deny ───────────────────────────────────────
    var approveBg = jbColor(0x168B32, 0x2E9E48)
    var denyBg = jbColor(0xEBEEF2, 0x2C3036)

    // ─── Computed / Derived Colors (not user-customizable) ──────

    val borderFocus: JBColor get() = accent

    val userBubbleBorder: JBColor
        get() = JBColor(
            Color(userBubbleBg.rgb and 0x00FFFFFF or (0x10 shl 24), true),
            Color(0xFF, 0xFF, 0xFF, 0x10)
        )

    val separatorColor: JBColor
        get() = JBColor(Color(0x00, 0x00, 0x00, 0x12), Color(0xFF, 0xFF, 0xFF, 0x12))

    val hoverOverlay: JBColor
        get() = JBColor(Color(0x00, 0x00, 0x00, 0x0D), Color(0xFF, 0xFF, 0xFF, 0x1A))

    val iconHoverBg: JBColor
        get() = JBColor(Color(0x00, 0x00, 0x00, 0x15), Color(0xFF, 0xFF, 0xFF, 0x15))

    val toolbarBg: JBColor
        get() = JBColor(Color(0x00, 0x00, 0x00, 0x08), Color(0x00, 0x00, 0x00, 0x1A))

    val approveHover: JBColor
        get() = JBColor(Color(0x13, 0x78, 0x2B), Color(0x3A, 0xB0, 0x56))

    val denyHover: JBColor
        get() = JBColor(Color(0xDE, 0xE1, 0xE5), Color(0x38, 0x3C, 0x44))

    val denyBorder: JBColor
        get() = JBColor(Color(0xC8, 0xCB, 0xD0), Color(0x48, 0x4C, 0x54))

    // ── Chip colors (for QuestionSelectionPanel) ────────────────
    val chipBg: JBColor get() = codeBg
    val chipSelectedBg: JBColor
        get() = JBColor(Color(0xE3, 0xF2, 0xFD), Color(0x09, 0x47, 0x71))
    val chipHover: JBColor
        get() = JBColor(Color(0xE8, 0xE8, 0xE8), Color(0x33, 0x36, 0x37))
    val chipBorder: JBColor get() = borderNormal
    val chipSelectedBorder: JBColor get() = accent

    // ── Nav button colors (for ChatPanel) ───────────────────────
    val navBg: JBColor
        get() = JBColor(Color(0xFF, 0xFF, 0xFF, 0xE6), Color(0x2B, 0x2B, 0x2B, 0xE6))
    val navBorder: JBColor
        get() = JBColor(Color(0x00, 0x00, 0x00, 0x1A), Color(0xFF, 0xFF, 0xFF, 0x1A))
    val navArrow: JBColor get() = textSecondary
    val navHoverBg: JBColor
        get() = JBColor(Color(0xF0, 0xF0, 0xF0, 0xF0), Color(0x3C, 0x3C, 0x3C, 0xF0))

    // ── Thinking panel ──────────────────────────────────────────
    val thinkingBorder: JBColor
        get() = JBColor(Color(0x00, 0x00, 0x00, 0x20), Color(0xFF, 0xFF, 0xFF, 0x20))

    // ── Welcome screen ──────────────────────────────────────────
    val welcomeTipBg: JBColor
        get() = JBColor(Color(0x00, 0x00, 0x00, 0x06), Color(0xFF, 0xFF, 0xFF, 0x08))
    val welcomeTipBorder: JBColor
        get() = JBColor(Color(0x00, 0x00, 0x00, 0x0C), Color(0xFF, 0xFF, 0xFF, 0x0C))

    // ── PlanActionPanel specific ────────────────────────────────
    val planBarBg: JBColor
        get() = JBColor(Color(0xF5, 0xF8, 0xFE), Color(0x1E, 0x22, 0x2A))
    val planBarBorder: JBColor
        get() = JBColor(Color(0xC8, 0xD8, 0xEE), Color(0x33, 0x3D, 0x50))

    // ── HistoryPanel specific ───────────────────────────────────
    val historyAccent: JBColor
        get() = JBColor(Color(0xE8, 0x6B, 0x30), Color(0xE8, 0x8B, 0x50))

    // ── Toggle colors ───────────────────────────────────────────
    val toggleOn: JBColor get() = accent
    val toggleOff: JBColor
        get() = JBColor(Color(0xB0, 0xB0, 0xB0), Color(0x55, 0x55, 0x55))
    val toggleKnob: Color = Color.WHITE

    // ── Dropdown colors ─────────────────────────────────────────
    val dropdownBg: JBColor get() = surfaceHover
    val dropdownBorder: JBColor
        get() = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x45, 0x45, 0x45))
    val dropdownHover: JBColor get() = surfaceHover
    val dropdownSelected: JBColor get() = chipSelectedBg

    // ── Quote border ────────────────────────────────────────────
    val quoteBorder: JBColor
        get() = JBColor(Color(0xB0, 0xB0, 0xB0), Color(0x55, 0x55, 0x55))

    // ── Status panel specific ───────────────────────────────────
    val statusPending: JBColor
        get() = JBColor(Color(0xB0, 0xB0, 0xB0), Color(0x66, 0x66, 0x66))
    val statusProgress: JBColor get() = accent

    // ── HistoryPanel card colors ────────────────────────────────
    val cardBg: JBColor get() = surfacePrimary
    val cardHover: JBColor
        get() = JBColor(Color(0xEE, 0xEE, 0xEE), Color(0x33, 0x36, 0x38))

    // ─── Public API ─────────────────────────────────────────────

    /** All customizable color keys with their current values. */
    val allKeys: List<String> get() = DEFAULTS.keys.toList()

    /** Get default values for a key. */
    fun getDefault(key: String): Pair<Int, Int>? = DEFAULTS[key]

    /** Reset all customizable colors to factory defaults. */
    fun resetToDefaults() {
        DEFAULTS.forEach { (key, pair) ->
            setColorByKey(key, pair.first, pair.second)
        }
    }

    /** Apply a map of color overrides. */
    fun applyOverrides(overrides: Map<String, Pair<Int, Int>>) {
        overrides.forEach { (key, pair) ->
            setColorByKey(key, pair.first, pair.second)
        }
    }

    /** Get current light/dark RGB pair for a customizable key. */
    fun getColorPair(key: String): Pair<Int, Int>? {
        val color = getColorByKey(key) ?: return null
        // JBColor: first color = light, resolve in dark context is second
        // We store the RGB values directly
        return (color.rgb and 0xFFFFFF) to (JBColor(Color(0), color).rgb and 0xFFFFFF)
    }

    fun setColorByKey(key: String, lightRgb: Int, darkRgb: Int) {
        val color = jbColor(lightRgb, darkRgb)
        when (key) {
            "accent" -> accent = color
            "accentSecondary" -> accentSecondary = color
            "userBubbleBg" -> userBubbleBg = color
            "userBubbleFg" -> userBubbleFg = color
            "textPrimary" -> textPrimary = color
            "textSecondary" -> textSecondary = color
            "surfacePrimary" -> surfacePrimary = color
            "surfaceSecondary" -> surfaceSecondary = color
            "surfaceHover" -> surfaceHover = color
            "surfaceTertiary" -> surfaceTertiary = color
            "borderNormal" -> borderNormal = color
            "statusSuccess" -> statusSuccess = color
            "statusWarning" -> statusWarning = color
            "statusError" -> statusError = color
            "diffAddFg" -> diffAddFg = color
            "diffDelFg" -> diffDelFg = color
            "diffAddBg" -> diffAddBg = color
            "diffDelBg" -> diffDelBg = color
            "diffModifiedFg" -> diffModifiedFg = color
            "codeBg" -> codeBg = color
            "codeFg" -> codeFg = color
            "approveBg" -> approveBg = color
            "denyBg" -> denyBg = color
        }
    }

    private fun getColorByKey(key: String): JBColor? = when (key) {
        "accent" -> accent
        "accentSecondary" -> accentSecondary
        "userBubbleBg" -> userBubbleBg
        "userBubbleFg" -> userBubbleFg
        "textPrimary" -> textPrimary
        "textSecondary" -> textSecondary
        "surfacePrimary" -> surfacePrimary
        "surfaceSecondary" -> surfaceSecondary
        "surfaceHover" -> surfaceHover
        "surfaceTertiary" -> surfaceTertiary
        "borderNormal" -> borderNormal
        "statusSuccess" -> statusSuccess
        "statusWarning" -> statusWarning
        "statusError" -> statusError
        "diffAddFg" -> diffAddFg
        "diffDelFg" -> diffDelFg
        "diffAddBg" -> diffAddBg
        "diffDelBg" -> diffDelBg
        "diffModifiedFg" -> diffModifiedFg
        "codeBg" -> codeBg
        "codeFg" -> codeFg
        "approveBg" -> approveBg
        "denyBg" -> denyBg
        else -> null
    }

    private fun jbColor(lightRgb: Int, darkRgb: Int) =
        JBColor(Color(lightRgb), Color(darkRgb))
}
