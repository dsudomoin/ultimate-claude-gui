package ru.dsudomoin.claudecodegui.ui.compose.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors

/**
 * Tool use status.
 */
enum class ToolStatus { PENDING, COMPLETED, ERROR }

/**
 * Typed expandable content for tool blocks.
 */
sealed interface ExpandableContent {
    /** Diff view for edit tools — renders colored +/- lines with syntax highlighting. */
    data class Diff(val oldString: String, val newString: String, val filePath: String? = null) : ExpandableContent

    /** Plain monospace text (bash output, search results, etc.). */
    data class PlainText(val text: String) : ExpandableContent

    /** Syntax-highlighted code (write/create tool content). */
    data class Code(val content: String, val filePath: String? = null) : ExpandableContent
}

/**
 * Data for a single tool use block.
 */
data class ToolUseData(
    val id: String,
    val toolName: String,
    val displayName: String,
    val summary: String,
    val status: ToolStatus = ToolStatus.PENDING,
    val expandable: ExpandableContent? = null,
    val diffAdditions: Int = 0,
    val diffDeletions: Int = 0,
    val isFileLink: Boolean = false,
    val filePath: String? = null,
)

/**
 * Renders a single tool use as an expandable styled card.
 */
@Composable
fun ComposeToolUseBlock(
    data: ToolUseData,
    onFileClick: ((String) -> Unit)? = null,
    onShowDiff: (() -> Unit)? = null,
    onRevert: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val shape = RoundedCornerShape(16.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    var expanded by remember { mutableStateOf(false) }
    val hasExpandable = data.expandable != null

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, colors.borderNormal, shape)
            .background(if (isHovered && !expanded) colors.surfaceHover else colors.surfacePrimary)
            .hoverable(interactionSource)
            .then(
                if (hasExpandable) Modifier.clickable { expanded = !expanded }
                    .pointerHoverIcon(PointerIcon.Hand)
                else Modifier
            ),
    ) {
        // Header row
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .padding(horizontal = 12.dp),
        ) {
            // Expand chevron
            if (hasExpandable) {
                Text(
                    text = if (expanded) "\u25BC" else "\u25B6",
                    style = TextStyle(fontSize = 10.sp, color = colors.textSecondary),
                )
                Spacer(Modifier.width(6.dp))
            }

            // Tool icon (Unicode)
            Text(
                text = getToolEmoji(data.toolName),
                style = TextStyle(fontSize = 13.sp),
            )
            Spacer(Modifier.width(6.dp))

            // Tool display name
            Text(
                text = data.displayName,
                style = TextStyle(fontSize = 13.sp, color = colors.textPrimary),
            )
            Spacer(Modifier.width(8.dp))

            // Summary (truncated, possibly a file link)
            if (data.summary.isNotEmpty()) {
                val isClickableFile = data.isFileLink && onFileClick != null && data.filePath != null
                if (data.isFileLink) {
                    ru.dsudomoin.claudecodegui.ui.compose.input.FileTypeIcon(
                        fileName = data.summary,
                        size = 14.dp,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                val summaryInteraction = remember { MutableInteractionSource() }
                val summaryHovered by summaryInteraction.collectIsHoveredAsState()
                Text(
                    text = data.summary,
                    style = TextStyle(
                        fontSize = 13.sp,
                        color = if (data.isFileLink) colors.accent else colors.textSecondary,
                        textDecoration = if (isClickableFile && summaryHovered)
                            androidx.compose.ui.text.style.TextDecoration.Underline else null,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (isClickableFile) {
                                Modifier
                                    .hoverable(summaryInteraction)
                                    .clickable { onFileClick(data.filePath) }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            } else Modifier
                        ),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }

            // Diff stats for edit tools
            if (data.diffAdditions > 0) {
                Text(
                    text = "+${data.diffAdditions}",
                    style = TextStyle(fontSize = 11.sp, color = colors.diffAddFg),
                )
                Spacer(Modifier.width(4.dp))
            }
            if (data.diffDeletions > 0) {
                Text(
                    text = "-${data.diffDeletions}",
                    style = TextStyle(fontSize = 11.sp, color = colors.diffDelFg),
                )
                Spacer(Modifier.width(4.dp))
            }

            // Action buttons (show diff / revert) — visible on hover for completed edit/write tools
            if (isHovered && data.status == ToolStatus.COMPLETED && data.expandable != null) {
                if (onShowDiff != null && (data.expandable is ExpandableContent.Diff || data.expandable is ExpandableContent.Code)) {
                    ToolActionButton(
                        icon = "\u2194",
                        tooltip = "Diff",
                        onClick = onShowDiff,
                    )
                    Spacer(Modifier.width(2.dp))
                }
                if (onRevert != null && (data.expandable is ExpandableContent.Diff || data.expandable is ExpandableContent.Code)) {
                    ToolActionButton(
                        icon = "\u21A9",
                        tooltip = "Revert",
                        onClick = onRevert,
                    )
                    Spacer(Modifier.width(2.dp))
                }
            }

            Spacer(Modifier.width(4.dp))

            // Status dot
            StatusDot(status = data.status)
        }

        // Expandable details
        AnimatedVisibility(
            visible = expanded && data.expandable != null,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceSecondary),
            ) {
                // Separator line
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.borderNormal)
                )

                when (val content = data.expandable) {
                    is ExpandableContent.Diff -> {
                        DiffContentPanel(
                            oldString = content.oldString,
                            newString = content.newString,
                            filePath = content.filePath,
                        )
                    }
                    is ExpandableContent.Code -> {
                        CodeContentPanel(
                            code = content.content,
                            filePath = content.filePath,
                        )
                    }
                    is ExpandableContent.PlainText -> {
                        val plainScrollState = rememberScrollState()
                        Text(
                            text = content.text.take(2000),
                            style = TextStyle(
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.textPrimary,
                            ),
                            softWrap = false,
                            modifier = Modifier
                                .horizontalScroll(plainScrollState)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    null -> {}
                }
            }
        }
    }
}

// ── Diff rendering ─────────────────────────────────────────────────────

private enum class DiffLineType { ADDED, DELETED, UNCHANGED }
private data class DiffLine(val type: DiffLineType, val content: String)

@Composable
internal fun DiffContentPanel(oldString: String, newString: String, filePath: String? = null) {
    val colors = LocalClaudeColors.current
    val diffLines = remember(oldString, newString) { computeDiff(oldString, newString) }
    val maxLines = diffLines.size.coerceAtMost(50)

    val highlighter = remember(filePath) {
        if (filePath == null) null
        else try {
            val fileName = filePath.substringAfterLast('/')
            val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                .getFileTypeByFileName(fileName)
            com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
                .getSyntaxHighlighter(fileType, null, null)
        } catch (_: Exception) { null }
    }
    val scheme = remember {
        com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .horizontalScroll(scrollState),
    ) {
        for (i in 0 until maxLines) {
            val line = diffLines[i]
            val bgColor = when (line.type) {
                DiffLineType.ADDED -> colors.diffAddBg
                DiffLineType.DELETED -> colors.diffDelBg
                DiffLineType.UNCHANGED -> colors.surfaceSecondary
            }
            val markerColor = when (line.type) {
                DiffLineType.ADDED -> colors.diffAddFg
                DiffLineType.DELETED -> colors.diffDelFg
                DiffLineType.UNCHANGED -> colors.textSecondary
            }
            val marker = when (line.type) {
                DiffLineType.ADDED -> "+"
                DiffLineType.DELETED -> "-"
                DiffLineType.UNCHANGED -> " "
            }

            val highlightedText = remember(line.content, highlighter) {
                if (highlighter != null) {
                    highlightLine(line.content, highlighter, scheme, colors.textPrimary)
                } else null
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .widthIn(min = 600.dp)
                    .background(bgColor)
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = marker,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = markerColor,
                    ),
                    modifier = Modifier.width(16.dp),
                )
                if (highlightedText != null) {
                    Text(
                        text = highlightedText,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        softWrap = false,
                    )
                } else {
                    Text(
                        text = line.content,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textPrimary,
                        ),
                        softWrap = false,
                    )
                }
            }
        }

        if (diffLines.size > maxLines) {
            Text(
                text = "... ${diffLines.size - maxLines} more lines",
                style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }
    }
}

// ── Syntax-highlighted code rendering ──────────────────────────────────

@Composable
internal fun CodeContentPanel(code: String, filePath: String? = null) {
    val colors = LocalClaudeColors.current
    val lines = remember(code) { code.split("\n") }
    val maxLines = lines.size.coerceAtMost(80)

    val highlighter = remember(filePath) {
        if (filePath == null) null
        else try {
            val fileName = filePath.substringAfterLast('/')
            val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance()
                .getFileTypeByFileName(fileName)
            com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
                .getSyntaxHighlighter(fileType, null, null)
        } catch (_: Exception) { null }
    }
    val scheme = remember {
        com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme
    }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .horizontalScroll(scrollState),
    ) {
        for (i in 0 until maxLines) {
            val line = lines[i]
            val lineNum = (i + 1).toString().padStart(4)

            val highlightedText = remember(line, highlighter) {
                if (highlighter != null) {
                    highlightLine(line, highlighter, scheme, colors.textPrimary)
                } else null
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .widthIn(min = 600.dp)
                    .padding(horizontal = 8.dp),
            ) {
                // Line number
                Text(
                    text = lineNum,
                    style = TextStyle(
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.width(IntrinsicSize.Max),
                )
                Spacer(Modifier.width(12.dp))
                if (highlightedText != null) {
                    Text(
                        text = highlightedText,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        ),
                        softWrap = false,
                    )
                } else {
                    Text(
                        text = line,
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textPrimary,
                        ),
                        softWrap = false,
                    )
                }
            }
        }

        if (lines.size > maxLines) {
            Text(
                text = "... ${lines.size - maxLines} more lines",
                style = TextStyle(fontSize = 11.sp, color = colors.textSecondary),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * Tokenize a single line using IntelliJ's syntax highlighter and return an AnnotatedString.
 */
private fun highlightLine(
    text: String,
    highlighter: com.intellij.openapi.fileTypes.SyntaxHighlighter,
    scheme: com.intellij.openapi.editor.colors.EditorColorsScheme,
    fallbackColor: androidx.compose.ui.graphics.Color,
): AnnotatedString {
    return buildAnnotatedString {
        val lexer = highlighter.highlightingLexer
        lexer.start(text, 0, text.length, 0)
        while (lexer.tokenType != null) {
            val tokenText = text.substring(lexer.tokenStart, lexer.tokenEnd)
            val keys = highlighter.getTokenHighlights(lexer.tokenType!!)
            val awtColor = keys.firstNotNullOfOrNull { key ->
                scheme.getAttributes(key)?.foregroundColor
            }
            val color = awtColor
                ?.let { androidx.compose.ui.graphics.Color(it.red, it.green, it.blue, it.alpha) }
                ?: fallbackColor
            withStyle(SpanStyle(color = color)) {
                append(tokenText)
            }
            lexer.advance()
        }
    }
}

/**
 * LCS-based diff computation.
 */
private fun computeDiff(oldStr: String, newStr: String): List<DiffLine> {
    val oldLines = oldStr.split("\n")
    val newLines = newStr.split("\n")

    val m = oldLines.size
    val n = newLines.size
    val dp = Array(m + 1) { IntArray(n + 1) }

    for (i in 1..m) {
        for (j in 1..n) {
            dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) {
                dp[i - 1][j - 1] + 1
            } else {
                maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
    }

    val result = mutableListOf<DiffLine>()
    var i = m
    var j = n
    while (i > 0 || j > 0) {
        when {
            i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1] -> {
                result.add(DiffLine(DiffLineType.UNCHANGED, oldLines[i - 1]))
                i--; j--
            }
            j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j]) -> {
                result.add(DiffLine(DiffLineType.ADDED, newLines[j - 1]))
                j--
            }
            else -> {
                result.add(DiffLine(DiffLineType.DELETED, oldLines[i - 1]))
                i--
            }
        }
    }
    return result.reversed()
}

/**
 * Computes diff stats (additions, deletions) from old/new strings.
 */
fun computeDiffStats(oldStr: String, newStr: String): Pair<Int, Int> {
    val diff = computeDiff(oldStr, newStr)
    val additions = diff.count { it.type == DiffLineType.ADDED }
    val deletions = diff.count { it.type == DiffLineType.DELETED }
    return additions to deletions
}

/**
 * Animated status dot indicator.
 */
@Composable
fun StatusDot(
    status: ToolStatus,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    val dotColor = when (status) {
        ToolStatus.PENDING -> colors.statusWarning
        ToolStatus.COMPLETED -> colors.statusSuccess
        ToolStatus.ERROR -> colors.statusError
    }

    // Breathing animation for pending
    val alpha = if (status == ToolStatus.PENDING) {
        val transition = rememberInfiniteTransition()
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.4f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000),
                repeatMode = RepeatMode.Reverse,
            ),
        )
        a
    } else {
        1f
    }

    Canvas(
        modifier = modifier.size(8.dp).alpha(alpha),
    ) {
        drawCircle(color = dotColor)
    }
}

/**
 * Small hover-activated action button for tool blocks.
 * Shows a text label instead of icon-only to be self-explanatory.
 */
@Composable
fun ToolActionButton(
    icon: String,
    tooltip: String? = null,
    onClick: () -> Unit,
) {
    val colors = LocalClaudeColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (isHovered) colors.surfaceHover else colors.surfacePrimary.copy(alpha = 0f))
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            text = tooltip ?: icon,
            style = TextStyle(fontSize = 10.sp, color = if (isHovered) colors.accent else colors.textSecondary),
            maxLines = 1,
        )
    }
}

private fun getToolEmoji(toolName: String): String {
    val lower = toolName.lowercase()
    return when {
        lower in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command") -> "\u2699\uFE0F"
        lower in setOf("write", "write_to_file", "save-file", "create_file") -> "\u270F\uFE0F"
        lower in setOf("edit", "edit_file", "replace_string") -> "\u270F\uFE0F"
        lower in setOf("read", "read_file") -> "\uD83D\uDC41"
        lower in setOf("grep", "search") -> "\uD83D\uDD0D"
        lower in setOf("glob", "find", "list", "listfiles") -> "\uD83D\uDCC1"
        lower == "task" || lower == "taskoutput" -> "\uD83D\uDCCB"
        lower == "webfetch" || lower == "websearch" -> "\uD83C\uDF10"
        lower == "todowrite" || lower.startsWith("update_plan") -> "\u2705"
        lower.startsWith("mcp__") -> "\uD83D\uDD0C"
        else -> "\u26A1"
    }
}
