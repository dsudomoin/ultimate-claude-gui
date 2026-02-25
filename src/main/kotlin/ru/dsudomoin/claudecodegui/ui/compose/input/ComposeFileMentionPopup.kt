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
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Content composable for the @-file mention autocomplete popup.
 *
 * Uses [MentionSuggestionData] to decouple from IntelliJ-specific types.
 * [onSelect] receives the index of the selected item.
 */
@Composable
fun ComposeFileMentionContent(
    suggestions: List<MentionSuggestionData>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .background(colors.dropdownBg),
    ) {
        itemsIndexed(suggestions.take(MAX_VISIBLE_ITEMS)) { index, entry ->
            FileEntryItem(
                entry = entry,
                isSelected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun FileEntryItem(
    entry: MentionSuggestionData,
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
        // Filename
        Text(
            text = entry.fileName,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = colors.textPrimary,
            ),
        )

        Spacer(Modifier.height(2.dp))

        // Relative path or library name
        val pathText = if (entry.isLibrary) {
            entry.libraryName ?: entry.relativePath
        } else {
            entry.relativePath
        }
        Text(
            text = pathText,
            style = TextStyle(fontSize = 12.sp, color = colors.textSecondary),
        )
    }
}

private const val MAX_VISIBLE_ITEMS = 10
