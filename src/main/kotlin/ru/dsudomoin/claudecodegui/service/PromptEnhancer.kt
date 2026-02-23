package ru.dsudomoin.claudecodegui.service

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import ru.dsudomoin.claudecodegui.bridge.BridgeManager
import ru.dsudomoin.claudecodegui.bridge.NodeDetector

/**
 * Enhances user prompts by calling the Claude SDK bridge with a special system prompt.
 * Uses maxTurns=1 and plan mode to get a single text response quickly.
 */
object PromptEnhancer {

    private val log = Logger.getInstance(PromptEnhancer::class.java)

    private const val SYSTEM_PROMPT = """You are a prompt enhancer for a coding AI assistant (Claude Code).
Your task: rewrite the user's prompt to be more specific, detailed, and effective.

Rules:
- Keep the original intent and meaning intact
- Add specificity: mention expected output format, edge cases, constraints
- If the prompt references code, add context about what kind of changes are expected
- Return ONLY the enhanced prompt text — no explanations, no meta-commentary, no markdown wrapper
- Keep the same language as the original prompt (if Russian — respond in Russian, etc.)
- Do NOT add greetings or sign-offs
- The enhanced prompt should be 2-4x longer than the original, but not bloated"""

    /**
     * Enhances the given prompt text using the Claude SDK bridge.
     * Returns the enhanced prompt, or null on failure.
     */
    suspend fun enhance(
        originalPrompt: String,
        model: String = "claude-haiku-4-5-20251001",
        cwd: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (!BridgeManager.isReady) {
                val ready = BridgeManager.ensureReady()
                if (!ready) return@withContext null
            }

            val nodePath = NodeDetector.detect() ?: return@withContext null

            val payload = buildJsonObject {
                put("command", "send")
                put("message", "Enhance this prompt:\n\n$originalPrompt")
                put("cwd", cwd ?: System.getProperty("user.home"))
                put("permissionMode", "plan")
                put("model", model)
                put("maxTurns", 1)
                put("streaming", false)
                put("systemPrompt", SYSTEM_PROMPT)
            }

            val process = ProcessBuilder(nodePath, BridgeManager.bridgeScript.absolutePath)
                .directory(BridgeManager.bridgeScript.parentFile)
                .redirectErrorStream(false)
                .start()

            process.outputStream.bufferedWriter().use { writer ->
                writer.write(payload.toString())
                writer.newLine()
                writer.flush()
            }

            val responseText = StringBuilder()
            val tagPattern = Regex("""^\[([A-Z_]+)](.*)$""")

            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val match = tagPattern.matchEntire(line!!) ?: continue
                    val tag = match.groupValues[1]
                    val data = match.groupValues[2]

                    when (tag) {
                        "CONTENT_DELTA" -> {
                            // Data is JSON-encoded string
                            val text = try {
                                kotlinx.serialization.json.Json.decodeFromString<String>(data)
                            } catch (_: Exception) { data }
                            responseText.append(text)
                        }
                        "ERROR" -> {
                            log.warn("Enhance error: $data")
                            process.destroyForcibly()
                            return@withContext null
                        }
                        "STREAM_END" -> break
                    }
                }
            }

            process.waitFor()
            val result = responseText.toString().trim()
            if (result.isNotEmpty()) result else null
        } catch (e: Exception) {
            log.warn("Prompt enhancement failed", e)
            null
        }
    }
}
