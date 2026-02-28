package ru.dsudomoin.claudecodegui.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.messages.Topic

@Service(Service.Level.APP)
@State(
    name = "ClaudeCodeGuiSettings",
    storages = [Storage("claudeCodeGui.xml")]
)
class SettingsService : PersistentStateComponent<SettingsService.State> {

    data class State(
        var claudeBaseUrl: String = "https://api.anthropic.com",
        var claudeModel: String = "claude-opus-4-6",
        var maxTokens: Int = 32000,
        var systemPrompt: String = "",
        var nodePath: String = "",
        var claudeExecutablePath: String = "",
        var permissionMode: String = "default",
        var streamingEnabled: Boolean = true,
        var thinkingEnabled: Boolean = true,
        /** Effort level for Claude reasoning: low, medium, high, max */
        var effort: String = "high",
        /** Max budget in USD per query, empty = unlimited */
        var maxBudgetUsd: String = "",
        /** Enable 1M context window beta */
        var betaContext1m: Boolean = false,
        var themePresetId: String = "default",
        var customColorOverrides: Map<String, String> = emptyMap(),
        /** Per-session custom titles: key = "<projectLocationHash>::<sessionId>" */
        var sessionTitleOverrides: Map<String, String> = emptyMap(),
        /** Comma-separated list of tools to explicitly allow (e.g. "Read,Grep") */
        var allowedTools: String = "",
        /** Comma-separated list of tools to disallow (e.g. "Bash,Write") */
        var disallowedTools: String = "",
        /** Continue conversation without explicit prompt continuation marker */
        var continueSession: Boolean = false,
        /** Output format JSON passed to SDK (e.g. {"type":"json","schema":{...}}) */
        var outputFormatJson: String = "",
        /** Enable file checkpointing for rewind support */
        var enableFileCheckpointing: Boolean = false,
        /** MCP servers configuration as JSON: {"name": {"command":"...", "args":[...], "env":{...}}} */
        var mcpServersJson: String = "",
        /** Fallback model when primary model fails */
        var fallbackModel: String = "",
        /** Additional directories Claude can access (comma-separated) */
        var additionalDirectories: String = "",
        /** Custom agents definition as JSON: {"name": {"description":"...", "prompt":"...", "tools":[...]}} */
        var agentsJson: String = "",
        /** Hooks configuration as JSON: {"PreToolUse": [{"matcher":"...", "command":"..."}], ...} */
        var hooksJson: String = "",
        /** Don't persist session to disk */
        var ephemeralSessions: Boolean = false,
        /** Fork session on resume (create branch without modifying original) */
        var forkSessionOnResume: Boolean = false,
        /** Sandbox settings as JSON: {"allowedPaths":[...], "blockedPaths":[...]} */
        var sandboxJson: String = "",
        /** SDK plugins configuration as JSON array: [{"name":"...", "path":"..."}] */
        var pluginsJson: String = "",
        /** Plugin UI language: "" = IDE default, "en" = English, "ru" = Russian */
        var language: String = ""
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(): SettingsService =
            ApplicationManager.getApplication().getService(SettingsService::class.java)
    }
}

/** Fired when plugin UI language changes. */
fun interface LanguageChangeListener {
    fun onLanguageChanged()

    companion object {
        @JvmField
        val TOPIC = Topic.create("ClaudeCodeGui.LanguageChanged", LanguageChangeListener::class.java)
    }
}
