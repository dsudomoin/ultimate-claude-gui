package ru.dsudomoin.claudecodegui.ui.compose.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

// ── Data Models ──────────────────────────────────────────────────────────────

data class ModelOption(
    val id: String,
    val displayName: String,
    val description: String,
)

data class PermissionModeOption(
    val id: String,
    val shortLabel: String,
    val icon: String,
)

// ── Popup Item Data ─────────────────────────────────────────────────────────

private data class PopupOption(
    val id: String,
    val icon: String,
    val name: String,
    val description: String,
)

// ── Toolbar Composable ───────────────────────────────────────────────────────

/**
 * Bottom toolbar row: [settings] [mode] [model] ←→ [enhance] | [send/stop]
 */
@Composable
fun ComposeInputToolbar(
    selectedModelName: String,
    selectedModelId: String,
    modeLabel: String,
    selectedModeId: String,
    isSending: Boolean,
    streamingEnabled: Boolean,
    thinkingEnabled: Boolean,
    onStreamingToggle: () -> Unit,
    onThinkingToggle: () -> Unit,
    onModelSelect: (String) -> Unit,
    onModeSelect: (String) -> Unit,
    onEnhanceClick: () -> Unit,
    onSendOrStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    var showSettingsPopup by remember { mutableStateOf(false) }
    var showModePopup by remember { mutableStateOf(false) }
    var showModelPopup by remember { mutableStateOf(false) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(colors.toolbarBg)
            .padding(4.dp),
    ) {
        // Left: settings + mode + model
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Settings
            Box {
                ToolbarIconButton(
                    icon = "\u2699",
                    onClick = { showSettingsPopup = !showSettingsPopup },
                )
                if (showSettingsPopup) {
                    SettingsPopup(
                        streamingEnabled = streamingEnabled,
                        thinkingEnabled = thinkingEnabled,
                        onStreamingToggle = onStreamingToggle,
                        onThinkingToggle = onThinkingToggle,
                        onDismiss = { showSettingsPopup = false },
                    )
                }
            }

            // Mode selector
            Box {
                SelectorButton(
                    text = modeLabel,
                    onClick = { showModePopup = !showModePopup },
                    chevronUp = showModePopup,
                )
                if (showModePopup) {
                    ModeSelectionPopup(
                        selectedModeId = selectedModeId,
                        onSelect = { id ->
                            onModeSelect(id)
                            showModePopup = false
                        },
                        onDismiss = { showModePopup = false },
                    )
                }
            }

            // Model selector
            Box {
                SelectorButton(
                    text = "\u2728 $selectedModelName",
                    onClick = { showModelPopup = !showModelPopup },
                    chevronUp = showModelPopup,
                )
                if (showModelPopup) {
                    ModelSelectionPopup(
                        selectedModelId = selectedModelId,
                        onSelect = { id ->
                            onModelSelect(id)
                            showModelPopup = false
                        },
                        onDismiss = { showModelPopup = false },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // Right: enhance + divider + send/stop
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ToolbarIconButton(
                icon = "\u26A1",
                onClick = onEnhanceClick,
                tooltip = UcuBundle.message("input.enhance"),
            )
            ToolbarDivider()
            SendStopButton(
                isSending = isSending,
                onClick = onSendOrStop,
            )
        }
    }
}

// ── Mode Selection Popup ────────────────────────────────────────────────────

@Composable
private fun ModeSelectionPopup(
    selectedModeId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val modes = remember {
        listOf(
            PopupOption("default", "\uD83D\uDCAC", UcuBundle.message("mode.default.name"), UcuBundle.message("mode.default.desc")),
            PopupOption("plan", "\uD83D\uDCCB", UcuBundle.message("mode.plan.name"), UcuBundle.message("mode.plan.desc")),
            PopupOption("autoEdit", "\uD83E\uDD16", UcuBundle.message("mode.agent.name"), UcuBundle.message("mode.agent.desc")),
            PopupOption("bypassPermissions", "\u26A1", UcuBundle.message("mode.auto.name"), UcuBundle.message("mode.auto.desc")),
        )
    }

    SelectionPopup(
        options = modes,
        selectedId = selectedModeId,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

// ── Model Selection Popup ───────────────────────────────────────────────────

@Composable
private fun ModelSelectionPopup(
    selectedModelId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val models = remember {
        listOf(
            PopupOption("claude-sonnet-4-6", "\u2728", "Sonnet 4.6", UcuBundle.message("model.sonnet.desc")),
            PopupOption("claude-opus-4-6", "\uD83E\uDDE0", "Opus 4.6", UcuBundle.message("model.opus.desc")),
            PopupOption("claude-opus-4-6-max", "\uD83E\uDDE0", "Opus 4.6 (1M)", UcuBundle.message("model.opus1m.desc")),
            PopupOption("claude-haiku-4-5-20251001", "\u26A1", "Haiku 4.5", UcuBundle.message("model.haiku.desc")),
        )
    }

    SelectionPopup(
        options = models,
        selectedId = selectedModelId,
        onSelect = onSelect,
        onDismiss = onDismiss,
    )
}

// ── Generic Selection Popup ─────────────────────────────────────────────────

@Composable
private fun SelectionPopup(
    options: List<PopupOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalClaudeColors.current

    Popup(
        alignment = Alignment.BottomStart,
        offset = IntOffset(0, -44),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .width(360.dp)
                .background(colors.surfaceSecondary, RoundedCornerShape(10.dp))
                .border(1.dp, colors.borderNormal, RoundedCornerShape(10.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { option ->
                SelectionOptionRow(
                    icon = option.icon,
                    name = option.name,
                    description = option.description,
                    isSelected = option.id == selectedId,
                    onClick = { onSelect(option.id) },
                )
            }
        }
    }
}

@Composable
private fun SelectionOptionRow(
    icon: String,
    name: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bgColor = when {
        isSelected -> colors.accent.copy(alpha = 0.15f)
        isHovered -> colors.hoverOverlay
        else -> Color.Transparent
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // Icon
        Text(
            text = icon,
            style = TextStyle(fontSize = 18.sp),
        )

        Spacer(Modifier.width(12.dp))

        // Name + description
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
            Text(
                text = description,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                ),
            )
        }

        // Checkmark for selected
        if (isSelected) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "\u2713",
                style = TextStyle(
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.statusSuccess,
                ),
            )
        }
    }
}

// ── Settings Popup ──────────────────────────────────────────────────────────

@Composable
private fun SettingsPopup(
    streamingEnabled: Boolean,
    thinkingEnabled: Boolean,
    onStreamingToggle: () -> Unit,
    onThinkingToggle: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalClaudeColors.current

    Popup(
        alignment = Alignment.BottomStart,
        offset = IntOffset(0, -44),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .width(240.dp)
                .background(colors.surfaceSecondary, RoundedCornerShape(8.dp))
                .border(1.dp, colors.borderNormal, RoundedCornerShape(8.dp))
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            SettingsToggleRow(
                icon = "\u21BB",
                label = UcuBundle.message("settings.streaming"),
                checked = streamingEnabled,
                onToggle = onStreamingToggle,
            )
            SettingsToggleRow(
                icon = "\uD83D\uDCA1",
                label = UcuBundle.message("settings.thinking"),
                checked = thinkingEnabled,
                onToggle = onThinkingToggle,
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: String,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isHovered) colors.hoverOverlay else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onToggle)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = icon,
            style = TextStyle(fontSize = 14.sp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = TextStyle(
                fontSize = 13.sp,
                color = colors.textPrimary,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        ToggleSwitch(checked = checked)
    }
}

// ── Toggle Switch ───────────────────────────────────────────────────────────

@Composable
private fun ToggleSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val trackColor = if (checked) colors.accent else colors.borderNormal
    val thumbColor = if (checked) Color.White else colors.textSecondary

    Box(
        modifier = modifier
            .width(36.dp)
            .height(20.dp)
            .clip(CircleShape)
            .background(trackColor),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(3.dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(thumbColor),
        )
    }
}

// ── Button Components ────────────────────────────────────────────────────────

@Composable
fun ToolbarIconButton(
    icon: String,
    onClick: () -> Unit,
    tooltip: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box {
        Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (isHovered) colors.hoverOverlay else Color.Transparent)
                .hoverable(interactionSource)
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(
                text = icon,
                style = TextStyle(fontSize = 14.sp),
            )
        }
        if (isHovered && tooltip != null) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -32),
            ) {
                Box(
                    modifier = Modifier
                        .background(colors.surfaceTertiary, RoundedCornerShape(6.dp))
                        .border(1.dp, colors.borderNormal, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = tooltip,
                        style = TextStyle(fontSize = 11.sp, color = colors.textPrimary),
                    )
                }
            }
        }
    }
}

@Composable
fun SelectorButton(
    text: String,
    onClick: () -> Unit,
    chevronUp: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val contentColor = if (isHovered) colors.textPrimary else colors.textSecondary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) colors.hoverOverlay else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 11.sp,
                color = contentColor,
            ),
        )
        // Canvas-drawn chevron (small down arrow)
        Box(
            modifier = Modifier
                .size(8.dp)
                .drawBehind {
                    val chevronW = 6.dp.toPx()
                    val chevronH = 3.dp.toPx()
                    val cx = size.width / 2
                    val cy = size.height / 2

                    val path = Path().apply {
                        if (chevronUp) {
                            moveTo(cx - chevronW / 2, cy + chevronH / 2)
                            lineTo(cx, cy - chevronH / 2)
                            lineTo(cx + chevronW / 2, cy + chevronH / 2)
                        } else {
                            moveTo(cx - chevronW / 2, cy - chevronH / 2)
                            lineTo(cx, cy + chevronH / 2)
                            lineTo(cx + chevronW / 2, cy - chevronH / 2)
                        }
                    }

                    drawPath(
                        path = path,
                        color = contentColor,
                        style = Stroke(
                            width = 1.2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                        ),
                    )
                },
        )
    }
}

@Composable
fun SendStopButton(
    isSending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bg = if (isSending) colors.statusError else colors.accent
    val alpha = if (isHovered) 0.85f else 1f

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(28.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg.copy(alpha = alpha))
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(
            text = if (isSending) "\u23F9" else "\u25B6",
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            ),
        )
    }
}

@Composable
private fun ToolbarDivider() {
    val colors = LocalClaudeColors.current
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(16.dp)
            .background(colors.borderNormal.copy(alpha = 0.5f)),
    )
}
