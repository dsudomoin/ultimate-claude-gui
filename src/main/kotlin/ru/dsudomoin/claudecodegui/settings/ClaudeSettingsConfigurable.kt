package ru.dsudomoin.claudecodegui.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
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
    private var effortCombo: ComboBox<String>? = null
    private var maxBudgetField: JBTextField? = null
    private var betaContextCheckbox: JBCheckBox? = null
    private var allowedToolsField: JBTextField? = null
    private var disallowedToolsField: JBTextField? = null
    private var continueSessionCheckbox: JBCheckBox? = null
    private var outputFormatField: JBTextField? = null
    private var fileCheckpointingCheckbox: JBCheckBox? = null
    private var mcpServersField: JBTextField? = null
    private var fallbackModelField: JBTextField? = null
    private var additionalDirsField: JBTextField? = null
    private var agentsField: JBTextField? = null
    private var hooksField: JBTextField? = null
    private var ephemeralSessionsCheckbox: JBCheckBox? = null
    private var forkSessionCheckbox: JBCheckBox? = null
    private var sandboxField: JBTextField? = null
    private var pluginsField: JBTextField? = null
    private var languageCombo: ComboBox<String>? = null
    private var panel: JPanel? = null

    companion object {
        private val PERMISSION_MODES = arrayOf("default", "plan", "bypassPermissions")
        private val EFFORT_LEVELS = arrayOf("low", "medium", "high", "max")
        private val LANGUAGES = arrayOf("", "en", "ru")

        private fun permissionModeLabel(value: String): String = when (value) {
            "default" -> UcuBundle.message("settings.permDefault")
            "plan" -> UcuBundle.message("settings.permPlan")
            "bypassPermissions" -> UcuBundle.message("settings.permBypass")
            else -> value
        }

        private fun effortLabel(value: String): String = when (value) {
            "low" -> UcuBundle.message("settings.effort.low")
            "medium" -> UcuBundle.message("settings.effort.medium")
            "high" -> UcuBundle.message("settings.effort.high")
            "max" -> UcuBundle.message("settings.effort.max")
            else -> value
        }

        private fun languageLabel(value: String): String = when (value) {
            "" -> UcuBundle.message("settings.lang.default")
            "en" -> "English"
            "ru" -> "Русский"
            else -> value
        }
    }

    override fun getDisplayName() = "Ultimate Claude GUI"

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
        effortCombo = ComboBox(DefaultComboBoxModel(EFFORT_LEVELS)).apply {
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ) = super.getListCellRendererComponent(
                    list, effortLabel(value as? String ?: ""), index, isSelected, cellHasFocus
                )
            }
        }
        maxBudgetField = JBTextField()
        betaContextCheckbox = JBCheckBox(UcuBundle.message("settings.betaContext"))
        allowedToolsField = JBTextField()
        disallowedToolsField = JBTextField()
        continueSessionCheckbox = JBCheckBox(UcuBundle.message("settings.continueSession"))
        outputFormatField = JBTextField()
        fileCheckpointingCheckbox = JBCheckBox(UcuBundle.message("settings.fileCheckpointing"))
        mcpServersField = JBTextField()
        fallbackModelField = JBTextField()
        additionalDirsField = JBTextField()
        agentsField = JBTextField()
        hooksField = JBTextField()
        ephemeralSessionsCheckbox = JBCheckBox(UcuBundle.message("settings.ephemeralSessions"))
        forkSessionCheckbox = JBCheckBox(UcuBundle.message("settings.forkSession"))
        sandboxField = JBTextField()
        pluginsField = JBTextField()

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
            .addLabeledComponent(UcuBundle.message("settings.effort") + ":", effortCombo!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.effort.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.maxBudget") + ":", maxBudgetField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.maxBudget.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addComponent(betaContextCheckbox!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.betaContext.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.allowedTools") + ":", allowedToolsField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.allowedTools.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.disallowedTools") + ":", disallowedToolsField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.disallowedTools.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addComponent(continueSessionCheckbox!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.continueSession.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.outputFormat") + ":", outputFormatField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.outputFormat.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addComponent(fileCheckpointingCheckbox!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.fileCheckpointing.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.mcpServers") + ":", mcpServersField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.mcpServers.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.fallbackModel") + ":", fallbackModelField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.fallbackModel.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.additionalDirs") + ":", additionalDirsField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.additionalDirs.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.agents") + ":", agentsField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.agents.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.hooks") + ":", hooksField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.hooks.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addComponent(ephemeralSessionsCheckbox!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.ephemeralSessions.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addComponent(forkSessionCheckbox!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.forkSession.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.sandbox") + ":", sandboxField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.sandbox.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent(UcuBundle.message("settings.plugins") + ":", pluginsField!!)
            .addComponentToRightColumn(JBLabel(UcuBundle.message("settings.plugins.desc")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addSeparator()
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
                effortCombo?.selectedItem != s.effort ||
                maxBudgetField?.text != s.maxBudgetUsd ||
                betaContextCheckbox?.isSelected != s.betaContext1m ||
                allowedToolsField?.text != s.allowedTools ||
                disallowedToolsField?.text != s.disallowedTools ||
                continueSessionCheckbox?.isSelected != s.continueSession ||
                outputFormatField?.text != s.outputFormatJson ||
                fileCheckpointingCheckbox?.isSelected != s.enableFileCheckpointing ||
                mcpServersField?.text != s.mcpServersJson ||
                fallbackModelField?.text != s.fallbackModel ||
                additionalDirsField?.text != s.additionalDirectories ||
                agentsField?.text != s.agentsJson ||
                hooksField?.text != s.hooksJson ||
                ephemeralSessionsCheckbox?.isSelected != s.ephemeralSessions ||
                forkSessionCheckbox?.isSelected != s.forkSessionOnResume ||
                sandboxField?.text != s.sandboxJson ||
                pluginsField?.text != s.pluginsJson ||
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
        s.effort = effortCombo?.selectedItem as? String ?: "high"
        s.maxBudgetUsd = maxBudgetField?.text?.trim() ?: ""
        s.betaContext1m = betaContextCheckbox?.isSelected ?: false
        s.allowedTools = allowedToolsField?.text?.trim() ?: ""
        s.disallowedTools = disallowedToolsField?.text?.trim() ?: ""
        s.continueSession = continueSessionCheckbox?.isSelected ?: false
        s.outputFormatJson = outputFormatField?.text?.trim() ?: ""
        s.enableFileCheckpointing = fileCheckpointingCheckbox?.isSelected ?: false
        s.mcpServersJson = mcpServersField?.text?.trim() ?: ""
        s.fallbackModel = fallbackModelField?.text?.trim() ?: ""
        s.additionalDirectories = additionalDirsField?.text?.trim() ?: ""
        s.agentsJson = agentsField?.text?.trim() ?: ""
        s.hooksJson = hooksField?.text?.trim() ?: ""
        s.ephemeralSessions = ephemeralSessionsCheckbox?.isSelected ?: false
        s.forkSessionOnResume = forkSessionCheckbox?.isSelected ?: false
        s.sandboxJson = sandboxField?.text?.trim() ?: ""
        s.pluginsJson = pluginsField?.text?.trim() ?: ""
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
        effortCombo?.selectedItem = s.effort
        maxBudgetField?.text = s.maxBudgetUsd
        betaContextCheckbox?.isSelected = s.betaContext1m
        allowedToolsField?.text = s.allowedTools
        disallowedToolsField?.text = s.disallowedTools
        continueSessionCheckbox?.isSelected = s.continueSession
        outputFormatField?.text = s.outputFormatJson
        fileCheckpointingCheckbox?.isSelected = s.enableFileCheckpointing
        mcpServersField?.text = s.mcpServersJson
        fallbackModelField?.text = s.fallbackModel
        additionalDirsField?.text = s.additionalDirectories
        agentsField?.text = s.agentsJson
        hooksField?.text = s.hooksJson
        ephemeralSessionsCheckbox?.isSelected = s.ephemeralSessions
        forkSessionCheckbox?.isSelected = s.forkSessionOnResume
        sandboxField?.text = s.sandboxJson
        pluginsField?.text = s.pluginsJson
        languageCombo?.selectedItem = s.language
    }
}
