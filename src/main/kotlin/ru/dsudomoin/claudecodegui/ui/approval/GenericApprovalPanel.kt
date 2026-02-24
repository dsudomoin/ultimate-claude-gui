package ru.dsudomoin.claudecodegui.ui.approval

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Generic inline approval panel for tools that don't have a specialised panel.
 * Shows title + input details as plain text.
 */
class GenericApprovalPanel(
    project: Project,
    request: ToolApprovalRequest,
    onApprove: (Boolean) -> Unit,
    onReject: () -> Unit
) : InlineApprovalPanel(project, request, onApprove, onReject) {

    init {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10)
        }

        // Title
        val title = JBLabel(request.title, AllIcons.General.QuestionDialog, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD)
            iconTextGap = JBUI.scale(6)
        }
        content.add(wrap(title))

        // Details
        if (request.details.isNotBlank()) {
            val details = JBTextArea(request.details.take(500)).apply {
                isEditable = false
                isFocusable = false
                lineWrap = true
                wrapStyleWord = true
                foreground = ThemeColors.textSecondary
                font = JBUI.Fonts.smallFont()
                isOpaque = false
                border = JBUI.Borders.empty()
            }
            content.add(wrap(details, topGap = 4))
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
