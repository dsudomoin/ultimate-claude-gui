package ru.dsudomoin.claudecodegui.ui.compose.approval

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.approval.BashPayload
import ru.dsudomoin.claudecodegui.ui.approval.ToolApprovalRequest
import ru.dsudomoin.claudecodegui.ui.approval.ToolApprovalType
import ru.dsudomoin.claudecodegui.ui.approval.WritePayload
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Compose inline approval panel that replaces all Swing InlineApprovalPanel subclasses.
 *
 * Dispatches to the appropriate content based on [ToolApprovalRequest.type].
 */
@Composable
fun ComposeApprovalPanel(
    request: ToolApprovalRequest,
    onApprove: (autoApproveSession: Boolean) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val accentColor = colors.statusWarning

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.codeBg.copy(alpha = 0.5f))
            .border(1.dp, colors.borderNormal.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
    ) {
        // Left accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accentColor),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            // Title
            Text(
                text = request.title,
                style = TextStyle(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                ),
            )

            Spacer(Modifier.height(6.dp))

            // Type-specific content
            when (request.type) {
                ToolApprovalType.BASH -> BashContent(request)
                ToolApprovalType.EDIT -> EditContent(request)
                ToolApprovalType.WRITE -> WriteContent(request)
                ToolApprovalType.GENERIC -> GenericContent(request)
            }

            Spacer(Modifier.height(10.dp))

            // Action buttons
            ApprovalActionRow(
                onApprove = { onApprove(false) },
                onAlwaysApprove = { onApprove(true) },
                onReject = onReject,
            )
        }
    }
}

@Composable
private fun BashContent(request: ToolApprovalRequest) {
    val colors = LocalClaudeColors.current
    val payload = request.payload as? BashPayload
    val command = payload?.command ?: request.details

    // Command preview in code block
    CodePreview(text = command)

    // Optional description
    payload?.description?.let { desc ->
        Spacer(Modifier.height(4.dp))
        Text(
            text = desc,
            style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
        )
    }
}

@Composable
private fun EditContent(request: ToolApprovalRequest) {
    val colors = LocalClaudeColors.current
    val details = request.details

    if (details.isNotBlank()) {
        Text(
            text = details,
            style = TextStyle(fontSize = 12.sp, color = colors.accent),
        )
    }
}

@Composable
private fun WriteContent(request: ToolApprovalRequest) {
    val colors = LocalClaudeColors.current
    val payload = request.payload as? WritePayload

    if (payload != null) {
        // File name
        if (payload.filePath.isNotBlank()) {
            val fileName = payload.filePath.substringAfterLast('/')
            Text(
                text = fileName,
                style = TextStyle(fontSize = 12.sp, color = colors.accent),
            )
        }

        // Metrics
        val bytes = payload.content.length
        val lines = payload.content.lines().size
        Text(
            text = "$bytes bytes \u00b7 $lines lines",
            style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
            modifier = Modifier.padding(top = 2.dp),
        )

        Spacer(Modifier.height(4.dp))

        // Content preview
        val preview = payload.content.take(2000).let {
            if (payload.content.length > 2000) "$it\n..." else it
        }
        CodePreview(text = preview)
    }
}

@Composable
private fun GenericContent(request: ToolApprovalRequest) {
    val colors = LocalClaudeColors.current

    if (request.details.isNotBlank()) {
        Text(
            text = request.details.take(500),
            style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
        )
    }
}

@Composable
private fun CodePreview(
    text: String,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    Text(
        text = text,
        style = TextStyle(
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = colors.textPrimary,
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.codeBg)
            .heightIn(max = 120.dp)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    )
}

@Composable
private fun ApprovalActionRow(
    onApprove: () -> Unit,
    onAlwaysApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val colors = LocalClaudeColors.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Allow — green accent
        ActionButton(
            text = UcuBundle.message("permission.allow"),
            bgColor = colors.statusSuccess.copy(alpha = 0.15f),
            textColor = colors.statusSuccess,
            hoverBgColor = colors.statusSuccess.copy(alpha = 0.25f),
            onClick = onApprove,
        )
        // Always allow — subtle
        ActionButton(
            text = UcuBundle.message("permission.alwaysAllow"),
            bgColor = colors.borderNormal.copy(alpha = 0.3f),
            textColor = colors.textSecondary,
            hoverBgColor = colors.borderNormal.copy(alpha = 0.5f),
            onClick = onAlwaysApprove,
        )
        // Deny — red accent
        ActionButton(
            text = UcuBundle.message("permission.deny"),
            bgColor = colors.statusError.copy(alpha = 0.12f),
            textColor = colors.statusError,
            hoverBgColor = colors.statusError.copy(alpha = 0.22f),
            onClick = onReject,
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    bgColor: Color,
    textColor: Color,
    hoverBgColor: Color,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val bg = if (isHovered) hoverBgColor else bgColor

    Text(
        text = text,
        style = TextStyle(
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    )
}
