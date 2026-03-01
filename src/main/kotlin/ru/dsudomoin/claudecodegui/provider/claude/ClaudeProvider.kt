package ru.dsudomoin.claudecodegui.provider.claude

import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.io.awaitExit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
import ru.dsudomoin.claudecodegui.service.SettingsService
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.TimeUnit

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

    /** Send an elicitation response back to the bridge process via stdin. */
    fun sendElicitationResponse(allow: Boolean, value: JsonElement? = null) {
        try {
            val response = buildJsonObject {
                put("allow", allow)
                if (allow && value != null) put("value", value)
                if (!allow) put("message", "User cancelled elicitation")
            }
            stdinWriter?.let { writer ->
                writer.write(response.toString())
                writer.newLine()
                writer.flush()
            }
        } catch (e: Exception) {
            log.warn("Failed to send elicitation response: ${e.message}")
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

        val settings = SettingsService.getInstance().state

        val payload = buildJsonObject {
            put("command", "send")
            put("message", lastUserMessage.textContent)
            put("cwd", cwd)
            put("permissionMode", permissionMode.ifBlank { "default" })
            if (model.isNotBlank()) put("model", model)
            if (!systemPrompt.isNullOrBlank()) put("systemPrompt", systemPrompt)
            put("streaming", streaming)
            sessionId?.let { put("sessionId", it) }
            if (settings.effort.isNotBlank()) put("effort", settings.effort)
            if (settings.maxBudgetUsd.isNotBlank()) put("maxBudgetUsd", settings.maxBudgetUsd)
            if (settings.betaContext1m) put("betaContext1m", true)
            if (settings.allowedTools.isNotBlank()) put("allowedTools", settings.allowedTools)
            if (settings.disallowedTools.isNotBlank()) put("disallowedTools", settings.disallowedTools)
            if (settings.continueSession) put("continueSession", true)
            if (settings.outputFormatJson.isNotBlank()) {
                try {
                    put("outputFormat", Json.parseToJsonElement(settings.outputFormatJson))
                } catch (_: Exception) { }
            }
            if (settings.enableFileCheckpointing) put("enableFileCheckpointing", true)
            if (settings.mcpServersJson.isNotBlank()) {
                try {
                    put("mcpServers", Json.parseToJsonElement(settings.mcpServersJson))
                } catch (_: Exception) { }
            }
            if (settings.fallbackModel.isNotBlank()) put("fallbackModel", settings.fallbackModel)
            if (settings.additionalDirectories.isNotBlank()) {
                put("additionalDirectories", kotlinx.serialization.json.JsonArray(
                    settings.additionalDirectories.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        .map { kotlinx.serialization.json.JsonPrimitive(it) }
                ))
            }
            if (settings.agentsJson.isNotBlank()) {
                try { put("agents", Json.parseToJsonElement(settings.agentsJson)) } catch (_: Exception) { }
            }
            if (settings.hooksJson.isNotBlank()) {
                try { put("hooks", Json.parseToJsonElement(settings.hooksJson)) } catch (_: Exception) { }
            }
            if (settings.ephemeralSessions) put("persistSession", false)
            if (settings.forkSessionOnResume) put("forkSession", true)
            if (settings.sandboxJson.isNotBlank()) {
                try { put("sandbox", Json.parseToJsonElement(settings.sandboxJson)) } catch (_: Exception) { }
            }
            if (settings.pluginsJson.isNotBlank()) {
                try { put("plugins", Json.parseToJsonElement(settings.pluginsJson)) } catch (_: Exception) { }
            }
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

                val stderrLines = java.util.concurrent.CopyOnWriteArrayList<String>()
                var receivedError = false

                coroutineScope {
                    val stderrReaderJob = launch(Dispatchers.IO) {
                        try {
                            process.errorStream.bufferedReader().use { stderrReader ->
                                var stderrLine: String?
                                while (stderrReader.readLine().also { stderrLine = it } != null) {
                                    stderrLine?.let {
                                        log.info("Bridge: $it")
                                        stderrLines.add(it)
                                    }
                                }
                            }
                        } catch (_: Exception) {
                            // Process may exit abruptly/cancel; safe to ignore.
                        }
                    }

                    try {
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
                    } finally {
                        runCatching { writer.close() }
                        stdinWriter = null
                        runCatching { process.inputStream.close() }
                        runCatching { process.errorStream.close() }
                        stderrReaderJob.cancel()
                        withTimeoutOrNull(1000) { stderrReaderJob.join() }
                    }
                }

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
            abort()
            throw e
        } catch (e: Exception) {
            abort()
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
        val cwd = projectDir ?: System.getProperty("user.dir")
        val localCommands = discoverLocalSlashCommands(cwd)
        try {
            if (!BridgeManager.isReady) {
                val ready = BridgeManager.ensureReady()
                if (!ready) return@withContext localCommands
            } else {
                BridgeManager.ensureScriptsUpdated()
            }

            val nodePath = NodeDetector.detect() ?: return@withContext localCommands

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
                                val pair = when {
                                    elem is kotlinx.serialization.json.JsonObject -> {
                                        val name = elem["name"]?.jsonPrimitive?.contentOrNull
                                            ?: elem["command"]?.jsonPrimitive?.contentOrNull
                                            ?: elem["id"]?.jsonPrimitive?.contentOrNull
                                        val desc = elem["description"]?.jsonPrimitive?.contentOrNull ?: ""
                                        if (name.isNullOrBlank()) null else name to desc
                                    }
                                    elem is kotlinx.serialization.json.JsonPrimitive -> {
                                        val name = elem.contentOrNull
                                        if (name.isNullOrBlank()) null else name to ""
                                    }
                                    else -> null
                                }
                                if (pair != null) result.add(pair)
                            }
                        } catch (e: Exception) {
                            log.warn("Failed to parse slash commands JSON: ${e.message}")
                        }
                    }
                }
            }

            process.waitFor()
            mergeSlashCommands(result, localCommands)
        } catch (e: Exception) {
            log.warn("Failed to fetch slash commands: ${e.message}")
            localCommands
        }
    }

    private fun mergeSlashCommands(
        sdkCommands: List<Pair<String, String>>,
        localCommands: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        val byName = LinkedHashMap<String, Pair<String, String>>()

        fun putAll(commands: List<Pair<String, String>>) {
            for ((rawName, rawDesc) in commands) {
                val normalizedName = normalizeSlashName(rawName) ?: continue
                val key = normalizedName.lowercase()
                val desc = rawDesc.trim()
                val existing = byName[key]
                if (existing == null) {
                    byName[key] = normalizedName to desc
                } else if (existing.second.isBlank() && desc.isNotBlank()) {
                    byName[key] = existing.first to desc
                }
            }
        }

        putAll(sdkCommands)
        putAll(localCommands)
        return byName.values.toList()
    }

    private fun normalizeSlashName(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null
        return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
    }

    private fun discoverLocalSlashCommands(cwd: String): List<Pair<String, String>> {
        val home = System.getProperty("user.home")
        val candidates = linkedSetOf<File>()
        if (!home.isNullOrBlank()) {
            candidates += File(home, ".claude/skills")
            candidates += File(home, ".claude/commands")
        }
        candidates += File(cwd, ".claude/skills")
        candidates += File(cwd, ".claude/commands")

        val found = LinkedHashMap<String, Pair<String, String>>()

        fun put(name: String, description: String) {
            val normalized = normalizeSlashName(name) ?: return
            val key = normalized.lowercase()
            val desc = description.trim()
            val existing = found[key]
            if (existing == null || (existing.second.isBlank() && desc.isNotBlank())) {
                found[key] = normalized to desc
            }
        }

        for (root in candidates) {
            if (!root.exists() || !root.isDirectory) continue

            if (root.name == "skills") {
                root.listFiles()
                    ?.filter { it.isDirectory }
                    ?.forEach { skillDir ->
                        val skillName = skillDir.name.trim()
                        if (skillName.isBlank()) return@forEach
                        val skillMd = File(skillDir, "SKILL.md")
                        if (!skillMd.isFile) return@forEach
                        val desc = readMarkdownSummary(skillMd) ?: "Local skill"
                        put("/$skillName", desc)
                    }
            } else if (root.name == "commands") {
                root.walkTopDown()
                    .onEnter { file -> file == root || file.name != ".git" }
                    .forEach { file ->
                        if (!file.isFile) return@forEach
                        val ext = file.extension.lowercase()
                        if (ext != "md" && ext != "markdown") return@forEach
                        val cmdName = file.nameWithoutExtension.trim()
                        if (cmdName.isBlank()) return@forEach
                        val desc = readMarkdownSummary(file) ?: "Local command"
                        put("/$cmdName", desc)
                    }
            }
        }

        return found.values.toList()
    }

    private fun readMarkdownSummary(file: File): String? {
        return runCatching {
            file.useLines { lines ->
                lines.map { it.trim() }
                    .firstOrNull { line ->
                        line.isNotBlank() &&
                            line != "---" &&
                            !line.startsWith("#") &&
                            !line.startsWith("```")
                    }
            }
        }.getOrNull()
    }

    fun abort() {
        val writer = stdinWriter
        val process = currentProcess
        stdinWriter = null
        currentProcess = null

        if (writer != null && process != null && process.isAlive) {
            // Send graceful abort command to bridge
            try {
                val abortCmd = buildJsonObject { put("command", "abort") }
                writer.write(abortCmd.toString())
                writer.newLine()
                writer.flush()
                log.info("Sent abort command to bridge")
            } catch (e: Exception) {
                log.warn("Failed to send abort command: ${e.message}")
            }

            runCatching { writer.close() }
            try {
                val exited = process.waitFor(1500, TimeUnit.MILLISECONDS)
                if (!exited && process.isAlive) {
                    process.destroy()
                }
                val exitedAfterDestroy = !process.isAlive || process.waitFor(1000, TimeUnit.MILLISECONDS)
                if (!exitedAfterDestroy && process.isAlive) {
                    log.warn("Bridge did not exit after abort, force killing")
                    process.destroyForcibly()
                    process.waitFor(1000, TimeUnit.MILLISECONDS)
                }
            } catch (_: Exception) {
                process.destroyForcibly()
            }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
            return
        }

        runCatching { writer?.close() }
        process?.let {
            runCatching { it.destroyForcibly() }
            runCatching { it.inputStream.close() }
            runCatching { it.errorStream.close() }
            runCatching { it.outputStream.close() }
        }
    }

    fun stopTask(taskId: String): Boolean {
        return sendBridgeCommand(buildJsonObject {
            put("command", "stopTask")
            put("taskId", taskId)
        })
    }

    fun rewindFiles(userMessageId: String): Boolean {
        return sendBridgeCommand(buildJsonObject {
            put("command", "rewindFiles")
            put("userMessageId", userMessageId)
        })
    }

    fun setModel(model: String): Boolean {
        return sendBridgeCommand(buildJsonObject {
            put("command", "setModel")
            put("model", model)
        })
    }

    fun setPermissionMode(mode: String): Boolean {
        return sendBridgeCommand(buildJsonObject {
            put("command", "setPermissionMode")
            put("mode", mode)
        })
    }

    private fun sendBridgeCommand(command: kotlinx.serialization.json.JsonObject): Boolean {
        val writer = stdinWriter
        val process = currentProcess
        if (writer != null && process != null && process.isAlive) {
            try {
                writer.write(command.toString())
                writer.newLine()
                writer.flush()
                log.info("Sent bridge command: ${command["command"]}")
                return true
            } catch (e: Exception) {
                log.warn("Failed to send bridge command: ${e.message}")
                return false
            }
        }
        return false
    }

    fun close() {
        abort()
    }

    fun resetSession() {
        sessionId = null
    }
}
