package ru.dsudomoin.claudecodegui.ui.compose.dialog

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Compose panel for elicitation requests from MCP servers.
 *
 * Supports input types:
 * - "text" — free text input
 * - "boolean" — Yes/No buttons
 * - "select" — choose from schema.enum values
 * - fallback — text input for unknown types
 */
@Composable
fun ComposeElicitationPanel(
    title: String,
    description: String,
    type: String,
    schema: JsonObject?,
    onSubmit: (JsonElement) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val accentColor = colors.accent

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
                text = title.ifBlank { UcuBundle.message("elicitation.title") },
                style = TextStyle(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = colors.textPrimary,
                ),
            )

            // Description
            if (description.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = TextStyle(fontSize = 12.sp, color = colors.textSecondary),
                )
            }

            Spacer(Modifier.height(8.dp))

            // Input widget based on type
            when (type) {
                "boolean" -> BooleanInput(onSubmit = onSubmit, onCancel = onCancel)
                "select" -> SelectInput(schema = schema, onSubmit = onSubmit, onCancel = onCancel)
                else -> TextInput(onSubmit = onSubmit, onCancel = onCancel)
            }
        }
    }
}

@Composable
private fun TextInput(
    onSubmit: (JsonElement) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalClaudeColors.current
    var text by remember { mutableStateOf("") }

    BasicTextField(
        value = text,
        onValueChange = { text = it },
        textStyle = TextStyle(fontSize = 13.sp, color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accent),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfacePrimary)
            .border(1.dp, colors.borderNormal, RoundedCornerShape(6.dp))
            .padding(8.dp),
        decorationBox = { innerTextField ->
            Box {
                if (text.isEmpty()) {
                    Text(
                        text = UcuBundle.message("elicitation.placeholder"),
                        style = TextStyle(fontSize = 13.sp, color = colors.textSecondary.copy(alpha = 0.5f)),
                    )
                }
                innerTextField()
            }
        },
    )

    Spacer(Modifier.height(8.dp))

    ElicitationActionRow(
        onSubmit = { onSubmit(JsonPrimitive(text)) },
        onCancel = onCancel,
        submitEnabled = text.isNotBlank(),
    )
}

@Composable
private fun BooleanInput(
    onSubmit: (JsonElement) -> Unit,
    onCancel: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val colors = LocalClaudeColors.current

        ElicitationButton(
            text = "Yes",
            bgColor = colors.statusSuccess.copy(alpha = 0.15f),
            textColor = colors.statusSuccess,
            hoverBgColor = colors.statusSuccess.copy(alpha = 0.25f),
            onClick = { onSubmit(JsonPrimitive(true)) },
        )
        ElicitationButton(
            text = "No",
            bgColor = colors.statusError.copy(alpha = 0.12f),
            textColor = colors.statusError,
            hoverBgColor = colors.statusError.copy(alpha = 0.22f),
            onClick = { onSubmit(JsonPrimitive(false)) },
        )
        ElicitationButton(
            text = UcuBundle.message("elicitation.cancel"),
            bgColor = colors.borderNormal.copy(alpha = 0.3f),
            textColor = colors.textSecondary,
            hoverBgColor = colors.borderNormal.copy(alpha = 0.5f),
            onClick = onCancel,
        )
    }
}

@Composable
private fun SelectInput(
    schema: JsonObject?,
    onSubmit: (JsonElement) -> Unit,
    onCancel: () -> Unit,
) {
    val colors = LocalClaudeColors.current
    val options = schema?.get("enum")?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        ?: emptyList()

    var selected by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (option in options) {
            val isSelected = selected == option
            val interactionSource = remember { MutableInteractionSource() }
            val isHovered by interactionSource.collectIsHoveredAsState()
            val bg = when {
                isSelected -> colors.accent.copy(alpha = 0.2f)
                isHovered -> colors.surfaceHover
                else -> colors.surfacePrimary
            }

            Text(
                text = option,
                style = TextStyle(
                    fontSize = 13.sp,
                    color = if (isSelected) colors.accent else colors.textPrimary,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(bg)
                    .border(
                        width = 1.dp,
                        color = if (isSelected) colors.accent.copy(alpha = 0.5f) else colors.borderNormal,
                        shape = RoundedCornerShape(6.dp),
                    )
                    .hoverable(interactionSource)
                    .clickable { selected = option }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
    }

    Spacer(Modifier.height(8.dp))

    ElicitationActionRow(
        onSubmit = { selected?.let { onSubmit(JsonPrimitive(it)) } },
        onCancel = onCancel,
        submitEnabled = selected != null,
    )
}

@Composable
private fun ElicitationActionRow(
    onSubmit: () -> Unit,
    onCancel: () -> Unit,
    submitEnabled: Boolean = true,
) {
    val colors = LocalClaudeColors.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ElicitationButton(
            text = UcuBundle.message("elicitation.submit"),
            bgColor = if (submitEnabled) colors.statusSuccess.copy(alpha = 0.15f) else colors.borderNormal.copy(alpha = 0.2f),
            textColor = if (submitEnabled) colors.statusSuccess else colors.textSecondary.copy(alpha = 0.5f),
            hoverBgColor = if (submitEnabled) colors.statusSuccess.copy(alpha = 0.25f) else colors.borderNormal.copy(alpha = 0.2f),
            onClick = { if (submitEnabled) onSubmit() },
        )
        ElicitationButton(
            text = UcuBundle.message("elicitation.cancel"),
            bgColor = colors.borderNormal.copy(alpha = 0.3f),
            textColor = colors.textSecondary,
            hoverBgColor = colors.borderNormal.copy(alpha = 0.5f),
            onClick = onCancel,
        )
    }
}

@Composable
private fun ElicitationButton(
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
