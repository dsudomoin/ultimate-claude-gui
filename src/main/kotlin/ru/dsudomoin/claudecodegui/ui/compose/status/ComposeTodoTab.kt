package ru.dsudomoin.claudecodegui.ui.compose.status

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors
import ru.dsudomoin.claudecodegui.ui.status.TodoItem
import ru.dsudomoin.claudecodegui.ui.status.TodoStatus

@Composable
fun ComposeTodoTab(
    todos: List<TodoItem>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    if (todos.isEmpty()) {
        EmptyTabPlaceholder(
            text = UcuBundle.message("status.noTodos"),
            modifier = modifier,
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(4.dp),
    ) {
        items(todos) { todo ->
            TodoRow(item = todo)
        }
    }
}

@Composable
private fun TodoRow(
    item: TodoItem,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .then(
                if (isHovered) Modifier.background(colors.surfaceHover)
                else Modifier
            )
            .hoverable(interactionSource)
            .padding(horizontal = 8.dp),
    ) {
        // Status icon
        TodoStatusIcon(status = item.status)

        Spacer(Modifier.width(8.dp))

        // Content text
        val textColor = if (item.status == TodoStatus.COMPLETED) colors.textSecondary else colors.textPrimary
        val decoration = if (item.status == TodoStatus.COMPLETED) TextDecoration.LineThrough else TextDecoration.None

        Text(
            text = item.content,
            style = TextStyle(
                fontSize = 12.sp,
                color = textColor,
                textDecoration = decoration,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TodoStatusIcon(status: TodoStatus) {
    val colors = LocalClaudeColors.current

    when (status) {
        TodoStatus.PENDING -> {
            // Empty circle
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .drawBehind {
                        drawCircle(
                            color = colors.statusPending,
                            radius = size.minDimension / 2 - 1.dp.toPx(),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    },
            )
        }

        TodoStatus.IN_PROGRESS -> {
            SpinnerIcon(
                color = colors.statusProgress,
                size = 14.dp,
            )
        }

        TodoStatus.COMPLETED -> {
            // Checkmark circle
            Text(
                text = "\u2713",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = colors.statusSuccess,
                ),
            )
        }
    }
}
