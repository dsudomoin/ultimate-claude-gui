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
    data class ToolProgress(
        val toolUseId: String,
        val toolName: String,
        val parentToolUseId: String? = null,
        val elapsedTimeSeconds: Double = 0.0,
        val taskId: String? = null,
    ) : StreamEvent()
    data class ToolUseSummary(
        val summary: String,
        val precedingToolUseIds: List<String> = emptyList(),
    ) : StreamEvent()
    data class TaskStarted(
        val taskId: String,
        val toolUseId: String? = null,
        val description: String = "",
        val taskType: String? = null,
    ) : StreamEvent()
    data class TaskProgress(
        val taskId: String,
        val toolUseId: String? = null,
        val description: String = "",
        val totalTokens: Int = 0,
        val toolUses: Int = 0,
        val durationMs: Int = 0,
        val lastToolName: String? = null,
    ) : StreamEvent()
    data class TaskNotification(
        val taskId: String,
        val toolUseId: String? = null,
        val status: String = "completed",
        val outputFile: String = "",
        val summary: String = "",
        val totalTokens: Int = 0,
        val toolUses: Int = 0,
        val durationMs: Int = 0,
    ) : StreamEvent()
    data class CompactBoundary(
        val trigger: String = "manual",
        val preTokens: Int = 0,
    ) : StreamEvent()
    data class RateLimit(
        val status: String = "allowed",
        val resetsAt: Long? = null,
        val utilization: Double? = null,
        val rateLimitType: String? = null,
        val isUsingOverage: Boolean? = null,
    ) : StreamEvent()
    data class AuthStatus(
        val isAuthenticating: Boolean,
        val output: List<String> = emptyList(),
        val error: String? = null,
    ) : StreamEvent()
    data class PromptSuggestion(val suggestion: String) : StreamEvent()
    data class UserMessageId(val id: String) : StreamEvent()
    data class Status(
        val status: String? = null,
        val permissionMode: String? = null,
    ) : StreamEvent()
    data class Usage(val inputTokens: Int, val outputTokens: Int, val cacheCreation: Int = 0, val cacheRead: Int = 0) : StreamEvent()
    data class PermissionRequest(val toolName: String, val input: JsonObject) : StreamEvent()
    data class ElicitationRequest(
        val type: String = "text",
        val title: String = "",
        val description: String = "",
        val schema: JsonObject? = null,
    ) : StreamEvent()
    data class Error(val message: String, val code: String? = null) : StreamEvent()
    data object PlanModeEnter : StreamEvent()
    data object PlanModeExit : StreamEvent()
    data class Init(
        val tools: List<String> = emptyList(),
        val model: String = "",
        val mcpServers: List<McpServerInfo> = emptyList(),
        val agents: List<String> = emptyList(),
        val skills: List<String> = emptyList(),
        val plugins: List<PluginInfo> = emptyList(),
        val slashCommands: List<String> = emptyList(),
        val claudeCodeVersion: String = "",
        val apiKeySource: String = "",
        val permissionMode: String = "",
        val fastModeState: String? = null,
        val cwd: String = "",
        val betas: List<String> = emptyList(),
    ) : StreamEvent() {
        data class McpServerInfo(val name: String, val status: String)
        data class PluginInfo(val name: String, val path: String)
    }
    data class HookEvent(
        val hookName: String,
        val hookEvent: String,
        val toolName: String? = null,
        val progress: String? = null,
    ) : StreamEvent()
    data class FilesPersisted(
        val files: List<String> = emptyList(),
    ) : StreamEvent()
    data class ResultMeta(
        val fastModeState: String? = null,
        val modelUsage: Map<String, ModelUsageEntry> = emptyMap(),
        val permissionDenials: List<String> = emptyList(),
    ) : StreamEvent() {
        data class ModelUsageEntry(val inputTokens: Int = 0, val outputTokens: Int = 0)
    }
    data object StreamStart : StreamEvent()
    data object StreamEnd : StreamEvent()
    data object MessageStop : StreamEvent()
}
