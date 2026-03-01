package ru.dsudomoin.claudecodegui.ui.compose.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Compact bar showing SDK version and optional update button.
 * Displayed between StatusPanel and InputPanel.
 */
@Composable
fun ComposeSdkVersionBar(
    currentVersion: String?,
    latestVersion: String?,
    updateAvailable: Boolean,
    updating: Boolean,
    updateError: String?,
    onUpdate: () -> Unit,
    nodeVersion: String? = null,
    initModel: String = "",
    initClaudeCodeVersion: String = "",
    initToolCount: Int = 0,
    initMcpServerCount: Int = 0,
    initAgentCount: Int = 0,
    initSkillCount: Int = 0,
    initPluginCount: Int = 0,
    initSlashCommandCount: Int = 0,
    initPermissionMode: String = "",
    initFastModeState: String = "",
    initApiKeySource: String = "",
    initBetasCount: Int = 0,
    totalInputTokens: Int = 0,
    totalOutputTokens: Int = 0,
    centered: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (currentVersion == null && nodeVersion == null && initClaudeCodeVersion.isBlank()) return

    val colors = LocalClaudeColors.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(horizontal = 12.dp),
    ) {
        // Init metadata from SDK (takes priority when available)
        val parts = mutableListOf<String>()
        if (initClaudeCodeVersion.isNotBlank()) {
            parts += "CC $initClaudeCodeVersion"
        } else if (currentVersion != null) {
            parts += UcuBundle.message("sdk.version", currentVersion)
        }
        if (nodeVersion != null) {
            parts += UcuBundle.message("node.version", nodeVersion)
        }
        if (initModel.isNotBlank()) {
            parts += initModel
        }
        if (initToolCount > 0) {
            parts += "$initToolCount tools"
        }
        if (initMcpServerCount > 0) {
            parts += "$initMcpServerCount MCP"
        }
        if (initAgentCount > 0) {
            parts += "$initAgentCount agents"
        }
        if (initSkillCount > 0) {
            parts += "$initSkillCount skills"
        }
        if (initPluginCount > 0) {
            parts += "$initPluginCount plugins"
        }
        if (initSlashCommandCount > 0) {
            parts += "$initSlashCommandCount /cmd"
        }
        if (initPermissionMode.isNotBlank()) {
            parts += "mode:$initPermissionMode"
        }
        if (initFastModeState.isNotBlank()) {
            parts += "fast:$initFastModeState"
        }
        if (initApiKeySource.isNotBlank()) {
            parts += "auth:$initApiKeySource"
        }
        if (initBetasCount > 0) {
            parts += "beta:$initBetasCount"
        }

        Text(
            text = parts.joinToString(" · "),
            style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
            maxLines = 1,
        )

        // Token usage from ResultMeta
        if (totalInputTokens > 0 || totalOutputTokens > 0) {
            Text(
                text = " · ${UcuBundle.message("sdk.usage", formatTokenCount(totalInputTokens), formatTokenCount(totalOutputTokens))}",
                style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
                maxLines = 1,
            )
        }

        if (!centered) {
            Spacer(Modifier.weight(1f))
        } else {
            Spacer(Modifier.width(6.dp))
        }

        when {
            updating -> {
                Text(
                    text = UcuBundle.message("sdk.updating"),
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                    ),
                )
            }

            updateError != null -> {
                Text(
                    text = UcuBundle.message("sdk.updateError"),
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = colors.statusError,
                    ),
                )
            }

            updateAvailable && latestVersion != null -> {
                val interactionSource = remember { MutableInteractionSource() }
                val isHovered by interactionSource.collectIsHoveredAsState()

                Text(
                    text = UcuBundle.message("sdk.updateAvailable", latestVersion),
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isHovered) colors.accentSecondary.copy(alpha = 0.8f)
                                else colors.accentSecondary,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .hoverable(interactionSource)
                        .clickable { onUpdate() }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun formatTokenCount(count: Int): String = when {
    count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format("%.1fK", count / 1_000.0)
    else -> count.toString()
}
