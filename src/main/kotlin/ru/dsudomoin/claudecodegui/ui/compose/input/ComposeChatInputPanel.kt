package ru.dsudomoin.claudecodegui.ui.compose.input

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

@Composable
fun ComposeChatInputPanel(
    text: String,
    onTextChange: (String) -> Unit,
    isSending: Boolean,
    selectedModelName: String,
    modeLabel: String,
    contextUsage: ContextUsageData,
    fileContext: FileContextData?,
    attachedImages: List<AttachedImageData>,
    mentionedFiles: List<MentionChipData>,
    mentionPopupVisible: Boolean = false,
    mentionSuggestions: List<MentionSuggestionData> = emptyList(),
    onSendOrStop: () -> Unit,
    onAttachClick: () -> Unit,
    onPasteImage: () -> Boolean = { false },
    onSettingsClick: () -> Unit,
    onModelClick: () -> Unit,
    onModeClick: () -> Unit,
    onEnhanceClick: () -> Unit,
    onFileContextClick: (String) -> Unit,
    onFileContextRemove: () -> Unit,
    onImageClick: (String) -> Unit,
    onImageRemove: (String) -> Unit,
    onMentionRemove: (String) -> Unit,
    onMentionQuery: (String) -> Unit = {},
    onMentionSelect: (Int) -> Unit = {},
    onMentionDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    // TextFieldValue for cursor-aware editing (Shift+Enter newlines)
    var tfv by remember { mutableStateOf(TextFieldValue(text)) }

    // Sync from external text changes (e.g. after mention selection clears @query)
    LaunchedEffect(text) {
        if (tfv.text != text) {
            tfv = TextFieldValue(text, TextRange(text.length))
        }
    }

    // Local state for mention popup keyboard navigation
    var mentionSelectedIndex by remember { mutableIntStateOf(0) }

    // Reset selection when suggestions change
    LaunchedEffect(mentionSuggestions) {
        mentionSelectedIndex = 0
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        // Image attachments (above the rounded box)
        ComposeImageStrip(
            images = attachedImages,
            onImageClick = onImageClick,
            onImageRemove = onImageRemove,
        )

        // File mention chips (above the rounded box)
        ComposeMentionStrip(
            mentions = mentionedFiles,
            onRemove = onMentionRemove,
        )

        // Drag-to-resize state for the text input area
        val density = LocalDensity.current
        var inputHeightPx by remember { mutableStateOf(with(density) { 80.dp.toPx() }) }
        val minHeightPx = with(density) { 60.dp.toPx() }
        val maxHeightPx = with(density) { 400.dp.toPx() }

        // Drag handle at top — dragging up increases height
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.N_RESIZE_CURSOR)))
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        // Dragging up (negative Y) increases height
                        inputHeightPx = (inputHeightPx - dragAmount.y).coerceIn(minHeightPx, maxHeightPx)
                    }
                },
        ) {
            // Visual grip indicator (small bar)
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(3.dp)
                    .background(colors.borderNormal.copy(alpha = 0.5f), RoundedCornerShape(1.5.dp)),
            )
        }

        // Rounded input container
        var isFocused by remember { mutableStateOf(false) }
        val borderColor = if (isFocused) colors.borderFocus else colors.borderNormal
        val borderWidth = if (isFocused) 2.dp else 1.dp

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfacePrimary, RoundedCornerShape(14.dp))
                .drawBehind {
                    val arc = 14.dp.toPx()
                    if (isFocused) {
                        drawRoundRect(
                            color = colors.borderFocus.copy(alpha = 0.3f),
                            cornerRadius = CornerRadius(arc),
                            style = Stroke(width = 2.5.dp.toPx()),
                        )
                    }
                    drawRoundRect(
                        color = borderColor,
                        cornerRadius = CornerRadius(arc),
                        style = Stroke(width = borderWidth.toPx()),
                    )
                },
        ) {
            // Header bar
            ComposeInputHeaderBar(
                contextUsage = contextUsage,
                fileContext = fileContext,
                onAttachClick = onAttachClick,
                onFileContextClick = onFileContextClick,
                onFileContextRemove = onFileContextRemove,
            )

            // Mention suggestions (inline, between header and text area)
            if (mentionPopupVisible && mentionSuggestions.isNotEmpty()) {
                ComposeFileMentionContent(
                    suggestions = mentionSuggestions,
                    selectedIndex = mentionSelectedIndex,
                    onSelect = { index -> onMentionSelect(index) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .padding(horizontal = 4.dp)
                        .background(colors.dropdownBg, RoundedCornerShape(8.dp))
                        .border(1.dp, colors.borderNormal, RoundedCornerShape(8.dp)),
                )
            }

            // Text input area
            val scrollState = rememberScrollState()
            val inputHeightDp = with(density) { inputHeightPx.toDp() }

            BasicTextField(
                value = tfv,
                onValueChange = { newValue ->
                    tfv = newValue
                    onTextChange(newValue.text)
                    // Detect @ mention query
                    val query = extractMentionQuery(newValue.text)
                    if (query != null) {
                        onMentionQuery(query)
                    } else {
                        onMentionDismiss()
                    }
                },
                textStyle = TextStyle(
                    fontSize = 14.sp,
                    color = colors.textPrimary,
                ),
                cursorBrush = SolidColor(colors.textPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(inputHeightDp)
                    .verticalScroll(scrollState)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .onFocusChanged { isFocused = it.isFocused }
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown) {
                            when {
                                // Ctrl+V / Cmd+V — try paste image from clipboard first
                                event.key == Key.V && (event.isCtrlPressed || event.isMetaPressed) -> {
                                    if (onPasteImage()) true else false // let default paste handle text
                                }
                                // Mention popup navigation
                                mentionPopupVisible && event.key == Key.DirectionDown -> {
                                    mentionSelectedIndex = (mentionSelectedIndex + 1)
                                        .coerceAtMost(mentionSuggestions.size - 1)
                                    true
                                }
                                mentionPopupVisible && event.key == Key.DirectionUp -> {
                                    mentionSelectedIndex = (mentionSelectedIndex - 1).coerceAtLeast(0)
                                    true
                                }
                                mentionPopupVisible && event.key == Key.Enter -> {
                                    onMentionSelect(mentionSelectedIndex)
                                    true
                                }
                                mentionPopupVisible && event.key == Key.Escape -> {
                                    onMentionDismiss()
                                    true
                                }
                                // Shift+Enter = insert newline at cursor position
                                event.key == Key.Enter && event.isShiftPressed -> {
                                    val cursorPos = tfv.selection.start
                                    val newText = tfv.text.substring(0, cursorPos) + "\n" + tfv.text.substring(cursorPos)
                                    tfv = TextFieldValue(newText, TextRange(cursorPos + 1))
                                    onTextChange(newText)
                                    true
                                }
                                // Plain Enter = send message
                                event.key == Key.Enter -> {
                                    if (text.isNotBlank() && !isSending) {
                                        onSendOrStop()
                                    }
                                    true
                                }
                                else -> false
                            }
                        } else {
                            false
                        }
                    },
                decorationBox = { innerTextField ->
                    if (text.isEmpty()) {
                        Text(
                            text = UcuBundle.message("chat.placeholder"),
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = colors.textSecondary,
                            ),
                        )
                    }
                    innerTextField()
                },
            )

            // Bottom toolbar
            ComposeInputToolbar(
                selectedModelName = selectedModelName,
                modeLabel = modeLabel,
                isSending = isSending,
                onSettingsClick = onSettingsClick,
                onModelClick = onModelClick,
                onModeClick = onModeClick,
                onEnhanceClick = onEnhanceClick,
                onSendOrStop = onSendOrStop,
            )
        }
    }
}

private fun extractMentionQuery(text: String): String? {
    val atIdx = text.lastIndexOf('@')
    if (atIdx < 0) return null
    val afterAt = text.substring(atIdx + 1)
    if (afterAt.contains(' ') || afterAt.contains('\n')) return null
    return afterAt
}
