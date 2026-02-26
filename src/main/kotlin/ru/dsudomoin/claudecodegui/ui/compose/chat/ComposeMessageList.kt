package ru.dsudomoin.claudecodegui.ui.compose.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.bridge.AuthState
import ru.dsudomoin.claudecodegui.bridge.NodeState
import ru.dsudomoin.claudecodegui.bridge.SetupStatus
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.Role
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Main message list composable.
 *
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.chat.MessageListPanel].
 */
@Composable
fun ComposeMessageList(
    messages: List<Message>,
    listState: LazyListState,
    isStreaming: Boolean = false,
    streamingState: StreamingState? = null,
    streamingContentFlow: List<ContentFlowItem>? = null,
    onFileClick: ((String) -> Unit)? = null,
    onToolShowDiff: ((ExpandableContent) -> Unit)? = null,
    onToolRevert: ((ExpandableContent) -> Unit)? = null,
    setupStatus: SetupStatus? = null,
    setupInstalling: Boolean = false,
    setupError: String? = null,
    onInstallSdk: (() -> Unit)? = null,
    onInstallNode: (() -> Unit)? = null,
    onLogin: (() -> Unit)? = null,
    onDownloadNode: (() -> Unit)? = null,
    // ── SDK version ──
    sdkCurrentVersion: String? = null,
    sdkLatestVersion: String? = null,
    sdkUpdateAvailable: Boolean = false,
    sdkUpdating: Boolean = false,
    sdkUpdateError: String? = null,
    onUpdateSdk: () -> Unit = {},
    modifier: Modifier = Modifier,
) {

    if (messages.isEmpty()) {
        val status = setupStatus
        if (status != null && !status.allReady) {
            SetupPanel(
                status = status,
                installing = setupInstalling,
                error = setupError,
                onInstallSdk = onInstallSdk ?: {},
                onInstallNode = onInstallNode ?: {},
                onLogin = onLogin ?: {},
                onDownloadNode = onDownloadNode ?: {},
                modifier = modifier,
            )
        } else {
            WelcomeScreen(
                sdkCurrentVersion = sdkCurrentVersion,
                sdkLatestVersion = sdkLatestVersion,
                sdkUpdateAvailable = sdkUpdateAvailable,
                sdkUpdating = sdkUpdating,
                sdkUpdateError = sdkUpdateError,
                onUpdateSdk = onUpdateSdk,
                modifier = modifier,
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        itemsIndexed(messages, key = { index, msg -> "${index}_${msg.timestamp}" }) { index, message ->
            val prevRole = if (index > 0) messages[index - 1].role else null

            // Separator between role transitions
            if (prevRole != null && prevRole != message.role) {
                MessageSeparator()
            }

            val isLastMessage = index == messages.lastIndex
            val isStreamingBubble = isStreaming && isLastMessage && message.role == Role.ASSISTANT

            MessageWrapper(
                message = message,
                streaming = isStreamingBubble,
                streamingState = if (isStreamingBubble) streamingState else null,
                contentFlow = if (isStreamingBubble) streamingContentFlow else null,
                onFileClick = onFileClick,
                onToolShowDiff = onToolShowDiff,
                onToolRevert = onToolRevert,
            )
        }
    }
}

/**
 * Wraps a message bubble with alignment, timestamp, and copy button.
 */
@Composable
private fun MessageWrapper(
    message: Message,
    streaming: Boolean,
    streamingState: StreamingState?,
    contentFlow: List<ContentFlowItem>?,
    onFileClick: ((String) -> Unit)?,
    onToolShowDiff: ((ExpandableContent) -> Unit)? = null,
    onToolRevert: ((ExpandableContent) -> Unit)? = null,
) {
    val colors = LocalClaudeColors.current
    val timeFormat = remember { SimpleDateFormat("HH:mm") }

    when (message.role) {
        Role.USER -> {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Time + copy row (right aligned)
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                ) {
                    Text(
                        text = timeFormat.format(Date(message.timestamp)),
                        style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
                    )
                    Spacer(Modifier.width(6.dp))
                    CopyButton(text = message.textContent, alwaysVisible = true)
                }

                ComposeMessageBubble(
                    message = message,
                    onFileClick = onFileClick,
                )
            }
        }

        Role.ASSISTANT -> {
            Box(modifier = Modifier.fillMaxWidth()) {
                ComposeMessageBubble(
                    message = message,
                    streaming = streaming,
                    streamingState = streamingState,
                    contentFlow = contentFlow,
                    onFileClick = onFileClick,
                    onToolShowDiff = onToolShowDiff,
                    onToolRevert = onToolRevert,
                )

                // Copy button (top-right, visible on hover)
                if (!streaming) {
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    ) {
                        CopyButton(
                            text = message.textContent,
                            alwaysVisible = false,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Small copy icon button.
 */
@Composable
private fun CopyButton(
    text: String,
    alwaysVisible: Boolean,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    // For assistant messages, only show on hover (parent hover will be handled at integration time)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) colors.iconHoverBg else colors.surfacePrimary.copy(alpha = 0f))
            .hoverable(interactionSource)
            .clickable {
                Toolkit.getDefaultToolkit().systemClipboard
                    .setContents(StringSelection(text), null)
            }
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(
            text = "\uD83D\uDCCB",
            style = TextStyle(fontSize = 12.sp),
        )
    }
}

/**
 * Horizontal separator between user/assistant message groups.
 */
@Composable
private fun MessageSeparator() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        val colors = LocalClaudeColors.current
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.separatorColor),
        )
    }
}

// ── Setup Panel ───────────────────────────────────────────────────────

@Composable
private fun SetupPanel(
    status: SetupStatus,
    installing: Boolean,
    error: String?,
    onInstallSdk: () -> Unit,
    onInstallNode: () -> Unit,
    onLogin: () -> Unit,
    onDownloadNode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 40.dp),
        ) {
            Text(
                text = UcuBundle.message("setup.title"),
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accentSecondary,
                ),
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = UcuBundle.message("setup.subtitle"),
                style = TextStyle(fontSize = 13.sp, color = colors.textSecondary),
            )

            Spacer(Modifier.height(24.dp))

            // ── Node.js ──
            val nodeIcon: String
            val nodeText: String
            val nodeAction: SetupAction?
            when (status.node.state) {
                NodeState.OK -> {
                    nodeIcon = "\u2705"
                    nodeText = "Node.js ${status.node.version ?: ""}"
                    nodeAction = null
                }
                NodeState.WRONG_VERSION -> {
                    nodeIcon = "\u274C"
                    nodeText = UcuBundle.message("setup.nodeWrongVersion", status.node.version ?: "?")
                    nodeAction = if (status.canInstallNode) {
                        val managerName = when {
                            status.node.nvmAvailable -> "nvm"
                            status.node.fnmAvailable -> "fnm"
                            else -> "brew"
                        }
                        SetupAction(UcuBundle.message("setup.installVia", managerName), onInstallNode)
                    } else {
                        SetupAction(UcuBundle.message("setup.downloadNode"), onDownloadNode)
                    }
                }
                NodeState.NOT_FOUND -> {
                    nodeIcon = "\u274C"
                    nodeText = UcuBundle.message("setup.nodeNotFound")
                    nodeAction = if (status.canInstallNode) {
                        val managerName = when {
                            status.node.nvmAvailable -> "nvm"
                            status.node.fnmAvailable -> "fnm"
                            else -> "brew"
                        }
                        SetupAction(UcuBundle.message("setup.installVia", managerName), onInstallNode)
                    } else {
                        SetupAction(UcuBundle.message("setup.downloadNode"), onDownloadNode)
                    }
                }
            }
            SetupCheckRow(icon = nodeIcon, text = nodeText, action = nodeAction, disabled = installing)
            Spacer(Modifier.height(8.dp))

            // ── SDK ──
            val sdkIcon = if (status.sdk.installed) "\u2705" else "\u274C"
            val sdkText = if (status.sdk.installed) {
                "Claude Code SDK ${status.sdk.version ?: ""}"
            } else {
                UcuBundle.message("setup.sdkNotInstalled")
            }
            val sdkAction = if (status.canInstallSdk) {
                SetupAction(UcuBundle.message("setup.installSdk"), onInstallSdk)
            } else null
            SetupCheckRow(icon = sdkIcon, text = sdkText, action = sdkAction, disabled = installing)
            Spacer(Modifier.height(8.dp))

            // ── Auth ──
            val authIcon: String
            val authText: String
            val authAction: SetupAction?
            when (status.auth.state) {
                AuthState.OK -> {
                    authIcon = "\u2705"
                    authText = UcuBundle.message("setup.authOk")
                    authAction = null
                }
                AuthState.EXPIRED -> {
                    authIcon = "\u26A0\uFE0F"
                    authText = UcuBundle.message("setup.authExpired")
                    authAction = if (status.canLogin) SetupAction(UcuBundle.message("setup.login"), onLogin) else null
                }
                AuthState.NOT_LOGGED_IN -> {
                    authIcon = "\u274C"
                    authText = UcuBundle.message("setup.authNotLoggedIn")
                    authAction = if (status.canLogin) SetupAction(UcuBundle.message("setup.login"), onLogin) else null
                }
            }
            SetupCheckRow(icon = authIcon, text = authText, action = authAction, disabled = installing)

            // Installing indicator
            if (installing) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = UcuBundle.message("setup.installing"),
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontStyle = FontStyle.Italic,
                        color = colors.textSecondary,
                    ),
                )
            }

            // Error message
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = error,
                    style = TextStyle(
                        fontSize = 12.sp,
                        color = colors.statusError,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

private data class SetupAction(val label: String, val onClick: () -> Unit)

@Composable
private fun SetupCheckRow(
    icon: String,
    text: String,
    action: SetupAction?,
    disabled: Boolean,
) {
    val colors = LocalClaudeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.welcomeTipBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = icon,
            style = TextStyle(fontSize = 14.sp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = TextStyle(fontSize = 13.sp, color = colors.textPrimary),
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            Spacer(Modifier.width(8.dp))
            SetupActionButton(label = action.label, onClick = action.onClick, disabled = disabled)
        }
    }
}

@Composable
private fun SetupActionButton(
    label: String,
    onClick: () -> Unit,
    disabled: Boolean,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (disabled) colors.textSecondary.copy(alpha = 0.2f)
                else if (isHovered) colors.accentSecondary.copy(alpha = 0.85f)
                else colors.accentSecondary
            )
            .hoverable(interactionSource)
            .then(if (!disabled) Modifier.clickable { onClick() } else Modifier)
            .pointerHoverIcon(if (disabled) PointerIcon.Default else PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (disabled) colors.textSecondary else colors.surfacePrimary,
            ),
        )
    }
}

// ── Welcome Screen ─────────────────────────────────────────────────────

@Composable
private fun WelcomeScreen(
    sdkCurrentVersion: String? = null,
    sdkLatestVersion: String? = null,
    sdkUpdateAvailable: Boolean = false,
    sdkUpdating: Boolean = false,
    sdkUpdateError: String? = null,
    onUpdateSdk: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 40.dp),
        ) {
            // Title
            Text(
                text = UcuBundle.message("welcome.title"),
                style = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accentSecondary,
                ),
            )

            Spacer(Modifier.height(6.dp))

            // Subtitle
            Text(
                text = UcuBundle.message("welcome.subtitle"),
                style = TextStyle(
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                ),
            )

            // SDK version (under subtitle)
            if (sdkCurrentVersion != null) {
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = UcuBundle.message("sdk.version", sdkCurrentVersion),
                        style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
                    )
                    when {
                        sdkUpdating -> {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = UcuBundle.message("sdk.updating"),
                                style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
                            )
                        }
                        sdkUpdateError != null -> {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = UcuBundle.message("sdk.updateError"),
                                style = TextStyle(fontSize = 11.sp, color = colors.statusError),
                            )
                        }
                        sdkUpdateAvailable && sdkLatestVersion != null -> {
                            Spacer(Modifier.width(6.dp))
                            val interactionSource = remember { MutableInteractionSource() }
                            val isHovered by interactionSource.collectIsHoveredAsState()
                            Text(
                                text = UcuBundle.message("sdk.updateAvailable", sdkLatestVersion),
                                style = TextStyle(
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isHovered) colors.accentSecondary.copy(alpha = 0.8f)
                                            else colors.accentSecondary,
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .hoverable(interactionSource)
                                    .clickable { onUpdateSdk() }
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Tip cards
            val tips = listOf(
                "\uD83D\uDCAC" to "welcome.tip1",
                "\u2728" to "welcome.tip2",
                "\uD83D\uDCCE" to "welcome.tip3",
                "\uD83D\uDDBC\uFE0F" to "welcome.tip4",
            )

            tips.forEachIndexed { index, (icon, key) ->
                TipCard(icon = icon, text = UcuBundle.message(key))
                if (index < tips.lastIndex) {
                    Spacer(Modifier.height(8.dp))
                }
            }

            Spacer(Modifier.height(20.dp))

            // Hint
            Text(
                text = UcuBundle.message("welcome.hint"),
                style = TextStyle(
                    fontSize = 12.sp,
                    fontStyle = FontStyle.Italic,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
private fun TipCard(icon: String, text: String) {
    val colors = LocalClaudeColors.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.welcomeTipBg)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = icon,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = TextStyle(fontSize = 13.sp, color = colors.textPrimary),
        )
    }
}
