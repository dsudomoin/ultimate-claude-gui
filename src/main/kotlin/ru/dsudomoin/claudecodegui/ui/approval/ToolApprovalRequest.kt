package ru.dsudomoin.claudecodegui.ui.approval

/**
 * Data model describing a tool-approval request independently of any UI.
 */
data class ToolApprovalRequest(
    val type: ToolApprovalType,
    val title: String,
    val details: String,
    val payload: ToolApprovalPayload? = null
)

enum class ToolApprovalType { BASH, WRITE, EDIT, GENERIC }

sealed interface ToolApprovalPayload

data class BashPayload(
    val command: String,
    val description: String? = null
) : ToolApprovalPayload

data class WritePayload(
    val filePath: String,
    val content: String
) : ToolApprovalPayload

data class EditPayload(
    val filePath: String,
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean = false
) : ToolApprovalPayload
