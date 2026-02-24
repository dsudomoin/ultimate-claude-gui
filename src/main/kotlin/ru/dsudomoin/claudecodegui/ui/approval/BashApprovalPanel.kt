package ru.dsudomoin.claudecodegui.ui.approval

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * Inline approval panel for Bash / terminal commands.
 *
 * ┌────────────────────────────────────────┐
 * │ [>_] Bash                              │
 * │ ┌──────────────────────────────────┐   │
 * │ │ $ command-text                   │   │
 * │ └──────────────────────────────────┘   │
 * │ Allow | Always allow | Deny            │
 * └────────────────────────────────────────┘
 */
class BashApprovalPanel(
    project: Project,
    request: ToolApprovalRequest,
    onApprove: (Boolean) -> Unit,
    onReject: () -> Unit
) : InlineApprovalPanel(project, request, onApprove, onReject) {

    init {
        val payload = request.payload as? BashPayload
        val command = payload?.command ?: request.details

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10)
        }

        // Title
        val title = JBLabel(request.title, AllIcons.Debugger.Console, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD)
            iconTextGap = JBUI.scale(6)
        }
        content.add(wrap(title))

        // Command preview
        val commandArea = JBTextArea(command).apply {
            isEditable = false
            isFocusable = false
            lineWrap = true
            wrapStyleWord = false
            font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
            background = ThemeColors.codeBg
            foreground = ThemeColors.textPrimary
            border = JBUI.Borders.empty(6, 8)
        }
        val scroll = JBScrollPane(commandArea).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.customLine(ThemeColors.borderNormal, 1)
            preferredSize = java.awt.Dimension(0, JBUI.scale(80))
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(120))
        }
        content.add(wrap(scroll, topGap = 6))

        // Description
        payload?.description?.let { desc ->
            val descLabel = JBLabel(desc).apply {
                foreground = ThemeColors.textSecondary
                font = JBUI.Fonts.smallFont()
            }
            content.add(wrap(descLabel, topGap = 4))
        }

        // Action row
        content.add(createActionRow())

        add(content, BorderLayout.CENTER)
    }

    private fun wrap(component: javax.swing.JComponent, topGap: Int = 0): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            if (topGap > 0) border = JBUI.Borders.emptyTop(topGap)
            add(component, BorderLayout.CENTER)
        }
    }
}
