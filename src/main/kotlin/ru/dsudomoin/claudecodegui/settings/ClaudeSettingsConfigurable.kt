package ru.dsudomoin.claudecodegui.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.bridge.BridgeManager
import ru.dsudomoin.claudecodegui.bridge.NodeDetector
import ru.dsudomoin.claudecodegui.service.LanguageChangeListener
import ru.dsudomoin.claudecodegui.service.OAuthCredentialService
import ru.dsudomoin.claudecodegui.service.SettingsService
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel

class ClaudeSettingsConfigurable : Configurable {
    private var baseUrlField: JBTextField? = null
    private var modelField: JBTextField? = null
    private var maxTokensField: JBTextField? = null
    private var systemPromptField: JBTextField? = null
    private var nodePathField: JBTextField? = null
    private var claudePathField: JBTextField? = null
    private var permissionModeCombo: ComboBox<String>? = null
    private var languageCombo: ComboBox<String>? = null
    private var panel: JPanel? = null

    companion object {
        private val PERMISSION_MODES = arrayOf("default", "plan", "bypassPermissions")
        private val LANGUAGES = arrayOf("", "en", "ru")

        private fun permissionModeLabel(value: String): String = when (value) {
            "default" -> UcuBundle.message("settings.permDefault")
            "plan" -> UcuBundle.message("settings.permPlan")
            "bypassPermissions" -> UcuBundle.message("settings.permBypass")
            else -> value
        }

        private fun languageLabel(value: String): String = when (value) {
            "" -> UcuBundle.message("settings.lang.default")
            "en" -> "English"
            "ru" -> "Русский"
            else -> value
        }
    }

    override fun getDisplayName() = "Ultimate Claude UI"

    override fun createComponent(): JComponent {
        baseUrlField = JBTextField()
        modelField = JBTextField()
        maxTokensField = JBTextField()
        systemPromptField = JBTextField()
        nodePathField = JBTextField()
        claudePathField = JBTextField()
        permissionModeCombo = ComboBox(DefaultComboBoxModel(PERMISSION_MODES)).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ) = super.getListCellRendererComponent(
                    list, permissionModeLabel(value as? String ?: ""), index, isSelected, cellHasFocus
                )
            }
        }
        languageCombo = ComboBox(DefaultComboBoxModel(LANGUAGES)).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ) = super.getListCellRendererComponent(
                    list, languageLabel(value as? String ?: ""), index, isSelected, cellHasFocus
                )
            }
        }

        // Login status indicator
        val loginStatus = createLoginStatusPanel()

        // Detection info
        val detectedNode = NodeDetector.detect() ?: "not found"
        val detectedClaude = NodeDetector.detectClaude() ?: "not found"
        val sdkVersion = BridgeManager.detectSdkVersion() ?: UcuBundle.message("settings.sdkNotInstalled")

        panel = FormBuilder.createFormBuilder()
            .addComponent(loginStatus)
            .addLabeledComponent(UcuBundle.message("settings.sdkVersion"), JBLabel(sdkVersion).apply {
                foreground = JBColor.GRAY
            })
            .addSeparator()
            .addLabeledComponent(UcuBundle.message("settings.language") + ":", languageCombo!!)
            .addSeparator()
            .addLabeledComponent(UcuBundle.message("settings.nodePath") + ":", nodePathField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.nodeAutoDetected", detectedNode)).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.claudePath") + ":", claudePathField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.nodeAutoDetected", detectedClaude)).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addSeparator()
            .addLabeledComponent(UcuBundle.message("settings.model") + ":", modelField!!)
            .addLabeledComponent(UcuBundle.message("settings.maxTokens") + ":", maxTokensField!!)
            .addLabeledComponent(UcuBundle.message("settings.permissionMode") + ":", permissionModeCombo!!)
            .addLabeledComponent(UcuBundle.message("settings.baseUrl") + ":", baseUrlField!!)
            .addLabeledComponent(UcuBundle.message("settings.systemPrompt") + ":", systemPromptField!!)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    private fun createLoginStatusPanel(): JPanel {
        val info = OAuthCredentialService.getInstance().getLoginInfo()

        val statusLabel = JBLabel().apply {
            if (info.loggedIn && !info.expired) {
                text = UcuBundle.message("settings.loggedIn", info.source)
                foreground = JBColor.namedColor("Claude.StatusOk", JBColor(0x2E7D32, 0x81C784))
            } else if (info.loggedIn && info.expired) {
                text = UcuBundle.message("settings.expired")
                foreground = JBColor.ORANGE
            } else {
                text = UcuBundle.message("settings.notLoggedIn")
                foreground = JBColor.RED
            }
            border = JBUI.Borders.empty(4)
        }

        return JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel(UcuBundle.message("settings.authStatus")).apply {
                font = font.deriveFont(java.awt.Font.BOLD)
            })
            add(statusLabel)
        }
    }

    override fun isModified(): Boolean {
        val s = SettingsService.getInstance().state
        return baseUrlField?.text != s.claudeBaseUrl ||
                modelField?.text != s.claudeModel ||
                maxTokensField?.text != s.maxTokens.toString() ||
                systemPromptField?.text != s.systemPrompt ||
                nodePathField?.text != s.nodePath ||
                claudePathField?.text != s.claudeExecutablePath ||
                permissionModeCombo?.selectedItem != s.permissionMode ||
                languageCombo?.selectedItem != s.language
    }

    override fun apply() {
        val s = SettingsService.getInstance().state
        s.claudeBaseUrl = baseUrlField?.text ?: "https://api.anthropic.com"
        s.claudeModel = modelField?.text ?: "claude-sonnet-4-20250514"
        s.maxTokens = maxTokensField?.text?.toIntOrNull() ?: 8192
        s.systemPrompt = systemPromptField?.text ?: ""
        s.nodePath = nodePathField?.text ?: ""
        s.claudeExecutablePath = claudePathField?.text ?: ""
        s.permissionMode = permissionModeCombo?.selectedItem as? String ?: "default"
        val newLang = languageCombo?.selectedItem as? String ?: ""
        if (newLang != s.language) {
            s.language = newLang
            UcuBundle.clearCache()
            com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
                .syncPublisher(LanguageChangeListener.TOPIC).onLanguageChanged()
        }
    }

    override fun reset() {
        val s = SettingsService.getInstance().state
        baseUrlField?.text = s.claudeBaseUrl
        modelField?.text = s.claudeModel
        maxTokensField?.text = s.maxTokens.toString()
        systemPromptField?.text = s.systemPrompt
        nodePathField?.text = s.nodePath
        claudePathField?.text = s.claudeExecutablePath
        permissionModeCombo?.selectedItem = s.permissionMode
        languageCombo?.selectedItem = s.language
    }
}
