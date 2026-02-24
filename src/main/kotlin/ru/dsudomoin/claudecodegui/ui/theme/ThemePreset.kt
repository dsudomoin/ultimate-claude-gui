package ru.dsudomoin.claudecodegui.ui.theme

data class ThemePreset(
    val id: String,
    val displayName: String,
    val colors: Map<String, Pair<Int, Int>> // key → (lightRGB, darkRGB)
)

object ThemePresets {

    val DEFAULT = ThemePreset(
        id = "default",
        displayName = "Default",
        colors = emptyMap()
    )

    val DARK_PLUS = ThemePreset(
        id = "dark_plus",
        displayName = "Dark+",
        colors = mapOf(
            "accent" to (0x569CD6 to 0x569CD6),
            "accentSecondary" to (0xC586C0 to 0xC586C0),
            "userBubbleBg" to (0x264F78 to 0x264F78),
            "userBubbleFg" to (0xFFFFFF to 0xFFFFFF),
            "textPrimary" to (0x1E1E1E to 0xD4D4D4),
            "textSecondary" to (0x6A6A6A to 0x808080),
            "surfacePrimary" to (0xFFFFFF to 0x1E1E1E),
            "surfaceSecondary" to (0xF3F3F3 to 0x252526),
            "surfaceHover" to (0xE8E8E8 to 0x2A2D2E),
            "borderNormal" to (0xCECECE to 0x3C3C3C),
            "statusSuccess" to (0x6A9955 to 0x6A9955),
            "statusError" to (0xF44747 to 0xF44747),
            "codeBg" to (0xF5F5F5 to 0x1E1E1E),
            "codeFg" to (0xA31515 to 0xCE9178),
        )
    )

    val WARM = ThemePreset(
        id = "warm",
        displayName = "Warm",
        colors = mapOf(
            "accent" to (0xE86B30 to 0xE88B50),
            "accentSecondary" to (0xD4A373 to 0xE0B084),
            "userBubbleBg" to (0xE86B30 to 0xC4632A),
            "userBubbleFg" to (0xFFFFFF to 0xFFFFFF),
            "textPrimary" to (0x2C1810 to 0xE8D5C4),
            "textSecondary" to (0x7A5C4A to 0x9A8070),
            "surfacePrimary" to (0xFFF8F0 to 0x1E1A16),
            "surfaceSecondary" to (0xFAF0E6 to 0x24201C),
            "surfaceHover" to (0xF0E4D6 to 0x2E2824),
            "borderNormal" to (0xD4B89C to 0x4A3C30),
            "statusSuccess" to (0x5D8C3E to 0x7DAF5E),
            "codeBg" to (0xF5EDE4 to 0x28221E),
            "codeFg" to (0xB85C30 to 0xD49A6A),
            "approveBg" to (0x5D8C3E to 0x5D8C3E),
        )
    )

    val ALL = listOf(DEFAULT, DARK_PLUS, WARM)

    fun findById(id: String): ThemePreset? = ALL.find { it.id == id }
}
