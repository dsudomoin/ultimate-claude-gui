package ru.dsudomoin.claudecodegui.provider.claude

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.awaitExit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import ru.dsudomoin.claudecodegui.bridge.BridgeManager
import ru.dsudomoin.claudecodegui.bridge.NodeDetector
import ru.dsudomoin.claudecodegui.bridge.ParsedEvent
import ru.dsudomoin.claudecodegui.bridge.ProcessEnvironment.withNodeEnvironment
import ru.dsudomoin.claudecodegui.bridge.SDKMessageParser
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.StreamEvent
import ru.dsudomoin.claudecodegui.provider.AiProvider
import java.io.BufferedWriter

/**
 * Claude provider that delegates to the Node.js bridge running @anthropic-ai/claude-code SDK.
 *
 * The SDK handles authentication, tools, session management, and the full agent loop.
 * Communication is bidirectional:
 *   - stdin line 1: JSON command
 *   - stdin subsequent lines: permission responses
 *   - stdout: tagged lines [TAG]payload
 */
class ClaudeProvider : AiProvider {
    override val id = "claude"
    override val displayName = "Claude (SDK)"

    private val log = Logger.getInstance(ClaudeProvider::class.java)

    @Volatile
    private var currentProcess: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    var sessionId: String? = null
        private set

    /** Set sessionId for resuming a session from history. */
    fun setResumeSessionId(id: String) {
        sessionId = id
    }

    var projectDir: String? = null

    override fun isConfigured(): Boolean {
        return NodeDetector.detect() != null
    }

    /** Send a permission response back to the bridge process via stdin. */
    fun sendPermissionResponse(allow: Boolean, message: String? = null) {
        try {
            val response = buildJsonObject {
                put("allow", allow)
                if (!allow && message != null) put("message", message)
            }
            stdinWriter?.let { writer ->
                writer.write(response.toString())
                writer.newLine()
                writer.flush()
            }
        } catch (e: Exception) {
            log.warn("Failed to send permission response: ${e.message}")
        }
    }

    /** Send a permission response with updatedInput (for AskUserQuestion). */
    fun sendPermissionResponseWithInput(allow: Boolean, updatedInput: JsonElement) {
        try {
            val response = buildJsonObject {
                put("allow", allow)
                put("updatedInput", updatedInput)
            }
            stdinWriter?.let { writer ->
                writer.write(response.toString())
                writer.newLine()
                writer.flush()
            }
        } catch (e: Exception) {
            log.warn("Failed to send permission response with input: ${e.message}")
        }
    }

    override fun sendMessage(
        messages: List<Message>,
        model: String,
        maxTokens: Int,
        systemPrompt: String?,
        permissionMode: String,
        streaming: Boolean
    ): Flow<StreamEvent> = channelFlow {
        // Ensure bridge is ready (extract + npm install)
        if (!BridgeManager.isReady) {
            send(StreamEvent.TextDelta("Setting up Claude SDK bridge (first run)...\n"))
            val ready = withContext(Dispatchers.IO) { BridgeManager.ensureReady() }
            if (!ready) {
                send(StreamEvent.Error("Failed to set up SDK bridge. Check that Node.js 18+ is installed."))
                return@channelFlow
            }
        } else {
            // Always re-extract scripts to pick up plugin updates
            withContext(Dispatchers.IO) { BridgeManager.ensureScriptsUpdated() }
        }

        val nodePath = NodeDetector.detect()
        if (nodePath == null) {
            send(StreamEvent.Error("Node.js not found. Install Node.js 18+ and restart IDE."))
            return@channelFlow
        }

        // Build the stdin payload
        val lastUserMessage = messages.lastOrNull { it.role == ru.dsudomoin.claudecodegui.core.model.Role.USER }
        if (lastUserMessage == null) {
            send(StreamEvent.Error("No user message to send."))
            return@channelFlow
        }

        val cwd = projectDir ?: System.getProperty("user.dir")

        val payload = buildJsonObject {
            put("command", "send")
            put("message", lastUserMessage.textContent)
            put("cwd", cwd)
            put("permissionMode", permissionMode.ifBlank { "default" })
            if (model.isNotBlank()) put("model", model)
            if (!systemPrompt.isNullOrBlank()) put("systemPrompt", systemPrompt)
            put("streaming", streaming)
            sessionId?.let { put("sessionId", it) }
        }

        send(StreamEvent.StreamStart)

        try {
            withContext(Dispatchers.IO) {
                val process = ProcessBuilder(
                    nodePath,
                    BridgeManager.bridgeScript.absolutePath
                )
                    .directory(BridgeManager.bridgeScript.parentFile)
                    .withNodeEnvironment(nodePath)
                    .redirectErrorStream(false)
                    .start()

                currentProcess = process

                // Write JSON payload as first line, keep stdin open for permission responses
                val writer = process.outputStream.bufferedWriter()
                stdinWriter = writer
                writer.write(payload.toString())
                writer.newLine()
                writer.flush()

                // Read stderr concurrently — collect into buffer for error reporting
                val stderrLines = java.util.concurrent.CopyOnWriteArrayList<String>()
                val stderrThread = Thread({
                    try {
                        process.errorStream.bufferedReader().forEachLine { stderrLine ->
                            log.info("Bridge: $stderrLine")
                            stderrLines.add(stderrLine)
                        }
                    } catch (_: Exception) { }
                }, "bridge-stderr-reader").apply { isDaemon = true; start() }

                // Read tagged lines from stdout
                var receivedError = false
                process.inputStream.bufferedReader().use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        if (!currentCoroutineContext().isActive) {
                            process.destroyForcibly()
                            break
                        }

                        val parsed = SDKMessageParser.parse(line!!) ?: continue

                        when (parsed) {
                            is ParsedEvent.SessionId -> {
                                sessionId = parsed.id
                                log.info("SDK session: ${parsed.id}")
                            }

                            is ParsedEvent.Stream -> {
                                if (parsed.event is StreamEvent.Error) receivedError = true
                                send(parsed.event)
                            }
                        }
                    }
                }

                // Clean up stdin writer
                try {
                    writer.close()
                } catch (_: Exception) { }
                stdinWriter = null

                stderrThread.join(3000)

                val exitCode = process.awaitExit()
                if (exitCode != 0) {
                    log.warn("Bridge exited with code $exitCode")
                    // Surface stderr to the user if no [ERROR] tag was already sent
                    if (!receivedError && stderrLines.isNotEmpty()) {
                        val stderrText = stderrLines.takeLast(10).joinToString("\n")
                        send(StreamEvent.Error("Bridge exited with code $exitCode:\n$stderrText"))
                    }
                }

                currentProcess = null
            }
        } catch (e: CancellationException) {
            stdinWriter = null
            currentProcess?.destroyForcibly()
            currentProcess = null
            throw e
        } catch (e: Exception) {
            stdinWriter = null
            currentProcess?.destroyForcibly()
            currentProcess = null
            send(StreamEvent.Error("Bridge error: ${e.message}"))
        }

        send(StreamEvent.StreamEnd)
    }

    /**
     * Fetch slash commands from the SDK bridge.
     * Spawns a short-lived bridge process with command=getSlashCommands.
     * Returns list of {name, description} objects.
     */
    suspend fun fetchSlashCommands(): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            if (!BridgeManager.isReady) {
                val ready = BridgeManager.ensureReady()
                if (!ready) return@withContext emptyList()
            } else {
                BridgeManager.ensureScriptsUpdated()
            }

            val nodePath = NodeDetector.detect() ?: return@withContext emptyList()
            val cwd = projectDir ?: System.getProperty("user.dir")

            val payload = buildJsonObject {
                put("command", "getSlashCommands")
                put("cwd", cwd)
            }

            val process = ProcessBuilder(nodePath, BridgeManager.bridgeScript.absolutePath)
                .directory(BridgeManager.bridgeScript.parentFile)
                .withNodeEnvironment(nodePath)
                .redirectErrorStream(false)
                .start()

            process.outputStream.bufferedWriter().use { writer ->
                writer.write(payload.toString())
                writer.newLine()
                writer.flush()
            }

            val result = mutableListOf<Pair<String, String>>()

            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (l.startsWith("[SLASH_COMMANDS]")) {
                        val json = l.removePrefix("[SLASH_COMMANDS]")
                        try {
                            val arr = Json.parseToJsonElement(json).jsonArray
                            for (elem in arr) {
                                val obj = elem.jsonObject
                                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                                val desc = obj["description"]?.jsonPrimitive?.contentOrNull ?: ""
                                result.add(name to desc)
                            }
                        } catch (e: Exception) {
                            log.warn("Failed to parse slash commands JSON: ${e.message}")
                        }
                    }
                }
            }

            process.waitFor()
            result
        } catch (e: Exception) {
            log.warn("Failed to fetch slash commands: ${e.message}")
            emptyList()
        }
    }

    fun abort() {
        stdinWriter = null
        currentProcess?.destroyForcibly()
        currentProcess = null
    }

    fun close() {
        abort()
    }

    fun resetSession() {
        sessionId = null
    }
}
