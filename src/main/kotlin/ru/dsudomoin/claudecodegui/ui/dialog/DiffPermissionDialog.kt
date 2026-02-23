package ru.dsudomoin.claudecodegui.ui.dialog

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.dsudomoin.claudecodegui.MyMessageBundle
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Shows an IntelliJ native diff viewer inside a dialog for Edit/Write permission requests.
 *
 * Left side: current file content (before edit)
 * Right side: proposed content (after edit)
 *
 * User clicks "Allow" (accept changes) or "Deny" (reject changes).
 */
class DiffPermissionDialog(
    private val project: Project,
    private val toolName: String,
    private val toolInput: JsonObject
) : DialogWrapper(project, true) {

    private val filePath: String = getStr("file_path") ?: getStr("path") ?: getStr("target_file") ?: ""
    private val fileName: String = filePath.substringAfterLast('/')

    init {
        title = MyMessageBundle.message("permission.wantsToUse", toolName, fileName)
        setOKButtonText(MyMessageBundle.message("permission.allow"))
        setCancelButtonText(MyMessageBundle.message("permission.deny"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(JBUI.scale(800), JBUI.scale(500))

        // Header with file path
        val header = JBLabel(filePath).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(4, 8, 4, 8)
        }
        panel.add(header, BorderLayout.NORTH)

        // Build diff content
        val diffPanel = createDiffPanel()
        if (diffPanel != null) {
            panel.add(diffPanel, BorderLayout.CENTER)
        }

        return panel
    }

    private fun createDiffPanel(): JComponent? {
        val lower = toolName.lowercase()

        val (originalContent, proposedContent) = when {
            lower in setOf("edit", "edit_file", "replace_string") -> {
                computeEditDiff()
            }
            lower in setOf("write", "write_to_file", "create_file") -> {
                computeWriteDiff()
            }
            else -> return null
        } ?: return null

        val factory = DiffContentFactory.getInstance()
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)

        val originalDoc = factory.create(originalContent, fileType)
        val proposedDoc = factory.create(proposedContent, fileType)

        val request = SimpleDiffRequest(
            fileName,
            originalDoc,
            proposedDoc,
            MyMessageBundle.message("permission.current"),
            MyMessageBundle.message("permission.proposed")
        )

        // Use DiffManager to create an embeddable diff panel
        val diffPanel = DiffManager.getInstance().createRequestPanel(project, {}, null)
        diffPanel.setRequest(request)
        return diffPanel.component
    }

    /**
     * For Edit tools: read file, compute what it would look like after the edit.
     * Returns (original, proposed) content pair.
     */
    private fun computeEditDiff(): Pair<String, String>? {
        if (filePath.isBlank()) return null
        val file = File(filePath)
        val currentContent = if (file.exists()) file.readText() else ""

        val oldStr = getStr("old_string") ?: getStr("oldString") ?: return null
        val newStr = getStr("new_string") ?: getStr("newString") ?: return null

        // Proposed = current file with old_string replaced by new_string
        val proposedContent = if (currentContent.contains(oldStr)) {
            val idx = currentContent.indexOf(oldStr)
            currentContent.substring(0, idx) + newStr + currentContent.substring(idx + oldStr.length)
        } else {
            // old_string not found — show the raw replacement
            currentContent
        }

        return currentContent to proposedContent
    }

    /**
     * For Write tools: show empty/git content vs proposed content.
     */
    private fun computeWriteDiff(): Pair<String, String>? {
        val content = getStr("content") ?: return null

        val currentContent = if (filePath.isNotBlank()) {
            val file = File(filePath)
            if (file.exists()) file.readText() else ""
        } else ""

        return currentContent to content
    }

    private fun getStr(key: String): String? {
        val el = toolInput[key] ?: return null
        return (el as? JsonPrimitive)?.content
    }
}
