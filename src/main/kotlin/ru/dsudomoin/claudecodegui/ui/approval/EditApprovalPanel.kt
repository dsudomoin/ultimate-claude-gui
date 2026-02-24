package ru.dsudomoin.claudecodegui.ui.approval

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.common.BadgeChip
import ru.dsudomoin.claudecodegui.ui.common.ChangeColors
import ru.dsudomoin.claudecodegui.ui.common.diffBadgeText
import ru.dsudomoin.claudecodegui.ui.common.lineDiffStats
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Inline approval panel for Edit / Replace operations.
 *
 * Shows file link, diff stats badges, inline diff viewer, and action row.
 */
class EditApprovalPanel(
    project: Project,
    request: ToolApprovalRequest,
    onApprove: (Boolean) -> Unit,
    onReject: () -> Unit
) : InlineApprovalPanel(project, request, onApprove, onReject), Disposable {

    init {
        val payload = request.payload as? EditPayload

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

        // File link + diff badges
        if (payload != null && payload.filePath.isNotBlank()) {
            val fileRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false }
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
            fileRow.add(fileLink)

            // Diff stats
            val currentContent = readFileContent(payload.filePath)
            val proposedContent = applyEdit(currentContent, payload)
            val (ins, del, _) = lineDiffStats(currentContent, proposedContent)

            if (ins > 0 || del > 0) {
                fileRow.add(JLabel("  ").apply { isOpaque = false })
                if (ins > 0) {
                    fileRow.add(BadgeChip(
                        text = "+$ins",
                        backgroundColor = ChangeColors.inserted,
                    ))
                    fileRow.add(JLabel(" ").apply { isOpaque = false })
                }
                if (del > 0) {
                    fileRow.add(BadgeChip(
                        text = "-$del",
                        backgroundColor = ChangeColors.deleted,
                    ))
                }
            }

            content.add(wrap(fileRow, topGap = 4))

            // Inline diff viewer
            val diffPanel = createDiffPanel(payload.filePath, currentContent, proposedContent)
            if (diffPanel != null) {
                content.add(wrap(diffPanel, topGap = 6))
            }
        }

        // Action row
        content.add(createActionRow())

        add(content, BorderLayout.CENTER)
    }

    private fun createDiffPanel(filePath: String, original: String, proposed: String): JPanel? {
        if (original == proposed) return null

        return try {
            val factory = DiffContentFactory.getInstance()
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(
                filePath.substringAfterLast('/')
            )
            val originalDoc = factory.create(original, fileType)
            val proposedDoc = factory.create(proposed, fileType)

            val request = SimpleDiffRequest(
                filePath.substringAfterLast('/'),
                originalDoc,
                proposedDoc,
                UcuBundle.message("permission.current"),
                UcuBundle.message("permission.proposed")
            )

            val diffPanel = DiffManager.getInstance().createRequestPanel(project, {}, null)
            diffPanel.setRequest(request)

            JPanel(BorderLayout()).apply {
                isOpaque = false
                add(diffPanel.component, BorderLayout.CENTER)
                preferredSize = Dimension(0, JBUI.scale(200))
                maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(300))
                border = JBUI.Borders.customLine(ThemeColors.borderNormal, 1)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun applyEdit(currentContent: String, payload: EditPayload): String {
        if (payload.oldString.isEmpty()) return currentContent
        return if (payload.replaceAll) {
            currentContent.replace(payload.oldString, payload.newString)
        } else {
            currentContent.replaceFirst(payload.oldString, payload.newString)
        }
    }

    private fun readFileContent(path: String): String {
        return try {
            File(path).takeIf { it.exists() }?.readText() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun wrap(component: javax.swing.JComponent, topGap: Int = 0): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            if (topGap > 0) border = JBUI.Borders.emptyTop(topGap)
            add(component, BorderLayout.CENTER)
        }
    }

    override fun dispose() {}
}
