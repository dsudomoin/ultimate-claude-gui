package ru.dsudomoin.claudecodegui.ui.compose.status

import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
    modifier: Modifier = Modifier,
) {
    if (currentVersion == null) return

    val colors = LocalClaudeColors.current

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(horizontal = 12.dp),
    ) {
        // Version label
        Text(
            text = UcuBundle.message("sdk.version", currentVersion),
            style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
        )

        Spacer(Modifier.weight(1f))

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
