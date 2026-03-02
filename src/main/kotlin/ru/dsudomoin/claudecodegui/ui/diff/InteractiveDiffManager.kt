package ru.dsudomoin.claudecodegui.ui.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Opens IntelliJ native VCS diff viewer for files changed by Claude.
 *
 * Diff uses document-backed content from VFS (editable, synced with editor)
 * and VCS base revision from [ChangeListManager].
 */
object InteractiveDiffManager {

    /**
     * Show native IntelliJ VCS diff: HEAD vs working copy.
     * Uses document-backed right side (editable in diff viewer, synced with editor).
     */
    fun showFileDiff(project: Project, filePath: String) {
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath) ?: return

        val factory = DiffContentFactory.getInstance()
        val currentContent = factory.create(project, vFile)

        val change = ChangeListManager.getInstance(project).getChange(vFile)
        val baseText = try { change?.beforeRevision?.content } catch (_: Exception) { null }
        val baseContent = if (baseText != null) {
            factory.create(baseText, vFile.fileType)
        } else {
            factory.createEmpty()
        }

        val request = SimpleDiffRequest(
            vFile.name,
            baseContent,
            currentContent,
            "HEAD",
            "Working Copy",
        )

        DiffManager.getInstance().showDiff(project, request)
    }

    /**
     * Revert an Edit tool change: replace new_string back with old_string in the file.
     * Returns true if successfully reverted.
     */
    fun revertEdit(filePath: String, oldString: String, newString: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        val current = file.readText()
        if (!current.contains(newString)) return false

        val original = reconstructOriginal(current, oldString, newString)
        file.writeText(original)

        ApplicationManager.getApplication().invokeLater {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)?.refresh(false, false)
        }
        return true
    }

    /**
     * Revert a Write tool change: delete the file if it was newly created,
     * or restore from git if it existed before.
     */
    fun revertWrite(project: Project, filePath: String): Boolean {
        val file = File(filePath)
        if (!file.exists()) return false

        val change = ChangeListManager.getInstance(project).getChange(
            LocalFileSystem.getInstance().findFileByPath(filePath) ?: return false
        )
        val gitContent = try { change?.beforeRevision?.content } catch (_: Exception) { null }

        if (gitContent != null) {
            file.writeText(gitContent)
        } else {
            file.delete()
        }

        ApplicationManager.getApplication().invokeLater {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)?.refresh(false, false)
        }
        return true
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private fun reconstructOriginal(currentContent: String, oldString: String, newString: String): String {
        val idx = currentContent.indexOf(newString)
        if (idx < 0) return currentContent
        return currentContent.substring(0, idx) + oldString + currentContent.substring(idx + newString.length)
    }
}
