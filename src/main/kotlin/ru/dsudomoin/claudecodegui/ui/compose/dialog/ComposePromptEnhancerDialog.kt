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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Prompt enhancer overlay dialog.
 * Shows original prompt and enhanced version side by side (vertically).
 * User can choose to use the enhanced prompt or keep the original.
 */
@Composable
fun ComposePromptEnhancerDialog(
    originalText: String,
    enhancedText: String,
    isLoading: Boolean,
    error: String?,
    onUseEnhanced: () -> Unit,
    onKeepOriginal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val shape = RoundedCornerShape(8.dp)

    // Semi-transparent backdrop
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onKeepOriginal,
            ),
    ) {
        // Dialog card
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .shadow(12.dp, shape)
                .clip(shape)
                .background(colors.surfacePrimary)
                .border(1.dp, colors.borderNormal, shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}, // prevent click-through
                ),
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.planBarBg)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "\u26A1",
                    style = TextStyle(fontSize = 16.sp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = UcuBundle.message("enhancer.title"),
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    ),
                )
            }

            // Separator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.borderNormal),
            )

            // ── Original prompt section ─────────────────────────────────────
            PromptSection(
                label = UcuBundle.message("enhancer.original"),
                text = originalText,
            )

            // Separator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.borderNormal),
            )

            // ── Enhanced prompt section ─────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = UcuBundle.message("enhancer.enhanced"),
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textSecondary,
                    ),
                )
                Spacer(Modifier.height(6.dp))

                when {
                    isLoading -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Simple loading indicator
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.accent),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = UcuBundle.message("enhancer.enhancing"),
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = colors.textSecondary,
                                ),
                            )
                        }
                    }
                    error != null -> {
                        Text(
                            text = error,
                            style = TextStyle(
                                fontSize = 13.sp,
                                color = colors.statusError,
                            ),
                        )
                    }
                    else -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp, max = 200.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.codeBg)
                                .verticalScroll(rememberScrollState())
                                .padding(10.dp),
                        ) {
                            Text(
                                text = enhancedText,
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    color = colors.textPrimary,
                                ),
                            )
                        }
                    }
                }
            }

            // Separator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(colors.borderNormal),
            )

            // ── Action buttons ──────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Spacer(Modifier.weight(1f))

                // Keep Original
                EnhancerButton(
                    text = UcuBundle.message("enhancer.keepOriginal"),
                    backgroundColor = colors.denyBg,
                    hoverColor = colors.denyHover,
                    textColor = colors.textPrimary,
                    borderColor = colors.denyBorder,
                    enabled = true,
                    onClick = onKeepOriginal,
                )

                Spacer(Modifier.width(8.dp))

                // Use Enhanced
                EnhancerButton(
                    text = UcuBundle.message("enhancer.useEnhanced"),
                    backgroundColor = colors.approveBg,
                    hoverColor = colors.approveHover,
                    textColor = Color.White,
                    borderColor = null,
                    enabled = !isLoading && enhancedText.isNotEmpty() && error == null,
                    onClick = onUseEnhanced,
                )
            }
        }
    }
}

@Composable
private fun PromptSection(
    label: String,
    text: String,
) {
    val colors = LocalClaudeColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.textSecondary,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp, max = 150.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.codeBg)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(
                text = text,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                ),
            )
        }
    }
}

@Composable
private fun EnhancerButton(
    text: String,
    backgroundColor: Color,
    hoverColor: Color,
    textColor: Color,
    borderColor: Color?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    val alpha = if (enabled) 1f else 0.5f
    val bgColor = if (isHovered && enabled) hoverColor else backgroundColor

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(shape)
            .background(bgColor.copy(alpha = alpha), shape)
            .then(
                if (borderColor != null) Modifier.border(1.dp, borderColor.copy(alpha = alpha), shape)
                else Modifier,
            )
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 20.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = textColor.copy(alpha = alpha),
            ),
        )
    }
}
