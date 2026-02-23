package ru.dsudomoin.claudecodegui.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.MyMessageBundle
import ru.dsudomoin.claudecodegui.bridge.NodeDetector
import ru.dsudomoin.claudecodegui.service.OAuthCredentialService
import ru.dsudomoin.claudecodegui.service.SettingsService
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

class ClaudeSettingsConfigurable : Configurable {
    private var baseUrlField: JBTextField? = null
    private var modelField: JBTextField? = null
    private var maxTokensField: JBTextField? = null
    private var systemPromptField: JBTextField? = null
    private var nodePathField: JBTextField? = null
    private var claudePathField: JBTextField? = null
    private var permissionModeCombo: ComboBox<String>? = null
    private var panel: JPanel? = null

    companion object {
        private val PERMISSION_MODES = arrayOf("default", "plan", "bypassPermissions")
        private val PERMISSION_MODE_LABELS = mapOf(
            "default" to MyMessageBundle.message("settings.permDefault"),
            "plan" to MyMessageBundle.message("settings.permPlan"),
            "bypassPermissions" to MyMessageBundle.message("settings.permBypass")
        )
    }

    override fun getDisplayName() = "Claude Code GUI"

    override fun createComponent(): JComponent {
        baseUrlField = JBTextField()
        modelField = JBTextField()
        maxTokensField = JBTextField()
        systemPromptField = JBTextField()
        nodePathField = JBTextField()
        claudePathField = JBTextField()
        permissionModeCombo = ComboBox(DefaultComboBoxModel(PERMISSION_MODES)).apply {
            renderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ) = super.getListCellRendererComponent(
                    list, PERMISSION_MODE_LABELS[value] ?: value, index, isSelected, cellHasFocus
                )
            }
        }

        // Login status indicator
        val loginStatus = createLoginStatusPanel()

        // Detection info
        val detectedNode = NodeDetector.detect() ?: "not found"
        val detectedClaude = NodeDetector.detectClaude() ?: "not found"

        panel = FormBuilder.createFormBuilder()
            .addComponent(loginStatus)
            .addSeparator()
            .addLabeledComponent("Node.js Path:", nodePathField!!)
            .addComponentToRightColumn(JBLabel("Auto-detected: $detectedNode").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addLabeledComponent("Claude Executable Path:", claudePathField!!)
            .addComponentToRightColumn(JBLabel("Auto-detected: $detectedClaude").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(font.size2D - 1f)
            })
            .addSeparator()
            .addLabeledComponent("Model:", modelField!!)
            .addLabeledComponent("Max Tokens:", maxTokensField!!)
            .addLabeledComponent("Permission Mode:", permissionModeCombo!!)
            .addLabeledComponent("Base URL:", baseUrlField!!)
            .addLabeledComponent("System Prompt:", systemPromptField!!)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    private fun createLoginStatusPanel(): JPanel {
        val info = OAuthCredentialService.getInstance().getLoginInfo()

        val statusLabel = JBLabel().apply {
            if (info.loggedIn && !info.expired) {
                text = MyMessageBundle.message("settings.loggedIn", info.source)
                foreground = JBColor.namedColor("Claude.StatusOk", JBColor(0x2E7D32, 0x81C784))
            } else if (info.loggedIn && info.expired) {
                text = MyMessageBundle.message("settings.expired")
                foreground = JBColor.ORANGE
            } else {
                text = MyMessageBundle.message("settings.notLoggedIn")
                foreground = JBColor.RED
            }
            border = JBUI.Borders.empty(4)
        }

        return JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JBLabel(MyMessageBundle.message("settings.authStatus")).apply {
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
                permissionModeCombo?.selectedItem != s.permissionMode
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
    }
}
