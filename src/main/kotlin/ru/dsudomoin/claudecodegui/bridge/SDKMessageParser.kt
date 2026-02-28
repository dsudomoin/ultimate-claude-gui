package ru.dsudomoin.claudecodegui.bridge

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.dsudomoin.claudecodegui.core.model.StreamEvent

/**
 * Parses tagged lines from the Node.js bridge stdout into [StreamEvent] objects.
 *
 * Protocol:
 *   [TAG]payload
 *
 * Tags: SESSION_ID, INIT, CONTENT_DELTA, THINKING_DELTA, CONTENT, THINKING,
 *       TOOL_USE, TOOL_RESULT, USAGE, MESSAGE_END, STREAM_START, STREAM_END, ERROR
 */
object SDKMessageParser {

    private val log = Logger.getInstance(SDKMessageParser::class.java)
    private val TAG_REGEX = Regex("""^\[([A-Z_]+)](.*)$""")

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    /**
     * Parses a single tagged line from stdout.
     * Returns null for lines that don't match the tag protocol or are non-event tags.
     */
    fun parse(line: String): ParsedEvent? {
        val match = TAG_REGEX.matchEntire(line) ?: return null
        val tag = match.groupValues[1]
        val payload = match.groupValues[2]

        return try {
            when (tag) {
                "STREAM_START" -> ParsedEvent.Stream(StreamEvent.StreamStart)
                "STREAM_END" -> ParsedEvent.Stream(StreamEvent.StreamEnd)
                "MESSAGE_END" -> ParsedEvent.Stream(StreamEvent.MessageStop)

                "CONTENT_DELTA" -> {
                    // Payload is JSON-encoded to preserve newlines and special chars
                    val text = try {
                        json.decodeFromString<String>(payload)
                    } catch (_: Exception) {
                        payload // fallback to raw string
                    }
                    ParsedEvent.Stream(StreamEvent.TextDelta(text))
                }
                "THINKING_DELTA" -> {
                    val text = try {
                        json.decodeFromString<String>(payload)
                    } catch (_: Exception) {
                        payload
                    }
                    ParsedEvent.Stream(StreamEvent.ThinkingDelta(text))
                }

                // CONTENT and THINKING are full-block snapshots from assistant messages
                "CONTENT" -> ParsedEvent.Stream(StreamEvent.TextSnapshot(payload))
                "THINKING" -> ParsedEvent.Stream(StreamEvent.ThinkingSnapshot(payload))

                "SESSION_ID" -> ParsedEvent.SessionId(payload)

                "TOOL_USE" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.ToolUse(
                            id = obj["id"]?.jsonPrimitive?.content ?: "",
                            name = obj["name"]?.jsonPrimitive?.content ?: "",
                            input = obj["input"]?.jsonObject ?: JsonObject(emptyMap())
                        )
                    )
                }

                "TOOL_RESULT" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    val contentEl = obj["content"]
                    val contentStr = when {
                        contentEl == null -> ""
                        contentEl is kotlinx.serialization.json.JsonPrimitive -> contentEl.content
                        contentEl is kotlinx.serialization.json.JsonArray -> contentEl
                            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
                            .joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
                        else -> contentEl.toString()
                    }
                    ParsedEvent.Stream(
                        StreamEvent.ToolResult(
                            id = obj["id"]?.jsonPrimitive?.content ?: "",
                            content = contentStr,
                            isError = obj["isError"]?.jsonPrimitive?.content?.toBoolean() ?: false
                        )
                    )
                }

                "TOOL_PROGRESS" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.ToolProgress(
                            toolUseId = obj["toolUseId"]?.jsonPrimitive?.content ?: "",
                            toolName = obj["toolName"]?.jsonPrimitive?.content ?: "",
                            parentToolUseId = obj["parentToolUseId"]?.jsonPrimitive?.contentOrNull,
                            elapsedTimeSeconds = obj["elapsedTimeSeconds"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                            taskId = obj["taskId"]?.jsonPrimitive?.contentOrNull,
                        )
                    )
                }

                "TOOL_USE_SUMMARY" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    val ids = obj["precedingToolUseIds"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                    ParsedEvent.Stream(
                        StreamEvent.ToolUseSummary(
                            summary = obj["summary"]?.jsonPrimitive?.content ?: "",
                            precedingToolUseIds = ids,
                        )
                    )
                }

                "TASK_STARTED" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.TaskStarted(
                            taskId = obj["taskId"]?.jsonPrimitive?.content ?: "",
                            toolUseId = obj["toolUseId"]?.jsonPrimitive?.contentOrNull,
                            description = obj["description"]?.jsonPrimitive?.content ?: "",
                            taskType = obj["taskType"]?.jsonPrimitive?.contentOrNull,
                        )
                    )
                }

                "TASK_PROGRESS" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    val usage = obj["usage"]?.jsonObject ?: JsonObject(emptyMap())
                    ParsedEvent.Stream(
                        StreamEvent.TaskProgress(
                            taskId = obj["taskId"]?.jsonPrimitive?.content ?: "",
                            toolUseId = obj["toolUseId"]?.jsonPrimitive?.contentOrNull,
                            description = obj["description"]?.jsonPrimitive?.content ?: "",
                            totalTokens = usage["totalTokens"]?.jsonPrimitive?.int ?: 0,
                            toolUses = usage["toolUses"]?.jsonPrimitive?.int ?: 0,
                            durationMs = usage["durationMs"]?.jsonPrimitive?.int ?: 0,
                            lastToolName = obj["lastToolName"]?.jsonPrimitive?.contentOrNull,
                        )
                    )
                }

                "TASK_NOTIFICATION" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    val usage = obj["usage"]?.jsonObject ?: JsonObject(emptyMap())
                    ParsedEvent.Stream(
                        StreamEvent.TaskNotification(
                            taskId = obj["taskId"]?.jsonPrimitive?.content ?: "",
                            toolUseId = obj["toolUseId"]?.jsonPrimitive?.contentOrNull,
                            status = obj["status"]?.jsonPrimitive?.content ?: "completed",
                            outputFile = obj["outputFile"]?.jsonPrimitive?.content ?: "",
                            summary = obj["summary"]?.jsonPrimitive?.content ?: "",
                            totalTokens = usage["totalTokens"]?.jsonPrimitive?.int ?: 0,
                            toolUses = usage["toolUses"]?.jsonPrimitive?.int ?: 0,
                            durationMs = usage["durationMs"]?.jsonPrimitive?.int ?: 0,
                        )
                    )
                }

                "COMPACT_BOUNDARY" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.CompactBoundary(
                            trigger = obj["trigger"]?.jsonPrimitive?.content ?: "manual",
                            preTokens = obj["preTokens"]?.jsonPrimitive?.int ?: 0,
                        )
                    )
                }

                "RATE_LIMIT" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.RateLimit(
                            status = obj["status"]?.jsonPrimitive?.content ?: "allowed",
                            resetsAt = obj["resetsAt"]?.jsonPrimitive?.longOrNull,
                            utilization = obj["utilization"]?.jsonPrimitive?.doubleOrNull,
                            rateLimitType = obj["rateLimitType"]?.jsonPrimitive?.contentOrNull,
                            isUsingOverage = obj["isUsingOverage"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull(),
                        )
                    )
                }

                "AUTH_STATUS" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    val output = obj["output"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                    ParsedEvent.Stream(
                        StreamEvent.AuthStatus(
                            isAuthenticating = obj["isAuthenticating"]?.jsonPrimitive?.content?.toBoolean() ?: false,
                            output = output,
                            error = obj["error"]?.jsonPrimitive?.contentOrNull,
                        )
                    )
                }

                "PROMPT_SUGGESTION" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.PromptSuggestion(
                            suggestion = obj["suggestion"]?.jsonPrimitive?.content ?: "",
                        )
                    )
                }

                "USER_MESSAGE_ID" -> {
                    ParsedEvent.Stream(StreamEvent.UserMessageId(payload))
                }

                "SDK_STATUS" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.Status(
                            status = obj["status"]?.jsonPrimitive?.contentOrNull,
                            permissionMode = obj["permissionMode"]?.jsonPrimitive?.contentOrNull,
                        )
                    )
                }

                "USAGE" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.Usage(
                            inputTokens = obj["inputTokens"]?.jsonPrimitive?.int ?: 0,
                            outputTokens = obj["outputTokens"]?.jsonPrimitive?.int ?: 0,
                            cacheCreation = obj["cacheCreation"]?.jsonPrimitive?.int ?: 0,
                            cacheRead = obj["cacheRead"]?.jsonPrimitive?.int ?: 0
                        )
                    )
                }

                "PERMISSION_REQUEST" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.PermissionRequest(
                            toolName = obj["toolName"]?.jsonPrimitive?.content ?: "",
                            input = obj["input"]?.jsonObject ?: JsonObject(emptyMap())
                        )
                    )
                }

                "ELICITATION_REQUEST" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.ElicitationRequest(
                            type = obj["type"]?.jsonPrimitive?.content ?: "text",
                            title = obj["title"]?.jsonPrimitive?.content ?: "",
                            description = obj["description"]?.jsonPrimitive?.content ?: "",
                            schema = obj["schema"]?.jsonObject,
                        )
                    )
                }

                "PLAN_MODE" -> {
                    when (payload.trim()) {
                        "enter" -> ParsedEvent.Stream(StreamEvent.PlanModeEnter)
                        "exit" -> ParsedEvent.Stream(StreamEvent.PlanModeExit)
                        else -> null
                    }
                }

                "INIT" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    val tools = obj["tools"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                    val mcpServers = obj["mcpServers"]?.jsonArray?.map { el ->
                        val s = el.jsonObject
                        StreamEvent.Init.McpServerInfo(
                            name = s["name"]?.jsonPrimitive?.content ?: "",
                            status = s["status"]?.jsonPrimitive?.content ?: "",
                        )
                    } ?: emptyList()
                    val agents = obj["agents"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                    val skills = obj["skills"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                    val plugins = obj["plugins"]?.jsonArray?.map { el ->
                        val p = el.jsonObject
                        StreamEvent.Init.PluginInfo(
                            name = p["name"]?.jsonPrimitive?.content ?: "",
                            path = p["path"]?.jsonPrimitive?.content ?: "",
                        )
                    } ?: emptyList()
                    val slashCommands = obj["slashCommands"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                    val betas = obj["betas"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                    ParsedEvent.Stream(
                        StreamEvent.Init(
                            tools = tools,
                            model = obj["model"]?.jsonPrimitive?.content ?: "",
                            mcpServers = mcpServers,
                            agents = agents,
                            skills = skills,
                            plugins = plugins,
                            slashCommands = slashCommands,
                            claudeCodeVersion = obj["claudeCodeVersion"]?.jsonPrimitive?.content ?: "",
                            apiKeySource = obj["apiKeySource"]?.jsonPrimitive?.content ?: "",
                            permissionMode = obj["permissionMode"]?.jsonPrimitive?.content ?: "",
                            fastModeState = obj["fastModeState"]?.jsonPrimitive?.contentOrNull,
                            cwd = obj["cwd"]?.jsonPrimitive?.content ?: "",
                            betas = betas,
                        )
                    )
                }

                "HOOK_EVENT" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.HookEvent(
                            hookName = obj["hookName"]?.jsonPrimitive?.content ?: "",
                            hookEvent = obj["hookEvent"]?.jsonPrimitive?.content ?: "",
                            toolName = obj["toolName"]?.jsonPrimitive?.contentOrNull,
                            progress = obj["progress"]?.jsonPrimitive?.contentOrNull,
                        )
                    )
                }

                "FILES_PERSISTED" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    val files = obj["files"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                    ParsedEvent.Stream(StreamEvent.FilesPersisted(files))
                }

                "RESULT_META" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    val modelUsage = try {
                        val mu = obj["modelUsage"]
                        if (mu is JsonObject) {
                            mu.entries.mapNotNull { (key, value) ->
                                if (value is JsonObject) {
                                    key to StreamEvent.ResultMeta.ModelUsageEntry(
                                        inputTokens = (value["input_tokens"] as? JsonPrimitive)?.int ?: 0,
                                        outputTokens = (value["output_tokens"] as? JsonPrimitive)?.int ?: 0,
                                    )
                                } else null
                            }.toMap()
                        } else emptyMap()
                    } catch (_: Exception) { emptyMap() }
                    val denials = try {
                        obj["permissionDenials"]?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?: emptyList()
                    } catch (_: Exception) { emptyList() }
                    ParsedEvent.Stream(
                        StreamEvent.ResultMeta(
                            fastModeState = (obj["fastModeState"] as? JsonPrimitive)?.contentOrNull,
                            modelUsage = modelUsage,
                            permissionDenials = denials,
                        )
                    )
                }

                "ERROR" -> ParsedEvent.Stream(StreamEvent.Error(payload))

                else -> {
                    log.debug("Unknown bridge tag: $tag")
                    null
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to parse bridge message [$tag]: ${e.message}")
            ParsedEvent.Stream(StreamEvent.Error("Parse error [$tag]: ${e.message}"))
        }
    }
}

sealed class ParsedEvent {
    data class Stream(val event: StreamEvent) : ParsedEvent()
    data class SessionId(val id: String) : ParsedEvent()
}
