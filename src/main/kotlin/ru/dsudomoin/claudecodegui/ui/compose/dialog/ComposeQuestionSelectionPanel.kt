package ru.dsudomoin.claudecodegui.ui.compose.dialog

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

// ── Data Models ──────────────────────────────────────────────────────────────

data class QuestionData(
    val question: String,
    val header: String,
    val options: List<OptionData>,
    val multiSelect: Boolean,
)

data class OptionData(
    val label: String,
    val description: String,
)

private const val OTHER_MARKER = "__OTHER__"

// ── Main Panel ───────────────────────────────────────────────────────────────

/**
 * Compose equivalent of QuestionSelectionPanel.
 * Shows question text, option chips, "Other" text input, and Submit/Cancel buttons.
 */
@Composable
fun ComposeQuestionSelectionPanel(
    questions: List<QuestionData>,
    onSubmit: (answers: Map<String, List<String>>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    var currentIndex by remember { mutableIntStateOf(0) }
    // Use immutable Set<String> so map mutations trigger recomposition
    val answers = remember { mutableStateMapOf<String, Set<String>>() }
    val customInputs = remember { mutableStateMapOf<String, String>() }
    var otherInputOpen by remember { mutableStateOf(false) }

    val question = questions.getOrNull(currentIndex) ?: return
    val selected: Set<String> = answers.getOrPut(question.question) { emptySet() }

    // Helper to update selection for current question
    fun updateSelection(transform: (Set<String>) -> Set<String>) {
        answers[question.question] = transform(selected)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceSecondary)
            .drawBehind {
                // Top border
                drawRect(
                    color = colors.borderNormal,
                    size = size.copy(height = 1.dp.toPx()),
                )
            }
            .padding(8.dp),
    ) {
        // Header + counter
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (question.header.isNotEmpty()) {
                Text(
                    text = question.header,
                    style = TextStyle(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.accent,
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .drawBehind {
                            drawRoundRect(
                                color = colors.accent,
                                cornerRadius = CornerRadius(2.dp.toPx()),
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(8.dp))
            }
            if (questions.size > 1) {
                Text(
                    text = "${currentIndex + 1} / ${questions.size}",
                    style = TextStyle(fontSize = 10.sp, color = colors.textSecondary),
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        // Question text
        Text(
            text = question.question,
            style = TextStyle(fontSize = 13.sp, color = colors.textPrimary),
        )

        Spacer(Modifier.height(8.dp))

        // Options list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(question.options) { opt ->
                OptionChip(
                    label = opt.label,
                    description = opt.description,
                    isSelected = opt.label in selected,
                    multiSelect = question.multiSelect,
                    onClick = {
                        if (question.multiSelect) {
                            updateSelection {
                                if (opt.label in it) it - opt.label else it + opt.label
                            }
                        } else {
                            updateSelection { setOf(opt.label) }
                        }
                        otherInputOpen = false
                    },
                )
            }

            // "Other" option
            item {
                val isOtherSelected = OTHER_MARKER in selected
                val customText = customInputs[question.question]?.trim() ?: ""
                val otherDesc = if (isOtherSelected && customText.isNotEmpty() && !otherInputOpen) {
                    UcuBundle.message("question.otherAnswer", customText)
                } else {
                    UcuBundle.message("question.otherDesc")
                }

                OptionChip(
                    label = UcuBundle.message("question.otherLabel"),
                    description = otherDesc,
                    isSelected = isOtherSelected,
                    multiSelect = question.multiSelect,
                    onClick = {
                        if (question.multiSelect) {
                            if (OTHER_MARKER in selected) {
                                updateSelection { it - OTHER_MARKER }
                                otherInputOpen = false
                            } else {
                                updateSelection { it + OTHER_MARKER }
                                otherInputOpen = true
                            }
                        } else {
                            updateSelection { setOf(OTHER_MARKER) }
                            otherInputOpen = true
                        }
                    },
                )

                // Custom text input with Shift+Enter support
                if (isOtherSelected && otherInputOpen) {
                    Spacer(Modifier.height(6.dp))

                    var textFieldValue by remember(question.question) {
                        mutableStateOf(TextFieldValue(text = customInputs[question.question] ?: ""))
                    }

                    BasicTextField(
                        value = textFieldValue,
                        onValueChange = { newValue ->
                            textFieldValue = newValue
                            customInputs[question.question] = newValue.text
                        },
                        textStyle = TextStyle(fontSize = 13.sp, color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 50.dp, max = 120.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .drawBehind {
                                drawRoundRect(
                                    color = colors.accent,
                                    cornerRadius = CornerRadius(4.dp.toPx()),
                                    style = Stroke(width = 1.dp.toPx()),
                                )
                            }
                            .onPreviewKeyEvent { event ->
                                if (event.type == KeyEventType.KeyDown &&
                                    event.key == Key.Enter &&
                                    event.isShiftPressed
                                ) {
                                    val text = textFieldValue.text
                                    val sel = textFieldValue.selection
                                    val newText = text.substring(0, sel.start) + "\n" + text.substring(sel.end)
                                    val newCursor = sel.start + 1
                                    textFieldValue = TextFieldValue(
                                        text = newText,
                                        selection = TextRange(newCursor),
                                    )
                                    customInputs[question.question] = newText
                                    true // consumed
                                } else false
                            }
                            .padding(6.dp),
                        decorationBox = { innerTextField ->
                            if (textFieldValue.text.isEmpty()) {
                                Text(
                                    text = UcuBundle.message("question.otherDesc"),
                                    style = TextStyle(fontSize = 13.sp, color = colors.textSecondary),
                                )
                            }
                            innerTextField()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Bottom: Cancel + Submit/Next
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ActionButton(
                text = UcuBundle.message("question.cancel"),
                isPrimary = false,
                onClick = onCancel,
            )
            Spacer(Modifier.width(8.dp))

            val isLast = currentIndex >= questions.size - 1
            val canProceed = selected.any { it != OTHER_MARKER } ||
                    (OTHER_MARKER in selected && (customInputs[question.question]?.isNotBlank() == true))

            ActionButton(
                text = if (isLast) UcuBundle.message("question.submit") else UcuBundle.message("question.next"),
                isPrimary = true,
                enabled = canProceed,
                onClick = {
                    if (isLast) {
                        // Collect all answers
                        val result = mutableMapOf<String, List<String>>()
                        for ((q, sel) in answers) {
                            val labels = sel.filter { it != OTHER_MARKER }.toMutableList()
                            if (OTHER_MARKER in sel) {
                                val custom = customInputs[q]?.trim()
                                if (!custom.isNullOrEmpty()) labels.add(custom)
                            }
                            result[q] = labels
                        }
                        onSubmit(result)
                    } else {
                        currentIndex++
                        otherInputOpen = false
                    }
                },
            )
        }
    }
}

// ── Option Chip ──────────────────────────────────────────────────────────────

@Composable
private fun OptionChip(
    label: String,
    description: String,
    isSelected: Boolean,
    multiSelect: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor = when {
        isSelected -> colors.chipSelectedBg
        isHovered -> colors.chipHover
        else -> colors.chipBg
    }
    val borderColor = if (isSelected) colors.chipSelectedBorder else colors.chipBorder

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Radio/checkbox icon
        val icon = if (multiSelect) {
            if (isSelected) "\u2611" else "\u2610"
        } else {
            if (isSelected) "\u25C9" else "\u25CB"
        }
        Text(
            text = icon,
            style = TextStyle(
                fontSize = 14.sp,
                color = if (isSelected) colors.accent else colors.textSecondary,
            ),
        )

        Spacer(Modifier.width(6.dp))

        Column {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                ),
            )
            if (description.isNotEmpty()) {
                Text(
                    text = description,
                    style = TextStyle(
                        fontSize = 11.sp,
                        color = colors.textSecondary,
                    ),
                )
            }
        }
    }
}

// ── Action Button ────────────────────────────────────────────────────────────

@Composable
private fun ActionButton(
    text: String,
    isPrimary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bgColor = when {
        isPrimary && enabled -> if (isHovered) colors.accent.copy(alpha = 0.85f) else colors.accent
        !isPrimary -> if (isHovered) colors.chipHover else colors.chipBg
        else -> colors.chipBg
    }
    val textColor = when {
        isPrimary && enabled -> Color.White
        enabled -> colors.textPrimary
        else -> colors.textSecondary
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
            .then(
                if (!isPrimary) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = colors.chipBorder,
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                } else Modifier
            )
            .hoverable(interactionSource)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 20.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 12.sp,
                color = textColor,
            ),
        )
    }
}
