package ru.dsudomoin.claudecodegui.core.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import ru.dsudomoin.claudecodegui.core.model.Message

/**
 * Project-level service for accessing CLI session storage.
 * Sessions are managed by the SDK — we only read/list/delete.
 */
@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) {

    private val projectPath: String
        get() = project.basePath ?: project.name

    /** List all saved sessions (newest first). */
    fun listSessions(): List<SessionInfo> =
        SessionStorage.listSessions(projectPath)

    /** Load session messages from CLI JSONL. */
    fun load(sessionId: String): List<Message>? =
        SessionStorage.load(projectPath, sessionId)

    /** Delete a session. */
    fun delete(sessionId: String) =
        SessionStorage.delete(projectPath, sessionId)

    /** Get session title (slug or first user message). */
    fun getTitle(sessionId: String): String? =
        SessionStorage.getTitle(projectPath, sessionId)

    /** Get usage from the last assistant message (inputTokens, cacheCreation, cacheRead). */
    fun getLastUsage(sessionId: String): Triple<Int, Int, Int>? =
        SessionStorage.getLastUsage(projectPath, sessionId)

    companion object {
        fun getInstance(project: Project): SessionManager =
            project.getService(SessionManager::class.java)
    }
}
