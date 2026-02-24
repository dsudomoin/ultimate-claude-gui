package ru.dsudomoin.claudecodegui.ui.approval

import com.intellij.openapi.project.Project
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.dsudomoin.claudecodegui.ui.chat.ToolUseBlock

/**
 * Factory that classifies a tool-approval request and creates the appropriate inline panel.
 */
object ApprovalPanelFactory {

    /**
     * Determine the approval type and build a [ToolApprovalRequest] from raw tool data.
     */
    fun classifyTool(toolName: String, input: JsonObject): ToolApprovalRequest {
        val lower = toolName.lowercase()
        return when {
            lower in BASH_TOOLS -> {
                val command = getStr(input, "command") ?: getStr(input, "cmd") ?: ""
                val desc = getStr(input, "description")
                ToolApprovalRequest(
                    type = ToolApprovalType.BASH,
                    title = ToolUseBlock.getToolDisplayName(toolName),
                    details = command,
                    payload = BashPayload(command, desc)
                )
            }
            lower in EDIT_TOOLS -> {
                val filePath = getStr(input, "file_path") ?: getStr(input, "path") ?: ""
                val oldStr = getStr(input, "old_string") ?: getStr(input, "oldString") ?: ""
                val newStr = getStr(input, "new_string") ?: getStr(input, "newString") ?: ""
                val replaceAll = getStr(input, "replace_all")?.toBoolean() ?: false
                ToolApprovalRequest(
                    type = ToolApprovalType.EDIT,
                    title = ToolUseBlock.getToolDisplayName(toolName),
                    details = filePath.substringAfterLast('/'),
                    payload = EditPayload(filePath, oldStr, newStr, replaceAll)
                )
            }
            lower in WRITE_TOOLS -> {
                val filePath = getStr(input, "file_path") ?: getStr(input, "path") ?: ""
                val content = getStr(input, "content") ?: ""
                ToolApprovalRequest(
                    type = ToolApprovalType.WRITE,
                    title = ToolUseBlock.getToolDisplayName(toolName),
                    details = filePath.substringAfterLast('/'),
                    payload = WritePayload(filePath, content)
                )
            }
            else -> {
                ToolApprovalRequest(
                    type = ToolApprovalType.GENERIC,
                    title = ToolUseBlock.getToolDisplayName(toolName),
                    details = formatGenericInput(input)
                )
            }
        }
    }

    /**
     * Create the correct [InlineApprovalPanel] for the given [request].
     */
    fun create(
        project: Project,
        request: ToolApprovalRequest,
        onApprove: (autoApproveSession: Boolean) -> Unit,
        onReject: () -> Unit
    ): InlineApprovalPanel = when (request.type) {
        ToolApprovalType.BASH -> BashApprovalPanel(project, request, onApprove, onReject)
        ToolApprovalType.EDIT -> EditApprovalPanel(project, request, onApprove, onReject)
        ToolApprovalType.WRITE -> WriteApprovalPanel(project, request, onApprove, onReject)
        ToolApprovalType.GENERIC -> GenericApprovalPanel(project, request, onApprove, onReject)
    }

    // ── constants ──────────────────────────────────────────

    private val BASH_TOOLS = setOf(
        "bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command"
    )

    private val EDIT_TOOLS = setOf(
        "edit", "edit_file", "replace_string"
    )

    private val WRITE_TOOLS = setOf(
        "write", "write_to_file", "create_file", "save-file"
    )

    // ── helpers ────────────────────────────────────────────

    private fun getStr(input: JsonObject, key: String): String? {
        val el = input[key] ?: return null
        return (el as? JsonPrimitive)?.content
    }

    private fun formatGenericInput(input: JsonObject): String {
        return buildString {
            for ((key, value) in input) {
                val valueStr = value.toString().trim('"')
                if (valueStr.length > 200) {
                    appendLine("$key: ${valueStr.take(200)}...")
                } else {
                    appendLine("$key: $valueStr")
                }
            }
        }.trimEnd()
    }
}
