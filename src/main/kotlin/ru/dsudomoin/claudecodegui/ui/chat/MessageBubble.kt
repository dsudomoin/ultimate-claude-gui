package ru.dsudomoin.claudecodegui.ui.chat

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.core.model.ContentBlock
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.Role
import ru.dsudomoin.claudecodegui.ui.common.MarkdownRenderer
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*

/**
 * Renders a single message in the chat.
 *
 * Two modes:
 * - **streaming** = true: shows ThinkingPanel (collapsible) + JBTextArea for response
 * - **streaming** = false: renders Markdown via MarkdownRenderer with collapsed thinking block
 */
class MessageBubble(
    private val message: Message,
    private val project: Project? = null,
    private val streaming: Boolean = false
) : JPanel() {

    private var thinkingPanel: ThinkingPanel? = null
    private var contentFlowContainer: JPanel? = null
    private val textSegments = mutableListOf<JEditorPane>()
    private val segmentRawTexts = mutableListOf<String>()
    private val textCheckpoints = mutableListOf<Int>()
    private var lastTextLength = 0
    private var lastRenderTime = 0L
    private var renderTimer: Timer? = null
    private val toolBlocks = mutableMapOf<String, ToolUseBlock>()
    private val toolGroups = mutableMapOf<String, ToolGroupBlock>()  // toolId → group
    private var activeGroup: ToolGroupBlock? = null
    private var lastToolCategory: ToolUseBlock.ToolCategory? = null
    private var lastStandaloneToolId: String? = null  // ID of the last standalone tool (for upgrade to group)
    private var streamingTimerPanel: JPanel? = null
    private var streamingTimer: Timer? = null
    private var streamingStartTime: Long = 0

    companion object {
        private const val RENDER_DEBOUNCE_MS = 80L

        // User bubble colors — sourced from ThemeColors
        private const val BUBBLE_ARC = 12
        private const val SHARP_ARC = 2

        /** Extract a human-readable summary from tool input parameters. */
        fun extractToolSummary(toolName: String, input: kotlinx.serialization.json.JsonObject): String {
            val lower = toolName.lowercase()
            val filePath = getStringField(input, "file_path")
                ?: getStringField(input, "path")
                ?: getStringField(input, "target_file")
                ?: getStringField(input, "notebook_path")
            if (filePath != null) return filePath.substringAfterLast('/')

            if (lower in setOf("bash", "run_terminal_cmd", "execute_command", "shell_command")) {
                val cmd = getStringField(input, "command")
                if (cmd != null) return if (cmd.length > 60) cmd.take(57) + "..." else cmd
            }

            if (lower in setOf("grep", "search")) {
                val pattern = getStringField(input, "pattern") ?: getStringField(input, "search_term")
                if (pattern != null) return pattern
            }

            if (lower == "glob") {
                val pattern = getStringField(input, "pattern")
                if (pattern != null) return pattern
            }

            if (lower == "task") {
                val desc = getStringField(input, "description")
                val subType = getStringField(input, "subagent_type")
                return when {
                    desc != null && subType != null -> "$subType: $desc"
                    desc != null -> desc
                    subType != null -> subType
                    else -> ""
                }
            }

            return ""
        }

        private fun getStringField(obj: kotlinx.serialization.json.JsonObject, key: String): String? {
            val element = obj[key] ?: return null
            return try {
                (element as? kotlinx.serialization.json.JsonPrimitive)?.content
            } catch (_: Exception) {
                null
            }
        }
    }

    init {
        isOpaque = false
        if (message.role == Role.USER) {
            layout = VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)
            border = JBUI.Borders.empty(10, 14)
        } else {
            layout = VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(8), true, false)
            border = JBUI.Borders.empty(4, 10)
        }

        // Content
        if (streaming) {
            // Streaming timer with spinner
            val timerLabel = JBLabel(UcuBundle.message("streaming.generating")).apply {
                icon = AnimatedIcon.Default()
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor.GRAY
            }
            val timerElapsed = JBLabel("").apply {
                font = font.deriveFont(Font.PLAIN, 11f)
                foreground = JBColor.GRAY
            }
            val timerPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.emptyBottom(4)
                add(timerLabel)
                add(timerElapsed)
            }
            streamingTimerPanel = timerPanel
            add(timerPanel)

            streamingStartTime = System.currentTimeMillis()
            streamingTimer = Timer(1000) {
                val totalSeconds = (System.currentTimeMillis() - streamingStartTime) / 1000
                val formatted = if (totalSeconds < 60) {
                    UcuBundle.message("streaming.elapsed.seconds", totalSeconds)
                } else {
                    val minutes = totalSeconds / 60
                    val seconds = totalSeconds % 60
                    UcuBundle.message("streaming.elapsed.minutes", minutes, seconds)
                }
                timerElapsed.text = formatted
            }.apply { start() }

            // During streaming: ThinkingPanel + tool blocks + response text area
            val tp = ThinkingPanel().apply {
                isVisible = false
            }
            thinkingPanel = tp
            add(tp)

            // Container for interleaved text and tool blocks (preserves document order)
            val flow = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(4), true, false)).apply {
                isOpaque = false
            }
            contentFlowContainer = flow

            // Initial pane for text before first tool block
            val pane = MarkdownRenderer.createStyledEditorPane().apply { isVisible = false }
            textSegments.add(pane)
            segmentRawTexts.add("")
            flow.add(pane)
            add(flow)
        } else if (message.role == Role.ASSISTANT && project != null) {
            // Finished assistant message: render thinking (collapsed) + content blocks in order
            val thinkingBlock = message.content.filterIsInstance<ContentBlock.Thinking>().firstOrNull()

            if (thinkingBlock != null && thinkingBlock.text.isNotBlank()) {
                val tp = ThinkingPanel().apply {
                    updateText(thinkingBlock.text)
                    setCollapsed(true)
                }
                add(tp)
            }

            // Render content blocks with grouping of consecutive same-type tools
            // First pass: collect tool results by tool_use_id for lookup
            val toolResultMap = mutableMapOf<String, ContentBlock.ToolResult>()
            var prevToolUseId: String? = null
            for (block in message.content) {
                when (block) {
                    is ContentBlock.ToolUse -> prevToolUseId = block.id
                    is ContentBlock.ToolResult -> {
                        val useId = block.toolUseId.ifEmpty { prevToolUseId }
                        if (useId != null) toolResultMap[useId] = block
                    }
                    else -> {}
                }
            }

            // Second pass: render with grouping
            var currentGroup: ToolGroupBlock? = null
            var currentGroupCategory: ToolUseBlock.ToolCategory? = null

            fun flushGroup() {
                val group = currentGroup ?: return
                if (group.itemCount == 1) {
                    // Single item — render as standalone ToolUseBlock
                    val item = group.items[0]
                    val result = toolResultMap[item.id]
                    val status = when {
                        result?.isError == true -> ToolUseBlock.Status.ERROR
                        else -> ToolUseBlock.Status.COMPLETED
                    }
                    val toolBlock = ToolUseBlock(item.toolName, item.summary, status, item.input, project)
                    toolBlock.alignmentX = LEFT_ALIGNMENT
                    if (result != null && result.content.isNotBlank()) {
                        toolBlock.setResultContent(result.content)
                    }
                    add(toolBlock)
                } else {
                    group.alignmentX = LEFT_ALIGNMENT
                    add(group)
                }
                currentGroup = null
                currentGroupCategory = null
            }

            for (block in message.content) {
                when (block) {
                    is ContentBlock.ToolUse -> {
                        val cat = ToolUseBlock.getToolCategory(block.name)
                        val summary = extractToolSummary(block.name, block.input)
                        val result = toolResultMap[block.id]

                        if (cat != ToolUseBlock.ToolCategory.OTHER && cat == currentGroupCategory && currentGroup != null) {
                            // Add to existing group
                            currentGroup!!.addItem(block.id, block.name, summary, block.input)
                            if (result != null) {
                                if (result.isError) currentGroup!!.errorItem(block.id, result.content)
                                else currentGroup!!.completeItem(block.id, result.content)
                            } else {
                                currentGroup!!.completeItem(block.id, null)
                            }
                        } else {
                            flushGroup()

                            if (cat != ToolUseBlock.ToolCategory.OTHER) {
                                // Start a new potential group
                                val group = ToolGroupBlock(cat, project)
                                group.addItem(block.id, block.name, summary, block.input)
                                if (result != null) {
                                    if (result.isError) group.errorItem(block.id, result.content)
                                    else group.completeItem(block.id, result.content)
                                } else {
                                    group.completeItem(block.id, null)
                                }
                                currentGroup = group
                                currentGroupCategory = cat
                            } else {
                                // OTHER: standalone block
                                val status = if (result?.isError == true) ToolUseBlock.Status.ERROR else ToolUseBlock.Status.COMPLETED
                                val toolBlock = ToolUseBlock(block.name, summary, status, block.input, project)
                                toolBlock.alignmentX = LEFT_ALIGNMENT
                                if (result != null && result.content.isNotBlank()) {
                                    toolBlock.setResultContent(result.content)
                                }
                                add(toolBlock)
                            }
                        }
                    }
                    is ContentBlock.ToolResult -> {
                        // Already handled via toolResultMap
                    }
                    is ContentBlock.Text -> {
                        flushGroup()
                        if (block.text.isNotBlank()) {
                            val rendered = MarkdownRenderer.render(project, block.text)
                            add(rendered)
                        }
                    }
                    else -> {} // Thinking already handled above
                }
            }
            flushGroup()
        } else {
            // User message or no project: plain text
            message.content.forEach { block ->
                val component = renderContentBlock(block)
                component.alignmentX = LEFT_ALIGNMENT
                add(component)
            }
        }
    }

    override fun getPreferredSize(): Dimension {
        if (message.role == Role.USER) {
            // Use fallback width when parent hasn't been laid out yet (e.g., loading history)
            val parentW = (parent?.width ?: 0).let { if (it > 0) it else JBUI.scale(600) }
            val maxW = (parentW * 0.85).toInt().coerceAtLeast(JBUI.scale(120))
            val hasImages = message.content.any { it is ContentBlock.Image }

            if (hasImages) {
                val layoutPref = super.getPreferredSize()
                val w = layoutPref.width.coerceIn(JBUI.scale(60), maxW)
                return Dimension(w, layoutPref.height)
            }

            val ins = insets
            val textFont = UIManager.getFont("Label.font") ?: font
            val fm = getFontMetrics(textFont)
            val text = message.textContent
            val lines = text.split("\n")
            val maxLineW = lines.maxOfOrNull { fm.stringWidth(it) } ?: 0

            // Width: actual text width + bubble padding, capped at 85%
            val neededW = (maxLineW + ins.left + ins.right + JBUI.scale(8))
                .coerceIn(JBUI.scale(120), maxW)

            // Height: account for line wrapping within the constrained width
            val textAreaW = neededW - ins.left - ins.right - JBUI.scale(4)
            var totalLines = 0
            for (line in lines) {
                val lineW = fm.stringWidth(line)
                totalLines += if (textAreaW > 0 && lineW > textAreaW) {
                    (lineW + textAreaW - 1) / textAreaW
                } else 1
            }
            val totalH = fm.height * totalLines + ins.top + ins.bottom + JBUI.scale(4)

            return Dimension(neededW, totalH)
        }
        return super.getPreferredSize()
    }

    override fun getMaximumSize(): Dimension {
        if (message.role == Role.USER) {
            val parentW = parent?.width ?: Int.MAX_VALUE
            val maxW = (parentW * 0.85).toInt().coerceAtLeast(JBUI.scale(60))
            return Dimension(maxW, Int.MAX_VALUE)
        }
        return super.getMaximumSize()
    }

    override fun paintComponent(g: Graphics) {
        if (message.role == Role.USER) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val arc = JBUI.scale(BUBBLE_ARC).toFloat()
            val sharp = JBUI.scale(SHARP_ARC).toFloat()
            val w = width.toFloat()
            val h = height.toFloat()

            // Rounded rect: all corners 12px except bottom-right = 2px
            val path = java.awt.geom.GeneralPath()
            path.moveTo(arc, 0f)
            path.lineTo(w - arc, 0f)
            path.quadTo(w, 0f, w, arc)                     // top-right
            path.lineTo(w, h - sharp)
            path.quadTo(w, h, w - sharp, h)                // bottom-right (sharp)
            path.lineTo(arc, h)
            path.quadTo(0f, h, 0f, h - arc)                // bottom-left
            path.lineTo(0f, arc)
            path.quadTo(0f, 0f, arc, 0f)                   // top-left
            path.closePath()

            g2.color = ThemeColors.userBubbleBg
            g2.fill(path)

            g2.color = ThemeColors.userBubbleBorder
            g2.stroke = BasicStroke(1f)
            g2.draw(path)
        }
        super.paintComponent(g)
    }

    /** Update the thinking text during streaming. Shows thinking panel if hidden. */
    fun updateThinkingText(text: String) {
        thinkingPanel?.let { tp ->
            if (!tp.isVisible) tp.isVisible = true
            tp.updateText(text)
        }
    }

    /** Update the response text during streaming, distributing across text segments with debounced markdown rendering. */
    fun updateResponseText(text: String) {
        lastTextLength = text.length
        var prevIdx = 0
        for (i in textCheckpoints.indices) {
            val endIdx = textCheckpoints[i].coerceAtMost(text.length)
            if (i < segmentRawTexts.size) {
                segmentRawTexts[i] = text.substring(prevIdx, endIdx)
            }
            prevIdx = endIdx
        }
        val lastIdx = textCheckpoints.size
        if (lastIdx < segmentRawTexts.size) {
            segmentRawTexts[lastIdx] = if (prevIdx <= text.length) text.substring(prevIdx) else ""
        }

        // Debounced markdown rendering
        val now = System.currentTimeMillis()
        if (now - lastRenderTime >= RENDER_DEBOUNCE_MS) {
            renderSegments()
            lastRenderTime = now
            renderTimer?.stop()
        } else {
            renderTimer?.stop()
            renderTimer = Timer(RENDER_DEBOUNCE_MS.toInt()) {
                renderSegments()
                lastRenderTime = System.currentTimeMillis()
            }.apply { isRepeats = false; start() }
        }
    }

    private fun renderSegments() {
        for (i in segmentRawTexts.indices) {
            if (i < textSegments.size) {
                val rawText = segmentRawTexts[i]
                if (rawText.isNotEmpty()) {
                    try {
                        val html = MarkdownRenderer.renderToHtml(rawText)
                        textSegments[i].text = "<html><body>$html</body></html>"
                    } catch (_: Exception) {
                        textSegments[i].text = rawText
                    }
                    textSegments[i].isVisible = true
                } else {
                    textSegments[i].isVisible = false
                }
            }
        }
        contentFlowContainer?.revalidate()
        contentFlowContainer?.repaint()
    }

    /** Collapse the thinking panel (called when Claude starts the actual response). */
    fun collapseThinking() {
        thinkingPanel?.setCollapsed(true)
    }

    /** Stop the streaming timer and hide the timer panel. */
    fun stopStreamingTimer() {
        streamingTimer?.stop()
        streamingTimer = null
        streamingTimerPanel?.isVisible = false
    }

    /** Legacy method for backward compatibility. */
    fun updateText(text: String) {
        updateResponseText(text)
    }

    /** Add a tool use block during streaming with automatic grouping of consecutive same-type tools. */
    fun addToolBlock(id: String, toolName: String, summary: String, input: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())): JComponent {
        val category = ToolUseBlock.getToolCategory(toolName)

        // Record text checkpoint
        textCheckpoints.add(lastTextLength)

        // Check if the last text segment is empty (required for grouping)
        val lastSegmentIdx = segmentRawTexts.lastIndex
        val lastSegmentEmpty = lastSegmentIdx < 0 || segmentRawTexts[lastSegmentIdx].isBlank()

        // Grouping decision
        if (category != ToolUseBlock.ToolCategory.OTHER && lastToolCategory == category && lastSegmentEmpty) {
            val existingGroup = activeGroup
            if (existingGroup != null) {
                // Case A: Group already exists — just add to it
                existingGroup.addItem(id, toolName, summary, input)
                toolGroups[id] = existingGroup

                // Still need text segment for checkpoint alignment
                val newPane = MarkdownRenderer.createStyledEditorPane().apply { isVisible = false }
                textSegments.add(newPane)
                segmentRawTexts.add("")
                contentFlowContainer?.add(newPane)

                contentFlowContainer?.revalidate()
                contentFlowContainer?.repaint()
                return existingGroup
            } else {
                // Case B: Previous was standalone ToolUseBlock of same category — upgrade to group
                val prevId = lastStandaloneToolId
                val prevBlock = prevId?.let { toolBlocks[it] }

                if (prevBlock != null && prevId != null) {
                    val group = ToolGroupBlock(category, project)
                    group.alignmentX = LEFT_ALIGNMENT

                    // Move previous standalone item into the group
                    group.addItem(prevId, prevBlock.toolName, prevBlock.summary, prevBlock.input)
                    // Transfer status if already completed
                    if (prevBlock.status != ToolUseBlock.Status.PENDING) {
                        when (prevBlock.status) {
                            ToolUseBlock.Status.COMPLETED -> group.completeItem(prevId, null)
                            ToolUseBlock.Status.ERROR -> group.errorItem(prevId, null)
                            else -> {}
                        }
                    }

                    // Replace prevBlock in contentFlowContainer with the group
                    val container = contentFlowContainer
                    if (container != null) {
                        val idx = container.components.indexOf(prevBlock)
                        if (idx >= 0) {
                            container.remove(idx)
                            container.add(group, idx)
                        }
                    }
                    prevBlock.removeFromParentAndDispose()
                    toolBlocks.remove(prevId)
                    toolGroups[prevId] = group

                    // Add current item to the group
                    group.addItem(id, toolName, summary, input)
                    toolGroups[id] = group
                    activeGroup = group
                    lastStandaloneToolId = null

                    // Text segment for checkpoint alignment
                    val newPane = MarkdownRenderer.createStyledEditorPane().apply { isVisible = false }
                    textSegments.add(newPane)
                    segmentRawTexts.add("")
                    contentFlowContainer?.add(newPane)

                    contentFlowContainer?.revalidate()
                    contentFlowContainer?.repaint()
                    return group
                }
                // Fallthrough: prevBlock not found, create standalone
            }
        }

        // Different category or OTHER — create standalone ToolUseBlock (original behavior)
        activeGroup = null
        lastToolCategory = category

        val block = ToolUseBlock(toolName, summary, ToolUseBlock.Status.PENDING, input, project)
        block.alignmentX = LEFT_ALIGNMENT
        toolBlocks[id] = block
        lastStandaloneToolId = id

        contentFlowContainer?.add(block)

        // Immediately render the now-frozen previous segment
        val frozenIdx = textCheckpoints.size - 1
        if (frozenIdx in segmentRawTexts.indices && frozenIdx in textSegments.indices) {
            val rawText = segmentRawTexts[frozenIdx]
            if (rawText.isNotEmpty()) {
                try {
                    val html = MarkdownRenderer.renderToHtml(rawText)
                    textSegments[frozenIdx].text = "<html><body>$html</body></html>"
                    textSegments[frozenIdx].isVisible = true
                } catch (_: Exception) {}
            }
        }

        // Create new pane for text after this tool block
        val newPane = MarkdownRenderer.createStyledEditorPane().apply { isVisible = false }
        textSegments.add(newPane)
        segmentRawTexts.add("")
        contentFlowContainer?.add(newPane)

        contentFlowContainer?.revalidate()
        contentFlowContainer?.repaint()
        return block
    }

    /** Insert an inline approval panel at the end of the current content flow. */
    fun addInlineApproval(panel: JPanel) {
        panel.alignmentX = LEFT_ALIGNMENT
        contentFlowContainer?.add(panel)
        contentFlowContainer?.revalidate()
        contentFlowContainer?.repaint()
    }

    /** Mark a tool block as completed, optionally showing result content. */
    fun completeToolBlock(id: String, resultContent: String? = null) {
        toolBlocks[id]?.let { block ->
            block.status = ToolUseBlock.Status.COMPLETED
            if (resultContent != null) block.setResultContent(resultContent)
            return
        }
        toolGroups[id]?.completeItem(id, resultContent)
    }

    /** Mark a tool block as error, optionally showing result content. */
    fun errorToolBlock(id: String, resultContent: String? = null) {
        toolBlocks[id]?.let { block ->
            block.status = ToolUseBlock.Status.ERROR
            if (resultContent != null) block.setResultContent(resultContent)
            return
        }
        toolGroups[id]?.errorItem(id, resultContent)
    }

    private fun renderContentBlock(block: ContentBlock): JComponent = when (block) {
        is ContentBlock.Text -> createTextPanel(block.text)
        is ContentBlock.Code -> createCodePanel(block.code, block.language)
        is ContentBlock.Thinking -> createThinkingPanel(block.text)
        is ContentBlock.ToolUse -> {
            val summary = extractToolSummary(block.name, block.input)
            ToolUseBlock(block.name, summary, ToolUseBlock.Status.COMPLETED, block.input, project)
        }
        is ContentBlock.ToolResult -> createToolResultPanel(block.content, block.isError)
        is ContentBlock.Image -> createImagePanel(block.source)
    }

    private fun createTextPanel(text: String): JComponent {
        return JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty(2)
            font = UIManager.getFont("Label.font") ?: font
            if (message.role == Role.USER) foreground = ThemeColors.userBubbleFg
            highlighter = null
        }
    }

    private fun createCodePanel(code: String, language: String?): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor.namedColor("Claude.CodeBackground", JBColor(0xF0F0F0, 0x1E1E1E))
            border = JBUI.Borders.empty(4)

            if (language != null) {
                add(JBLabel(language).apply {
                    font = font.deriveFont(Font.ITALIC, 10f)
                    border = JBUI.Borders.empty(2, 8, 2, 8)
                }, BorderLayout.NORTH)
            }

            add(JBTextArea(code).apply {
                isEditable = false
                lineWrap = false
                isOpaque = false
                font = Font("JetBrains Mono", Font.PLAIN, 12)
                border = JBUI.Borders.empty(4, 8)
                highlighter = null
            }, BorderLayout.CENTER)
        }
    }

    private fun createThinkingPanel(text: String): JComponent {
        return ThinkingPanel().apply {
            updateText(text)
            setCollapsed(true)
        }
    }

    private fun createToolResultPanel(content: String, isError: Boolean): JComponent {
        return JBTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            foreground = if (isError) JBColor.RED else JBColor.namedColor("Claude.ToolResult", JBColor(0x2E7D32, 0x81C784))
            border = JBUI.Borders.empty(4)
            font = Font("JetBrains Mono", Font.PLAIN, 11)
            highlighter = null
        }
    }

    private fun createImagePanel(filePath: String): JComponent {
        val file = File(filePath)
        val maxH = JBUI.scale(200)

        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
        }

        try {
            val orig = ImageIO.read(file)
            if (orig != null) {
                // Constrain image width to bubble's available width (accounting for insets)
                val bubbleMaxW = parent?.let { p ->
                    val parentW = p.width
                    if (parentW > 0) {
                        val bubbleW = (parentW * 0.85).toInt()
                        bubbleW - insets.left - insets.right - JBUI.scale(8)
                    } else null
                } ?: JBUI.scale(280)
                val maxW = bubbleMaxW.coerceIn(JBUI.scale(100), JBUI.scale(400))

                val scale = minOf(maxW.toDouble() / orig.width, maxH.toDouble() / orig.height, 1.0)
                val w = (orig.width * scale).toInt().coerceAtLeast(1)
                val h = (orig.height * scale).toInt().coerceAtLeast(1)
                val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val g2 = scaled.createGraphics()
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2.drawImage(orig, 0, 0, w, h, null)
                g2.dispose()

                val label = JLabel(ImageIcon(scaled)).apply {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = file.name
                    border = BorderFactory.createLineBorder(
                        JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x3E, 0x3E, 0x42)), 1
                    )
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            if (project != null) {
                                val vf = LocalFileSystem.getInstance().findFileByIoFile(file)
                                if (vf != null) {
                                    FileEditorManager.getInstance(project).openFile(vf, true)
                                }
                            }
                        }
                    })
                }
                panel.add(label, BorderLayout.WEST)
            } else {
                panel.add(JBLabel("[Image: ${file.name}]"), BorderLayout.WEST)
            }
        } catch (_: Exception) {
            panel.add(JBLabel("[Image: ${file.name}]"), BorderLayout.WEST)
        }

        return panel
    }
}
