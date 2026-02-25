package ru.dsudomoin.claudecodegui.ui.compose.status

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Animated spinner icon (arc rotating 360 degrees).
 */
@Composable
fun SpinnerIcon(
    color: Color,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
        ),
        label = "spinAngle",
    )

    Box(
        modifier = modifier
            .size(size)
            .drawBehind {
                drawArc(
                    color = color,
                    startAngle = angle,
                    sweepAngle = 270f,
                    useCenter = false,
                    style = Stroke(width = 2.dp.toPx()),
                )
            },
    )
}

/**
 * Centered placeholder text for empty tabs.
 */
@Composable
fun EmptyTabPlaceholder(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize(),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 12.sp,
                color = colors.textSecondary,
            ),
        )
    }
}
