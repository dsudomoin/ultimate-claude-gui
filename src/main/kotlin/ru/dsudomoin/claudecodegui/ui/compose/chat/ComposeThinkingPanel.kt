package ru.dsudomoin.claudecodegui.ui.compose.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import ru.dsudomoin.claudecodegui.ui.compose.theme.scaledSp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Collapsible panel displaying Claude's thinking/reasoning.
 *
 * When [isStreaming] is true, the panel auto-expands and shows a pulsing indicator.
 * When [isFinished] is true, the panel auto-collapses.
 *
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.chat.ThinkingPanel].
 */
@Composable
fun ComposeThinkingPanel(
    text: String,
    isFinished: Boolean = false,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    // Initialize from current mode to avoid one-frame height jump when
    // historical (finished) messages first enter the viewport.
    var collapsed by remember(isFinished, isStreaming) {
        mutableStateOf(isFinished && !isStreaming)
    }

    // Auto-expand when streaming starts, auto-collapse when finished
    LaunchedEffect(isStreaming, isFinished) {
        if (isStreaming && !isFinished) {
            collapsed = false
        } else if (isFinished) {
            collapsed = true
        }
    }

    val borderColor = colors.thinkingBorder

    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                // Left border line (2dp)
                drawLine(
                    color = borderColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
    ) {
        // Header: clickable toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { collapsed = !collapsed }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(vertical = 2.dp),
        ) {
            // Track thinking duration locally
            var elapsedSeconds by remember { mutableIntStateOf(0) }
            LaunchedEffect(isStreaming, isFinished) {
                if (isStreaming && !isFinished) {
                    elapsedSeconds = 0
                    while (true) {
                        delay(1000)
                        elapsedSeconds++
                    }
                }
            }

            val indicator = if (collapsed) "\u25B6" else "\u25BC"
            val label = if (isStreaming && !isFinished) {
                UcuBundle.message("thinking.active")
            } else if (elapsedSeconds > 0) {
                UcuBundle.message("thinking.duration", elapsedSeconds)
            } else {
                UcuBundle.message("thinking.done")
            }
            Text(
                text = "$indicator $label",
                style = TextStyle(
                    fontSize = scaledSp(11),
                    color = colors.textSecondary,
                ),
            )

            // Pulsing indicator when actively streaming thinking
            if (isStreaming && !isFinished) {
                Spacer(Modifier.width(6.dp))
                PulsingThinkingIndicator()
            }
        }

        // Content: visible when not collapsed
        if (!collapsed) {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = scaledSp(12),
                    color = colors.textSecondary,
                ),
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
            )
        }
    }
}

/**
 * A pulsing dot indicator that signals active thinking.
 */
@Composable
private fun PulsingThinkingIndicator() {
    val colors = LocalClaudeColors.current
    val transition = rememberInfiniteTransition(label = "thinking-pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-pulse-alpha",
    )

    Text(
        text = "\u2B24",
        style = TextStyle(
            fontSize = scaledSp(6),
            color = colors.thinkingBorder,
        ),
        modifier = Modifier.alpha(alpha),
    )
}
