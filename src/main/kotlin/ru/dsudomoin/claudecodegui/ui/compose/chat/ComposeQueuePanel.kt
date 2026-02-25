package ru.dsudomoin.claudecodegui.ui.compose.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors
import java.io.File

/**
 * Displays queued messages in a compact list above the chat input area.
 *
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.chat.QueuePanel].
 */
@Composable
fun ComposeQueuePanel(
    items: List<QueueItemData>,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val colors = LocalClaudeColors.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Header
        Text(
            text = UcuBundle.message("queue.header", items.size),
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textSecondary,
            ),
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )

        // Items
        items.forEach { item ->
            QueueItemRow(
                item = item,
                onRemove = { onRemove(item.index) },
            )
        }
    }
}

/**
 * Data for a single queued message — decoupled from ChatPanel.QueuedMessage.
 */
data class QueueItemData(
    val text: String,
    val imageCount: Int,
    val index: Int,
)

@Composable
private fun QueueItemRow(
    item: QueueItemData,
    onRemove: () -> Unit,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(shape)
            .background(if (isHovered) colors.surfaceHover else colors.surfaceSecondary.copy(alpha = 0f), shape)
            .hoverable(interactionSource)
            .padding(horizontal = 8.dp),
    ) {
        // Badge #N
        val badgeBg = colors.accent.copy(alpha = 0.1f)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(badgeBg)
                .padding(horizontal = 4.dp, vertical = 1.dp),
        ) {
            Text(
                text = "#${item.index + 1}",
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent,
                ),
            )
        }

        Spacer(Modifier.width(6.dp))

        // Image count indicator
        if (item.imageCount > 0) {
            Text(
                text = "\uD83D\uDDBC ${item.imageCount}",
                style = TextStyle(fontSize = 10.sp, color = colors.textSecondary),
            )
            Spacer(Modifier.width(6.dp))
        }

        // Message text (truncated)
        val displayText = item.text.replace('\n', ' ').trim()
        Text(
            text = displayText,
            style = TextStyle(fontSize = 12.sp, color = colors.textPrimary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(8.dp))

        // Remove button
        val removeInteraction = remember { MutableInteractionSource() }
        val removeHovered by removeInteraction.collectIsHoveredAsState()
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (removeHovered) colors.iconHoverBg else colors.surfaceSecondary.copy(alpha = 0f))
                .hoverable(removeInteraction)
                .clickable(onClick = onRemove)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(
                text = "\u2715",
                style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
            )
        }
    }
}
