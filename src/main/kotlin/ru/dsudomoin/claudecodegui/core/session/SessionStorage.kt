package ru.dsudomoin.claudecodegui.core.session

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.*
import ru.dsudomoin.claudecodegui.bridge.BridgeManager
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

    /**
     * Load session messages via SDK getSessionMessages() bridge command.
     * Falls back to null on any bridge/protocol issue so caller can use JSONL path.
     */
    fun loadViaSdk(projectPath: String, sessionId: String, limit: Int = 1000): List<Message>? {
        if (!BridgeManager.isReady) return null

        return try {
            val command = buildJsonObject {
                put("command", "getSessionMessages")
                put("sessionId", sessionId)
                put("dir", projectPath)
                put("limit", limit)
            }.toString()

            val output = BridgeManager.runBridgeCommand(command)
            val messagesJson = output["SESSION_MESSAGES"] ?: return null
            val arr = json.parseToJsonElement(messagesJson).jsonArray

            val parsed = arr.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                parseSessionObject(obj)
            }

            if (parsed.isEmpty()) null else aggregateMessages(parsed)
        } catch (e: Exception) {
            log.warn("SDK getSessionMessages failed, will use JSONL fallback: ${e.message}")
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

    /**
     * List sessions using the SDK's listSessions() function via bridge.
     * Returns richer metadata than JSONL scanning (firstPrompt, gitBranch, cwd).
     * Falls back to null if bridge is not available.
     */
    fun listSessionsViaSdk(projectPath: String, limit: Int = 50): List<SessionInfo>? {
        if (!BridgeManager.isReady) return null

        return try {
            val command = buildJsonObject {
                put("command", "listSessions")
                put("dir", projectPath)
                put("limit", limit)
            }.toString()

            val output = BridgeManager.runBridgeCommand(command)
            val sessionsJson = output["SESSIONS"] ?: return null
            val arr = json.parseToJsonElement(sessionsJson).jsonArray

            arr.mapNotNull { elem ->
                val obj = elem.jsonObject
                val sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val rawSummary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: "New Chat"
                val lastModified = obj["lastModified"]?.jsonPrimitive?.longOrNull ?: 0L
                val customTitle = obj["customTitle"]?.jsonPrimitive?.contentOrNull
                val rawFirstPrompt = obj["firstPrompt"]?.jsonPrimitive?.contentOrNull
                val gitBranch = obj["gitBranch"]?.jsonPrimitive?.contentOrNull
                val cwd = obj["cwd"]?.jsonPrimitive?.contentOrNull

                // SDK metadata + local JSONL snapshot (for accurate title/count).
                val localInfo = runCatching {
                    val file = File(projectDir(projectPath), "$sessionId.jsonl")
                    if (file.exists()) extractSessionInfo(file) else null
                }.getOrNull()

                // Strip enriched IDE context from summary and firstPrompt
                val summary = stripEnrichedContext(rawSummary).trim()
                val firstPrompt = rawFirstPrompt?.let { stripEnrichedContext(it).trim() }?.takeIf { it.isNotBlank() }
                val localTitle = localInfo?.title
                    ?.let { stripEnrichedContext(it).trim() }
                    ?.takeIf { it.isNotBlank() && it != "New Chat" }

                val title = when {
                    !customTitle.isNullOrBlank() -> customTitle
                    !localTitle.isNullOrBlank() -> localTitle
                    summary.isNotBlank() -> summary.take(60).let {
                        if (it.length >= 60) "${it.take(57)}..." else it
                    }
                    !firstPrompt.isNullOrBlank() -> firstPrompt.take(60).let {
                        if (it.length >= 60) "${it.take(57)}..." else it
                    }
                    else -> "New Chat"
                }

                val messageCount = localInfo?.messageCount
                    ?: obj["messageCount"]?.jsonPrimitive?.intOrNull
                    ?: 0

                SessionInfo(
                    sessionId = sessionId,
                    title = title,
                    lastTimestamp = lastModified,
                    messageCount = messageCount,
                    slug = customTitle ?: localInfo?.slug,
                    firstPrompt = firstPrompt,
                    gitBranch = gitBranch,
                    cwd = cwd,
                )
            }
                .filter { it.messageCount > 0 }
                .sortedByDescending { it.lastTimestamp }
        } catch (e: Exception) {
            log.warn("SDK listSessions failed, will use JSONL fallback: ${e.message}")
            null
        }
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
        return parseSessionObject(obj)
    }

    /**
     * Parse SDK/CLI message object into ParsedMessage.
     * Supports both JSONL line schema and getSessionMessages response schema.
     */
    private fun parseSessionObject(obj: JsonObject): ParsedMessage? {
        val topType = obj["type"]?.jsonPrimitive?.contentOrNull?.lowercase()
        val roleText = topType
            ?: obj["role"]?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: obj["message"]?.jsonObject?.get("role")?.jsonPrimitive?.contentOrNull?.lowercase()
            ?: return null

        val role = when (roleText) {
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            else -> return null
        }

        val messageObj = obj["message"]?.jsonObject ?: obj
        val contentElement = messageObj["content"] ?: obj["content"] ?: return null

        val timestamp = listOfNotNull(
            obj["timestamp"]?.jsonPrimitive?.contentOrNull,
            messageObj["timestamp"]?.jsonPrimitive?.contentOrNull,
        ).firstNotNullOfOrNull { raw ->
            runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()
        } ?: System.currentTimeMillis()

        val blocks = parseContentBlocks(contentElement, role)
        if (blocks.isEmpty()) return null
        return ParsedMessage(role, blocks, timestamp)
    }

    private fun parseContentBlocks(contentElement: JsonElement, role: Role): List<ContentBlock> {
        val contentArray = when (contentElement) {
            is JsonArray -> contentElement
            is JsonPrimitive -> {
                val text = contentElement.contentOrNull?.trim().orEmpty()
                if (text.isBlank()) return emptyList()
                return listOf(ContentBlock.Text(text))
            }
            else -> return emptyList()
        }

        val blocks = contentArray.mapNotNull { block ->
            val blockObj = block as? JsonObject ?: return@mapNotNull null
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
                    val resultContent = blockObj["content"]?.let { result ->
                        when (result) {
                            is JsonPrimitive -> result.contentOrNull ?: ""
                            is JsonArray -> result.mapNotNull { item ->
                                val itemObj = item as? JsonObject ?: return@mapNotNull null
                                if (itemObj["type"]?.jsonPrimitive?.contentOrNull == "text") {
                                    itemObj["text"]?.jsonPrimitive?.contentOrNull
                                } else null
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
                "compact_boundary" -> {
                    val trigger = blockObj["trigger"]?.jsonPrimitive?.contentOrNull ?: "manual"
                    val preTokens = blockObj["preTokens"]?.jsonPrimitive?.intOrNull ?: 0
                    ContentBlock.CompactBoundary(trigger = trigger, preTokens = preTokens)
                }
                else -> null
            }
        }

        if (role == Role.USER && blocks.all { it is ContentBlock.ToolResult }) {
            return blocks
        }
        return blocks
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
        // Remove explicit rich-context suffixes.
        var cleaned = text.replace(Regex("\n\n_Currently viewing `[^`]+` \\(line \\d+\\)_$"), "")
        cleaned = cleaned.replace(Regex("\n\n_Attached screenshots:_(\n- `[^`]+`)+$"), "")
        cleaned = cleaned.replace(Regex("\n\n_Referenced files:_.*$", setOf(RegexOption.DOT_MATCHES_ALL)), "")

        // Defensive cleanup for truncated summaries where enrichment is cut mid-string.
        cleaned = cleaned.replace(Regex("\\s*_Currently viewing.*$"), "")
        cleaned = cleaned.replace(Regex("\\s*_Attached screenshots:.*$"), "")
        cleaned = cleaned.replace(Regex("\\s*_Referenced files:.*$"), "")

        return cleaned.trim()
    }
}

data class SessionInfo(
    val sessionId: String,
    val title: String,
    val lastTimestamp: Long,
    val messageCount: Int,
    val slug: String? = null,
    val firstPrompt: String? = null,
    val gitBranch: String? = null,
    val cwd: String? = null,
)
