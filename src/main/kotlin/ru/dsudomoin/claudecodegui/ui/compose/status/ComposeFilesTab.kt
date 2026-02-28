package ru.dsudomoin.claudecodegui.ui.compose.status

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors
import ru.dsudomoin.claudecodegui.ui.status.FileChangeSummary
import ru.dsudomoin.claudecodegui.ui.status.FileChangeType

@Composable
fun ComposeFilesTab(
    fileChanges: List<FileChangeSummary>,
    onFileClick: (String) -> Unit,
    onShowDiff: (FileChangeSummary) -> Unit,
    onUndoFile: (FileChangeSummary) -> Unit,
    onDiscardAll: () -> Unit,
    onKeepAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    if (fileChanges.isEmpty()) {
        EmptyTabPlaceholder(
            text = UcuBundle.message("status.noFiles"),
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Batch actions bar
        BatchActionsBar(
            onDiscardAll = onDiscardAll,
            onKeepAll = onKeepAll,
        )

        // File list
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(4.dp),
        ) {
            fileChanges.forEach { fc ->
                FileRow(
                    file = fc,
                    onFileClick = { onFileClick(fc.filePath) },
                    onShowDiff = { onShowDiff(fc) },
                    onUndo = { onUndoFile(fc) },
                )
            }
        }
    }
}

@Composable
private fun BatchActionsBar(
    onDiscardAll: () -> Unit,
    onKeepAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(colors.surfaceTertiary)
            .padding(horizontal = 8.dp),
    ) {
        // Discard All
        BatchActionButton(
            text = "Discard All",
            isDestructive = true,
            onClick = onDiscardAll,
        )

        Spacer(Modifier.width(6.dp))

        // Keep All
        BatchActionButton(
            text = "Keep All",
            isDestructive = false,
            onClick = onKeepAll,
        )
    }
}

@Composable
private fun BatchActionButton(
    text: String,
    isDestructive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor = when {
        isDestructive && isHovered -> colors.statusError.copy(alpha = 0.1f)
        !isDestructive && isHovered -> colors.surfaceHover
        else -> Color.Transparent
    }
    val borderColor = if (isDestructive && isHovered) colors.statusError else colors.borderNormal
    val textColor = if (isDestructive && isHovered) colors.statusError else colors.textPrimary

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(22.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 11.sp,
                color = textColor,
            ),
        )
    }
}

@Composable
private fun FileRow(
    file: FileChangeSummary,
    onFileClick: () -> Unit,
    onShowDiff: () -> Unit,
    onUndo: () -> Unit,
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
        // A/M badge
        FileChangeBadge(changeType = file.changeType)

        Spacer(Modifier.width(6.dp))

        // File name (clickable)
        Text(
            text = file.fileName,
            style = TextStyle(
                fontSize = 12.sp,
                color = colors.textPrimary,
                textDecoration = if (isHovered) TextDecoration.Underline else TextDecoration.None,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onFileClick)
                .pointerHoverIcon(PointerIcon.Hand),
        )

        // Stats (+N -N)
        if (file.additions > 0) {
            Text(
                text = "+${file.additions}",
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.diffAddFg,
                ),
            )
            Spacer(Modifier.width(3.dp))
        }
        if (file.deletions > 0) {
            Text(
                text = "-${file.deletions}",
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.diffDelFg,
                ),
            )
            Spacer(Modifier.width(3.dp))
        }

        Spacer(Modifier.width(4.dp))

        // Diff button
        IconActionButton(label = "\u2194", onClick = onShowDiff)

        Spacer(Modifier.width(2.dp))

        // Undo button
        IconActionButton(label = "\u21A9", onClick = onUndo)
    }
}

@Composable
private fun FileChangeBadge(changeType: FileChangeType) {
    val colors = LocalClaudeColors.current
    val badgeText = if (changeType == FileChangeType.ADDED) "A" else "M"
    val badgeColor = if (changeType == FileChangeType.ADDED) colors.statusSuccess else colors.accent

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(badgeColor.copy(alpha = 0.1f))
            .padding(horizontal = 4.dp, vertical = 1.dp),
    ) {
        Text(
            text = badgeText,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = badgeColor,
            ),
        )
    }
}

@Composable
private fun IconActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) colors.iconHoverBg else Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(3.dp),
    ) {
        Text(
            text = label,
            style = TextStyle(fontSize = 12.sp),
        )
    }
}
