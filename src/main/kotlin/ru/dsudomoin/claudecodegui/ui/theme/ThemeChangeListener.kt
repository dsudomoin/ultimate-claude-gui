package ru.dsudomoin.claudecodegui.ui.theme

import com.intellij.util.messages.Topic

fun interface ThemeChangeListener {
    companion object {
        @JvmField
        val TOPIC = Topic.create("ClaudeGuiThemeChanged", ThemeChangeListener::class.java)
    }

    fun themeColorsChanged()
}
