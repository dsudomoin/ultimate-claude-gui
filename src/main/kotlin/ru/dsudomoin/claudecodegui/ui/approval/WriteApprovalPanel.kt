package ru.dsudomoin.claudecodegui.ui.approval

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants

/**
 * Inline approval panel for Write / Create file operations.
 *
 * Shows file link, metrics (bytes / lines), content preview, and action row.
 */
class WriteApprovalPanel(
    project: Project,
    request: ToolApprovalRequest,
    onApprove: (Boolean) -> Unit,
    onReject: () -> Unit
) : InlineApprovalPanel(project, request, onApprove, onReject) {

    init {
        val payload = request.payload as? WritePayload

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(8, 10)
        }

        // Title
        val title = JBLabel(request.title, AllIcons.Actions.Edit, SwingConstants.LEFT).apply {
            font = font.deriveFont(Font.BOLD)
            iconTextGap = JBUI.scale(6)
        }
        content.add(wrap(title))

        if (payload != null) {
            // File link
            if (payload.filePath.isNotBlank()) {
                val fileName = payload.filePath.substringAfterLast('/')
                val fileLink = JLabel(fileName).apply {
                    foreground = ThemeColors.accent
                    cursor = Cursor(Cursor.HAND_CURSOR)
                    toolTipText = payload.filePath
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent?) {
                            val vf = LocalFileSystem.getInstance().findFileByPath(payload.filePath) ?: return
                            FileEditorManager.getInstance(project).openTextEditor(
                                OpenFileDescriptor(project, vf), true
                            )
                        }
                    })
                }
                content.add(wrap(fileLink, topGap = 4))
            }

            // Metrics
            val bytes = payload.content.length
            val lines = payload.content.lines().size
            val metrics = JBLabel("$bytes bytes \u00b7 $lines lines").apply {
                foreground = ThemeColors.textSecondary
                font = JBUI.Fonts.smallFont()
            }
            content.add(wrap(metrics, topGap = 2))

            // Content preview
            val preview = payload.content.take(2000).let {
                if (payload.content.length > 2000) "$it\n..." else it
            }
            val previewArea = JBTextArea(preview).apply {
                isEditable = false
                isFocusable = false
                lineWrap = true
                wrapStyleWord = false
                font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
                background = ThemeColors.codeBg
                foreground = ThemeColors.textPrimary
                border = JBUI.Borders.empty(6, 8)
            }
            val scroll = JBScrollPane(previewArea).apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
                border = JBUI.Borders.customLine(ThemeColors.borderNormal, 1)
                preferredSize = Dimension(0, JBUI.scale(140))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(200))
            }
            content.add(wrap(scroll, topGap = 6))
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
