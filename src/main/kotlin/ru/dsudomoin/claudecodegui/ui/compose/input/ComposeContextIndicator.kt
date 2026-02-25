package ru.dsudomoin.claudecodegui.ui.compose.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

// ── Data ─────────────────────────────────────────────────────────────────────

data class ContextUsageData(
    val percentage: Int = 0,
    val usedTokens: Int = 0,
    val maxTokens: Int = 200_000,
) {
    val displayPercentage: Int
        get() = if (maxTokens > 0) {
            ((usedTokens.toDouble() / maxTokens) * 100).toInt().coerceIn(0, 100)
        } else percentage.coerceIn(0, 100)

    val tooltipText: String
        get() = "Context: $displayPercentage% — ${formatTokens(usedTokens)} / ${formatTokens(maxTokens)} tokens"

    private fun formatTokens(count: Int): String = when {
        count >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(java.util.Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }
}

// ── Context Indicator ────────────────────────────────────────────────────────

@Composable
fun ComposeContextIndicator(
    usage: ContextUsageData,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val pct = usage.displayPercentage
    val arcColor = when {
        pct >= 80 -> colors.statusError
        pct >= 50 -> Color(0xFF, 0xA5, 0x00)
        else -> colors.accent
    }
    val textColor = when {
        pct >= 80 -> colors.statusError
        pct >= 50 -> Color(0xFF, 0xA5, 0x00)
        else -> colors.textSecondary
    }

    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.hoverable(interactionSource),
        ) {
            // Circular progress
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .drawBehind {
                        // Background track
                        drawCircle(
                            color = colors.borderNormal,
                            style = Stroke(width = 2.dp.toPx()),
                        )
                        // Filled arc (clockwise from top)
                        if (pct > 0) {
                            drawArc(
                                color = arcColor,
                                startAngle = -90f,
                                sweepAngle = pct * 360f / 100f,
                                useCenter = false,
                                style = Stroke(width = 2.dp.toPx()),
                            )
                        }
                    },
            )

            Spacer(Modifier.width(4.dp))

            Text(
                text = "$pct%",
                style = TextStyle(
                    fontSize = 11.sp,
                    color = textColor,
                ),
            )
        }

        // Tooltip on hover
        if (isHovered) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, -32),
            ) {
                Box(
                    modifier = Modifier
                        .background(colors.surfaceTertiary, RoundedCornerShape(6.dp))
                        .border(1.dp, colors.borderNormal, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = usage.tooltipText,
                        style = TextStyle(fontSize = 11.sp, color = colors.textPrimary),
                    )
                }
            }
        }
    }
}

// ── Header Bar ───────────────────────────────────────────────────────────────

/**
 * Header bar above the text area: [attach] [context%] | [file chip]
 */
@Composable
fun ComposeInputHeaderBar(
    contextUsage: ContextUsageData,
    fileContext: FileContextData?,
    onAttachClick: () -> Unit,
    onFileContextClick: (String) -> Unit,
    onFileContextRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        // Attach button
        ToolbarIconButton(
            icon = "\u2795",
            onClick = onAttachClick,
        )

        Spacer(Modifier.width(4.dp))

        // Context usage
        ComposeContextIndicator(usage = contextUsage)

        Spacer(Modifier.width(4.dp))

        // Divider
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(14.dp)
                .drawBehind {
                    drawRect(
                        color = colors.borderNormal.copy(alpha = 0.4f),
                    )
                },
        )

        // File context chip (takes remaining space)
        ComposeFileContextChip(
            context = fileContext,
            onFileClick = onFileContextClick,
            onRemove = onFileContextRemove,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
