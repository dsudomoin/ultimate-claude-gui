package ru.dsudomoin.claudecodegui.ui.compose.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.core.session.SessionInfo
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Session history browser panel.
 *
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.history.HistoryPanel].
 */
@Composable
fun ComposeHistoryPanel(
    sessions: List<SessionInfo>,
    onLoadSession: (String) -> Unit,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    var searchQuery by remember { mutableStateOf("") }

    val filteredSessions by remember(sessions, searchQuery) {
        derivedStateOf {
            val q = searchQuery.trim().lowercase()
            if (q.isEmpty()) sessions
            else sessions.filter {
                it.title.lowercase().contains(q) || it.sessionId.contains(q)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceSecondary),
    ) {
        // ── Header: ← Back ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        ) {
            Text(
                text = "\u2190 ${UcuBundle.message("history.back")}",
                style = TextStyle(fontSize = 13.sp, color = colors.textPrimary),
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .pointerHoverIcon(PointerIcon.Hand),
            )
        }

        // ── Toolbar: stats | refresh | search ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            // Stats
            val totalMessages = sessions.sumOf { it.messageCount }
            Text(
                text = UcuBundle.message("history.stats", sessions.size, totalMessages),
                style = TextStyle(fontSize = 12.sp, color = colors.textSecondary),
            )

            Spacer(Modifier.width(6.dp))

            // Refresh
            Text(
                text = "\u21BB",
                style = TextStyle(fontSize = 14.sp, color = colors.textSecondary),
                modifier = Modifier
                    .clickable(onClick = onRefresh)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(4.dp),
            )

            Spacer(Modifier.weight(1f))

            // Search field
            BasicTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = colors.textPrimary),
                decorationBox = { inner ->
                    Box(
                        contentAlignment = Alignment.CenterStart,
                        modifier = Modifier
                            .width(180.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.surfacePrimary)
                            .border(1.dp, colors.dropdownBorder, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = UcuBundle.message("history.search"),
                                style = TextStyle(fontSize = 12.sp, color = colors.textSecondary),
                            )
                        }
                        inner()
                    }
                },
            )
        }

        Spacer(Modifier.height(4.dp))

        // ── Session list ──
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            items(filteredSessions, key = { it.sessionId }) { session ->
                SessionCard(
                    session = session,
                    onDoubleClick = { onLoadSession(session.sessionId) },
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: SessionInfo,
    onDoubleClick: () -> Unit,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(8.dp)
    val bgColor = if (isHovered) colors.cardHover else colors.cardBg

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor, shape)
            .border(0.5.dp, colors.borderNormal, shape)
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClickLabel = null,
                onClick = onDoubleClick,
            )
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(10.dp),
    ) {
        // Icon
        Text(
            text = "\u2733",
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = colors.historyAccent,
            ),
            modifier = Modifier.padding(end = 8.dp),
        )

        // Center content
        Column(modifier = Modifier.weight(1f)) {
            // Top row: title + time
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val displayTitle = if (session.title.length > 24) {
                    "${session.title.take(21)}\u2026"
                } else {
                    session.title
                }
                Text(
                    text = displayTitle,
                    style = TextStyle(
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.textPrimary,
                    ),
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = relativeTime(session.lastTimestamp),
                    style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(Modifier.height(3.dp))

            // Bottom row: message count + session ID
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = UcuBundle.message("history.messages", session.messageCount),
                    style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
                )
                Text(
                    text = session.sessionId.take(8),
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary,
                    ),
                )
            }
        }
    }
}

private fun relativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        seconds < 60 -> UcuBundle.message("history.secondsAgo", seconds)
        minutes < 60 -> UcuBundle.message("history.minutesAgo", minutes)
        hours < 24 -> UcuBundle.message("history.hoursAgo", hours)
        days < 30 -> UcuBundle.message("history.daysAgo", days)
        else -> SimpleDateFormat("dd MMM yyyy").format(Date(timestamp))
    }
}
