package ru.dsudomoin.claudecodegui.ui.theme

import java.awt.Color

object ThemeColorSerializer {

    /** Serialize a light/dark color pair to "RRGGBB:RRGGBB" format. */
    fun serialize(light: Color, dark: Color): String =
        "${colorToHex(light)}:${colorToHex(dark)}"

    /** Serialize a light/dark RGB int pair. */
    fun serialize(lightRgb: Int, darkRgb: Int): String =
        "${String.format("%06x", lightRgb and 0xFFFFFF)}:${String.format("%06x", darkRgb and 0xFFFFFF)}"

    /** Deserialize "RRGGBB:RRGGBB" string to (lightRGB, darkRGB) pair. */
    fun deserialize(value: String): Pair<Int, Int>? {
        val parts = value.split(":")
        if (parts.size != 2) return null
        return try {
            Integer.parseInt(parts[0], 16) to Integer.parseInt(parts[1], 16)
        } catch (_: Exception) {
            null
        }
    }

    private fun colorToHex(c: Color): String =
        String.format("%06x", c.rgb and 0xFFFFFF)
}
