package ru.dsudomoin.claudecodegui.core.session

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.service.SettingsService

/**
 * Project-level service for accessing CLI session storage.
 * Sessions are managed by the SDK — we only read/list/delete.
 */
@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) {

    private val projectPath: String
        get() = project.basePath ?: project.name

    private val projectKey: String
        get() = project.locationHash

    private fun overrideKey(sessionId: String): String = "$projectKey::$sessionId"

    private val projectPathWithUserHomeMacro: String
        get() {
            val home = System.getProperty("user.home").orEmpty()
            if (home.isBlank()) return projectPath
            return if (projectPath.startsWith(home)) {
                "\$USER_HOME$${projectPath.removePrefix(home)}"
            } else {
                projectPath
            }
        }

    private fun getTitleOverride(sessionId: String): String? {
        val settings = SettingsService.getInstance()
        val state = settings.state
        val key = overrideKey(sessionId)
        state.sessionTitleOverrides[key]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }

        // Backward compatibility: previously key used project path, which may be macro-expanded in XML.
        val suffix = "::$sessionId"
        val legacyCandidates = linkedSetOf(
            "$projectPath::$sessionId",
            "$projectPathWithUserHomeMacro::$sessionId",
        )
        val fallback = state.sessionTitleOverrides.entries.firstOrNull {
            it.key in legacyCandidates || it.key.endsWith(suffix)
        } ?: return null

        val normalized = fallback.value.trim()
        if (normalized.isBlank()) return null

        // Migrate to stable locationHash key so title survives IDE restarts reliably.
        val map = state.sessionTitleOverrides.toMutableMap()
        map[key] = normalized
        map.keys.filter { it.endsWith(suffix) && it != key }.forEach { map.remove(it) }
        state.sessionTitleOverrides = map
        return normalized
    }

    fun setCustomTitle(sessionId: String, title: String) {
        val settings = SettingsService.getInstance()
        val state = settings.state
        val map = state.sessionTitleOverrides.toMutableMap()
        val key = overrideKey(sessionId)
        val normalized = title.trim()
        val suffix = "::$sessionId"

        // Drop all legacy/duplicate keys for this session.
        map.keys.filter { it.endsWith(suffix) && it != key }.forEach { map.remove(it) }
        if (normalized.isBlank()) {
            map.remove(key)
        } else {
            map[key] = normalized
        }
        state.sessionTitleOverrides = map
    }

    fun clearCustomTitle(sessionId: String) {
        val settings = SettingsService.getInstance()
        val state = settings.state
        val suffix = "::$sessionId"
        val keysToRemove = state.sessionTitleOverrides.keys.filter { it.endsWith(suffix) }
        if (keysToRemove.isNotEmpty()) {
            val map = state.sessionTitleOverrides.toMutableMap()
            keysToRemove.forEach { map.remove(it) }
            state.sessionTitleOverrides = map
        }
    }

    /**
     * List all saved sessions (newest first).
     * Tries SDK-native listing first (richer metadata), falls back to JSONL scanning.
     */
    fun listSessions(): List<SessionInfo> =
        (SessionStorage.listSessionsViaSdk(projectPath)
            ?: SessionStorage.listSessions(projectPath)
            ).map { info ->
                val override = getTitleOverride(info.sessionId)
                if (override.isNullOrBlank()) info
                else info.copy(title = override, slug = override)
            }

    /** Load session messages via SDK API first, then fallback to CLI JSONL. */
    fun load(sessionId: String): List<Message>? =
        SessionStorage.loadViaSdk(projectPath, sessionId)
            ?: SessionStorage.load(projectPath, sessionId)

    /** Delete a session. */
    fun delete(sessionId: String) {
        SessionStorage.delete(projectPath, sessionId)
        clearCustomTitle(sessionId)
    }

    /** Get session title (slug or first user message). */
    fun getTitle(sessionId: String): String? =
        getTitleOverride(sessionId) ?: SessionStorage.getTitle(projectPath, sessionId)

    /** Get usage from the last assistant message (inputTokens, cacheCreation, cacheRead). */
    fun getLastUsage(sessionId: String): Triple<Int, Int, Int>? =
        SessionStorage.getLastUsage(projectPath, sessionId)

    companion object {
        fun getInstance(project: Project): SessionManager =
            project.getService(SessionManager::class.java)
    }
}
