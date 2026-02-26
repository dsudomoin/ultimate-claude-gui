@file:OptIn(ExperimentalJewelApi::class)

package ru.dsudomoin.claudecodegui.ui.compose.toolwindow

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import ru.dsudomoin.claudecodegui.ui.compose.chat.ChatViewModel
import ru.dsudomoin.claudecodegui.ui.compose.chat.ComposeChatPanel
import ru.dsudomoin.claudecodegui.ui.compose.history.ComposeHistoryPanel
import ru.dsudomoin.claudecodegui.ui.compose.chat.ExpandableContent
import ru.dsudomoin.claudecodegui.ui.status.FileChangeSummary

/**
 * Compose root for a single tool window tab.
 *
 * Switches between Chat view and History view (animated).
 * The `viewModel.showingHistory` flag toggles between the two.
 *
 * This composable is hosted inside a `createThemedComposePanel()`
 * JComponent that lives in ChatContainerPanel.
 */
@Composable
fun ComposeChatContainer(
    viewModel: ChatViewModel,
    callbacks: ChatCallbacks,
    modifier: Modifier = Modifier,
) {
    // Observe viewModel changes
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(viewModel) {
        val listener: () -> Unit = { revision++ }
        viewModel.addListener(listener)
        onDispose { viewModel.removeListener(listener) }
    }
    @Suppress("UNUSED_EXPRESSION") revision
    val showingHistory = viewModel.showingHistory

    AnimatedContent(
        targetState = showingHistory,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = modifier.fillMaxSize(),
    ) { isHistory ->
        if (isHistory) {
            ComposeHistoryPanel(
                sessions = viewModel.historySessions,
                onLoadSession = callbacks.onLoadSession,
                onBack = callbacks.onHistoryBack,
                onRefresh = callbacks.onHistoryRefresh,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            ComposeChatPanel(
                viewModel = viewModel,
                onSendOrStop = callbacks.onSendOrStop,
                onAttachClick = callbacks.onAttachClick,
                onPasteImage = callbacks.onPasteImage,
                onStreamingToggle = callbacks.onStreamingToggle,
                onThinkingToggle = callbacks.onThinkingToggle,
                onModelSelect = callbacks.onModelSelect,
                onModeSelect = callbacks.onModeSelect,
                onEnhanceClick = callbacks.onEnhanceClick,
                onFileContextClick = callbacks.onFileContextClick,
                onFileContextRemove = callbacks.onFileContextRemove,
                onImageClick = callbacks.onImageClick,
                onImageRemove = callbacks.onImageRemove,
                onMentionRemove = callbacks.onMentionRemove,
                onMentionQuery = callbacks.onMentionQuery,
                onMentionSelect = callbacks.onMentionSelect,
                onMentionDismiss = callbacks.onMentionDismiss,
                onFileClick = callbacks.onFileClick,
                onToolShowDiff = callbacks.onToolShowDiff,
                onToolRevert = callbacks.onToolRevert,
                onStatusFileClick = callbacks.onStatusFileClick,
                onStatusShowDiff = callbacks.onStatusShowDiff,
                onStatusUndoFile = callbacks.onStatusUndoFile,
                onStatusDiscardAll = callbacks.onStatusDiscardAll,
                onStatusKeepAll = callbacks.onStatusKeepAll,
                onQueueRemove = callbacks.onQueueRemove,
                onQuestionSubmit = callbacks.onQuestionSubmit,
                onQuestionCancel = callbacks.onQuestionCancel,
                onPlanApprove = callbacks.onPlanApprove,
                onPlanApproveCompact = callbacks.onPlanApproveCompact,
                onPlanDeny = callbacks.onPlanDeny,
                onApprovalApprove = callbacks.onApprovalApprove,
                onApprovalReject = callbacks.onApprovalReject,
                onInstallSdk = callbacks.onInstallSdk,
                onInstallNode = callbacks.onInstallNode,
                onLogin = callbacks.onLogin,
                onDownloadNode = callbacks.onDownloadNode,
                onUpdateSdk = callbacks.onUpdateSdk,
                onTitleChange = callbacks.onTitleChange,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * All callbacks from Compose UI to the Swing/business-logic host.
 * Grouped into a single data class to avoid 20+ lambda parameters in the container.
 */
data class ChatCallbacks(
    // Input
    val onSendOrStop: () -> Unit = {},
    val onAttachClick: () -> Unit = {},
    val onPasteImage: () -> Boolean = { false },
    val onStreamingToggle: () -> Unit = {},
    val onThinkingToggle: () -> Unit = {},
    val onModelSelect: (String) -> Unit = {},
    val onModeSelect: (String) -> Unit = {},
    val onEnhanceClick: () -> Unit = {},
    val onFileContextClick: (String) -> Unit = {},
    val onFileContextRemove: () -> Unit = {},
    val onImageClick: (String) -> Unit = {},
    val onImageRemove: (String) -> Unit = {},
    val onMentionRemove: (String) -> Unit = {},
    // Mention popup
    val onMentionQuery: (String) -> Unit = {},
    val onMentionSelect: (Int) -> Unit = {},
    val onMentionDismiss: () -> Unit = {},
    // File navigation
    val onFileClick: ((String) -> Unit)? = null,
    // Tool actions (diff / revert)
    val onToolShowDiff: ((ExpandableContent) -> Unit)? = null,
    val onToolRevert: ((ExpandableContent) -> Unit)? = null,
    // Status
    val onStatusFileClick: (String) -> Unit = {},
    val onStatusShowDiff: (FileChangeSummary) -> Unit = {},
    val onStatusUndoFile: (FileChangeSummary) -> Unit = {},
    val onStatusDiscardAll: () -> Unit = {},
    val onStatusKeepAll: () -> Unit = {},
    // Queue
    val onQueueRemove: (Int) -> Unit = {},
    // Question panel
    val onQuestionSubmit: (Map<String, List<String>>) -> Unit = {},
    val onQuestionCancel: () -> Unit = {},
    // Plan action panel
    val onPlanApprove: () -> Unit = {},
    val onPlanApproveCompact: () -> Unit = {},
    val onPlanDeny: () -> Unit = {},
    // Approval panel
    val onApprovalApprove: () -> Unit = {},
    val onApprovalReject: () -> Unit = {},
    // Setup
    val onInstallSdk: () -> Unit = {},
    val onInstallNode: () -> Unit = {},
    val onLogin: () -> Unit = {},
    val onDownloadNode: () -> Unit = {},
    // SDK update
    val onUpdateSdk: () -> Unit = {},
    // History
    val onLoadSession: (String) -> Unit = {},
    val onHistoryBack: () -> Unit = {},
    val onHistoryRefresh: () -> Unit = {},
    // Title
    val onTitleChange: (String) -> Unit = {},
)
