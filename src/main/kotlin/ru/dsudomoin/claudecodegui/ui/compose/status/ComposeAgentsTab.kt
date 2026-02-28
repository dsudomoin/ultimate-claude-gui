package ru.dsudomoin.claudecodegui.ui.compose.status

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors
import ru.dsudomoin.claudecodegui.ui.status.SubagentInfo
import ru.dsudomoin.claudecodegui.ui.status.SubagentStatus

@Composable
fun ComposeAgentsTab(
    agents: List<SubagentInfo>,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    if (agents.isEmpty()) {
        EmptyTabPlaceholder(
            text = UcuBundle.message("status.noAgents"),
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(4.dp),
    ) {
        agents.forEach { agent ->
            AgentRow(agent = agent)
        }
    }
}

@Composable
private fun AgentRow(
    agent: SubagentInfo,
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
        AgentStatusIcon(status = agent.status)

        Spacer(Modifier.width(8.dp))

        // Type badge
        TypeBadge(type = agent.type)

        Spacer(Modifier.width(8.dp))

        // Description
        Text(
            text = agent.description,
            style = TextStyle(
                fontSize = 12.sp,
                color = colors.textPrimary,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AgentStatusIcon(status: SubagentStatus) {
    val colors = LocalClaudeColors.current

    when (status) {
        SubagentStatus.RUNNING -> {
            SpinnerIcon(
                color = colors.statusProgress,
                size = 14.dp,
            )
        }

        SubagentStatus.COMPLETED -> {
            Text(
                text = "\u2713",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = colors.statusSuccess,
                ),
            )
        }

        SubagentStatus.ERROR -> {
            Text(
                text = "\u2717",
                style = TextStyle(
                    fontSize = 12.sp,
                    color = colors.statusError,
                ),
            )
        }
    }
}

@Composable
private fun TypeBadge(type: String) {
    val colors = LocalClaudeColors.current

    Text(
        text = type,
        style = TextStyle(
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textSecondary,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surfaceTertiary)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
