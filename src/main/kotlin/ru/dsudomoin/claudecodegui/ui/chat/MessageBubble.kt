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
import ru.dsudomoin.claudecodegui.MyMessageBundle
import ru.dsudomoin.claudecodegui.core.model.ContentBlock
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.Role
import ru.dsudomoin.claudecodegui.ui.common.MarkdownRenderer
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
    private var responseTextArea: JBTextArea? = null
    private var toolBlocksContainer: JPanel? = null
    private val toolBlocks = mutableMapOf<String, ToolUseBlock>()
    private var streamingTimerPanel: JPanel? = null
    private var streamingTimer: Timer? = null
    private var streamingStartTime: Long = 0

    companion object {
        // User bubble: blue background, white text
        private val USER_BG = JBColor(Color(0x00, 0x78, 0xD4), Color(0x00, 0x5F, 0xB8))
        private val USER_FG = Color.WHITE
        private val USER_BORDER = JBColor(Color(0x00, 0x78, 0xD4, 0x10), Color(0xFF, 0xFF, 0xFF, 0x10))
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
            val timerLabel = JBLabel(MyMessageBundle.message("streaming.generating")).apply {
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
                val elapsed = (System.currentTimeMillis() - streamingStartTime) / 1000
                timerElapsed.text = MyMessageBundle.message("streaming.elapsed", elapsed)
            }.apply { start() }

            // During streaming: ThinkingPanel + tool blocks + response text area
            val tp = ThinkingPanel().apply {
                isVisible = false
            }
            thinkingPanel = tp
            add(tp)

            // Container for tool use blocks (added dynamically during streaming)
            val container = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(8), true, false)).apply {
                isOpaque = false
                border = JBUI.Borders.empty()
            }
            toolBlocksContainer = container
            add(container)

            val textArea = JBTextArea(message.textContent).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
                border = JBUI.Borders.empty(2)
                font = UIManager.getFont("Label.font") ?: font
            }
            responseTextArea = textArea
            add(textArea)
        } else if (message.role == Role.ASSISTANT && project != null) {
            // Finished assistant message: render thinking (collapsed) + Markdown + tool blocks
            val thinkingBlock = message.content.filterIsInstance<ContentBlock.Thinking>().firstOrNull()
            val textContent = message.content.filterIsInstance<ContentBlock.Text>()
                .joinToString("\n") { it.text }

            if (thinkingBlock != null && thinkingBlock.text.isNotBlank()) {
                val tp = ThinkingPanel().apply {
                    updateText(thinkingBlock.text)
                    setCollapsed(true)
                }
                add(tp)
            }

            // Render tool use blocks (completed)
            message.content.filterIsInstance<ContentBlock.ToolUse>().forEach { block ->
                val summary = extractToolSummary(block.name, block.input)
                val toolBlock = ToolUseBlock(block.name, summary, ToolUseBlock.Status.COMPLETED, block.input, project)
                toolBlock.alignmentX = LEFT_ALIGNMENT
                add(toolBlock)
            }

            if (textContent.isNotBlank()) {
                val rendered = MarkdownRenderer.render(project, textContent)
                add(rendered)
            }
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
            val parentW = parent?.width ?: 0
            if (parentW > 0) {
                val maxW = (parentW * 0.85).toInt().coerceAtLeast(JBUI.scale(60))
                val ins = insets
                val textFont = UIManager.getFont("Label.font") ?: font
                val fm = getFontMetrics(textFont)
                val text = message.textContent
                val lines = text.split("\n")
                val maxLineW = lines.maxOfOrNull { fm.stringWidth(it) } ?: 0

                // Width: actual text width + bubble padding, capped at 85%
                val neededW = (maxLineW + ins.left + ins.right + JBUI.scale(8))
                    .coerceIn(JBUI.scale(60), maxW)

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

            g2.color = USER_BG
            g2.fill(path)

            g2.color = USER_BORDER
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

    /** Update the response text during streaming. */
    fun updateResponseText(text: String) {
        responseTextArea?.text = text
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
        responseTextArea?.text = text
    }

    /** Add a tool use block during streaming. Returns the block for future reference. */
    fun addToolBlock(id: String, toolName: String, summary: String, input: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())): ToolUseBlock {
        val block = ToolUseBlock(toolName, summary, ToolUseBlock.Status.PENDING, input, project)
        block.alignmentX = LEFT_ALIGNMENT
        toolBlocks[id] = block
        toolBlocksContainer?.add(block)
        toolBlocksContainer?.revalidate()
        toolBlocksContainer?.repaint()
        return block
    }

    /** Mark a tool block as completed. */
    fun completeToolBlock(id: String) {
        toolBlocks[id]?.status = ToolUseBlock.Status.COMPLETED
    }

    /** Mark a tool block as error. */
    fun errorToolBlock(id: String) {
        toolBlocks[id]?.status = ToolUseBlock.Status.ERROR
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
            if (message.role == Role.USER) foreground = USER_FG
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
        }
    }

    private fun createImagePanel(filePath: String): JComponent {
        val file = File(filePath)
        val maxW = JBUI.scale(280)
        val maxH = JBUI.scale(200)

        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
        }

        try {
            val orig = ImageIO.read(file)
            if (orig != null) {
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
