package ru.dsudomoin.claudecodegui.ui.compose.markdown

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope

/**
 * Handles `ide-file://` URIs produced by [FilePathPostProcessor].
 *
 * URI format:
 * - Absolute: `ide-file:///absolute/path/to/file.kt?line=42`
 * - Relative: `ide-file://relative/src/main/file.kt?line=10`
 *
 * Also routes `http://` / `https://` URLs to the system browser.
 *
 * As a fallback, bare file paths (e.g. from markdown links like
 * `[File.kt](src/main/File.kt)`) are resolved relative to the project
 * or found by filename in the project index.
 */
object FilePathLinkHandler {

    private const val SCHEME = "ide-file://"
    private const val ABSOLUTE_PREFIX = "ide-file:///"
    private const val RELATIVE_PREFIX = "ide-file://relative/"

    /** Schemes that should NOT be treated as file paths. */
    private val NON_FILE_SCHEMES = listOf(
        "mailto:", "ftp://", "ftps://", "ssh://", "tel:", "javascript:",
    )

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

    /**
     * Parse a path string that may end with `:lineNumber`.
     * Returns the clean path and optional line number.
     */
    fun parsePathAndLine(pathLike: String): Pair<String, Int?> {
        val colonIndex = pathLike.lastIndexOf(':')
        if (colonIndex > 0) {
            val afterColon = pathLike.substring(colonIndex + 1)
            if (afterColon.isNotEmpty() && afterColon.all { it.isDigit() }) {
                return pathLike.substring(0, colonIndex) to afterColon.toIntOrNull()
            }
        }
        return pathLike to null
    }

    fun resolveAbsolutePath(path: String, projectBasePath: String?): String {
        if (path.startsWith("/")) return path
        val base = projectBasePath ?: return path
        return "$base/$path"
    }

    /**
     * Handle a URL click from Jewel Markdown.
     *
     * Routing order:
     * 1. `ide-file://` → IDE editor (produced by [FilePathPostProcessor])
     * 2. `http://` / `https://` → system browser
     * 3. Bare file path → resolve in project, open in IDE editor
     */
    fun handleUrlClick(url: String, project: Project) {
        // 1. ide-file:// scheme (our custom links)
        val parsed = parseIdeFileUri(url)
        if (parsed != null) {
            navigateToFile(parsed.path, parsed.line, parsed.isAbsolute, project)
            return
        }

        // 2. Web URLs → browser
        if (url.startsWith("http://") || url.startsWith("https://")) {
            BrowserUtil.browse(url)
            return
        }

        // 3. Skip known non-file schemes
        if (NON_FILE_SCHEMES.any { url.startsWith(it) }) return

        // 4. Bare file path fallback (e.g. from markdown [text](path))
        tryNavigateToFilePath(url, project)
    }

    /**
     * Navigate to a file in the IDE editor at an optional line.
     */
    private fun navigateToFile(path: String, line: Int?, isAbsolute: Boolean, project: Project) {
        val absolutePath = if (isAbsolute) path else resolveAbsolutePath(path, project.basePath)
        val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return
        openVirtualFile(vFile, line, project)
    }

    /**
     * Try to resolve a bare path to a file in the project and navigate to it.
     * Handles paths like `src/main/File.kt`, `/absolute/File.kt`, or just `File.kt`.
     */
    private fun tryNavigateToFilePath(url: String, project: Project) {
        val (path, line) = parsePathAndLine(url)
        if (path.isBlank()) return

        // Try as absolute path
        if (path.startsWith("/")) {
            val vFile = LocalFileSystem.getInstance().findFileByPath(path)
            if (vFile != null) {
                openVirtualFile(vFile, line, project)
                return
            }
        }

        // Try relative to project base path
        val basePath = project.basePath
        if (basePath != null) {
            val absolutePath = "$basePath/$path"
            val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath)
            if (vFile != null) {
                openVirtualFile(vFile, line, project)
                return
            }
        }

        // Fallback: search by filename in the project index
        val fileName = path.substringAfterLast('/')
        if (fileName.contains('.')) {
            val candidates = FilenameIndex.getVirtualFilesByName(
                fileName, GlobalSearchScope.projectScope(project),
            )
            // If the path has directory components, prefer the candidate whose path ends with it
            val pathSuffix = if (path.contains('/')) "/$path" else null
            val best = if (pathSuffix != null) {
                candidates.firstOrNull { it.path.endsWith(pathSuffix) }
            } else null
            val target = best ?: candidates.firstOrNull()
            if (target != null) {
                openVirtualFile(target, line, project)
            }
        }
    }

    private fun openVirtualFile(vFile: VirtualFile, line: Int?, project: Project) {
        if (line != null) {
            OpenFileDescriptor(project, vFile, line - 1, 0).navigate(true)
        } else {
            OpenFileDescriptor(project, vFile).navigate(true)
        }
    }
}
