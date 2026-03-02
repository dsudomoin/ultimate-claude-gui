package ru.dsudomoin.claudecodegui.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ru.dsudomoin.claudecodegui.UcuBundle

/**
 * Pure-logic utilities for tool names and summaries.
 * Zero UI dependencies.
 */
object ToolSummaryExtractor {

    private val SKILL_TOOLS = setOf("skill", "useskill", "runskill", "run_skill", "execute_skill")
    private val WEB_FETCH_TOOLS = setOf("webfetch", "web_fetch")
    private val WEB_SEARCH_TOOLS = setOf("websearch", "web_search")

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
            lower in SKILL_TOOLS -> UcuBundle.message("tool.skill")
            lower == "enterplanmode" -> UcuBundle.message("tool.enterPlanMode")
            lower == "exitplanmode" -> UcuBundle.message("tool.exitPlanMode")
            lower.startsWith("mcp__") -> UcuBundle.message("tool.mcp")
            else -> toolName
        }
    }

    fun getToolDisplayName(toolName: String, input: JsonObject): String {
        val lower = toolName.lowercase()
        if (lower.startsWith("mcp__")) {
            if (hasEditLikePayload(input)) return UcuBundle.message("tool.edit")
            if (hasWriteLikePayload(input)) return UcuBundle.message("tool.write")
        }
        return getToolDisplayName(toolName)
    }

    fun extractToolSummary(toolName: String, input: JsonObject): String {
        val lower = toolName.lowercase()
        if (lower in SKILL_TOOLS) {
            val skill = extractSkillName(input)
            if (!skill.isNullOrBlank()) return skill
            val prompt = getStringField(input, "prompt")
                ?: getStringField(input, "input")
                ?: getStringField(input, "query")
            if (!prompt.isNullOrBlank()) {
                return if (prompt.length > 60) prompt.take(57) + "..." else prompt
            }
            return ""
        }

        if (lower in WEB_FETCH_TOOLS) {
            extractWebUrl(input)?.let { return it }
        }

        if (lower in WEB_SEARCH_TOOLS) {
            val query = getStringField(input, "query")
                ?: getStringField(input, "search_query")
                ?: getStringField(input, "q")
            if (!query.isNullOrBlank()) return query
        }

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

    private fun extractWebUrl(input: JsonObject): String? {
        return getStringField(input, "url")
            ?: getStringField(input, "uri")
            ?: getStringField(input, "link")
            ?: getStringField(input, "website")
            ?: getStringField(input, "page_url")
            ?: getStringField(input, "source")
    }

    fun isSkillTool(toolName: String): Boolean = toolName.lowercase() in SKILL_TOOLS

    fun extractSkillName(input: JsonObject): String? {
        return getStringField(input, "skill")
            ?: getStringField(input, "skill_name")
            ?: getStringField(input, "skillName")
            ?: getStringField(input, "name")
            ?: getStringField(input, "slug")
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
            ?: getStringField(input, "text")
            ?: getStringField(input, "new_content")
            ?: getStringField(input, "contents")
    }

    /**
     * Returns true if tool result content is useful to show (bash output, search results).
     * Edit/Write/TodoWrite return generic confirmation messages — skip those.
     */
    fun hasUsefulResultContent(toolName: String): Boolean {
        val lower = toolName.lowercase()
        return lower in BASH_TOOLS || lower.startsWith("mcp__") || lower in setOf(
            "read", "read_file",
            "grep", "search", "glob", "find", "list", "listfiles",
            "task", "taskoutput", "webfetch", "websearch",
        ) || lower in SKILL_TOOLS
    }

    /**
     * Extracts old_string and new_string from edit tool input for diff rendering.
     */
    fun extractEditDiffStrings(input: JsonObject): Pair<String, String>? {
        val oldStr = getStringField(input, "old_string")
            ?: getStringField(input, "old_str")
            ?: getStringField(input, "before")
            ?: getStringField(input, "search")
            ?: getStringField(input, "find")
            ?: return null
        val newStr = getStringField(input, "new_string")
            ?: getStringField(input, "new_str")
            ?: getStringField(input, "after")
            ?: getStringField(input, "replace")
            ?: getStringField(input, "replacement")
            ?: return null
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
            ?: getStringField(input, "file")
            ?: getStringField(input, "filename")
            ?: getStringField(input, "filepath")
            ?: getStringField(input, "target_path")
            ?: getStringField(input, "output_file")
    }

    fun hasEditLikePayload(input: JsonObject): Boolean = extractEditDiffStrings(input) != null

    fun hasWriteLikePayload(input: JsonObject): Boolean {
        if (extractFilePath(input).isNullOrBlank()) return false
        return hasAnyStringField(
            input,
            listOf("content", "file_text", "text", "new_content", "contents")
        )
    }

    /** Public alias for extracting a string field (used by ChatController for tool-specific fields). */
    fun getStringFieldPublic(obj: JsonObject, key: String): String? = getStringField(obj, key)

    private fun getStringField(obj: JsonObject, key: String): String? {
        val root = (obj[key] as? JsonPrimitive)?.contentOrNull
        if (root != null) return root

        val nestedKeys = listOf("arguments", "args", "params", "input", "payload", "data")
        for (containerKey in nestedKeys) {
            val nested = obj[containerKey] as? JsonObject ?: continue
            val nestedValue = (nested[key] as? JsonPrimitive)?.contentOrNull
            if (nestedValue != null) return nestedValue
        }
        return null
    }

    private fun hasAnyStringField(obj: JsonObject, keys: List<String>): Boolean {
        return keys.any { getStringField(obj, it) != null }
    }
}
