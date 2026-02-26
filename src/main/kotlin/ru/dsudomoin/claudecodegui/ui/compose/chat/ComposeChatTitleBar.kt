package ru.dsudomoin.claudecodegui.ui.compose.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Compact title bar above the message list.
 * Shows session title with inline edit on pencil click.
 */
@Composable
fun ComposeChatTitleBar(
    title: String,
    onTitleChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    var isEditing by remember { mutableStateOf(false) }
    var editValue by remember(title) { mutableStateOf(TextFieldValue(title, TextRange(title.length))) }
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .hoverable(interactionSource)
            .padding(horizontal = 12.dp),
    ) {
        if (isEditing) {
            // Edit mode: text field + save/cancel buttons
            BasicTextField(
                value = editValue,
                onValueChange = { newValue ->
                    if (newValue.text.length <= 50) editValue = newValue
                },
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier
                    .weight(1f)
                    .height(24.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.surfacePrimary)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when (event.key) {
                                Key.Enter -> {
                                    val trimmed = editValue.text.trim()
                                    if (trimmed.isNotEmpty() && trimmed != title) {
                                        onTitleChange(trimmed)
                                    }
                                    isEditing = false
                                    true
                                }
                                Key.Escape -> {
                                    editValue = TextFieldValue(title, TextRange(title.length))
                                    isEditing = false
                                    true
                                }
                                else -> false
                            }
                        } else false
                    },
            )

            Spacer(Modifier.width(4.dp))

            // Save button ✓
            TitleActionButton(
                icon = "\u2713",
                onClick = {
                    val trimmed = editValue.text.trim()
                    if (trimmed.isNotEmpty() && trimmed != title) {
                        onTitleChange(trimmed)
                    }
                    isEditing = false
                },
            )
            Spacer(Modifier.width(2.dp))
            // Cancel button ✕
            TitleActionButton(
                icon = "\u2715",
                onClick = {
                    editValue = TextFieldValue(title, TextRange(title.length))
                    isEditing = false
                },
            )

            // Auto-focus when entering edit mode
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
        } else {
            // Display mode: title text + pencil on hover
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
                modifier = Modifier.weight(1f),
            )

            if (isHovered) {
                Spacer(Modifier.width(4.dp))
                TitleActionButton(
                    icon = "\u270E", // ✎
                    onClick = {
                        editValue = TextFieldValue(title, TextRange(title.length))
                        isEditing = true
                    },
                    tooltip = UcuBundle.message("chat.titleEdit"),
                )
            }
        }
    }
}

@Composable
private fun TitleActionButton(
    icon: String,
    onClick: () -> Unit,
    tooltip: String? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) colors.hoverOverlay else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(
            text = icon,
            style = TextStyle(fontSize = 12.sp, color = colors.textSecondary),
        )
    }
}
