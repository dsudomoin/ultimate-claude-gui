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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
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
 * Shows question text, option cards, "Other" text input, and Submit/Cancel buttons.
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
    val answers = remember { mutableStateMapOf<String, Set<String>>() }
    val customInputs = remember { mutableStateMapOf<String, String>() }
    var otherInputOpen by remember { mutableStateOf(false) }

    val question = questions.getOrNull(currentIndex) ?: return
    val selected: Set<String> = answers.getOrPut(question.question) { emptySet() }

    fun updateSelection(transform: (Set<String>) -> Set<String>) {
        answers[question.question] = transform(selected)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceSecondary)
            .drawBehind {
                drawRect(
                    color = colors.borderNormal,
                    size = size.copy(height = 1.dp.toPx()),
                )
            }
            .padding(12.dp),
    ) {
        // Header badge + counter
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (question.header.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.accent.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = question.header,
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.accent,
                        ),
                    )
                }
                Spacer(Modifier.width(8.dp))
            }
            if (questions.size > 1) {
                Text(
                    text = "${currentIndex + 1} / ${questions.size}",
                    style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Question text
        Text(
            text = question.question,
            style = TextStyle(
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colors.textPrimary,
            ),
        )

        Spacer(Modifier.height(10.dp))

        // Options list
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(question.options) { opt ->
                OptionCard(
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

                OptionCard(
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

                // Custom text input
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
                            .clip(RoundedCornerShape(8.dp))
                            .drawBehind {
                                drawRoundRect(
                                    color = colors.accent,
                                    cornerRadius = CornerRadius(8.dp.toPx()),
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
                                    true
                                } else false
                            }
                            .padding(10.dp),
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

        Spacer(Modifier.height(12.dp))

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
            Spacer(Modifier.width(10.dp))

            val isLast = currentIndex >= questions.size - 1
            val canProceed = selected.any { it != OTHER_MARKER } ||
                    (OTHER_MARKER in selected && (customInputs[question.question]?.isNotBlank() == true))

            ActionButton(
                text = if (isLast) UcuBundle.message("question.submit") else UcuBundle.message("question.next"),
                isPrimary = true,
                enabled = canProceed,
                onClick = {
                    if (isLast) {
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

// ── Option Card ─────────────────────────────────────────────────────────────

@Composable
private fun OptionCard(
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
        isSelected -> colors.accent.copy(alpha = 0.12f)
        isHovered -> colors.hoverOverlay
        else -> colors.chipBg
    }
    val borderColor = if (isSelected) colors.accent.copy(alpha = 0.5f) else colors.chipBorder

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        // Canvas-drawn radio / checkbox
        if (multiSelect) {
            CheckboxIcon(checked = isSelected)
        } else {
            RadioIcon(selected = isSelected)
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textPrimary,
                ),
            )
            if (description.isNotEmpty()) {
                Spacer(Modifier.height(1.dp))
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

// ── Canvas Icons ────────────────────────────────────────────────────────────

@Composable
private fun RadioIcon(selected: Boolean) {
    val colors = LocalClaudeColors.current
    val accentColor = colors.accent
    val borderColor = if (selected) accentColor else colors.textSecondary

    Box(
        modifier = Modifier
            .size(18.dp)
            .drawBehind {
                val center = Offset(size.width / 2, size.height / 2)
                val outerRadius = size.minDimension / 2 - 1.dp.toPx()

                // Outer circle
                drawCircle(
                    color = borderColor,
                    radius = outerRadius,
                    center = center,
                    style = Stroke(width = 1.5.dp.toPx()),
                )

                // Inner filled circle when selected
                if (selected) {
                    drawCircle(
                        color = accentColor,
                        radius = outerRadius - 3.5.dp.toPx(),
                        center = center,
                    )
                }
            },
    )
}

@Composable
private fun CheckboxIcon(checked: Boolean) {
    val colors = LocalClaudeColors.current
    val accentColor = colors.accent
    val borderColor = if (checked) accentColor else colors.textSecondary

    Box(
        modifier = Modifier
            .size(18.dp)
            .drawBehind {
                val cornerRadius = 4.dp.toPx()
                val inset = 1.dp.toPx()

                if (checked) {
                    // Filled rounded rect
                    drawRoundRect(
                        color = accentColor,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius(cornerRadius),
                    )
                    // White checkmark
                    val path = Path().apply {
                        moveTo(size.width * 0.25f, size.height * 0.5f)
                        lineTo(size.width * 0.42f, size.height * 0.68f)
                        lineTo(size.width * 0.75f, size.height * 0.32f)
                    }
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(
                            width = 2.dp.toPx(),
                            cap = StrokeCap.Round,
                        ),
                    )
                } else {
                    // Outlined rounded rect
                    drawRoundRect(
                        color = borderColor,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - 2 * inset, size.height - 2 * inset),
                        cornerRadius = CornerRadius(cornerRadius),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
            },
    )
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
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .then(
                if (!isPrimary) {
                    Modifier.drawBehind {
                        drawRoundRect(
                            color = colors.chipBorder,
                            cornerRadius = CornerRadius(8.dp.toPx()),
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    }
                } else Modifier
            )
            .hoverable(interactionSource)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .padding(horizontal = 24.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 13.sp,
                fontWeight = if (isPrimary) FontWeight.SemiBold else FontWeight.Normal,
                color = textColor,
            ),
        )
    }
}
