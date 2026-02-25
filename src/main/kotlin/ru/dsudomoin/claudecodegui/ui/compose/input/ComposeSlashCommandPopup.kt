package ru.dsudomoin.claudecodegui.ui.compose.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.command.SlashCommand
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Content composable for the slash-command autocomplete popup.
 *
 * The JWindow shell stays in Swing (for non-focusable behaviour), but
 * its content is this Compose panel rendered inside a ComposePanel.
 *
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.input.SlashCommandPopup].
 */
@Composable
fun ComposeSlashCommandContent(
    commands: List<SlashCommand>,
    selectedIndex: Int,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .background(colors.dropdownBg),
    ) {
        itemsIndexed(commands) { index, cmd ->
            CommandItem(
                command = cmd,
                isSelected = index == selectedIndex,
                onClick = { onSelect(cmd) },
            )
        }
    }
}

@Composable
private fun CommandItem(
    command: SlashCommand,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bg = when {
        isSelected -> colors.chipSelectedBg
        isHovered -> colors.hoverOverlay
        else -> colors.dropdownBg
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        // Command name
        Text(
            text = command.name,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            ),
        )

        Spacer(Modifier.height(2.dp))

        // Description
        Text(
            text = command.description,
            style = TextStyle(fontSize = 12.sp, color = colors.textSecondary),
        )
    }
}
