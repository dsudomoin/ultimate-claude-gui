package ru.dsudomoin.claudecodegui.core.model

import kotlinx.serialization.json.JsonObject

sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ThinkingDelta(val text: String) : StreamEvent()
    data class TextSnapshot(val text: String) : StreamEvent()
    data class ThinkingSnapshot(val text: String) : StreamEvent()
    data class CodeDelta(val text: String, val language: String? = null) : StreamEvent()
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : StreamEvent()
    data class ToolResult(val id: String, val content: String, val isError: Boolean) : StreamEvent()
    data class Usage(val inputTokens: Int, val outputTokens: Int, val cacheCreation: Int = 0, val cacheRead: Int = 0) : StreamEvent()
    data class PermissionRequest(val toolName: String, val input: JsonObject) : StreamEvent()
    data class Error(val message: String, val code: String? = null) : StreamEvent()
    data object PlanModeEnter : StreamEvent()
    data object PlanModeExit : StreamEvent()
    data object StreamStart : StreamEvent()
    data object StreamEnd : StreamEvent()
    data object MessageStop : StreamEvent()
}
