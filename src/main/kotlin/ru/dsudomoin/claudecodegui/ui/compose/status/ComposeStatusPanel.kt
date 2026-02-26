package ru.dsudomoin.claudecodegui.ui.compose.status

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors
import ru.dsudomoin.claudecodegui.ui.status.FileChangeSummary
import ru.dsudomoin.claudecodegui.ui.status.SubagentInfo
import ru.dsudomoin.claudecodegui.ui.status.SubagentStatus
import ru.dsudomoin.claudecodegui.ui.status.TodoItem
import ru.dsudomoin.claudecodegui.ui.status.TodoStatus

/**
 * Always-visible status panel: horizontal tab bar with three independently expandable tabs.
 *
 * Layout:
 * ```
 * ┌───────────────┬───────────────┬───────────────┐
 * │ ☑ Tasks 3/6   │ ⚙ Subagent 2/3│ ✎ Edits +5 -2│
 * └───────────────┴───────────────┴───────────────┘
 *   ▼ expanded content for whichever tab(s) are open
 * ```
 */
@Composable
fun ComposeStatusPanel(
    todos: List<TodoItem>,
    fileChanges: List<FileChangeSummary>,
    agents: List<SubagentInfo>,
    onFileClick: (String) -> Unit,
    onShowDiff: (FileChangeSummary) -> Unit,
    onUndoFile: (FileChangeSummary) -> Unit,
    onDiscardAll: () -> Unit,
    onKeepAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    // Only one tab can be expanded at a time (null = all collapsed)
    var expandedTab by remember { mutableStateOf<StatusTabId?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // ── Horizontal tab bar ───────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfacePrimary)
                .drawBehind {
                    drawRoundRect(
                        color = colors.borderNormal,
                        cornerRadius = CornerRadius(8.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                },
        ) {
            StatusTab(
                label = "Tasks",
                icon = "\u2611",
                isSelected = expandedTab == StatusTabId.TODOS,
                onClick = { expandedTab = if (expandedTab == StatusTabId.TODOS) null else StatusTabId.TODOS },
                modifier = Modifier.weight(1f),
            ) {
                TodosTabStats(todos)
            }

            TabDivider()

            StatusTab(
                label = "Subagent",
                icon = "\u2699",
                isSelected = expandedTab == StatusTabId.AGENTS,
                onClick = { expandedTab = if (expandedTab == StatusTabId.AGENTS) null else StatusTabId.AGENTS },
                modifier = Modifier.weight(1f),
            ) {
                AgentsTabStats(agents)
            }

            TabDivider()

            StatusTab(
                label = "Edits",
                icon = "\u270E",
                isSelected = expandedTab == StatusTabId.FILES,
                onClick = { expandedTab = if (expandedTab == StatusTabId.FILES) null else StatusTabId.FILES },
                modifier = Modifier.weight(1f),
            ) {
                FilesTabStats(fileChanges)
            }
        }

        // ── Expanded content areas ───────────────────────────────────────────
        AnimatedVisibility(
            visible = expandedTab == StatusTabId.TODOS,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .padding(top = 4.dp),
            ) {
                if (todos.isNotEmpty()) {
                    ComposeTodoTab(todos = todos)
                } else {
                    EmptyMessage(UcuBundle.message("status.noTodos"))
                }
            }
        }

        AnimatedVisibility(
            visible = expandedTab == StatusTabId.AGENTS,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .padding(top = 4.dp),
            ) {
                if (agents.isNotEmpty()) {
                    ComposeAgentsTab(agents = agents)
                } else {
                    EmptyMessage(UcuBundle.message("status.noAgents"))
                }
            }
        }

        AnimatedVisibility(
            visible = expandedTab == StatusTabId.FILES,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .padding(top = 4.dp),
            ) {
                if (fileChanges.isNotEmpty()) {
                    ComposeFilesTab(
                        fileChanges = fileChanges,
                        onFileClick = onFileClick,
                        onShowDiff = onShowDiff,
                        onUndoFile = onUndoFile,
                        onDiscardAll = onDiscardAll,
                        onKeepAll = onKeepAll,
                    )
                } else {
                    EmptyMessage(UcuBundle.message("status.noFiles"))
                }
            }
        }
    }
}

// ── Tab ─────────────────────────────────────────────────────────────────────

@Composable
private fun StatusTab(
    label: String,
    icon: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stats: @Composable () -> Unit,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor = when {
        isSelected -> colors.surfaceTertiary
        isHovered -> colors.surfaceHover
        else -> Color.Transparent
    }
    val textColor = if (isSelected || isHovered) colors.textPrimary else colors.textSecondary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .height(34.dp)
            .background(bgColor)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = icon,
            style = TextStyle(fontSize = 12.sp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
            ),
        )
        Spacer(Modifier.width(4.dp))
        stats()
    }
}

@Composable
private fun TabDivider() {
    val colors = LocalClaudeColors.current
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(34.dp)
            .background(colors.borderNormal),
    )
}

// ── Tab Stats ───────────────────────────────────────────────────────────────

@Composable
private fun TodosTabStats(todos: List<TodoItem>) {
    if (todos.isEmpty()) return
    val colors = LocalClaudeColors.current
    val done = todos.count { it.status == TodoStatus.COMPLETED }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$done/${todos.size}",
            style = TextStyle(fontSize = 10.sp, color = colors.textSecondary),
        )
        if (todos.any { it.status == TodoStatus.IN_PROGRESS }) {
            Spacer(Modifier.width(4.dp))
            SpinnerIcon(color = colors.statusProgress, size = 10.dp)
        }
    }
}

@Composable
private fun FilesTabStats(fileChanges: List<FileChangeSummary>) {
    if (fileChanges.isEmpty()) return
    val colors = LocalClaudeColors.current
    val totalAdd = fileChanges.sumOf { it.additions }
    val totalDel = fileChanges.sumOf { it.deletions }

    Row(verticalAlignment = Alignment.CenterVertically) {
        if (totalAdd > 0) {
            Text(
                text = "+$totalAdd",
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.diffAddFg,
                ),
            )
            Spacer(Modifier.width(3.dp))
        }
        if (totalDel > 0) {
            Text(
                text = "-$totalDel",
                style = TextStyle(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.diffDelFg,
                ),
            )
        }
    }
}

@Composable
private fun AgentsTabStats(agents: List<SubagentInfo>) {
    if (agents.isEmpty()) return
    val colors = LocalClaudeColors.current
    val done = agents.count { it.status != SubagentStatus.RUNNING }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$done/${agents.size}",
            style = TextStyle(fontSize = 10.sp, color = colors.textSecondary),
        )
        if (agents.any { it.status == SubagentStatus.RUNNING }) {
            Spacer(Modifier.width(4.dp))
            SpinnerIcon(color = colors.statusProgress, size = 10.dp)
        }
    }
}

// ── Empty message ───────────────────────────────────────────────────────────

private enum class StatusTabId { TODOS, AGENTS, FILES }

@Composable
private fun EmptyMessage(text: String) {
    val colors = LocalClaudeColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
        )
    }
}
