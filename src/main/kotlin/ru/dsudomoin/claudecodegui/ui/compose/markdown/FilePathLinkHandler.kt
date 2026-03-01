package ru.dsudomoin.claudecodegui.ui.compose.markdown

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Handles `ide-file://` URIs produced by [FilePathPostProcessor].
 *
 * URI format:
 * - Absolute: `ide-file:///absolute/path/to/file.kt?line=42`
 * - Relative: `ide-file://relative/src/main/file.kt?line=10`
 *
 * Also routes `http://` / `https://` URLs to the system browser.
 */
object FilePathLinkHandler {

    private const val SCHEME = "ide-file://"
    private const val ABSOLUTE_PREFIX = "ide-file:///"
    private const val RELATIVE_PREFIX = "ide-file://relative/"

    data class ParsedFileUri(
        val path: String,
        val line: Int? = null,
        val isAbsolute: Boolean = true,
    )

    fun isIdeFileUri(url: String): Boolean = url.startsWith(SCHEME)

    fun parseIdeFileUri(url: String): ParsedFileUri? {
        if (!isIdeFileUri(url)) return null

        val isAbsolute = url.startsWith(ABSOLUTE_PREFIX) && !url.startsWith(RELATIVE_PREFIX)
        val pathAndQuery = if (isAbsolute) {
            url.removePrefix("ide-file://")
        } else {
            url.removePrefix(RELATIVE_PREFIX)
        }

        val queryIndex = pathAndQuery.indexOf('?')
        val path = if (queryIndex >= 0) pathAndQuery.substring(0, queryIndex) else pathAndQuery
        val line = if (queryIndex >= 0) {
            val query = pathAndQuery.substring(queryIndex + 1)
            query.split('&')
                .firstOrNull { it.startsWith("line=") }
                ?.removePrefix("line=")
                ?.toIntOrNull()
        } else null

        return ParsedFileUri(path = path, line = line, isAbsolute = isAbsolute)
    }

    fun resolveAbsolutePath(path: String, projectBasePath: String?): String {
        if (path.startsWith("/")) return path
        val base = projectBasePath ?: return path
        return "$base/$path"
    }

    /**
     * Handle a URL click from Jewel Markdown.
     * Routes `ide-file://` to the IDE editor, everything else to the browser.
     */
    fun handleUrlClick(url: String, project: Project) {
        val parsed = parseIdeFileUri(url)
        if (parsed != null) {
            val absolutePath = resolveAbsolutePath(parsed.path, project.basePath)
            val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return
            if (parsed.line != null) {
                OpenFileDescriptor(project, vFile, parsed.line - 1, 0).navigate(true)
            } else {
                OpenFileDescriptor(project, vFile).navigate(true)
            }
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            BrowserUtil.browse(url)
        }
    }
}
