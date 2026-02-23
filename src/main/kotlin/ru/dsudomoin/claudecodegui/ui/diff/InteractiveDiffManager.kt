package ru.dsudomoin.claudecodegui.ui.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/**
 * Opens IntelliJ native diff viewer for tool-use results (Edit / Write).
 *
 * For Edit tools:
 *   Left  = file before edit (reconstructed by reverse-applying old_string ↔ new_string)
 *   Right = file after edit (current on disk)
 *
 * For Write tools:
 *   Left  = empty (new file) or previous version from git
 *   Right = written content
 */
object InteractiveDiffManager {

    /** Open a diff tab with original vs modified content. */
    fun showDiff(
        project: Project,
        filePath: String,
        originalContent: String,
        modifiedContent: String
    ) {
        val factory = DiffContentFactory.getInstance()
        val fileName = filePath.substringAfterLast('/')

        // Detect file type for syntax highlighting
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        val originalDoc = factory.create(originalContent, fileType)
        val modifiedDoc = factory.create(modifiedContent, fileType)

        val request = SimpleDiffRequest(
            fileName,
            originalDoc,
            modifiedDoc,
            "Original",
            "Modified by Claude"
        )

        DiffManager.getInstance().showDiff(project, request)
    }

    /**
     * Show diff for an Edit tool call.
     * Reads the file from disk (already modified), reverse-applies the edit to get original.
     */
    fun showEditDiff(project: Project, filePath: String, oldString: String, newString: String) {
        val file = File(filePath)
        if (!file.exists()) return

        val currentContent = file.readText()
        val originalContent = reconstructOriginal(currentContent, oldString, newString)

        showDiff(project, filePath, originalContent, currentContent)
    }

    /**
     * Show diff for a Write tool call.
     * Left side = empty (new file) or previous git version.
     */
    fun showWriteDiff(project: Project, filePath: String, writtenContent: String) {
        // Try to get previous version from git
        val originalContent = getGitContent(project, filePath) ?: ""
        showDiff(project, filePath, originalContent, writtenContent)
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

        // Refresh VFS so IntelliJ picks up the change
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

        val gitContent = getGitContent(project, filePath)
        if (gitContent != null) {
            // File existed in git — restore previous version
            file.writeText(gitContent)
        } else {
            // New file — delete it
            file.delete()
        }

        ApplicationManager.getApplication().invokeLater {
            LocalFileSystem.getInstance().refreshAndFindFileByPath(filePath)?.refresh(false, false)
        }
        return true
    }

    // ── Internal ────────────────────────────────────────────────────────────

    private fun reconstructOriginal(currentContent: String, oldString: String, newString: String): String {
        // Replace the first occurrence of newString with oldString
        val idx = currentContent.indexOf(newString)
        if (idx < 0) return currentContent
        return currentContent.substring(0, idx) + oldString + currentContent.substring(idx + newString.length)
    }

    private fun getGitContent(project: Project, filePath: String): String? {
        return try {
            val basePath = project.basePath ?: return null
            val relativePath = File(filePath).relativeTo(File(basePath)).path
            val process = ProcessBuilder("git", "show", "HEAD:$relativePath")
                .directory(File(basePath))
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode == 0) output else null
        } catch (_: Exception) {
            null
        }
    }
}
