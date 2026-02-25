package ru.dsudomoin.claudecodegui.ui.compose.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
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

// ── Toolbar Composable ───────────────────────────────────────────────────────

/**
 * Bottom toolbar row: [settings] [mode] [model] ←→ [expand] [enhance] | [send/stop]
 */
@Composable
fun ComposeInputToolbar(
    selectedModelName: String,
    modeLabel: String,
    isSending: Boolean,
    onSettingsClick: () -> Unit,
    onModelClick: () -> Unit,
    onModeClick: () -> Unit,
    onEnhanceClick: () -> Unit,
    onSendOrStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

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
            ToolbarIconButton(
                icon = "\u2699",
                onClick = onSettingsClick,
            )
            SelectorButton(
                text = "$modeLabel \u25BE",
                onClick = onModeClick,
            )
            SelectorButton(
                text = "\u2728 $selectedModelName \u25BE",
                onClick = onModelClick,
            )
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
            )
            ToolbarDivider()
            SendStopButton(
                isSending = isSending,
                onClick = onSendOrStop,
            )
        }
    }
}

// ── Button Components ────────────────────────────────────────────────────────

@Composable
fun ToolbarIconButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

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
}

@Composable
fun SelectorButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        contentAlignment = Alignment.Center,
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
                color = if (isHovered) colors.textPrimary else colors.textSecondary,
            ),
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
