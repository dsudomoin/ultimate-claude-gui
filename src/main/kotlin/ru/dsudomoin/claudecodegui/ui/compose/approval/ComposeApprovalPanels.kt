package ru.dsudomoin.claudecodegui.ui.compose.approval

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .border(1.dp, colors.borderNormal, RoundedCornerShape(4.dp))
            .padding(8.dp),
    ) {
        // Title
        Text(
            text = request.title,
            style = TextStyle(fontWeight = FontWeight.Bold),
        )

        // Type-specific content
        when (request.type) {
            ToolApprovalType.BASH -> BashContent(request)
            ToolApprovalType.EDIT -> EditContent(request)
            ToolApprovalType.WRITE -> WriteContent(request)
            ToolApprovalType.GENERIC -> GenericContent(request)
        }

        // Action row: Allow | Always allow | Deny
        ApprovalActionRow(
            onApprove = { onApprove(false) },
            onAlwaysApprove = { onApprove(true) },
            onReject = onReject,
        )
    }
}

@Composable
private fun BashContent(request: ToolApprovalRequest) {
    val colors = LocalClaudeColors.current
    val payload = request.payload as? BashPayload
    val command = payload?.command ?: request.details

    // Command preview in code block
    CodePreview(
        text = command,
        modifier = Modifier.padding(top = 6.dp),
    )

    // Optional description
    payload?.description?.let { desc ->
        Text(
            text = desc,
            style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
            modifier = Modifier.padding(top = 4.dp),
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
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    // Note: inline diff viewer remains in Swing (EditApprovalPanel) during transition.
    // Full diff support will be migrated when MessageBubble moves to Compose (Phase 4+).
}

@Composable
private fun WriteContent(request: ToolApprovalRequest) {
    val colors = LocalClaudeColors.current
    val payload = request.payload as? WritePayload

    if (payload != null) {
        // File name link
        if (payload.filePath.isNotBlank()) {
            val fileName = payload.filePath.substringAfterLast('/')
            Text(
                text = fileName,
                style = TextStyle(fontSize = 12.sp, color = colors.accent),
                modifier = Modifier.padding(top = 4.dp),
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

        // Content preview
        val preview = payload.content.take(2000).let {
            if (payload.content.length > 2000) "$it\n..." else it
        }
        CodePreview(
            text = preview,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun GenericContent(request: ToolApprovalRequest) {
    val colors = LocalClaudeColors.current

    if (request.details.isNotBlank()) {
        Text(
            text = request.details.take(500),
            style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
            modifier = Modifier.padding(top = 4.dp),
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
            .clip(RoundedCornerShape(4.dp))
            .background(colors.codeBg)
            .border(1.dp, colors.borderNormal, RoundedCornerShape(4.dp))
            .heightIn(max = 120.dp)
            .verticalScroll(rememberScrollState())
            .padding(6.dp, 8.dp),
    )
}

@Composable
private fun ApprovalActionRow(
    onApprove: () -> Unit,
    onAlwaysApprove: () -> Unit,
    onReject: () -> Unit,
) {
    val colors = LocalClaudeColors.current
    val separatorColor = colors.textSecondary.copy(alpha = 0.5f)

    Row(
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        modifier = Modifier.padding(top = 6.dp),
    ) {
        ActionLink(text = UcuBundle.message("permission.allow"), onClick = onApprove)
        Text(
            text = " | ",
            style = TextStyle(color = separatorColor),
        )
        ActionLink(text = UcuBundle.message("permission.alwaysAllow"), onClick = onAlwaysApprove)
        Text(
            text = " | ",
            style = TextStyle(color = separatorColor),
        )
        ActionLink(text = UcuBundle.message("permission.deny"), onClick = onReject)
    }
}

@Composable
private fun ActionLink(
    text: String,
    onClick: () -> Unit,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val color = if (isHovered) {
        colors.accent.copy(alpha = 0.7f)
    } else {
        colors.accent
    }

    Text(
        text = text,
        style = TextStyle(color = color),
        modifier = Modifier
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    )
}
