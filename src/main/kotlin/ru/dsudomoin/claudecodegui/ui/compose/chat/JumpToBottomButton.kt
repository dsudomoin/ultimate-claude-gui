package ru.dsudomoin.claudecodegui.ui.compose.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import ru.dsudomoin.claudecodegui.ui.compose.theme.scaledSp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Floating "jump to bottom" button, shown when user scrolls away from the bottom.
 * Three visual states: default, hovered, pressed.
 */
@Composable
fun JumpToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier,
    ) {
        val colors = LocalClaudeColors.current
        val interactionSource = remember { MutableInteractionSource() }
        val isHovered by interactionSource.collectIsHoveredAsState()
        val isPressed by interactionSource.collectIsPressedAsState()

        val bgAlpha = when {
            isPressed -> 1f
            isHovered -> 0.9f
            else -> 0.75f
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(8.dp)
                .size(36.dp)
                .shadow(if (isPressed) 2.dp else 4.dp, CircleShape)
                .clip(CircleShape)
                .border(1.dp, colors.borderNormal.copy(alpha = 0.3f), CircleShape)
                .background(colors.accentSecondary.copy(alpha = bgAlpha))
                .hoverable(interactionSource)
                .clickable(interactionSource = interactionSource, indication = null) { onClick() }
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(
                text = "\u2193",
                style = TextStyle(fontSize = scaledSp(18), color = colors.surfacePrimary),
            )
        }
    }
}
