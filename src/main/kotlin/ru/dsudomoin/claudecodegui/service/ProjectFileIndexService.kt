package ru.dsudomoin.claudecodegui.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Project-level service that caches project file paths for fast @-mention search.
 * Indexes both project files and library source files (JDK, dependencies).
 */
@Service(Service.Level.PROJECT)
class ProjectFileIndexService(private val project: Project) {

    enum class FileSource { PROJECT, LIBRARY }

    data class FileEntry(
        val relativePath: String,
        val fileName: String,
        val absolutePath: String,
        val virtualFile: VirtualFile,
        val source: FileSource = FileSource.PROJECT,
        val libraryName: String? = null
    )

    private val fileEntries = CopyOnWriteArrayList<FileEntry>()

    @Volatile
    private var isIndexed = false

    private val indexScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val excludedDirs = setOf(
        ".git", ".idea", "node_modules", "build", ".gradle",
        "out", "dist", "target", "__pycache__", ".cache", ".cls"
    )

    fun ensureIndexed() {
        if (isIndexed) return
        indexScope.launch { buildIndex() }
    }

    private suspend fun buildIndex() {
        val baseDir = project.basePath?.let {
            com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(it)
        } ?: return

        val entries = mutableListOf<FileEntry>()

        // 1. Project files — separate readAction to release lock between operations
        val projectEntries = com.intellij.openapi.application.readAction {
            val result = mutableListOf<FileEntry>()
            fun walk(dir: VirtualFile) {
                for (child in dir.children) {
                    if (child.isDirectory) {
                        if (child.name !in excludedDirs) {
                            walk(child)
                        }
                    } else {
                        val rel = VfsUtilCore.getRelativePath(child, baseDir) ?: child.name
                        result.add(FileEntry(rel, child.name, child.path, child))
                    }
                }
            }
            walk(baseDir)
            result
        }
        entries.addAll(projectEntries)

        // 2. Library source files — separate readAction to give write actions a chance
        val libraryEntries = com.intellij.openapi.application.readAction {
            val result = mutableListOf<FileEntry>()
            val seen = mutableSetOf<String>()
            val modules = ProjectRootManager.getInstance(project).contentRoots
                .mapNotNull { com.intellij.openapi.module.ModuleUtilCore.findModuleForFile(it, project) }
                .toSet()
            for (module in modules) {
                for (orderEntry in ModuleRootManager.getInstance(module).orderEntries) {
                    if (orderEntry is LibraryOrderEntry) {
                        val libName = orderEntry.libraryName ?: orderEntry.presentableName
                        val library = orderEntry.library ?: continue
                        for (sourceRoot in library.getFiles(OrderRootType.SOURCES)) {
                            indexLibraryRoot(sourceRoot, sourceRoot, result, seen, libName)
                        }
                    }
                }
            }
            result
        }
        entries.addAll(libraryEntries)

        fileEntries.clear()
        fileEntries.addAll(entries)
        isIndexed = true
    }

    private fun indexLibraryRoot(
        root: VirtualFile,
        dir: VirtualFile,
        entries: MutableList<FileEntry>,
        seen: MutableSet<String>,
        libraryName: String
    ) {
        for (child in dir.children) {
            if (child.isDirectory) {
                indexLibraryRoot(root, child, entries, seen, libraryName)
            } else {
                val path = child.path
                if (seen.add(path)) {
                    val rel = VfsUtilCore.getRelativePath(child, root) ?: child.name
                    entries.add(
                        FileEntry(
                            relativePath = rel,
                            fileName = child.name,
                            absolutePath = path,
                            virtualFile = child,
                            source = FileSource.LIBRARY,
                            libraryName = libraryName
                        )
                    )
                }
            }
        }
    }

    /**
     * Search files by query.
     * Project files are prioritized over library files.
     * Scores: project-filename-starts(5) > project-filename-contains(4) > project-path-contains(3)
     *       > lib-filename-starts(2) > lib-filename-contains(1.5) > lib-path-contains(1)
     */
    fun search(query: String, limit: Int = 20): List<FileEntry> {
        if (query.isBlank()) return fileEntries.filter { it.source == FileSource.PROJECT }.take(limit)

        val lower = query.lowercase()
        return fileEntries
            .asSequence()
            .filter { it.virtualFile.isValid }
            .map { entry ->
                val isProject = entry.source == FileSource.PROJECT
                val score = when {
                    entry.fileName.lowercase().startsWith(lower) -> if (isProject) 5.0 else 2.0
                    entry.fileName.lowercase().contains(lower) -> if (isProject) 4.0 else 1.5
                    entry.relativePath.lowercase().contains(lower) -> if (isProject) 3.0 else 1.0
                    else -> 0.0
                }
                entry to score
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    fun refresh() {
        isIndexed = false
        ensureIndexed()
    }

    companion object {
        fun getInstance(project: Project): ProjectFileIndexService =
            project.getService(ProjectFileIndexService::class.java)
    }
}
