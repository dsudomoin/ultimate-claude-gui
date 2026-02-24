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
        var themePresetId: String = "default",
        var customColorOverrides: Map<String, String> = emptyMap(),
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
