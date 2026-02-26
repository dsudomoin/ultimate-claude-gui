@file:OptIn(ExperimentalJewelApi::class)

package ru.dsudomoin.claudecodegui.ui.compose.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    // ── Bridge: observe ChatViewModel via listener → trigger recomposition ──
    // A revision counter is incremented on every viewModel change.
    // Reading it forces Compose to recompose and re-read viewModel properties.
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(viewModel) {
        val listener: () -> Unit = { revision++ }
        viewModel.addListener(listener)
        onDispose { viewModel.removeListener(listener) }
    }

    // Read all values from viewModel (recompose triggered by revision change).
    // The `revision` variable is read here to establish the dependency.
    @Suppress("UNUSED_EXPRESSION") revision
    val messages = viewModel.messages
    val isStreaming = viewModel.isStreaming
    val isSending = viewModel.isSending
    val streamingThinkingText = viewModel.streamingThinkingText
    val streamingResponseText = viewModel.streamingResponseText
    val streamingContentFlow = viewModel.streamingContentFlow
    val thinkingCollapsed = viewModel.thinkingCollapsed
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
    val todos = viewModel.todos
    val fileChanges = viewModel.fileChanges
    val agents = viewModel.agents
    val queueItems = viewModel.queueItems
    val questionPanelVisible = viewModel.questionPanelVisible
    val currentQuestions = viewModel.currentQuestions
    val planPanelVisible = viewModel.planPanelVisible
    val planMarkdown = viewModel.planMarkdown
    val pendingApproval = viewModel.pendingApproval
    val scrollToBottomTrigger = viewModel.scrollToBottomTrigger
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

    val listState = rememberLazyListState()

    // ── Auto-scroll with pause support (feature 57) ─────────────────────────
    var autoScrollEnabled by remember { mutableStateOf(true) }

    val isAtBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastIndex = info.totalItemsCount - 1
            if (lastIndex < 0) true
            else info.visibleItemsInfo.any { it.index == lastIndex }
        }
    }

    // Resume auto-scroll when user scrolls back to bottom
    LaunchedEffect(isAtBottom) {
        if (isAtBottom) autoScrollEnabled = true
    }

    // Pause auto-scroll when user scrolls up during active scroll
    LaunchedEffect(Unit) {
        snapshotFlow { listState.isScrollInProgress to isAtBottom }
            .collect { (scrolling, atBottom) ->
                if (scrolling && !atBottom) autoScrollEnabled = false
            }
    }

    // Explicit scroll request (session load) — always honors
    LaunchedEffect(scrollToBottomTrigger) {
        if (messages.isNotEmpty()) {
            autoScrollEnabled = true
            listState.scrollToItem(messages.size - 1)
        }
    }

    // Auto-scroll on new messages or streaming updates — only when enabled
    LaunchedEffect(messages.size, streamingResponseText.length, streamingThinkingText.length) {
        if (messages.isNotEmpty() && autoScrollEnabled) {
            listState.animateScrollToItem(messages.size - 1)
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
                    listState = listState,
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
