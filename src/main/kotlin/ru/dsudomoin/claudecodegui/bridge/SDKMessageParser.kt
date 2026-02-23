package ru.dsudomoin.claudecodegui.bridge

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.dsudomoin.claudecodegui.core.model.StreamEvent

/**
 * Parses tagged lines from the Node.js bridge stdout into [StreamEvent] objects.
 *
 * Protocol:
 *   [TAG]payload
 *
 * Tags: SESSION_ID, CONTENT_DELTA, THINKING_DELTA, CONTENT, THINKING,
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
                    ParsedEvent.Stream(
                        StreamEvent.ToolResult(
                            id = obj["id"]?.jsonPrimitive?.content ?: "",
                            content = obj["content"]?.jsonPrimitive?.content ?: "",
                            isError = obj["isError"]?.jsonPrimitive?.content?.toBoolean() ?: false
                        )
                    )
                }

                "USAGE" -> {
                    val obj = json.parseToJsonElement(payload).jsonObject
                    ParsedEvent.Stream(
                        StreamEvent.Usage(
                            inputTokens = obj["inputTokens"]?.jsonPrimitive?.int ?: 0,
                            outputTokens = obj["outputTokens"]?.jsonPrimitive?.int ?: 0,
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
