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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors
import ru.dsudomoin.claudecodegui.ui.status.FileChangeSummary
import ru.dsudomoin.claudecodegui.ui.status.SubagentInfo
import ru.dsudomoin.claudecodegui.ui.status.SubagentStatus
import ru.dsudomoin.claudecodegui.ui.status.TodoItem
import ru.dsudomoin.claudecodegui.ui.status.TodoStatus

/**
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.status.StatusPanel].
 *
 * Collapsible panel with three tabs: Todos, File Changes, Subagents.
 * State is passed in from the parent (ChatPanel/ViewModel).
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
    val hasContent = todos.isNotEmpty() || fileChanges.isNotEmpty() || agents.isNotEmpty()
    if (!hasContent) return

    var selectedTab by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Collapsed strip (visible when collapsed)
        AnimatedVisibility(visible = !expanded) {
            CollapsedStrip(
                todos = todos,
                fileChanges = fileChanges,
                agents = agents,
                onExpand = { expanded = true },
            )
        }

        // Expanded panel (tab bar + content)
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column {
                // Tab bar
                StatusTabBar(
                    selectedTab = selectedTab,
                    onTabSelected = { selectedTab = it },
                    onCollapse = { expanded = false },
                    todos = todos,
                    fileChanges = fileChanges,
                    agents = agents,
                )

                // Content area (max height 180dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp),
                ) {
                    when (selectedTab) {
                        0 -> ComposeTodoTab(todos = todos)
                        1 -> ComposeFilesTab(
                            fileChanges = fileChanges,
                            onFileClick = onFileClick,
                            onShowDiff = onShowDiff,
                            onUndoFile = onUndoFile,
                            onDiscardAll = onDiscardAll,
                            onKeepAll = onKeepAll,
                        )
                        2 -> ComposeAgentsTab(agents = agents)
                    }
                }
            }
        }
    }
}

// ── Tab Bar ──────────────────────────────────────────────────────────────────

@Composable
private fun StatusTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    onCollapse: () -> Unit,
    todos: List<TodoItem>,
    fileChanges: List<FileChangeSummary>,
    agents: List<SubagentInfo>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    Row(
        modifier = modifier
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
        // Tabs take equal weight
        StatusTab(
            label = "Todos",
            icon = "\u2611",
            isSelected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            modifier = Modifier.weight(1f),
        ) {
            TodosTabStats(todos)
        }

        // Divider
        TabDivider()

        StatusTab(
            label = "Files",
            icon = "\u270E",
            isSelected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            modifier = Modifier.weight(1f),
        ) {
            FilesTabStats(fileChanges)
        }

        // Divider
        TabDivider()

        StatusTab(
            label = "Agents",
            icon = "\u2699",
            isSelected = selectedTab == 2,
            onClick = { onTabSelected(2) },
            modifier = Modifier.weight(1f),
        ) {
            AgentsTabStats(agents)
        }

        // Collapse button
        CollapseButton(onClick = onCollapse)
    }
}

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
            .height(20.dp)
            .padding(vertical = 3.dp)
            .background(colors.borderNormal),
    )
}

@Composable
private fun CollapseButton(onClick: () -> Unit) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .width(28.dp)
            .height(34.dp)
            .then(
                if (isHovered) Modifier.background(colors.surfaceHover) else Modifier
            )
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    ) {
        Text(
            text = "\u25BC",
            style = TextStyle(fontSize = 10.sp, color = colors.textSecondary),
        )
    }
}

// ── Tab Stats ────────────────────────────────────────────────────────────────

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

// ── Collapsed Strip ──────────────────────────────────────────────────────────

@Composable
private fun CollapsedStrip(
    todos: List<TodoItem>,
    fileChanges: List<FileChangeSummary>,
    agents: List<SubagentInfo>,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isHovered) colors.surfaceHover else colors.surfacePrimary)
            .drawBehind {
                drawRoundRect(
                    color = colors.borderNormal,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .hoverable(interactionSource)
            .clickable(onClick = onExpand)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp),
    ) {
        // Expand arrow
        Text(
            text = "\u25B6",
            style = TextStyle(fontSize = 9.sp, color = colors.textSecondary),
        )
        Spacer(Modifier.width(6.dp))

        // Brief stats
        val parts = buildList {
            val todoDone = todos.count { it.status == TodoStatus.COMPLETED }
            if (todos.isNotEmpty()) add("Todos $todoDone/${todos.size}")

            val totalAdd = fileChanges.sumOf { it.additions }
            val totalDel = fileChanges.sumOf { it.deletions }
            if (totalAdd > 0 || totalDel > 0) {
                add(buildString {
                    append("Files")
                    if (totalAdd > 0) append(" +$totalAdd")
                    if (totalDel > 0) append(" -$totalDel")
                })
            }

            val agentDone = agents.count { it.status != SubagentStatus.RUNNING }
            if (agents.isNotEmpty()) add("Agents $agentDone/${agents.size}")
        }

        parts.forEachIndexed { index, part ->
            if (index > 0) {
                Text(
                    text = " \u00B7 ",
                    style = TextStyle(fontSize = 11.sp, color = colors.borderNormal),
                )
            }
            Text(
                text = part,
                style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
            )
        }

        // Spinner if anything active
        val hasActive = todos.any { it.status == TodoStatus.IN_PROGRESS }
                || agents.any { it.status == SubagentStatus.RUNNING }
        if (hasActive) {
            Spacer(Modifier.width(4.dp))
            SpinnerIcon(color = colors.statusProgress, size = 10.dp)
        }
    }
}
