package ru.dsudomoin.claudecodegui.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.dsudomoin.claudecodegui.UcuBundle

/**
 * Pure-logic utilities for tool names and summaries.
 * Zero UI dependencies.
 */
object ToolSummaryExtractor {

    fun getToolDisplayName(toolName: String): String {
        val lower = toolName.lowercase()
        return when {
            lower in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command") -> UcuBundle.message("tool.bash")
            lower in setOf("write", "write_to_file", "save-file") -> UcuBundle.message("tool.write")
            lower == "create_file" -> UcuBundle.message("tool.create")
            lower in setOf("edit", "edit_file") -> UcuBundle.message("tool.edit")
            lower == "replace_string" -> UcuBundle.message("tool.replace")
            lower in setOf("read", "read_file") -> UcuBundle.message("tool.read")
            lower in setOf("grep", "search") -> UcuBundle.message("tool.grep")
            lower in setOf("glob", "find") -> UcuBundle.message("tool.glob")
            lower in setOf("list", "listfiles") -> UcuBundle.message("tool.listFiles")
            lower == "task" -> UcuBundle.message("tool.task")
            lower == "taskoutput" -> UcuBundle.message("tool.taskOutput")
            lower == "webfetch" -> UcuBundle.message("tool.webfetch")
            lower == "websearch" -> UcuBundle.message("tool.websearch")
            lower == "delete" -> UcuBundle.message("tool.delete")
            lower == "notebookedit" -> UcuBundle.message("tool.notebook")
            lower == "todowrite" -> UcuBundle.message("tool.todowrite")
            lower in setOf("update_plan", "updateplan") -> UcuBundle.message("tool.updatePlan")
            lower == "explore" -> UcuBundle.message("tool.explore")
            lower == "augmentcontextengine" -> UcuBundle.message("tool.contextEngine")
            lower == "createdirectory" -> UcuBundle.message("tool.createDir")
            lower == "movefile" -> UcuBundle.message("tool.moveFile")
            lower == "copyfile" -> UcuBundle.message("tool.copyFile")
            lower in setOf("skill", "useskill", "runskill", "run_skill", "execute_skill") -> UcuBundle.message("tool.skill")
            lower == "exitplanmode" -> UcuBundle.message("tool.exitPlanMode")
            lower.startsWith("mcp__") -> UcuBundle.message("tool.mcp")
            else -> toolName
        }
    }

    fun extractToolSummary(toolName: String, input: JsonObject): String {
        val lower = toolName.lowercase()
        val filePath = getStringField(input, "file_path")
            ?: getStringField(input, "path")
            ?: getStringField(input, "target_file")
            ?: getStringField(input, "notebook_path")
        if (filePath != null) return filePath.substringAfterLast('/')

        if (lower in setOf("bash", "run_terminal_cmd", "execute_command", "shell_command")) {
            val cmd = getStringField(input, "command")
            if (cmd != null) return if (cmd.length > 60) cmd.take(57) + "..." else cmd
        }

        if (lower in setOf("grep", "search")) {
            val pattern = getStringField(input, "pattern") ?: getStringField(input, "search_term")
            if (pattern != null) return pattern
        }

        if (lower == "glob") {
            val pattern = getStringField(input, "pattern")
            if (pattern != null) return pattern
        }

        if (lower == "task") {
            val desc = getStringField(input, "description")
            val subType = getStringField(input, "subagent_type")
            return when {
                desc != null && subType != null -> "$subType: $desc"
                desc != null -> desc
                subType != null -> subType
                else -> ""
            }
        }

        return ""
    }

    private val EDIT_TOOLS = setOf("edit", "edit_file", "replace_string")
    private val BASH_TOOLS = setOf("bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command")
    private val WRITE_TOOLS = setOf("write", "write_to_file", "save-file", "create_file")

    /**
     * Returns true if the tool has input-based expandable content (diff, command, code, etc.).
     */
    fun isInputExpandable(toolName: String): Boolean {
        val lower = toolName.lowercase()
        return lower in EDIT_TOOLS || lower in BASH_TOOLS || lower in WRITE_TOOLS
    }

    /**
     * Extracts the file content from write/create tool input.
     */
    fun extractWriteContent(input: JsonObject): String? {
        return getStringField(input, "content")
            ?: getStringField(input, "file_text")
    }

    /**
     * Returns true if tool result content is useful to show (bash output, search results).
     * Edit/Write/TodoWrite return generic confirmation messages — skip those.
     */
    fun hasUsefulResultContent(toolName: String): Boolean {
        val lower = toolName.lowercase()
        return lower in BASH_TOOLS || lower in setOf(
            "read", "read_file",
            "grep", "search", "glob", "find", "list", "listfiles",
            "task", "taskoutput", "webfetch", "websearch",
        )
    }

    /**
     * Extracts old_string and new_string from edit tool input for diff rendering.
     */
    fun extractEditDiffStrings(input: JsonObject): Pair<String, String>? {
        val oldStr = getStringField(input, "old_string")
            ?: getStringField(input, "old_str") ?: return null
        val newStr = getStringField(input, "new_string")
            ?: getStringField(input, "new_str") ?: return null
        return oldStr to newStr
    }

    /**
     * Extracts bash command from input.
     */
    fun extractBashCommand(input: JsonObject): String? {
        return getStringField(input, "command")
    }

    /**
     * Extracts the file path from tool input (for syntax highlighting).
     */
    fun extractFilePath(input: JsonObject): String? {
        return getStringField(input, "file_path")
            ?: getStringField(input, "path")
            ?: getStringField(input, "target_file")
    }

    private fun getStringField(obj: JsonObject, key: String): String? {
        val element = obj[key] ?: return null
        return try {
            (element as? JsonPrimitive)?.content
        } catch (_: Exception) {
            null
        }
    }
}
