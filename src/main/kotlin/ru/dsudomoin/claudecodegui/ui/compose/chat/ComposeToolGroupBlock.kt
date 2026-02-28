package ru.dsudomoin.claudecodegui.ui.compose.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.ui.compose.common.ComposeMarkdownContent
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Tool category for grouping.
 */
enum class ToolCategoryType { READ, EDIT, BASH, SEARCH, OTHER }

/**
 * Data for a single item inside a tool group.
 */
data class ToolGroupItemData(
    val id: String,
    val toolName: String,
    val summary: String,
    val status: ToolStatus = ToolStatus.PENDING,
    val diffAdditions: Int = 0,
    val diffDeletions: Int = 0,
    val isFileLink: Boolean = false,
    val expandable: ExpandableContent? = null,
    val filePath: String? = null,
)

/**
 * Data for a tool group block.
 */
data class ToolGroupData(
    val category: ToolCategoryType,
    val items: List<ToolGroupItemData>,
)

/**
 * Collapsible group block combining consecutive tool uses of the same category.
 *
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.chat.ToolGroupBlock].
 */
@Composable
fun ComposeToolGroupBlock(
    data: ToolGroupData,
    onFileClick: ((String) -> Unit)? = null,
    onToolShowDiff: ((ExpandableContent) -> Unit)? = null,
    onToolRevert: ((ExpandableContent) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var expanded by remember { mutableStateOf(false) }

    val aggregateStatus = when {
        data.items.any { it.status == ToolStatus.ERROR } -> ToolStatus.ERROR
        data.items.any { it.status == ToolStatus.PENDING } -> ToolStatus.PENDING
        else -> ToolStatus.COMPLETED
    }
    val statusColor = when (aggregateStatus) {
        ToolStatus.PENDING -> colors.statusWarning
        ToolStatus.COMPLETED -> colors.statusSuccess
        ToolStatus.ERROR -> colors.statusError
    }
    val borderColor = if (aggregateStatus == ToolStatus.COMPLETED) colors.borderNormal else statusColor.copy(alpha = 0.5f)
    val backgroundColor = when (aggregateStatus) {
        ToolStatus.ERROR -> colors.statusError.copy(alpha = 0.06f)
        ToolStatus.PENDING -> colors.statusWarning.copy(alpha = 0.05f)
        ToolStatus.COMPLETED -> colors.surfacePrimary
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, borderColor, shape)
            .background(if (isHovered && !expanded) colors.surfaceHover else backgroundColor)
            .hoverable(interactionSource),
    ) {
        // Group header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clickable { expanded = !expanded }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(horizontal = 12.dp),
        ) {
            // Chevron
            Text(
                text = if (expanded) "\u25BC" else "\u25B6",
                style = TextStyle(fontSize = 10.sp, color = colors.textSecondary),
            )
            Spacer(Modifier.width(6.dp))

            // Category icon
            Text(
                text = getCategoryEmoji(data.category),
                style = TextStyle(fontSize = 13.sp),
            )
            Spacer(Modifier.width(6.dp))

            // Category title with count
            Text(
                text = "${getCategoryLabel(data.category)} (${data.items.size})",
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colors.textPrimary),
            )
            Spacer(Modifier.width(8.dp))

            // Aggregate diff stats for edit groups
            if (data.category == ToolCategoryType.EDIT) {
                val totalAdd = data.items.sumOf { it.diffAdditions }
                val totalDel = data.items.sumOf { it.diffDeletions }
                if (totalAdd > 0) {
                    Text(
                        text = "+$totalAdd",
                        style = TextStyle(fontSize = 11.sp, color = colors.diffAddFg),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (totalDel > 0) {
                    Text(
                        text = "-$totalDel",
                        style = TextStyle(fontSize = 11.sp, color = colors.diffDelFg),
                    )
                    Spacer(Modifier.width(4.dp))
                }
            }

            Spacer(Modifier.weight(1f))

            // Aggregate status
            ToolStatusBadge(status = aggregateStatus, compact = true)
        }

        // Expandable items list
        AnimatedVisibility(
            visible = expanded,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Separator
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.borderNormal)
                )

                // Items (max visible with scroll handled by parent)
                data.items.forEach { item ->
                    GroupItemRow(
                        item = item,
                        category = data.category,
                        onFileClick = onFileClick,
                        onShowDiff = item.expandable?.let { exp -> onToolShowDiff?.let { cb -> { cb(exp) } } },
                        onRevert = item.expandable?.let { exp -> onToolRevert?.let { cb -> { cb(exp) } } },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupItemRow(
    item: ToolGroupItemData,
    category: ToolCategoryType = ToolCategoryType.OTHER,
    onFileClick: ((String) -> Unit)?,
    onShowDiff: (() -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var itemExpanded by remember { mutableStateOf(false) }
    val hasExpandable = item.expandable != null

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .hoverable(interactionSource)
                .then(
                    if (isHovered) Modifier.background(colors.surfaceHover) else Modifier
                )
                .then(
                    if (hasExpandable) Modifier
                        .clickable { itemExpanded = !itemExpanded }
                        .pointerHoverIcon(PointerIcon.Hand)
                    else Modifier
                )
                .padding(horizontal = 12.dp),
        ) {
            // Expand chevron for items with expandable content
            if (hasExpandable) {
                Text(
                    text = if (itemExpanded) "\u25BC" else "\u25B6",
                    style = TextStyle(fontSize = 8.sp, color = colors.textSecondary),
                )
                Spacer(Modifier.width(4.dp))
            }

            // Summary (file link or text)
            val summaryText = item.summary.ifEmpty { item.toolName }
            val isClickableFile = item.isFileLink && onFileClick != null && item.filePath != null
            val summaryInteraction = remember { MutableInteractionSource() }
            val summaryHovered by summaryInteraction.collectIsHoveredAsState()
            if (item.isFileLink) {
                ru.dsudomoin.claudecodegui.ui.compose.input.FileTypeIcon(
                    fileName = summaryText,
                    size = 12.dp,
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = summaryText,
                style = TextStyle(
                    fontSize = 12.sp,
                    color = if (item.isFileLink) colors.accent else colors.textPrimary,
                    textDecoration = if (isClickableFile && summaryHovered) TextDecoration.Underline else null,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (isClickableFile) {
                            Modifier
                                .hoverable(summaryInteraction)
                                .clickable { onFileClick(item.filePath) }
                                .pointerHoverIcon(PointerIcon.Hand)
                        } else Modifier
                    ),
            )

            // Diff stats
            if (item.diffAdditions > 0) {
                Text(
                    text = "+${item.diffAdditions}",
                    style = TextStyle(fontSize = 10.sp, color = colors.diffAddFg),
                )
                Spacer(Modifier.width(4.dp))
            }
            if (item.diffDeletions > 0) {
                Text(
                    text = "-${item.diffDeletions}",
                    style = TextStyle(fontSize = 10.sp, color = colors.diffDelFg),
                )
                Spacer(Modifier.width(4.dp))
            }

            // Action buttons (show diff / revert) for edit items
            if (isHovered && item.status == ToolStatus.COMPLETED && item.expandable != null) {
                if (onShowDiff != null) {
                    ToolActionButton(icon = "\u2194", tooltip = "Diff", onClick = onShowDiff)
                    Spacer(Modifier.width(2.dp))
                }
                if (onRevert != null) {
                    ToolActionButton(icon = "\u21A9", tooltip = "Revert", onClick = onRevert)
                    Spacer(Modifier.width(2.dp))
                }
            }

            Spacer(Modifier.width(4.dp))

            // Item status
            ToolStatusBadge(status = item.status, compact = true)
        }

        // Expandable content for this item
        AnimatedVisibility(
            visible = itemExpanded && hasExpandable,
            enter = EnterTransition.None,
            exit = ExitTransition.None,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceSecondary),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.borderNormal)
                )

                when (val content = item.expandable) {
                    is ExpandableContent.Diff -> {
                        DiffContentPanel(
                            oldString = content.oldString,
                            newString = content.newString,
                            filePath = content.filePath,
                        )
                    }
                    is ExpandableContent.Code -> {
                        CodeContentPanel(
                            code = content.content,
                            filePath = content.filePath,
                        )
                    }
                    is ExpandableContent.PlainText -> {
                        val plainScrollState = rememberScrollState()
                        Text(
                            text = content.text.take(2000),
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary,
                            ),
                            softWrap = false,
                            modifier = Modifier
                                .horizontalScroll(plainScrollState)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    is ExpandableContent.Markdown -> {
                        val mdScrollState = rememberScrollState()
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp)
                                .verticalScroll(mdScrollState)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            ComposeMarkdownContent(markdown = content.text, selectable = false)
                        }
                    }
                    null -> {}
                }
            }
        }
    }
}

private fun getCategoryEmoji(category: ToolCategoryType): String = when (category) {
    ToolCategoryType.READ -> "\uD83D\uDC41"
    ToolCategoryType.EDIT -> "\u270F\uFE0F"
    ToolCategoryType.BASH -> "\u2699\uFE0F"
    ToolCategoryType.SEARCH -> "\uD83D\uDD0D"
    ToolCategoryType.OTHER -> "\u26A1"
}

private fun getCategoryLabel(category: ToolCategoryType): String = when (category) {
    ToolCategoryType.READ -> "Read"
    ToolCategoryType.EDIT -> "Edit"
    ToolCategoryType.BASH -> "Bash"
    ToolCategoryType.SEARCH -> "Search"
    ToolCategoryType.OTHER -> "Tools"
}
