package ru.dsudomoin.claudecodegui.core.model

import ru.dsudomoin.claudecodegui.ui.status.EditOperation
import ru.dsudomoin.claudecodegui.ui.status.FileChangeSummary
import ru.dsudomoin.claudecodegui.ui.status.FileChangeType
import ru.dsudomoin.claudecodegui.ui.status.SubagentInfo
import ru.dsudomoin.claudecodegui.ui.status.SubagentStatus
import ru.dsudomoin.claudecodegui.ui.status.TodoItem

/**
 * Tracks status data (todos, file changes, subagents) without any UI dependency.
 * Pure data management — ChatController writes, ViewModel reads.
 */
class StatusTracker {

    private val todos = mutableListOf<TodoItem>()
    private val fileChanges = mutableMapOf<String, FileChangeSummary>()
    private val subagents = mutableMapOf<String, SubagentInfo>()

    val currentTodos: List<TodoItem> get() = todos.toList()
    val currentFileChanges: List<FileChangeSummary> get() = fileChanges.values.toList()
    val currentSubagents: List<SubagentInfo> get() = subagents.values.toList()

    fun updateTodos(newTodos: List<TodoItem>) {
        todos.clear()
        todos.addAll(newTodos)
    }

    fun trackFileChange(toolName: String, filePath: String, oldString: String, newString: String) {
        val fileName = filePath.substringAfterLast('/')
        val addLines = newString.lines().size
        val delLines = oldString.lines().size
        val additions = (addLines - delLines).coerceAtLeast(0)
        val deletions = (delLines - addLines).coerceAtLeast(0)

        val op = EditOperation(toolName, oldString, newString, additions, deletions)

        val existing = fileChanges[filePath]
        if (existing != null) {
            existing.operations.add(op)
            fileChanges[filePath] = existing.copy(
                additions = existing.additions + additions,
                deletions = existing.deletions + deletions
            )
        } else {
            val changeType = if (oldString.isEmpty()) FileChangeType.ADDED else FileChangeType.MODIFIED
            fileChanges[filePath] = FileChangeSummary(filePath, fileName, changeType, additions, deletions, mutableListOf(op))
        }
    }

    fun trackFileWrite(filePath: String, content: String, isNew: Boolean) {
        val fileName = filePath.substringAfterLast('/')
        val additions = content.lines().size
        val op = EditOperation("write", "", content, additions, 0)

        fileChanges[filePath] = FileChangeSummary(
            filePath, fileName,
            if (isNew) FileChangeType.ADDED else FileChangeType.MODIFIED,
            additions, 0, mutableListOf(op)
        )
    }

    fun trackSubagent(id: String, type: String, description: String) {
        subagents[id] = SubagentInfo(id, type, description, SubagentStatus.RUNNING)
    }

    fun completeSubagent(id: String, error: Boolean = false) {
        subagents[id]?.let {
            subagents[id] = it.copy(status = if (error) SubagentStatus.ERROR else SubagentStatus.COMPLETED)
        }
    }

    fun clear() {
        todos.clear()
        fileChanges.clear()
        subagents.clear()
    }

    fun removeFileChange(filePath: String) {
        fileChanges.remove(filePath)
    }

    fun clearFileChanges() {
        fileChanges.clear()
    }
}
