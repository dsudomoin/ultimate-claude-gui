package ru.dsudomoin.claudecodegui.core.session

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ru.dsudomoin.claudecodegui.core.model.Message
import java.io.File
import java.security.MessageDigest

/**
 * Low-level JSONL persistence for chat sessions.
 *
 * Storage layout:
 *   ~/.claude-code-gui/sessions/<project-hash>/<sessionId>.jsonl
 *
 * Each line in the JSONL file is a serialized [Message].
 * A companion .meta file stores session metadata (title, createdAt).
 */
object SessionStorage {

    private val log = Logger.getInstance(SessionStorage::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val baseDir: File
        get() = File(System.getProperty("user.home"), ".claude-code-gui/sessions")

    fun projectDir(projectPath: String): File {
        val hash = projectPath.md5().take(12)
        val safeName = projectPath.substringAfterLast('/').take(30).replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(baseDir, "${safeName}_$hash").also { it.mkdirs() }
    }

    /** Save all messages for a session (overwrites). */
    fun save(projectPath: String, sessionId: String, messages: List<Message>, title: String?, usedTokens: Int = 0) {
        val dir = projectDir(projectPath)
        val file = File(dir, "$sessionId.jsonl")
        try {
            file.bufferedWriter().use { writer ->
                messages.forEach { msg ->
                    writer.write(json.encodeToString(msg))
                    writer.newLine()
                }
            }
            // Save metadata
            if (title != null) {
                val metaFile = File(dir, "$sessionId.meta")
                metaFile.writeText(json.encodeToString(SessionMeta(title = title, messageCount = messages.size, usedTokens = usedTokens)))
            }
        } catch (e: Exception) {
            log.warn("Failed to save session $sessionId: ${e.message}")
        }
    }

    /** Load all messages for a session. */
    fun load(projectPath: String, sessionId: String): List<Message>? {
        val file = File(projectDir(projectPath), "$sessionId.jsonl")
        if (!file.exists()) return null
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .map { json.decodeFromString<Message>(it) }
        } catch (e: Exception) {
            log.warn("Failed to load session $sessionId: ${e.message}")
            null
        }
    }

    /** List all sessions for a project, sorted by last modified (newest first). */
    fun listSessions(projectPath: String): List<SessionInfo> {
        val dir = projectDir(projectPath)
        if (!dir.exists()) return emptyList()

        return dir.listFiles { f -> f.extension == "jsonl" }
            ?.map { file ->
                val sid = file.nameWithoutExtension
                val meta = loadMeta(dir, sid)
                SessionInfo(
                    sessionId = sid,
                    title = meta?.title ?: generateTitleFromFile(file),
                    createdAt = file.lastModified(),
                    messageCount = meta?.messageCount ?: countLines(file)
                )
            }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()
    }

    /** Delete a session. */
    fun delete(projectPath: String, sessionId: String) {
        val dir = projectDir(projectPath)
        File(dir, "$sessionId.jsonl").delete()
        File(dir, "$sessionId.meta").delete()
    }

    /** Update session title. */
    fun updateTitle(projectPath: String, sessionId: String, title: String) {
        val dir = projectDir(projectPath)
        val meta = loadMeta(dir, sessionId) ?: SessionMeta(title = title)
        val metaFile = File(dir, "$sessionId.meta")
        metaFile.writeText(json.encodeToString(meta.copy(title = title)))
    }

    /** Get title for a session. */
    fun getTitle(projectPath: String, sessionId: String): String? {
        val dir = projectDir(projectPath)
        return loadMeta(dir, sessionId)?.title
    }

    /** Get saved token usage for a session. */
    fun getUsedTokens(projectPath: String, sessionId: String): Int {
        val dir = projectDir(projectPath)
        return loadMeta(dir, sessionId)?.usedTokens ?: 0
    }

    private fun loadMeta(dir: File, sessionId: String): SessionMeta? {
        val metaFile = File(dir, "$sessionId.meta")
        if (!metaFile.exists()) return null
        return try {
            json.decodeFromString<SessionMeta>(metaFile.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun generateTitleFromFile(file: File): String {
        return try {
            val firstLine = file.bufferedReader().use { it.readLine() } ?: return "New Chat"
            val msg = json.decodeFromString<Message>(firstLine)
            val text = msg.textContent.take(60)
            if (text.length >= 60) "${text.take(57)}..." else text.ifBlank { "New Chat" }
        } catch (_: Exception) {
            "New Chat"
        }
    }

    private fun countLines(file: File): Int {
        return try {
            file.bufferedReader().use { reader ->
                var count = 0
                while (reader.readLine() != null) count++
                count
            }
        } catch (_: Exception) { 0 }
    }

    private fun String.md5(): String {
        val digest = MessageDigest.getInstance("MD5")
        return digest.digest(toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@kotlinx.serialization.Serializable
data class SessionMeta(
    val title: String = "New Chat",
    val messageCount: Int = 0,
    val usedTokens: Int = 0
)

data class SessionInfo(
    val sessionId: String,
    val title: String,
    val createdAt: Long,
    val messageCount: Int
)
