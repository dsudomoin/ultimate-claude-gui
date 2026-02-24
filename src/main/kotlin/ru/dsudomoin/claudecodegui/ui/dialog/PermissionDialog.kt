package ru.dsudomoin.claudecodegui.ui.dialog

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.JsonObject
import ru.dsudomoin.claudecodegui.UcuBundle
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants

/**
 * Dialog shown when Claude requests permission to use a tool.
 * User can Allow or Deny the operation.
 */
class PermissionDialog(
    project: Project?,
    private val toolName: String,
    private val toolInput: JsonObject
) : DialogWrapper(project) {

    init {
        title = UcuBundle.message("permission.title")
        setOKButtonText(UcuBundle.message("permission.allow"))
        setCancelButtonText(UcuBundle.message("permission.deny"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            preferredSize = java.awt.Dimension(480, 300)
        }

        // Header
        val header = JBLabel(UcuBundle.message("permission.wantsToUse", toolName, "")).apply {
            font = font.deriveFont(Font.BOLD, 14f)
            border = JBUI.Borders.emptyBottom(8)
        }
        panel.add(header, BorderLayout.NORTH)

        // Tool input details
        val inputText = formatInput(toolInput)
        val textArea = JBTextArea(inputText).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font("JetBrains Mono", Font.PLAIN, 12)
            background = JBColor.namedColor("Claude.CodeBackground", JBColor(0xF5F5F5, 0x1E1F22))
            border = JBUI.Borders.empty(8)
        }
        val scrollPane = JBScrollPane(textArea).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        return panel
    }

    private fun formatInput(input: JsonObject): String {
        return buildString {
            for ((key, value) in input) {
                val valueStr = value.toString().trim('"')
                if (valueStr.length > 200) {
                    appendLine("$key: ${valueStr.take(200)}...")
                } else {
                    appendLine("$key: $valueStr")
                }
            }
        }.trimEnd()
    }
}
