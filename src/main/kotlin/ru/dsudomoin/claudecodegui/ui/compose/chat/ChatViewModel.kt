package ru.dsudomoin.claudecodegui.ui.compose.chat

import ru.dsudomoin.claudecodegui.bridge.SetupStatus
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.ui.approval.ToolApprovalRequest
import ru.dsudomoin.claudecodegui.ui.compose.dialog.QuestionData
import ru.dsudomoin.claudecodegui.ui.compose.input.AttachedImageData
import ru.dsudomoin.claudecodegui.ui.compose.input.ContextUsageData
import ru.dsudomoin.claudecodegui.ui.compose.input.FileContextData
import ru.dsudomoin.claudecodegui.ui.compose.input.MentionChipData
import ru.dsudomoin.claudecodegui.ui.compose.input.MentionSuggestionData
import ru.dsudomoin.claudecodegui.ui.status.FileChangeSummary
import ru.dsudomoin.claudecodegui.ui.status.SubagentInfo
import ru.dsudomoin.claudecodegui.ui.status.TodoItem
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities

/**
 * Observable state holder for the chat UI.
 *
 * Uses plain Kotlin properties with a listener callback pattern —
 * no `kotlinx.coroutines.flow` and no `androidx.compose.runtime` types.
 * This avoids classloader conflicts between the plugin and the
 * `intellij.platform.compose` module.
 *
 * In the Compose layer, a bridge composable observes changes via
 * [addListener] and copies values into local `mutableStateOf`.
 */
class ChatViewModel {
    private val log = Logger.getInstance(ChatViewModel::class.java)

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** Always notify on EDT — listeners update Compose state which requires EDT. */
    private fun notifyListeners() {
        if (SwingUtilities.isEventDispatchThread()) {
            listeners.forEach { it() }
        } else {
            SwingUtilities.invokeLater { listeners.forEach { it() } }
        }
    }

    // ── Setup State ─────────────────────────────────────────────────────────

    var setupStatus: SetupStatus? = null
        set(value) { field = value; notifyListeners() }
    var setupInstalling: Boolean = false
        set(value) { field = value; notifyListeners() }
    var setupError: String? = null
        set(value) { field = value; notifyListeners() }

    // ── SDK Update State ──────────────────────────────────────────────────

    var sdkCurrentVersion: String? = null
        set(value) { field = value; notifyListeners() }
    var sdkLatestVersion: String? = null
        set(value) { field = value; notifyListeners() }
    var sdkUpdateAvailable: Boolean = false
        set(value) { field = value; notifyListeners() }
    var sdkUpdating: Boolean = false
        set(value) { field = value; notifyListeners() }
    var sdkUpdateError: String? = null
        set(value) { field = value; notifyListeners() }

    // ── Messages ─────────────────────────────────────────────────────────────

    var messages: List<Message> = emptyList()
        set(value) { field = value; notifyListeners() }

    // ── Streaming State ──────────────────────────────────────────────────────

    var isSending: Boolean = false
        set(value) { field = value; notifyListeners() }
    var isStreaming: Boolean = false
        set(value) { field = value; notifyListeners() }
    var streamingThinkingText: String = ""
        set(value) { field = value; notifyListeners() }
    var streamingResponseText: String = ""
        set(value) { field = value; notifyListeners() }
    var streamingContentFlow: List<ContentFlowItem> = emptyList()
        set(value) { field = value; notifyListeners() }
    var thinkingCollapsed: Boolean = false
        set(value) { field = value; notifyListeners() }

    // ── Input Panel State ────────────────────────────────────────────────────

    var inputText: String = ""
        set(value) { field = value; notifyListeners() }
    var selectedModelName: String = "Sonnet 4.6"
        set(value) { field = value; notifyListeners() }
    var selectedModelId: String = "claude-sonnet-4-6"
        set(value) { field = value; notifyListeners() }
    var modeLabel: String = "Default"
        set(value) { field = value; notifyListeners() }
    var selectedModeId: String = "default"
        set(value) { field = value; notifyListeners() }
    var isInputExpanded: Boolean = false
        set(value) { field = value; notifyListeners() }
    var contextUsage: ContextUsageData = ContextUsageData()
        set(value) { field = value; notifyListeners() }
    var fileContext: FileContextData? = null
        set(value) { field = value; notifyListeners() }

    var attachedImages: List<AttachedImageData> = emptyList()
        set(value) { field = value; notifyListeners() }
    var mentionedFiles: List<MentionChipData> = emptyList()
        set(value) { field = value; notifyListeners() }

    // ── Mention Popup State ─────────────────────────────────────────────────

    var mentionPopupVisible: Boolean = false
        set(value) { field = value; notifyListeners() }
    var mentionSuggestions: List<MentionSuggestionData> = emptyList()
        set(value) { field = value; notifyListeners() }

    // ── Status Panel State ───────────────────────────────────────────────────

    var todos: List<TodoItem> = emptyList()
        set(value) { field = value; notifyListeners() }
    var fileChanges: List<FileChangeSummary> = emptyList()
        set(value) { field = value; notifyListeners() }
    var agents: List<SubagentInfo> = emptyList()
        set(value) { field = value; notifyListeners() }

    // ── Queue ────────────────────────────────────────────────────────────────

    var queueItems: List<QueueItemDisplayData> = emptyList()
        set(value) { field = value; notifyListeners() }

    // ── Overlay State ────────────────────────────────────────────────────────

    var questionPanelVisible: Boolean = false
        set(value) { field = value; notifyListeners() }
    var currentQuestions: List<QuestionData> = emptyList()
        set(value) { field = value; notifyListeners() }
    var planPanelVisible: Boolean = false
        set(value) { field = value; notifyListeners() }
    var planMarkdown: String = ""
        set(value) {
            log.info("planMarkdown SET: length=${value.length}, blank=${value.isBlank()}, preview='${value.take(200)}'")
            field = value; notifyListeners()
        }

    // ── Prompt Enhancer State ───────────────────────────────────────────────

    var enhancerVisible: Boolean = false
        set(value) { field = value; notifyListeners() }
    var enhancerOriginalText: String = ""
        set(value) { field = value; notifyListeners() }
    var enhancerEnhancedText: String = ""
        set(value) { field = value; notifyListeners() }
    var enhancerLoading: Boolean = false
        set(value) { field = value; notifyListeners() }
    var enhancerError: String? = null
        set(value) { field = value; notifyListeners() }

    // ── Permission / Approval ─────────────────────────────────────────────────

    /** Currently pending approval request (null = none). */
    var pendingApproval: ToolApprovalRequest? = null
        set(value) { field = value; notifyListeners() }

    // ── History ──────────────────────────────────────────────────────────────

    var showingHistory: Boolean = false
        set(value) { field = value; notifyListeners() }

    var historySessions: List<ru.dsudomoin.claudecodegui.core.session.SessionInfo> = emptyList()
        set(value) { field = value; notifyListeners() }

    // ── Session Title (feature 58) ──────────────────────────────────────────

    var sessionTitle: String = ""
        set(value) { field = value; notifyListeners() }
    var sessionTitleVisible: Boolean = false
        set(value) { field = value; notifyListeners() }

    // ── Status Panel Visibility (feature 60) ────────────────────────────────

    var statusPanelVisible: Boolean = true
        set(value) { field = value; notifyListeners() }

    // ── Settings (streaming / thinking toggles) ──────────────────────────────

    var streamingEnabled: Boolean = true
        set(value) { field = value; notifyListeners() }
    var thinkingEnabled: Boolean = true
        set(value) { field = value; notifyListeners() }

    // ── Input History (feature 59) ──────────────────────────────────────────

    /** History of sent messages (most recent last). Not observed — read only during key events. */
    val inputHistory: MutableList<String> = mutableListOf()

    fun addToHistory(text: String) {
        if (text.isNotBlank()) {
            inputHistory.add(text)
            if (inputHistory.size > 100) inputHistory.removeAt(0)
        }
    }

    // ── Scroll ───────────────────────────────────────────────────────────────

    /** Incremented to trigger scroll-to-bottom in the LazyColumn. */
    var scrollToBottomTrigger: Int = 0
        private set

    fun requestScrollToBottom() {
        scrollToBottomTrigger++
        notifyListeners()
    }
}

/** Display data for a queued message (decoupled from ChatPanel.QueuedMessage). */
data class QueueItemDisplayData(
    val text: String,
    val imageCount: Int,
    val index: Int,
)
