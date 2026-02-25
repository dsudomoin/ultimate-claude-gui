@file:OptIn(ExperimentalJewelApi::class)

package ru.dsudomoin.claudecodegui.ui.compose.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.common.ComposeMarkdownContent
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Plan approval overlay displayed when Claude sends ExitPlanMode.
 * Shows the plan markdown with Approve / Deny buttons.
 *
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.dialog.PlanActionPanel].
 */
@Composable
fun ComposePlanActionPanel(
    planMarkdown: String = "",
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val shape = RoundedCornerShape(8.dp)
    val focusRequester = remember { FocusRequester() }

    // Auto-focus to capture keyboard events
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(8.dp, shape)
            .clip(shape)
            .background(colors.surfacePrimary)
            .border(1.dp, colors.planBarBorder, shape)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.Enter -> { onApprove(); true }
                        Key.Escape -> { onDeny(); true }
                        else -> false
                    }
                } else false
            }
            .focusable(),
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.planBarBg)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = UcuBundle.message("permission.planTitle"),
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = UcuBundle.message("permission.planDescription"),
                style = TextStyle(fontSize = 12.sp, color = colors.textSecondary),
            )
        }

        // Separator
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.planBarBorder),
        )

        // Plan content (scrollable markdown)
        if (planMarkdown.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp, max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                ComposeMarkdownContent(markdown = planMarkdown)
            }

            // Separator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.planBarBorder),
            )
        }

        // Action buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            // Keyboard hints
            Text(
                text = "[Enter] ${UcuBundle.message("permission.planApprove")}  \u00B7  [Esc] ${UcuBundle.message("permission.planDeny")}",
                style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
            )

            Spacer(Modifier.weight(1f))

            // Deny button
            PlanButton(
                text = UcuBundle.message("permission.planDeny"),
                backgroundColor = colors.denyBg,
                hoverColor = colors.denyHover,
                textColor = colors.textPrimary,
                borderColor = colors.denyBorder,
                onClick = onDeny,
            )

            Spacer(Modifier.width(8.dp))

            // Approve button
            PlanButton(
                text = UcuBundle.message("permission.planApprove"),
                backgroundColor = colors.approveBg,
                hoverColor = colors.approveHover,
                textColor = Color.White,
                borderColor = null,
                onClick = onApprove,
            )
        }
    }
}

@Composable
private fun PlanButton(
    text: String,
    backgroundColor: Color,
    hoverColor: Color,
    textColor: Color,
    borderColor: Color?,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    val bgColor = if (isHovered) hoverColor else backgroundColor

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(shape)
            .background(bgColor, shape)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor, shape)
                else Modifier,
            )
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
            ),
        )
    }
}
