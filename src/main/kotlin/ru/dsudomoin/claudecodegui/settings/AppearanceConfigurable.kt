package ru.dsudomoin.claudecodegui.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.service.SettingsService
import ru.dsudomoin.claudecodegui.ui.theme.*
import java.awt.*
import javax.swing.*

class AppearanceConfigurable : Configurable {

    private data class ColorRow(
        val key: String,
        val lightPicker: ColorPanel,
        val darkPicker: ColorPanel,
    )

    private var panel: JPanel? = null
    private var presetCombo: ComboBox<String>? = null
    private val colorRows = mutableListOf<ColorRow>()

    // Ordered list of (key, labelKey) for the settings UI
    private val colorEntries = listOf(
        // User Message
        null to "appearance.section.userMessage",
        "userBubbleBg" to "appearance.userBubbleBg",
        "userBubbleFg" to "appearance.userBubbleFg",
        // Accent
        null to "appearance.section.accent",
        "accent" to "appearance.accent",
        "accentSecondary" to "appearance.accentSecondary",
        // Text
        null to "appearance.section.text",
        "textPrimary" to "appearance.textPrimary",
        "textSecondary" to "appearance.textSecondary",
        // Surfaces & Borders
        null to "appearance.section.surfaces",
        "surfacePrimary" to "appearance.surfacePrimary",
        "surfaceSecondary" to "appearance.surfaceSecondary",
        "surfaceHover" to "appearance.surfaceHover",
        "borderNormal" to "appearance.borderNormal",
        // Status
        null to "appearance.section.status",
        "statusSuccess" to "appearance.statusSuccess",
        "statusWarning" to "appearance.statusWarning",
        "statusError" to "appearance.statusError",
        // Code
        null to "appearance.section.code",
        "codeBg" to "appearance.codeBg",
        "codeFg" to "appearance.codeFg",
        // Diff
        null to "appearance.section.diff",
        "diffAddFg" to "appearance.diffAddFg",
        "diffDelFg" to "appearance.diffDelFg",
        "diffAddBg" to "appearance.diffAddBg",
        "diffDelBg" to "appearance.diffDelBg",
        // Actions
        null to "appearance.section.actions",
        "approveBg" to "appearance.approveBg",
        "denyBg" to "appearance.denyBg",
    )

    override fun getDisplayName(): String = UcuBundle.message("appearance.title")

    override fun createComponent(): JComponent {
        colorRows.clear()

        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        // Preset selector row
        val presetPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            val presetIds = ThemePresets.ALL.map { it.displayName }.toTypedArray()
            presetCombo = ComboBox(presetIds).apply {
                addActionListener {
                    onPresetSelected()
                }
            }
            add(JBLabel(UcuBundle.message("appearance.preset")))
            add(presetCombo!!)

            val resetButton = JButton(UcuBundle.message("appearance.resetAll")).apply {
                addActionListener { onResetAll() }
            }
            add(Box.createHorizontalStrut(JBUI.scale(16)))
            add(resetButton)
        }
        mainPanel.add(presetPanel)
        mainPanel.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Light / Dark column headers
        val headerPanel = JPanel(GridBagLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(24))
            val gbc = GridBagConstraints().apply {
                gridy = 0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(0, 0, 0, 0)
            }
            gbc.gridx = 0; gbc.weightx = 1.0
            add(JBLabel(""), gbc)
            gbc.gridx = 1; gbc.weightx = 0.0; gbc.insets = Insets(0, JBUI.scale(8), 0, JBUI.scale(8))
            add(JBLabel("Light").apply { font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat()) }, gbc)
            gbc.gridx = 2
            add(JBLabel("Dark").apply { font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat()) }, gbc)
        }
        mainPanel.add(headerPanel)

        // Color rows
        for ((key, labelKey) in colorEntries) {
            if (key == null) {
                // Section header
                mainPanel.add(Box.createVerticalStrut(JBUI.scale(10)))
                val sectionLabel = JBLabel(UcuBundle.message(labelKey)).apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
                    foreground = JBColor(Color(0x33, 0x33, 0x33), Color(0xCC, 0xCC, 0xCC))
                    border = JBUI.Borders.emptyBottom(4)
                    alignmentX = Component.LEFT_ALIGNMENT
                }
                mainPanel.add(sectionLabel)
                mainPanel.add(createSeparator())
            } else {
                val defaults = ThemeColors.getDefault(key)!!
                val row = createColorRow(key, labelKey, defaults.first, defaults.second)
                mainPanel.add(row)
            }
        }

        mainPanel.add(Box.createVerticalGlue())

        val scrollPane = JScrollPane(mainPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        panel = JPanel(BorderLayout()).apply {
            add(scrollPane, BorderLayout.CENTER)
        }

        reset()
        return panel!!
    }

    private fun createColorRow(key: String, labelKey: String, defaultLight: Int, defaultDark: Int): JPanel {
        val lightPicker = ColorPanel().apply { selectedColor = Color(defaultLight) }
        val darkPicker = ColorPanel().apply { selectedColor = Color(defaultDark) }
        colorRows.add(ColorRow(key, lightPicker, darkPicker))

        return JPanel(GridBagLayout()).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(32))
            val gbc = GridBagConstraints().apply {
                gridy = 0
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(JBUI.scale(2), 0, JBUI.scale(2), 0)
            }
            gbc.gridx = 0; gbc.weightx = 1.0
            add(JBLabel(UcuBundle.message(labelKey)), gbc)
            gbc.gridx = 1; gbc.weightx = 0.0; gbc.insets = Insets(JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8))
            add(lightPicker, gbc)
            gbc.gridx = 2
            add(darkPicker, gbc)
        }
    }

    private fun createSeparator(): JComponent {
        return JSeparator(SwingConstants.HORIZONTAL).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(2))
        }
    }

    private fun onPresetSelected() {
        val idx = presetCombo?.selectedIndex ?: return
        val preset = ThemePresets.ALL.getOrNull(idx) ?: return

        // Reset all pickers to defaults first
        for (row in colorRows) {
            val defaults = ThemeColors.getDefault(row.key) ?: continue
            row.lightPicker.selectedColor = Color(defaults.first)
            row.darkPicker.selectedColor = Color(defaults.second)
        }

        // Apply preset overrides
        for ((key, pair) in preset.colors) {
            val row = colorRows.find { it.key == key } ?: continue
            row.lightPicker.selectedColor = Color(pair.first)
            row.darkPicker.selectedColor = Color(pair.second)
        }
    }

    private fun onResetAll() {
        presetCombo?.selectedIndex = 0
        for (row in colorRows) {
            val defaults = ThemeColors.getDefault(row.key) ?: continue
            row.lightPicker.selectedColor = Color(defaults.first)
            row.darkPicker.selectedColor = Color(defaults.second)
        }
    }

    override fun isModified(): Boolean {
        val state = SettingsService.getInstance().state
        val currentPresetIdx = presetCombo?.selectedIndex ?: 0
        val currentPresetId = ThemePresets.ALL.getOrNull(currentPresetIdx)?.id ?: "default"

        if (currentPresetId != state.themePresetId) return true

        val currentOverrides = buildOverridesMap()
        return currentOverrides != state.customColorOverrides
    }

    override fun apply() {
        val currentPresetIdx = presetCombo?.selectedIndex ?: 0
        val currentPresetId = ThemePresets.ALL.getOrNull(currentPresetIdx)?.id ?: "default"
        val overrides = buildOverridesMap()

        ThemeManager.saveAndApply(currentPresetId, overrides)
    }

    override fun reset() {
        val state = SettingsService.getInstance().state

        // Set preset combo
        val presetIdx = ThemePresets.ALL.indexOfFirst { it.id == state.themePresetId }
        presetCombo?.selectedIndex = if (presetIdx >= 0) presetIdx else 0

        // First apply preset defaults
        val preset = ThemePresets.findById(state.themePresetId)
        for (row in colorRows) {
            val defaults = ThemeColors.getDefault(row.key) ?: continue
            val presetOverride = preset?.colors?.get(row.key)
            val light = presetOverride?.first ?: defaults.first
            val dark = presetOverride?.second ?: defaults.second
            row.lightPicker.selectedColor = Color(light)
            row.darkPicker.selectedColor = Color(dark)
        }

        // Then apply custom overrides on top
        for ((key, value) in state.customColorOverrides) {
            val pair = ThemeColorSerializer.deserialize(value) ?: continue
            val row = colorRows.find { it.key == key } ?: continue
            row.lightPicker.selectedColor = Color(pair.first)
            row.darkPicker.selectedColor = Color(pair.second)
        }
    }

    /** Build overrides map: only include colors that differ from preset + defaults. */
    private fun buildOverridesMap(): Map<String, String> {
        val currentPresetIdx = presetCombo?.selectedIndex ?: 0
        val preset = ThemePresets.ALL.getOrNull(currentPresetIdx)
        val result = mutableMapOf<String, String>()

        for (row in colorRows) {
            val defaults = ThemeColors.getDefault(row.key) ?: continue
            val presetOverride = preset?.colors?.get(row.key)
            val expectedLight = presetOverride?.first ?: defaults.first
            val expectedDark = presetOverride?.second ?: defaults.second

            val actualLight = row.lightPicker.selectedColor?.rgb?.and(0xFFFFFF) ?: continue
            val actualDark = row.darkPicker.selectedColor?.rgb?.and(0xFFFFFF) ?: continue

            if (actualLight != expectedLight || actualDark != expectedDark) {
                result[row.key] = ThemeColorSerializer.serialize(actualLight, actualDark)
            }
        }
        return result
    }
}
