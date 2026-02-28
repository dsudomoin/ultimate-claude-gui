@file:OptIn(ExperimentalJewelApi::class)

package ru.dsudomoin.claudecodegui.ui.compose.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import ru.dsudomoin.claudecodegui.ui.compose.approval.ComposeApprovalPanel
import ru.dsudomoin.claudecodegui.ui.compose.dialog.ComposePlanActionPanel
import ru.dsudomoin.claudecodegui.ui.compose.dialog.ComposePromptEnhancerDialog
import ru.dsudomoin.claudecodegui.ui.compose.dialog.ComposeQuestionSelectionPanel
import ru.dsudomoin.claudecodegui.ui.compose.input.ComposeChatInputPanel
import ru.dsudomoin.claudecodegui.ui.compose.status.ComposeStatusPanel
import ru.dsudomoin.claudecodegui.ui.status.FileChangeSummary

/**
 * Top-level Compose chat panel — the Compose equivalent of ChatPanel.
 *
 * Layout:
 * ```
 * ┌─────────────────────────────────────┐
 * │                                     │
 * │        Message list (scrollable)    │
 * │                                     │
 * ├─────────────────────────────────────┤
 * │ Status panel (Todos/Files/Agents)   │
 * │ Queue panel                         │
 * │ Input panel / Question / Plan       │
 * └─────────────────────────────────────┘
 * ```
 *
 * All business logic (streaming, provider interaction, permission handling)
 * stays in the host. This composable is pure render + callbacks.
 */
@Composable
fun ComposeChatPanel(
    viewModel: ChatViewModel,
    // ── Input callbacks ──
    onSendOrStop: () -> Unit,
    onAttachClick: () -> Unit,
    onPasteImage: () -> Boolean = { false },
    onStreamingToggle: () -> Unit,
    onThinkingToggle: () -> Unit,
    onModelSelect: (String) -> Unit,
    onModeSelect: (String) -> Unit,
    onEnhanceClick: () -> Unit,
    onFileContextClick: (String) -> Unit,
    onFileContextRemove: () -> Unit,
    onImageClick: (String) -> Unit,
    onImageRemove: (String) -> Unit,
    onMentionRemove: (String) -> Unit,
    // ── Mention popup callbacks ──
    onMentionQuery: (String) -> Unit = {},
    onMentionSelect: (Int) -> Unit = {},
    onMentionDismiss: () -> Unit = {},
    // ── File navigation callback ──
    onFileClick: ((String) -> Unit)? = null,
    // ── Tool action callbacks ──
    onToolShowDiff: ((ExpandableContent) -> Unit)? = null,
    onToolRevert: ((ExpandableContent) -> Unit)? = null,
    // ── Status callbacks ──
    onStatusFileClick: (String) -> Unit,
    onStatusShowDiff: (FileChangeSummary) -> Unit,
    onStatusUndoFile: (FileChangeSummary) -> Unit,
    onStatusDiscardAll: () -> Unit,
    onStatusKeepAll: () -> Unit,
    // ── Queue callbacks ──
    onQueueRemove: (Int) -> Unit,
    // ── Question panel callbacks ──
    onQuestionSubmit: (Map<String, List<String>>) -> Unit,
    onQuestionCancel: () -> Unit,
    // ── Plan action callbacks ──
    onPlanApprove: () -> Unit,
    onPlanApproveCompact: () -> Unit,
    onPlanDeny: () -> Unit,
    // ── Approval callbacks ──
    onApprovalApprove: () -> Unit,
    onApprovalReject: () -> Unit,
    // ── Setup callbacks ──
    onInstallSdk: () -> Unit = {},
    onInstallNode: () -> Unit = {},
    onLogin: () -> Unit = {},
    onDownloadNode: () -> Unit = {},
    // ── SDK update callback ──
    onUpdateSdk: () -> Unit = {},
    // ── Title callback ──
    onTitleChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // ── Bridge: observe ChatViewModel via selective field listeners ──────────
    // Hot-path fields get individual mutableStateOf holders — only the changed
    // field triggers recomposition of composables that read it.
    // Cold-path fields (UI settings, setup, enhancer, etc.) share a single
    // revision counter — they change rarely and can afford a full re-read.

    // Hot-path state (updated selectively via field listener)
    var messages by remember { mutableStateOf(viewModel.messages) }
    var isStreaming by remember { mutableStateOf(viewModel.isStreaming) }
    var isSending by remember { mutableStateOf(viewModel.isSending) }
    var streamingThinkingText by remember { mutableStateOf(viewModel.streamingThinkingText) }
    var streamingResponseText by remember { mutableStateOf(viewModel.streamingResponseText) }
    var streamingContentFlow by remember { mutableStateOf(viewModel.streamingContentFlow) }
    var thinkingCollapsed by remember { mutableStateOf(viewModel.thinkingCollapsed) }
    var scrollToBottomTrigger by remember { mutableIntStateOf(viewModel.scrollToBottomTrigger) }
    var forceScrollToBottomTrigger by remember { mutableIntStateOf(viewModel.forceScrollToBottomTrigger) }
    var todos by remember { mutableStateOf(viewModel.todos) }
    var fileChanges by remember { mutableStateOf(viewModel.fileChanges) }
    var agents by remember { mutableStateOf(viewModel.agents) }

    // Cold-path revision counter (for all other, rarely-changing properties)
    var coldRevision by remember { mutableIntStateOf(0) }

    DisposableEffect(viewModel) {
        val fieldListener: (ChatViewModel.Field) -> Unit = { field ->
            when (field) {
                ChatViewModel.Field.MESSAGES -> messages = viewModel.messages
                ChatViewModel.Field.IS_STREAMING -> isStreaming = viewModel.isStreaming
                ChatViewModel.Field.IS_SENDING -> isSending = viewModel.isSending
                ChatViewModel.Field.STREAMING_THINKING -> streamingThinkingText = viewModel.streamingThinkingText
                ChatViewModel.Field.STREAMING_RESPONSE -> streamingResponseText = viewModel.streamingResponseText
                ChatViewModel.Field.STREAMING_CONTENT_FLOW -> streamingContentFlow = viewModel.streamingContentFlow
                ChatViewModel.Field.THINKING_COLLAPSED -> thinkingCollapsed = viewModel.thinkingCollapsed
                ChatViewModel.Field.SCROLL_TRIGGER -> scrollToBottomTrigger = viewModel.scrollToBottomTrigger
                ChatViewModel.Field.FORCE_SCROLL_TRIGGER -> forceScrollToBottomTrigger = viewModel.forceScrollToBottomTrigger
                ChatViewModel.Field.TODOS -> todos = viewModel.todos
                ChatViewModel.Field.FILE_CHANGES -> fileChanges = viewModel.fileChanges
                ChatViewModel.Field.AGENTS -> agents = viewModel.agents
            }
        }
        val coldListener: () -> Unit = { coldRevision++ }
        viewModel.addFieldListener(fieldListener)
        viewModel.addListener(coldListener)
        onDispose {
            viewModel.removeFieldListener(fieldListener)
            viewModel.removeListener(coldListener)
        }
    }

    // Cold-path properties: re-read only when coldRevision changes.
    @Suppress("UNUSED_EXPRESSION") coldRevision
    val inputText = viewModel.inputText
    val selectedModelName = viewModel.selectedModelName
    val selectedModelId = viewModel.selectedModelId
    val selectedModeId = viewModel.selectedModeId
    val modeLabel = viewModel.modeLabel
    val streamingEnabled = viewModel.streamingEnabled
    val thinkingEnabled = viewModel.thinkingEnabled
    val contextUsage = viewModel.contextUsage
    val fileContext = viewModel.fileContext
    val attachedImages = viewModel.attachedImages
    val mentionedFiles = viewModel.mentionedFiles
    val mentionPopupVisible = viewModel.mentionPopupVisible
    val mentionSuggestions = viewModel.mentionSuggestions
    val queueItems = viewModel.queueItems
    val questionPanelVisible = viewModel.questionPanelVisible
    val currentQuestions = viewModel.currentQuestions
    val planPanelVisible = viewModel.planPanelVisible
    val planMarkdown = viewModel.planMarkdown
    val pendingApproval = viewModel.pendingApproval
    val setupStatus = viewModel.setupStatus
    val setupInstalling = viewModel.setupInstalling
    val setupError = viewModel.setupError
    val sdkCurrentVersion = viewModel.sdkCurrentVersion
    val sdkLatestVersion = viewModel.sdkLatestVersion
    val sdkUpdateAvailable = viewModel.sdkUpdateAvailable
    val sdkUpdating = viewModel.sdkUpdating
    val sdkUpdateError = viewModel.sdkUpdateError
    val enhancerVisible = viewModel.enhancerVisible
    val enhancerOriginalText = viewModel.enhancerOriginalText
    val enhancerEnhancedText = viewModel.enhancerEnhancedText
    val enhancerLoading = viewModel.enhancerLoading
    val enhancerError = viewModel.enhancerError
    val sessionTitle = viewModel.sessionTitle
    val sessionTitleVisible = viewModel.sessionTitleVisible
    val statusPanelVisible = viewModel.statusPanelVisible

    val scrollState = rememberScrollState()
    var followTail by remember { mutableStateOf(true) }
    var autoScrollInProgress by remember { mutableStateOf(false) }
    var userInteracted by remember { mutableStateOf(false) }
    var initialBottomSnapDone by remember { mutableStateOf(false) }
    var pendingForceScrollTrigger by remember { mutableIntStateOf(0) }
    val datasetKey = messages.firstOrNull()?.timestamp

    suspend fun scrollToBottom(force: Boolean = false) {
        if (!force && !followTail) return
        val target = scrollState.maxValue
        if (!force && target - scrollState.value <= 2) return
        autoScrollInProgress = true
        try {
            scrollState.scrollTo(target)
        } finally {
            autoScrollInProgress = false
        }
    }

    // Reset follow state for a newly loaded dataset/session.
    LaunchedEffect(datasetKey) {
        followTail = true
        userInteracted = false
        initialBottomSnapDone = false
    }

    // Detect user intent from actual scroll position changes.
    LaunchedEffect(scrollState) {
        snapshotFlow { Triple(scrollState.value, scrollState.maxValue, scrollState.isScrollInProgress) }
            .distinctUntilChanged()
            .collect { (value, maxValue, isScrollInProgress) ->
                val nearBottom = (maxValue - value) <= 24
                if (nearBottom) {
                    followTail = true
                } else if (isScrollInProgress && !autoScrollInProgress) {
                    followTail = false
                    userInteracted = true
                }
            }
    }

    // Initial snap-to-bottom for loaded history/session after real layout.
    LaunchedEffect(datasetKey, scrollState.maxValue, messages.size) {
        if (messages.isEmpty() || initialBottomSnapDone || userInteracted) return@LaunchedEffect
        when {
            scrollState.maxValue > 0 -> {
                scrollToBottom(force = true)
                initialBottomSnapDone = true
            }
            messages.size <= 2 -> {
                initialBottomSnapDone = true
            }
        }
    }

    // Explicit bottom requests from controller (send/load flow) while generating.
    LaunchedEffect(scrollToBottomTrigger, isStreaming, isSending) {
        if (scrollToBottomTrigger > 0 && (isStreaming || isSending || !userInteracted) && followTail) {
            scrollToBottom(force = true)
        }
    }

    // Capture explicit force-scroll requests (session load/restore) and apply them
    // after the list has a real scroll range.
    LaunchedEffect(forceScrollToBottomTrigger) {
        if (forceScrollToBottomTrigger > 0) {
            pendingForceScrollTrigger = forceScrollToBottomTrigger
        }
    }

    LaunchedEffect(pendingForceScrollTrigger) {
        if (pendingForceScrollTrigger <= 0) return@LaunchedEffect
        followTail = true
        userInteracted = false

        // Session history can expand layout in several passes (markdown/tool blocks).
        // Keep snapping to tail until scroll range stabilizes.
        delay(16)
        var lastMaxValue = -1
        var stableTicks = 0
        var ticks = 0
        while (ticks < 100) {
            val maxValue = scrollState.maxValue
            if (maxValue == lastMaxValue) {
                stableTicks++
            } else {
                lastMaxValue = maxValue
                stableTicks = 0
            }

            if (messages.isNotEmpty()) {
                scrollToBottom(force = true)
            }

            if (messages.isNotEmpty() && stableTicks >= 6) {
                break
            }
            delay(20)
            ticks++
        }

        scrollToBottom(force = true)
        initialBottomSnapDone = true
        pendingForceScrollTrigger = 0
    }

    // Keep tail pinned during streaming updates if user didn't scroll away.
    LaunchedEffect(messages.size, streamingResponseText.length, streamingThinkingText.length, streamingContentFlow.size, isStreaming, isSending) {
        if (isStreaming || isSending) {
            scrollToBottom(force = false)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Session title bar ────────────────────────────────────────────────
            if (sessionTitleVisible && sessionTitle.isNotEmpty()) {
                ComposeChatTitleBar(
                    title = sessionTitle,
                    onTitleChange = onTitleChange,
                )
            }

            // ── Message list (takes remaining space) ─────────────────────────────
            Box(modifier = Modifier.weight(1f)) {
                ComposeMessageList(
                    messages = messages,
                    scrollState = scrollState,
                    isStreaming = isStreaming,
                    streamingState = if (isStreaming) StreamingState(
                        thinkingText = streamingThinkingText,
                        responseText = streamingResponseText,
                        isThinkingVisible = streamingThinkingText.isNotEmpty(),
                        isThinkingCollapsed = thinkingCollapsed,
                    ) else null,
                    streamingContentFlow = if (isStreaming) streamingContentFlow else null,
                    onFileClick = onFileClick,
                    onToolShowDiff = onToolShowDiff,
                    onToolRevert = onToolRevert,
                    setupStatus = setupStatus,
                    setupInstalling = setupInstalling,
                    setupError = setupError,
                    onInstallSdk = onInstallSdk,
                    onInstallNode = onInstallNode,
                    onLogin = onLogin,
                    onDownloadNode = onDownloadNode,
                    sdkCurrentVersion = sdkCurrentVersion,
                    sdkLatestVersion = sdkLatestVersion,
                    sdkUpdateAvailable = sdkUpdateAvailable,
                    sdkUpdating = sdkUpdating,
                    sdkUpdateError = sdkUpdateError,
                    onUpdateSdk = onUpdateSdk,
                    modifier = Modifier.fillMaxSize(),
                )

                // Plan overlay: appears on top of message list, anchored to bottom
                if (planPanelVisible) {
                    Box(
                        contentAlignment = Alignment.BottomCenter,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        ComposePlanActionPanel(
                            planMarkdown = planMarkdown,
                            onApprove = onPlanApprove,
                            onApproveCompact = onPlanApproveCompact,
                            onDeny = onPlanDeny,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            // ── Bottom section: status + queue + input ───────────────────────────
            Column(modifier = Modifier.fillMaxWidth()) {
                // Status panel (toggleable via toolbar button)
                if (statusPanelVisible) {
                    ComposeStatusPanel(
                        todos = todos,
                        fileChanges = fileChanges,
                        agents = agents,
                        onFileClick = onStatusFileClick,
                        onShowDiff = onStatusShowDiff,
                        onUndoFile = onStatusUndoFile,
                        onDiscardAll = onStatusDiscardAll,
                        onKeepAll = onStatusKeepAll,
                    )
                }

                // Queue panel
                if (queueItems.isNotEmpty()) {
                    ComposeQueuePanel(
                        items = queueItems.map { item ->
                            QueueItemData(
                                text = item.text,
                                imageCount = item.imageCount,
                                index = item.index,
                            )
                        },
                        onRemove = onQueueRemove,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                    )
                }

                // Input area: switches between approval, question, or normal input
                when {
                    pendingApproval != null -> {
                        ComposeApprovalPanel(
                            request = pendingApproval,
                            onApprove = { _ -> onApprovalApprove() },
                            onReject = onApprovalReject,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp),
                        )
                    }

                    questionPanelVisible && currentQuestions.isNotEmpty() -> {
                        ComposeQuestionSelectionPanel(
                            questions = currentQuestions,
                            onSubmit = onQuestionSubmit,
                            onCancel = onQuestionCancel,
                        )
                    }

                    planPanelVisible -> {
                        // Plan overlay is shown above — no input area needed
                    }

                    else -> {
                        ComposeChatInputPanel(
                            text = inputText,
                            onTextChange = { viewModel.inputText = it },
                            isSending = isSending,
                            selectedModelName = selectedModelName,
                            selectedModelId = selectedModelId,
                            modeLabel = modeLabel,
                            selectedModeId = selectedModeId,
                            contextUsage = contextUsage,
                            fileContext = fileContext,
                            attachedImages = attachedImages,
                            mentionedFiles = mentionedFiles,
                            mentionPopupVisible = mentionPopupVisible,
                            mentionSuggestions = mentionSuggestions,
                            onSendOrStop = onSendOrStop,
                            onAttachClick = onAttachClick,
                            onPasteImage = onPasteImage,
                            streamingEnabled = streamingEnabled,
                            thinkingEnabled = thinkingEnabled,
                            onStreamingToggle = onStreamingToggle,
                            onThinkingToggle = onThinkingToggle,
                            onModelSelect = onModelSelect,
                            onModeSelect = onModeSelect,
                            onEnhanceClick = onEnhanceClick,
                            onFileContextClick = onFileContextClick,
                            onFileContextRemove = onFileContextRemove,
                            onImageClick = onImageClick,
                            onImageRemove = onImageRemove,
                            onMentionRemove = onMentionRemove,
                            onMentionQuery = onMentionQuery,
                            onMentionSelect = onMentionSelect,
                            onMentionDismiss = onMentionDismiss,
                            inputHistory = viewModel.inputHistory,
                            onAddToHistory = { viewModel.addToHistory(it) },
                            statusPanelVisible = statusPanelVisible,
                            onStatusPanelToggle = { viewModel.statusPanelVisible = !viewModel.statusPanelVisible },
                        )
                    }
                }
            }
        }

        // ── Prompt enhancer overlay ─────────────────────────────────────────
        if (enhancerVisible) {
            ComposePromptEnhancerDialog(
                originalText = enhancerOriginalText,
                enhancedText = enhancerEnhancedText,
                isLoading = enhancerLoading,
                error = enhancerError,
                onUseEnhanced = {
                    viewModel.inputText = viewModel.enhancerEnhancedText
                    viewModel.enhancerVisible = false
                },
                onKeepOriginal = {
                    viewModel.enhancerVisible = false
                },
            )
        }
    }
}
