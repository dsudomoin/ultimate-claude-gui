package ru.dsudomoin.claudecodegui.core.session

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import ru.dsudomoin.claudecodegui.core.model.ContentBlock
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.Role
import java.io.File
import java.time.Instant

/**
 * Reads sessions directly from the Claude CLI storage at ~/.claude/projects/.
 *
 * CLI JSONL format:
 *   - Each line is a JSON object with `type` field
 *   - type=user/assistant: message data with `message.content` array
 *   - type=queue-operation, file-history-snapshot, system: skip
 *   - `slug` field appears on the first assistant message that has it
 *
 * Path: ~/.claude/projects/<sanitized-path>/<sessionId>.jsonl
 * Sanitization: all non-alphanumeric characters -> '-'
 */
object SessionStorage {

    private val log = Logger.getInstance(SessionStorage::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val claudeDir: File
        get() = File(System.getProperty("user.home"), ".claude/projects")

    /** Sanitize project path the same way CLI does: non-alphanumeric -> '-' */
    fun projectDir(projectPath: String): File {
        val sanitized = projectPath.replace(Regex("[^a-zA-Z0-9]"), "-")
        return File(claudeDir, sanitized)
    }

    /**
     * List all sessions for a project, sorted by last modified (newest first).
     * Skips agent-*.jsonl subagent files and sessions with < 2 messages.
     */
    fun listSessions(projectPath: String): List<SessionInfo> {
        val dir = projectDir(projectPath)
        if (!dir.exists()) return emptyList()

        return dir.listFiles { f ->
            f.extension == "jsonl" && !f.name.startsWith("agent-")
        }?.mapNotNull { file ->
            try {
                extractSessionInfo(file)
            } catch (e: Exception) {
                log.debug("Failed to read session info from ${file.name}: ${e.message}")
                null
            }
        }?.filter { it.messageCount >= 2 }
            ?.sortedByDescending { it.lastTimestamp }
            ?: emptyList()
    }

    /**
     * Load session messages from CLI JSONL file.
     * Parses user/assistant messages, skips technical entries.
     * Aggregates consecutive same-role message blocks into single Messages.
     */
    fun load(projectPath: String, sessionId: String): List<Message>? {
        val file = File(projectDir(projectPath), "$sessionId.jsonl")
        if (!file.exists()) return null

        return try {
            val rawMessages = mutableListOf<ParsedMessage>()
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val parsed = parseCliLine(line)
                    if (parsed != null) {
                        rawMessages.add(parsed)
                    }
                }
            }
            // Aggregate consecutive same-role blocks into single Messages
            aggregateMessages(rawMessages)
        } catch (e: Exception) {
            log.warn("Failed to load session $sessionId: ${e.message}")
            null
        }
    }

    /** Get title for a session (slug or first user message text). */
    fun getTitle(projectPath: String, sessionId: String): String? {
        val file = File(projectDir(projectPath), "$sessionId.jsonl")
        if (!file.exists()) return null

        return try {
            var slug: String? = null
            var firstUserText: String? = null

            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    val obj = json.parseToJsonElement(line).jsonObject
                    // Check for slug
                    if (slug == null) {
                        slug = obj["slug"]?.jsonPrimitive?.contentOrNull
                    }
                    // Check for first user text message
                    if (firstUserText == null && obj["type"]?.jsonPrimitive?.contentOrNull == "user") {
                        val content = obj["message"]?.jsonObject?.get("content")?.jsonArray
                        firstUserText = content?.firstOrNull { block ->
                            block.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text"
                        }?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                    }
                    // Stop early once we have both
                    if (slug != null && firstUserText != null) break
                }
            }

            slug ?: firstUserText?.take(60)?.let {
                if (it.length >= 60) "${it.take(57)}..." else it
            }
        } catch (e: Exception) {
            log.debug("Failed to get title for $sessionId: ${e.message}")
            null
        }
    }

    /** Delete a session: remove .jsonl file and associated directory (subagents, tool-results). */
    fun delete(projectPath: String, sessionId: String) {
        val dir = projectDir(projectPath)
        File(dir, "$sessionId.jsonl").delete()
        // Delete associated session directory (subagent files, etc.)
        val sessionDir = File(dir, sessionId)
        if (sessionDir.exists() && sessionDir.isDirectory) {
            sessionDir.deleteRecursively()
        }
    }

    /**
     * Extract usage from the last assistant message in the JSONL file.
     * Returns (inputTokens, cacheCreation, cacheRead) or null if not found.
     */
    fun getLastUsage(projectPath: String, sessionId: String): Triple<Int, Int, Int>? {
        val file = File(projectDir(projectPath), "$sessionId.jsonl")
        if (!file.exists()) return null

        var lastUsage: Triple<Int, Int, Int>? = null
        try {
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.isBlank()) continue
                    try {
                        val obj = json.parseToJsonElement(line).jsonObject
                        if (obj["type"]?.jsonPrimitive?.contentOrNull != "assistant") continue
                        val usage = obj["message"]?.jsonObject?.get("usage")?.jsonObject ?: continue
                        val input = usage["input_tokens"]?.jsonPrimitive?.int ?: 0
                        val cacheCreation = usage["cache_creation_input_tokens"]?.jsonPrimitive?.int ?: 0
                        val cacheRead = usage["cache_read_input_tokens"]?.jsonPrimitive?.int ?: 0
                        lastUsage = Triple(input, cacheCreation, cacheRead)
                    } catch (_: Exception) {
                        // Skip malformed lines
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("Failed to extract usage for $sessionId: ${e.message}")
        }
        return lastUsage
    }

    // ── Private helpers ──

    private data class ParsedMessage(
        val role: Role,
        val blocks: List<ContentBlock>,
        val timestamp: Long
    )

    /**
     * Extract lightweight session info by scanning the JSONL file.
     * Reads slug from first occurrence, counts user/assistant messages,
     * gets timestamp from last message.
     */
    private fun extractSessionInfo(file: File): SessionInfo {
        val sessionId = file.nameWithoutExtension
        var slug: String? = null
        var firstUserText: String? = null
        var messageCount = 0
        var lastTimestamp = file.lastModified()

        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                try {
                    val obj = json.parseToJsonElement(line).jsonObject
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: continue

                    if (type == "user" || type == "assistant") {
                        // Only count actual conversation messages (not tool_result user messages)
                        val content = obj["message"]?.jsonObject?.get("content")?.jsonArray
                        val blockTypes = content?.mapNotNull {
                            it.jsonObject["type"]?.jsonPrimitive?.contentOrNull
                        } ?: emptyList()

                        val isToolResult = blockTypes.all { it == "tool_result" }
                        if (!isToolResult) {
                            messageCount++
                        }

                        // Extract slug
                        if (slug == null) {
                            slug = obj["slug"]?.jsonPrimitive?.contentOrNull
                        }

                        // Extract first user text for title
                        if (firstUserText == null && type == "user" && !isToolResult) {
                            firstUserText = content?.firstOrNull { block ->
                                block.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text"
                            }?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
                        }

                        // Update last timestamp
                        val ts = obj["timestamp"]?.jsonPrimitive?.contentOrNull
                        if (ts != null) {
                            try {
                                lastTimestamp = Instant.parse(ts).toEpochMilli()
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Skip malformed lines
                }
            }
        }

        val title = slug ?: firstUserText?.take(60)?.let {
            if (it.length >= 60) "${it.take(57)}..." else it.ifBlank { "New Chat" }
        } ?: "New Chat"

        return SessionInfo(
            sessionId = sessionId,
            title = title,
            lastTimestamp = lastTimestamp,
            messageCount = messageCount,
            slug = slug
        )
    }

    /**
     * Parse a single CLI JSONL line into a ParsedMessage (or null if not a conversation message).
     */
    private fun parseCliLine(line: String): ParsedMessage? {
        val obj = json.parseToJsonElement(line).jsonObject
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return null

        if (type != "user" && type != "assistant") return null

        val role = if (type == "user") Role.USER else Role.ASSISTANT
        val timestamp = obj["timestamp"]?.jsonPrimitive?.contentOrNull?.let {
            try {
                Instant.parse(it).toEpochMilli()
            } catch (_: Exception) {
                System.currentTimeMillis()
            }
        } ?: System.currentTimeMillis()

        val messageObj = obj["message"]?.jsonObject ?: return null
        val contentElement = messageObj["content"] ?: return null

        // content can be a JSON array (normal) or a plain string (e.g. session continuation summary)
        val contentArray = when (contentElement) {
            is JsonArray -> contentElement
            is JsonPrimitive -> {
                val text = contentElement.contentOrNull ?: return null
                if (text.isBlank()) return null
                return ParsedMessage(role, listOf(ContentBlock.Text(text)), timestamp)
            }
            else -> return null
        }

        val blocks = contentArray.mapNotNull { block ->
            val blockObj = block.jsonObject
            when (blockObj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> {
                    val text = blockObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (text.isNotBlank()) ContentBlock.Text(text) else null
                }
                "thinking" -> {
                    val thinking = blockObj["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    if (thinking.isNotBlank()) ContentBlock.Thinking(thinking) else null
                }
                "tool_use" -> ContentBlock.ToolUse(
                    id = blockObj["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    name = blockObj["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    input = blockObj["input"]?.jsonObject ?: JsonObject(emptyMap())
                )
                "tool_result" -> {
                    val toolUseId = blockObj["tool_use_id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val isError = blockObj["is_error"]?.jsonPrimitive?.booleanOrNull ?: false
                    // tool_result content can be a string or array
                    val resultContent = blockObj["content"]?.let { contentElement ->
                        when (contentElement) {
                            is JsonPrimitive -> contentElement.contentOrNull ?: ""
                            is JsonArray -> contentElement.mapNotNull { el ->
                                el.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                            }.joinToString("\n")
                            else -> ""
                        }
                    } ?: ""
                    ContentBlock.ToolResult(
                        toolUseId = toolUseId,
                        content = resultContent,
                        isError = isError
                    )
                }
                "image" -> {
                    val source = blockObj["source"]?.let { src ->
                        when (src) {
                            is JsonPrimitive -> src.contentOrNull
                            is JsonObject -> src["data"]?.jsonPrimitive?.contentOrNull
                            else -> null
                        }
                    } ?: ""
                    if (source.isNotBlank()) ContentBlock.Image(source) else null
                }
                else -> null
            }
        }

        if (blocks.isEmpty()) return null
        return ParsedMessage(role, blocks, timestamp)
    }

    /**
     * Aggregate consecutive ParsedMessages with the same role into single Messages.
     * This converts the fine-grained CLI format (one line per API turn) into
     * our UI display format (one Message per logical turn).
     *
     * Special handling:
     * - User messages containing ONLY tool_result blocks are attached to the
     *   preceding assistant message (they're API protocol artifacts, not user text).
     * - Enriched IDE context (appended by the plugin) is stripped from user text blocks.
     */
    private fun aggregateMessages(parsed: List<ParsedMessage>): List<Message> {
        if (parsed.isEmpty()) return emptyList()

        val result = mutableListOf<Message>()
        var currentRole = parsed[0].role
        var currentBlocks = mutableListOf<ContentBlock>()
        var currentTimestamp = parsed[0].timestamp

        for (msg in parsed) {
            // User messages with only tool_result blocks → attach to previous assistant message
            if (msg.role == Role.USER && msg.blocks.all { it is ContentBlock.ToolResult }) {
                if (currentRole == Role.ASSISTANT) {
                    // Still accumulating assistant blocks — just append tool results
                    currentBlocks.addAll(msg.blocks)
                } else if (result.isNotEmpty() && result.last().role == Role.ASSISTANT) {
                    // Previous group already flushed — append to it
                    val last = result.removeAt(result.lastIndex)
                    result.add(Message(last.role, last.content + msg.blocks, last.timestamp))
                }
                // Otherwise (no preceding assistant) — drop these orphaned tool results
                continue
            }

            if (msg.role != currentRole) {
                // Flush accumulated blocks
                if (currentBlocks.isNotEmpty()) {
                    result.add(Message(currentRole, currentBlocks.toList(), currentTimestamp))
                }
                currentRole = msg.role
                currentBlocks = mutableListOf()
                currentTimestamp = msg.timestamp
            }

            if (msg.role == Role.USER) {
                // Strip enriched IDE context from user text blocks
                currentBlocks.addAll(msg.blocks.map { block ->
                    if (block is ContentBlock.Text) ContentBlock.Text(stripEnrichedContext(block.text))
                    else block
                })
            } else {
                currentBlocks.addAll(msg.blocks)
            }
        }

        // Flush last group
        if (currentBlocks.isNotEmpty()) {
            result.add(Message(currentRole, currentBlocks.toList(), currentTimestamp))
        }

        return result
    }

    /**
     * Strip IDE context enrichment that the plugin appends to user messages before sending to the API.
     * The plugin adds patterns like:
     *   \n\n_Currently viewing `<path>` (line N)_
     *   \n\n_Attached screenshots:_\n- `<path>`
     */
    private fun stripEnrichedContext(text: String): String {
        // Strip "_Currently viewing ..." suffix
        var cleaned = text.replace(Regex("\n\n_Currently viewing `[^`]+` \\(line \\d+\\)_$"), "")
        // Strip "_Attached screenshots:_" suffix (may span multiple lines)
        cleaned = cleaned.replace(Regex("\n\n_Attached screenshots:_(\n- `[^`]+`)+$"), "")
        return cleaned
    }
}

data class SessionInfo(
    val sessionId: String,
    val title: String,
    val lastTimestamp: Long,
    val messageCount: Int,
    val slug: String? = null
)
