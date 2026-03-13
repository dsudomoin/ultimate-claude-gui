package ru.dsudomoin.claudecodegui.ui.compose.common

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.dsudomoin.claudecodegui.ui.compose.theme.scaledSp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * A container that collapses content exceeding [maxCollapsedHeight] with a gradient
 * overlay and a "Show more" / "Show less" toggle. Inspired by JetBrains agents frontend
 * ExpandableBox pattern.
 *
 * When content fits within [maxCollapsedHeight], no controls are shown.
 */
@Composable
fun ExpandableBox(
    maxCollapsedHeight: Dp = 400.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalClaudeColors.current
    var expanded by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val maxPx = with(density) { maxCollapsedHeight.toPx() }
    var overflows by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .animateContentSize()
                .then(if (!expanded) Modifier.heightIn(max = maxCollapsedHeight) else Modifier)
                .clipToBounds(),
        ) {
            Box(
                modifier = Modifier.onGloballyPositioned { coords ->
                    if (!expanded) {
                        overflows = coords.size.height.toFloat() >= maxPx - 2f
                    }
                },
            ) {
                content()
            }

            if (!expanded && overflows) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    colors.surfaceSecondary.copy(alpha = 0f),
                                    colors.surfaceSecondary,
                                ),
                            ),
                        ),
                )
            }
        }

        if (overflows || expanded) {
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    text = if (expanded) {
                        UcuBundle.message("expandable.showLess")
                    } else {
                        UcuBundle.message("expandable.showMore")
                    },
                    style = TextStyle(
                        fontSize = scaledSp(11),
                        color = colors.accent,
                    ),
                )
            }
        }
    }
}
