package ru.dsudomoin.claudecodegui.core.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import ru.dsudomoin.claudecodegui.core.model.Message
import java.util.UUID

/**
 * Project-level service managing chat sessions.
 * Provides CRUD operations and delegates persistence to [SessionStorage].
 */
@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) {

    private val projectPath: String
        get() = project.basePath ?: project.name

    /** Create a new session and return its ID. */
    fun createSession(): String = UUID.randomUUID().toString()

    /** Save session messages. */
    fun save(sessionId: String, messages: List<Message>, title: String? = null, usedTokens: Int = 0) {
        val resolvedTitle = title ?: generateTitle(messages)
        SessionStorage.save(projectPath, sessionId, messages, resolvedTitle, usedTokens)
    }

    /** Load session messages. */
    fun load(sessionId: String): List<Message>? {
        return SessionStorage.load(projectPath, sessionId)
    }

    /** Get saved token usage for a session. */
    fun getUsedTokens(sessionId: String): Int {
        return SessionStorage.getUsedTokens(projectPath, sessionId)
    }

    /** List all saved sessions (newest first). */
    fun listSessions(): List<SessionInfo> {
        return SessionStorage.listSessions(projectPath)
    }

    /** Delete a session. */
    fun delete(sessionId: String) {
        SessionStorage.delete(projectPath, sessionId)
    }

    /** Get session title from metadata. */
    fun getTitle(sessionId: String): String? {
        return SessionStorage.getTitle(projectPath, sessionId)
    }

    /** Update session title. */
    fun updateTitle(sessionId: String, title: String) {
        SessionStorage.updateTitle(projectPath, sessionId, title)
    }

    /** Generate a title from the first user message. */
    private fun generateTitle(messages: List<Message>): String {
        val firstUserMsg = messages.firstOrNull {
            it.role == ru.dsudomoin.claudecodegui.core.model.Role.USER
        } ?: return "New Chat"
        val text = firstUserMsg.textContent
            .lineSequence()
            .firstOrNull { it.isNotBlank() && !it.startsWith("_") }
            ?.take(60)
            ?: return "New Chat"
        return if (text.length >= 60) "${text.take(57)}..." else text.ifBlank { "New Chat" }
    }

    companion object {
        fun getInstance(project: Project): SessionManager =
            project.getService(SessionManager::class.java)
    }
}
