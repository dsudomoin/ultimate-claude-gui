@file:OptIn(ExperimentalJewelApi::class)

package ru.dsudomoin.claudecodegui.ui.compose.dialog

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.ui.component.Text
import com.intellij.openapi.diagnostic.Logger
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.common.ComposeMarkdownContent
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

private val log = Logger.getInstance("ComposePlanActionPanel")

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
    onApproveCompact: () -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val shape = RoundedCornerShape(8.dp)
    val focusRequester = remember { FocusRequester() }

    // Log what we receive
    LaunchedEffect(planMarkdown) {
        log.info("ComposePlanActionPanel RENDER: planMarkdown length=${planMarkdown.length}, blank=${planMarkdown.isBlank()}, preview='${planMarkdown.take(300)}'")
    }

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
                        Key.Enter -> {
                            if (event.isShiftPressed) { onApproveCompact() } else { onApprove() }
                            true
                        }
                        Key.Escape -> { onDeny(); true }
                        else -> false
                    }
                } else false
            }
            .focusable(),
    ) {
        // Header with animated accent line
        Box(modifier = Modifier.fillMaxWidth()) {
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
        }

        // Animated gradient separator
        PlanGradientSeparator(accentColor = colors.accent)

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

        // Action buttons — no keyboard hints, just buttons
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Spacer(Modifier.weight(1f))

            // Deny button
            CanvasPlanButton(
                text = UcuBundle.message("permission.planDeny"),
                gradientStart = colors.denyBg,
                gradientEnd = colors.denyHover,
                textColor = colors.textPrimary,
                borderColor = colors.denyBorder,
                onClick = onDeny,
            )

            Spacer(Modifier.width(8.dp))

            // Approve & Compact button
            CanvasPlanButton(
                text = UcuBundle.message("permission.planApproveCompact"),
                gradientStart = colors.surfaceSecondary,
                gradientEnd = colors.surfaceHover,
                textColor = colors.textPrimary,
                borderColor = colors.borderNormal,
                onClick = onApproveCompact,
            )

            Spacer(Modifier.width(8.dp))

            // Approve button — primary action with glow
            CanvasPlanButton(
                text = UcuBundle.message("permission.planApprove"),
                gradientStart = colors.approveBg,
                gradientEnd = colors.approveHover,
                textColor = Color.White,
                borderColor = null,
                glowColor = colors.approveBg.copy(alpha = 0.4f),
                onClick = onApprove,
            )
        }
    }
}

/** Animated gradient line between header and content. */
@Composable
private fun PlanGradientSeparator(accentColor: Color) {
    val transition = rememberInfiniteTransition(label = "planSep")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "planSepOffset",
    )

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp),
    ) {
        val w = size.width
        val startX = w * offset
        val brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                accentColor.copy(alpha = 0.8f),
                accentColor,
                accentColor.copy(alpha = 0.8f),
                Color.Transparent,
            ),
            startX = startX,
            endX = startX + w * 0.4f,
        )
        drawRect(brush = brush, size = size)
    }
}

/**
 * Button drawn entirely via Canvas — gradient fill, rounded rect, optional glow on hover.
 */
@Composable
private fun CanvasPlanButton(
    text: String,
    gradientStart: Color,
    gradientEnd: Color,
    textColor: Color,
    borderColor: Color?,
    glowColor: Color? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val hoverProgress by animateFloatAsState(
        targetValue = if (isHovered) 1f else 0f,
        animationSpec = tween(200),
        label = "btnHover",
    )

    val textMeasurer = rememberTextMeasurer()
    val textStyle = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = textColor,
    )
    val measuredText = remember(text, textStyle) {
        textMeasurer.measure(text, textStyle)
    }

    val paddingH = 20.dp
    val paddingV = 7.dp
    val canvasWidth = measuredText.size.width.dp + paddingH * 2
    val canvasHeight = measuredText.size.height.dp + paddingV * 2

    Box(
        modifier = Modifier
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Canvas(
            modifier = Modifier
                .width(canvasWidth)
                .height(canvasHeight),
        ) {
            val cornerRadiusPx = 6.dp.toPx()
            val cr = CornerRadius(cornerRadiusPx, cornerRadiusPx)

            // Glow effect on hover for primary button
            if (glowColor != null && hoverProgress > 0f) {
                val glowSpread = 4.dp.toPx() * hoverProgress
                drawRoundRect(
                    color = glowColor.copy(alpha = glowColor.alpha * hoverProgress * 0.6f),
                    topLeft = Offset(-glowSpread, -glowSpread),
                    size = Size(size.width + glowSpread * 2, size.height + glowSpread * 2),
                    cornerRadius = CornerRadius(cornerRadiusPx + glowSpread),
                )
            }

            // Gradient fill — shifts toward hoverColor based on hover
            val fillBrush = Brush.verticalGradient(
                colors = listOf(
                    lerp(gradientStart, gradientEnd, hoverProgress * 0.5f),
                    lerp(gradientEnd, gradientStart, 0.3f - hoverProgress * 0.3f),
                ),
            )
            drawRoundRect(
                brush = fillBrush,
                cornerRadius = cr,
            )

            // Border
            if (borderColor != null) {
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = cr,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }

            // Text centered
            val textX = (size.width - measuredText.size.width) / 2f
            val textY = (size.height - measuredText.size.height) / 2f
            drawText(
                textLayoutResult = measuredText,
                topLeft = Offset(textX, textY),
            )
        }
    }
}

/** Simple color lerp for gradient transitions. */
private fun lerp(start: Color, end: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (end.red - start.red) * f,
        green = start.green + (end.green - start.green) * f,
        blue = start.blue + (end.blue - start.blue) * f,
        alpha = start.alpha + (end.alpha - start.alpha) * f,
    )
}
