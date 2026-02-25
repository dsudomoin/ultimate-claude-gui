package ru.dsudomoin.claudecodegui.ui.compose.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text

/**
 * Rounded pill-shaped badge with click handler.
 *
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.common.BadgeChip].
 */
@Composable
fun ComposeBadgeChip(
    text: String,
    backgroundColor: Color,
    textColor: Color = Color(0xDF, 0xE1, 0xE5),
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    val borderColor = backgroundColor.copy(
        red = (backgroundColor.red * 0.8f).coerceIn(0f, 1f),
        green = (backgroundColor.green * 0.8f).coerceIn(0f, 1f),
        blue = (backgroundColor.blue * 0.8f).coerceIn(0f, 1f),
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(18.dp)
            .clip(shape)
            .background(backgroundColor, shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 11.sp, color = textColor),
        )
    }
}
