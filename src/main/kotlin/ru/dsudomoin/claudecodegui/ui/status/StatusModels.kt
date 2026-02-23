package ru.dsudomoin.claudecodegui.ui.status

/** A todo item from Claude's TodoWrite tool. */
data class TodoItem(
    val content: String,
    val status: TodoStatus
)

enum class TodoStatus { PENDING, IN_PROGRESS, COMPLETED }

/** A file changed by Claude's Edit/Write tools. */
data class FileChangeSummary(
    val filePath: String,
    val fileName: String,
    val changeType: FileChangeType,
    val additions: Int,
    val deletions: Int,
    val operations: MutableList<EditOperation> = mutableListOf()
)

enum class FileChangeType { ADDED, MODIFIED }

data class EditOperation(
    val toolName: String,
    val oldString: String,
    val newString: String,
    val additions: Int,
    val deletions: Int
)

/** A subagent (Task tool) launched by Claude. */
data class SubagentInfo(
    val id: String,
    val type: String,
    val description: String,
    val status: SubagentStatus
)

enum class SubagentStatus { RUNNING, COMPLETED, ERROR }
