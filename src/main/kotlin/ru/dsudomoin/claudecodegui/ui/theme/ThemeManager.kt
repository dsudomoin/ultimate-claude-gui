package ru.dsudomoin.claudecodegui.ui.theme

import com.intellij.openapi.application.ApplicationManager
import ru.dsudomoin.claudecodegui.service.SettingsService

object ThemeManager {

    private var initialized = false

    /** Load theme from persisted settings. Call once at startup. */
    fun initialize() {
        if (initialized) return
        initialized = true
        val state = SettingsService.getInstance().state
        applyThemeQuietly(state.themePresetId, state.customColorOverrides)
    }

    /** Apply theme and broadcast change to all subscribers. */
    fun applyTheme(presetId: String, customOverrides: Map<String, String>) {
        applyThemeQuietly(presetId, customOverrides)
        notifyListeners()
    }

    /** Save to settings and apply. */
    fun saveAndApply(presetId: String, customOverrides: Map<String, String>) {
        val state = SettingsService.getInstance().state
        state.themePresetId = presetId
        state.customColorOverrides = customOverrides
        applyTheme(presetId, customOverrides)
    }

    /** Broadcast theme change notification. */
    fun notifyListeners() {
        ApplicationManager.getApplication().messageBus
            .syncPublisher(ThemeChangeListener.TOPIC)
            .themeColorsChanged()
    }

    private fun applyThemeQuietly(presetId: String, customOverrides: Map<String, String>) {
        ThemeColors.resetToDefaults()

        // Apply preset overrides
        val preset = ThemePresets.findById(presetId)
        if (preset != null) {
            ThemeColors.applyOverrides(preset.colors)
        }

        // Apply user custom overrides on top
        val parsed = customOverrides.mapNotNull { (key, value) ->
            ThemeColorSerializer.deserialize(value)?.let { key to it }
        }.toMap()
        ThemeColors.applyOverrides(parsed)
    }
}
