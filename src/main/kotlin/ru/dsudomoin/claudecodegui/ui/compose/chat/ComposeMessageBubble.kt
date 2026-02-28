package ru.dsudomoin.claudecodegui.ui.compose.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.jetbrains.jewel.ui.component.Text
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.core.model.ContentBlock
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.Role
import ru.dsudomoin.claudecodegui.core.model.ToolSummaryExtractor
import ru.dsudomoin.claudecodegui.ui.compose.common.ComposeMarkdownContent
import ru.dsudomoin.claudecodegui.ui.compose.theme.LocalClaudeColors
import javax.imageio.ImageIO

/**
 * State holder for streaming message data.
 */
data class StreamingState(
    val thinkingText: String = "",
    val responseText: String = "",
    val isThinkingVisible: Boolean = false,
    val isThinkingCollapsed: Boolean = false,
    val toolBlocks: List<ToolBlockEntry> = emptyList(),
    val isTimerRunning: Boolean = true,
)

/**
 * Visual metadata for compact-boundary divider blocks.
 */
data class CompactBoundaryData(
    val id: String,
    val trigger: String,
    val preTokens: Int,
)

/**
 * A tool block entry in the streaming content flow.
 * Content is interleaved: text before tool, tool, text after tool, etc.
 */
sealed interface ContentFlowItem {
    data class TextSegment(val text: String) : ContentFlowItem
    data class CompactBoundary(val data: CompactBoundaryData) : ContentFlowItem
    data class ToolBlock(val data: ToolUseData) : ContentFlowItem
    data class ToolGroup(val data: ToolGroupData) : ContentFlowItem
    data class ApprovalSlot(val id: String) : ContentFlowItem
}

/**
 * Entry representing a tool block or group in the streaming flow.
 */
data class ToolBlockEntry(
    val id: String,
    val toolName: String,
    val displayName: String,
    val summary: String,
    val status: ToolStatus = ToolStatus.PENDING,
)

/**
 * Renders a single message bubble.
 *
 * User messages: right-aligned with rounded corners (bottom-right sharp).
 * Assistant messages: full-width with content blocks.
 *
 * Compose equivalent of [ru.dsudomoin.claudecodegui.ui.chat.MessageBubble].
 */
@Composable
fun ComposeMessageBubble(
    message: Message,
    streaming: Boolean = false,
    streamingState: StreamingState? = null,
    contentFlow: List<ContentFlowItem>? = null,
    onFileClick: ((String) -> Unit)? = null,
    onToolShowDiff: ((ExpandableContent) -> Unit)? = null,
    onToolRevert: ((ExpandableContent) -> Unit)? = null,
    onStopTask: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        Role.USER -> UserBubble(message = message, modifier = modifier)
        Role.ASSISTANT -> AssistantBubble(
            message = message,
            streaming = streaming,
            streamingState = streamingState,
            contentFlow = contentFlow,
            onFileClick = onFileClick,
            onToolShowDiff = onToolShowDiff,
            onToolRevert = onToolRevert,
            onStopTask = onStopTask,
            modifier = modifier,
        )
    }
}

// ── User Bubble ────────────────────────────────────────────────────────

@Composable
private fun UserBubble(
    message: Message,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current

    // Custom shape: all corners 12dp except bottom-right 2dp
    val bubbleShape = RoundedCornerShape(
        topStart = 12.dp,
        topEnd = 12.dp,
        bottomStart = 12.dp,
        bottomEnd = 2.dp,
    )

    val bgColor = colors.userBubbleBg
    val borderColor = colors.userBubbleBorder
    val fgColor = colors.userBubbleFg

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 48.dp), // push right for 85% effect
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .clip(bubbleShape)
                .drawBehind {
                    // Fill
                    drawRoundRect(
                        color = bgColor,
                        cornerRadius = CornerRadius(12.dp.toPx()),
                    )
                    // Border
                    drawRoundRect(
                        color = borderColor,
                        cornerRadius = CornerRadius(12.dp.toPx()),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            message.content.forEach { block ->
                when (block) {
                    is ContentBlock.Text -> {
                        Text(
                            text = block.text,
                            style = TextStyle(fontSize = 13.sp, color = fgColor),
                        )
                    }
                    is ContentBlock.Image -> {
                        val bitmap = remember(block.source) {
                            try {
                                ImageIO.read(java.io.File(block.source))?.toComposeImageBitmap()
                            } catch (_: Exception) { null }
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = block.source.substringAfterLast('/'),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        } else {
                            Text(
                                text = "\uD83D\uDDBC ${block.source.substringAfterLast('/')}",
                                style = TextStyle(fontSize = 12.sp, color = fgColor.copy(alpha = 0.7f)),
                            )
                        }
                    }
                    else -> {} // Other blocks shouldn't appear in user messages
                }
            }
        }
    }
}

// ── Assistant Bubble ───────────────────────────────────────────────────

@Composable
private fun AssistantBubble(
    message: Message,
    streaming: Boolean,
    streamingState: StreamingState?,
    contentFlow: List<ContentFlowItem>?,
    onFileClick: ((String) -> Unit)?,
    onToolShowDiff: ((ExpandableContent) -> Unit)?,
    onToolRevert: ((ExpandableContent) -> Unit)?,
    onStopTask: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        if (streaming && streamingState != null) {
            // Streaming mode
            StreamingAssistantContent(
                state = streamingState,
                contentFlow = contentFlow.orEmpty(),
                onFileClick = onFileClick,
                onToolShowDiff = onToolShowDiff,
                onToolRevert = onToolRevert,
                onStopTask = onStopTask,
            )
        } else {
            // Finished message — render content blocks
            FinishedAssistantContent(
                message = message,
                onFileClick = onFileClick,
                onToolShowDiff = onToolShowDiff,
                onToolRevert = onToolRevert,
            )
        }
    }
}

@Composable
private fun StreamingAssistantContent(
    state: StreamingState,
    contentFlow: List<ContentFlowItem>,
    onFileClick: ((String) -> Unit)?,
    onToolShowDiff: ((ExpandableContent) -> Unit)? = null,
    onToolRevert: ((ExpandableContent) -> Unit)? = null,
    onStopTask: ((String) -> Unit)? = null,
) {
    // Timer row
    if (state.isTimerRunning) {
        StreamingTimerRow()
    }

    // Thinking panel (collapsible, with streaming animation)
    if (state.isThinkingVisible && state.thinkingText.isNotEmpty()) {
        ComposeThinkingSection(
            text = state.thinkingText,
            collapsed = state.isThinkingCollapsed,
            isStreaming = true,
        )
    }

    // Content flow: interleaved text and tool blocks
    if (contentFlow.isNotEmpty()) {
        val lastTextIndex = contentFlow.indexOfLast { it is ContentFlowItem.TextSegment }
        contentFlow.forEachIndexed { index, item ->
            when (item) {
                is ContentFlowItem.TextSegment -> {
                    if (item.text.isNotBlank()) {
                        key("text_$index") {
                            if (index == lastTextIndex) {
                                // Live (growing) segment — always re-render
                                ComposeMarkdownContent(markdown = item.text, selectable = false)
                            } else {
                                // Frozen segment (before tools) — memoize to avoid re-parse
                                val memoizedText = remember(item.text) { item.text }
                                ComposeMarkdownContent(markdown = memoizedText, selectable = false)
                            }
                        }
                    }
                }
                is ContentFlowItem.CompactBoundary -> {
                    key("compact_${item.data.id}") {
                        CompactBoundaryBlock(
                            data = item.data,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    }
                }
                is ContentFlowItem.ToolBlock -> {
                    key("tool_${item.data.id}") {
                        ComposeToolUseBlock(
                            data = item.data,
                            onFileClick = onFileClick,
                            onShowDiff = item.data.expandable?.let { exp -> onToolShowDiff?.let { cb -> { cb(exp) } } },
                            onRevert = item.data.expandable?.let { exp -> onToolRevert?.let { cb -> { cb(exp) } } },
                            onStopTask = item.data.taskId?.let { tid -> onStopTask?.let { cb -> { cb(tid) } } },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                is ContentFlowItem.ToolGroup -> {
                    key(streamingGroupKey(item.data, index)) {
                        ComposeToolGroupBlock(
                            data = item.data,
                            onFileClick = onFileClick,
                            onToolShowDiff = onToolShowDiff,
                            onToolRevert = onToolRevert,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                is ContentFlowItem.ApprovalSlot -> {
                    key("approval_${item.id}") {
                        // Placeholder — approval panels will be inserted here by the orchestrator
                    }
                }
            }
        }
    } else if (state.responseText.isNotBlank()) {
        // Fallback: show raw response as markdown
        ComposeMarkdownContent(markdown = state.responseText, selectable = false)
    }
}

@Composable
private fun FinishedAssistantContent(
    message: Message,
    onFileClick: ((String) -> Unit)?,
    onToolShowDiff: ((ExpandableContent) -> Unit)? = null,
    onToolRevert: ((ExpandableContent) -> Unit)? = null,
) {
    val colors = LocalClaudeColors.current

    // Thinking block (collapsed by default)
    val thinkingBlock = message.content.filterIsInstance<ContentBlock.Thinking>().firstOrNull()
    if (thinkingBlock != null && thinkingBlock.text.isNotBlank()) {
        ComposeThinkingSection(
            text = thinkingBlock.text,
            collapsed = true,
        )
    }

    // Build a map of ToolResult by toolUseId for pairing
    val toolResultMap = message.content
        .filterIsInstance<ContentBlock.ToolResult>()
        .associateBy { it.toolUseId }

    // Build render items with tool grouping
    val renderItems = remember(message) {
        buildFinishedRenderItems(message.content, toolResultMap)
    }

    renderItems.forEachIndexed { index, item ->
        when (item) {
            is FinishedRenderItem.MarkdownText -> {
                key("finished_text_${index}_${item.text.hashCode()}") {
                    ComposeMarkdownContent(markdown = item.text, selectable = false)
                }
            }
            is FinishedRenderItem.CompactBoundaryItem -> {
                key("finished_compact_${item.data.id}") {
                    CompactBoundaryBlock(
                        data = item.data,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
            }
            is FinishedRenderItem.CodeItem -> {
                key("finished_code_${index}_${item.code.hashCode()}") {
                    CodeBlock(code = item.code, language = item.language)
                }
            }
            is FinishedRenderItem.ImageItem -> {
                val bitmap = remember(item.source) {
                    try {
                        ImageIO.read(java.io.File(item.source))?.toComposeImageBitmap()
                    } catch (_: Exception) { null }
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = item.source.substringAfterLast('/'),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Text(
                        text = "\uD83D\uDDBC ${item.source.substringAfterLast('/')}",
                        style = TextStyle(fontSize = 12.sp, color = colors.textSecondary),
                    )
                }
            }
            is FinishedRenderItem.SingleTool -> {
                key("finished_tool_${item.data.id}") {
                    ComposeToolUseBlock(
                        data = item.data,
                        onFileClick = onFileClick,
                        onShowDiff = item.data.expandable?.let { exp -> onToolShowDiff?.let { cb -> { cb(exp) } } },
                        onRevert = item.data.expandable?.let { exp -> onToolRevert?.let { cb -> { cb(exp) } } },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            is FinishedRenderItem.ToolGroupItem -> {
                key(finishedGroupKey(item.data, index)) {
                    ComposeToolGroupBlock(
                        data = item.data,
                        onFileClick = onFileClick,
                        onToolShowDiff = onToolShowDiff,
                        onToolRevert = onToolRevert,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }
}

// ── Sub-components ─────────────────────────────────────────────────────

@Composable
private fun StreamingTimerRow() {
    val colors = LocalClaudeColors.current
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSeconds++
        }
    }

    val timeText = if (elapsedSeconds < 60) {
        "${elapsedSeconds}s"
    } else {
        "${elapsedSeconds / 60}m ${elapsedSeconds % 60}s"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp),
    ) {
        // Spinner indicator (simple text-based)
        Text(
            text = "\u23F3",
            style = TextStyle(fontSize = 11.sp),
        )
        Spacer(Modifier.padding(start = 4.dp))
        Text(
            text = UcuBundle.message("streaming.generating"),
            style = TextStyle(fontSize = 11.sp, color = Color.Gray),
        )
        Spacer(Modifier.padding(start = 4.dp))
        Text(
            text = timeText,
            style = TextStyle(fontSize = 11.sp, color = Color.Gray),
        )
    }
}

@Composable
private fun CompactBoundaryBlock(
    data: CompactBoundaryData,
    modifier: Modifier = Modifier,
) {
    val colors = LocalClaudeColors.current
    val triggerLabel = when (data.trigger.lowercase()) {
        "auto" -> UcuBundle.message("system.compactTrigger.auto")
        else -> UcuBundle.message("system.compactTrigger.manual")
    }
    val tokenLabel = UcuBundle.message("system.compactBoundary.tokens", formatCompactTokenCount(data.preTokens))

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(colors.borderNormal.copy(alpha = 0.42f))
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                colors.surfaceSecondary.copy(alpha = 0.92f),
                                colors.surfaceTertiary.copy(alpha = 0.98f),
                                colors.surfaceSecondary.copy(alpha = 0.92f),
                            )
                        )
                    )
                    .border(1.dp, colors.borderNormal.copy(alpha = 0.76f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "\u2702",
                        style = TextStyle(fontSize = 11.sp, color = colors.accentSecondary),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = UcuBundle.message("system.compactBoundary.title"),
                        style = TextStyle(
                            fontSize = 10.sp,
                            color = colors.textSecondary,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(colors.borderNormal.copy(alpha = 0.42f))
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            colors.surfaceSecondary.copy(alpha = 0.75f),
                            colors.surfacePrimary.copy(alpha = 0.88f),
                            colors.surfaceSecondary.copy(alpha = 0.75f),
                        )
                    )
                )
                .border(1.dp, colors.borderNormal.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.accent.copy(alpha = 0.88f))
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = triggerLabel.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
                style = TextStyle(
                    fontSize = 11.sp,
                    color = colors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = tokenLabel,
                style = TextStyle(
                    fontSize = 11.sp,
                    color = colors.textSecondary,
                ),
            )
        }
    }
}

private fun formatCompactTokenCount(tokens: Int): String {
    if (tokens <= 0) return "0"
    return when {
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format("%.1fk", tokens / 1_000.0)
        else -> tokens.toString()
    }.replace(".0", "")
}

/**
 * Collapsible thinking section using the existing ComposeThinkingPanel logic.
 */
@Composable
private fun ComposeThinkingSection(
    text: String,
    collapsed: Boolean,
    isStreaming: Boolean = false,
) {
    ComposeThinkingPanel(
        text = text,
        isFinished = collapsed,
        isStreaming = isStreaming,
    )
}

@Composable
private fun CodeBlock(
    code: String,
    language: String?,
) {
    val colors = LocalClaudeColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.surfaceSecondary)
            .padding(8.dp),
    ) {
        if (language != null) {
            Text(
                text = language,
                style = TextStyle(
                    fontSize = 10.sp,
                    fontStyle = FontStyle.Italic,
                    color = colors.textSecondary,
                ),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            text = code,
            style = TextStyle(
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = colors.textPrimary,
            ),
        )
    }
}

private fun buildFinishedExpandable(
    toolName: String,
    input: kotlinx.serialization.json.JsonObject,
    resultContent: String?,
): ExpandableContent? {
    val lower = toolName.lowercase()
    if (lower in setOf("read", "read_file")) {
        return null
    }
    if (lower == "task" || lower == "taskoutput") {
        val markdown = buildTaskExpandableMarkdown(input, resultContent)
        if (markdown.isNotBlank()) return ExpandableContent.Markdown(markdown)
    }
    if (ToolSummaryExtractor.isSkillTool(toolName)) {
        val markdown = buildSkillExpandableMarkdown(input, resultContent)
        if (markdown.isNotBlank()) return ExpandableContent.Markdown(markdown)
    }
    if (lower in setOf("edit", "edit_file", "replace_string")) {
        val diff = ToolSummaryExtractor.extractEditDiffStrings(input)
        if (diff != null) {
            val filePath = ToolSummaryExtractor.extractFilePath(input)
            return ExpandableContent.Diff(diff.first, diff.second, filePath)
        }
    }
    if (lower in setOf("write", "write_to_file", "save-file", "create_file")) {
        val content = ToolSummaryExtractor.extractWriteContent(input)
        if (content != null && content.isNotBlank()) {
            val filePath = ToolSummaryExtractor.extractFilePath(input)
            return ExpandableContent.Code(content, filePath)
        }
    }
    if (lower in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command")) {
        val cmd = ToolSummaryExtractor.extractBashCommand(input)
        val output = resultContent?.takeIf { it.isNotBlank() }
        val text = buildString {
            if (cmd != null) { append("$ "); appendLine(cmd) }
            if (output != null) append(output)
        }.trimEnd()
        if (text.isNotBlank()) return ExpandableContent.PlainText(text)
    }
    if (ToolSummaryExtractor.hasUsefulResultContent(toolName) && resultContent?.isNotBlank() == true) {
        return ExpandableContent.PlainText(resultContent)
    }
    return null
}

private fun buildSkillExpandableMarkdown(
    input: kotlinx.serialization.json.JsonObject,
    resultContent: String?,
): String {
    val skillName = ToolSummaryExtractor.extractSkillName(input).orEmpty()
    val inputField = listOf("input", "arguments", "args", "params", "prompt", "query", "task", "request")
        .firstNotNullOfOrNull { key -> input[key]?.let { key to it } }
    val result = resultContent?.trim().orEmpty()

    fun stringifyForMarkdown(element: kotlinx.serialization.json.JsonElement): String {
        return when (element) {
            is kotlinx.serialization.json.JsonPrimitive -> element.content
            is kotlinx.serialization.json.JsonObject, is kotlinx.serialization.json.JsonArray -> "```json\n${element}\n```"
        }
    }

    return buildString {
        if (skillName.isNotBlank()) {
            appendLine("### Skill")
            append("`")
            append(skillName)
            appendLine("`")
        }

        if (inputField != null) {
            val (key, value) = inputField
            val rendered = stringifyForMarkdown(value).trim()
            if (rendered.isNotBlank()) {
                if (isNotEmpty()) appendLine()
                appendLine("### Input ($key)")
                appendLine(rendered)
            }
        }

        if (result.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            appendLine("### Result")
            appendLine(result)
        }

        if (isEmpty()) {
            appendLine("### Skill input")
            appendLine("```json")
            appendLine(input.toString())
            appendLine("```")
        }
    }.trim()
}

private fun buildTaskExpandableMarkdown(
    input: kotlinx.serialization.json.JsonObject,
    resultContent: String?,
): String {
    val description = input["description"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.trim().orEmpty()
    val prompt = input["prompt"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.trim().orEmpty()
    val subagentType = input["subagent_type"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }?.trim().orEmpty()
    val result = resultContent?.trim().orEmpty()

    return buildString {
        if (description.isNotBlank()) {
            appendLine("### Task")
            appendLine(description)
        }
        if (subagentType.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            append("**Subagent:** `")
            append(subagentType)
            appendLine("`")
        }
        if (prompt.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            appendLine("### Prompt")
            appendLine(prompt)
        }
        if (result.isNotBlank()) {
            if (isNotEmpty()) appendLine()
            appendLine("### Result")
            appendLine(result)
        }
        if (isEmpty()) {
            appendLine("### Task input")
            appendLine("```json")
            appendLine(input.toString())
            appendLine("```")
        }
    }.trim()
}

private fun buildFinishedDiffStats(
    toolName: String,
    input: kotlinx.serialization.json.JsonObject,
): Pair<Int, Int> {
    val lower = toolName.lowercase()
    if (lower in setOf("edit", "edit_file", "replace_string")) {
        val diff = ToolSummaryExtractor.extractEditDiffStrings(input)
        if (diff != null) return computeDiffStats(diff.first, diff.second)
    }
    return 0 to 0
}

// ── Finished message rendering helpers ──────────────────────────────────

private sealed interface FinishedRenderItem {
    data class MarkdownText(val text: String) : FinishedRenderItem
    data class CompactBoundaryItem(val data: CompactBoundaryData) : FinishedRenderItem
    data class CodeItem(val code: String, val language: String?) : FinishedRenderItem
    data class ImageItem(val source: String) : FinishedRenderItem
    data class SingleTool(val data: ToolUseData) : FinishedRenderItem
    data class ToolGroupItem(val data: ToolGroupData) : FinishedRenderItem
}

private fun streamingGroupKey(group: ToolGroupData, fallbackIndex: Int): String {
    val idsKey = group.items.joinToString(separator = "_") { it.id }
    val normalized = if (idsKey.isNotBlank()) idsKey else fallbackIndex.toString()
    return "stream_group_${group.category}_$normalized"
}

private fun finishedGroupKey(group: ToolGroupData, fallbackIndex: Int): String {
    val idsKey = group.items.joinToString(separator = "_") { it.id }
    val normalized = if (idsKey.isNotBlank()) idsKey else fallbackIndex.toString()
    return "finished_group_${group.category}_$normalized"
}

/**
 * Pre-processes content blocks into render items, grouping consecutive
 * ToolUse blocks of the same category into [ToolGroupData].
 */
private fun buildFinishedRenderItems(
    contentBlocks: List<ContentBlock>,
    toolResultMap: Map<String, ContentBlock.ToolResult>,
): List<FinishedRenderItem> {
    val items = mutableListOf<FinishedRenderItem>()
    val pendingTools = mutableListOf<ToolUseData>()
    var pendingCategory: ToolCategoryType? = null

    fun flushTools() {
        if (pendingTools.isEmpty()) return
        if (pendingTools.size == 1) {
            items.add(FinishedRenderItem.SingleTool(pendingTools[0]))
        } else {
            val groupItems = pendingTools.map { tool ->
                ToolGroupItemData(
                    id = tool.id,
                    toolName = tool.toolName,
                    summary = tool.summary,
                    status = tool.status,
                    diffAdditions = tool.diffAdditions,
                    diffDeletions = tool.diffDeletions,
                    isFileLink = tool.isFileLink,
                    expandable = tool.expandable,
                    filePath = tool.filePath,
                )
            }
            items.add(FinishedRenderItem.ToolGroupItem(ToolGroupData(
                category = pendingCategory!!,
                items = groupItems,
            )))
        }
        pendingTools.clear()
        pendingCategory = null
    }

    for (block in contentBlocks) {
        when (block) {
            is ContentBlock.Thinking -> {} // Already handled separately
            is ContentBlock.ToolResult -> {} // Paired with ToolUse
            is ContentBlock.Text -> {
                flushTools()
                if (block.text.isNotBlank()) {
                    items.add(FinishedRenderItem.MarkdownText(block.text))
                }
            }
            is ContentBlock.CompactBoundary -> {
                flushTools()
                items.add(
                    FinishedRenderItem.CompactBoundaryItem(
                        CompactBoundaryData(
                            id = "finished_${items.size}_${block.preTokens}_${block.trigger}",
                            trigger = block.trigger,
                            preTokens = block.preTokens,
                        )
                    )
                )
            }
            is ContentBlock.Code -> {
                flushTools()
                items.add(FinishedRenderItem.CodeItem(block.code, block.language))
            }
            is ContentBlock.Image -> {
                flushTools()
                items.add(FinishedRenderItem.ImageItem(block.source))
            }
            is ContentBlock.ToolUse -> {
                val result = toolResultMap[block.id]
                val status = when {
                    result?.isError == true -> ToolStatus.ERROR
                    result != null -> ToolStatus.COMPLETED
                    else -> ToolStatus.COMPLETED
                }
                val expandable = buildFinishedExpandable(block.name, block.input, result?.content)
                val (diffAdd, diffDel) = buildFinishedDiffStats(block.name, block.input)
                val category = classifyToolCategoryLocal(block.name)
                val isFileLink = category == ToolCategoryType.READ || category == ToolCategoryType.EDIT
                val toolFilePath = ToolSummaryExtractor.extractFilePath(block.input)
                val toolData = ToolUseData(
                    id = block.id,
                    toolName = block.name,
                    displayName = ToolSummaryExtractor.getToolDisplayName(block.name),
                    summary = ToolSummaryExtractor.extractToolSummary(block.name, block.input),
                    status = status,
                    expandable = expandable,
                    diffAdditions = diffAdd,
                    diffDeletions = diffDel,
                    isFileLink = isFileLink,
                    filePath = toolFilePath,
                )

                if (pendingCategory == category) {
                    pendingTools.add(toolData)
                } else {
                    flushTools()
                    pendingCategory = category
                    pendingTools.add(toolData)
                }
            }
        }
    }
    flushTools()
    return items
}

private fun classifyToolCategoryLocal(toolName: String): ToolCategoryType {
    val lower = toolName.lowercase()
    return when {
        lower in setOf("read", "read_file") -> ToolCategoryType.READ
        lower in setOf("edit", "edit_file", "replace_string") -> ToolCategoryType.EDIT
        lower in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command") -> ToolCategoryType.BASH
        lower in setOf("grep", "search", "glob", "find", "list", "listfiles") -> ToolCategoryType.SEARCH
        lower in setOf("write", "write_to_file", "save-file", "create_file") -> ToolCategoryType.EDIT
        lower in setOf("skill", "useskill", "runskill", "run_skill", "execute_skill") -> ToolCategoryType.SKILL
        else -> ToolCategoryType.OTHER
    }
}
