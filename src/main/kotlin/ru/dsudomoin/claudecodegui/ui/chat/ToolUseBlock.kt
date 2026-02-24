package ru.dsudomoin.claudecodegui.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.diff.InteractiveDiffManager
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.io.File
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Renders a single tool use as an expandable styled card.
 *
 * Collapsed:
 * ┌──────────────────────────────────────────┐
 * │ [icon] Tool Title  summary…     ●        │
 * └──────────────────────────────────────────┘
 *
 * Expanded (for edit tools — shows diff):
 * ┌──────────────────────────────────────────┐
 * │ [icon] Tool Title  summary…     ●        │
 * ├──────────────────────────────────────────┤
 * │ - old line                               │
 * │ + new line                               │
 * └──────────────────────────────────────────┘
 */
class ToolUseBlock(
    val toolName: String,
    val summary: String = "",
    initialStatus: Status = Status.PENDING,
    val input: JsonObject = JsonObject(emptyMap()),
    private val project: Project? = null
) : JPanel(BorderLayout()) {

    enum class Status { PENDING, COMPLETED, ERROR }

    enum class ToolCategory { READ, EDIT, BASH, SEARCH, OTHER }

    companion object {
        private const val ARC = 16
        private const val HEADER_HEIGHT = 36

        fun getToolCategory(toolName: String): ToolCategory {
            val lower = toolName.lowercase()
            return when {
                lower in setOf("read", "read_file") -> ToolCategory.READ
                lower in setOf("edit", "edit_file", "replace_string", "write", "write_to_file",
                    "create_file", "save-file") -> ToolCategory.EDIT
                lower in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand",
                    "shell_command") -> ToolCategory.BASH
                lower in setOf("grep", "search", "glob", "find", "list", "listfiles") -> ToolCategory.SEARCH
                else -> ToolCategory.OTHER
            }
        }

        fun getToolDisplayName(toolName: String): String {
            val lower = toolName.lowercase()
            return when {
                lower in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command") -> UcuBundle.message("tool.bash")
                lower in setOf("write", "write_to_file", "save-file") -> UcuBundle.message("tool.write")
                lower == "create_file" -> UcuBundle.message("tool.create")
                lower in setOf("edit", "edit_file") -> UcuBundle.message("tool.edit")
                lower == "replace_string" -> UcuBundle.message("tool.replace")
                lower in setOf("read", "read_file") -> UcuBundle.message("tool.read")
                lower in setOf("grep", "search") -> UcuBundle.message("tool.grep")
                lower in setOf("glob", "find") -> UcuBundle.message("tool.glob")
                lower in setOf("list", "listfiles") -> UcuBundle.message("tool.listFiles")
                lower == "task" -> UcuBundle.message("tool.task")
                lower == "taskoutput" -> UcuBundle.message("tool.taskOutput")
                lower == "webfetch" -> UcuBundle.message("tool.webfetch")
                lower == "websearch" -> UcuBundle.message("tool.websearch")
                lower == "delete" -> UcuBundle.message("tool.delete")
                lower == "notebookedit" -> UcuBundle.message("tool.notebook")
                lower == "todowrite" -> UcuBundle.message("tool.todowrite")
                lower in setOf("update_plan", "updateplan") -> UcuBundle.message("tool.updatePlan")
                lower == "explore" -> UcuBundle.message("tool.explore")
                lower == "augmentcontextengine" -> UcuBundle.message("tool.contextEngine")
                lower == "createdirectory" -> UcuBundle.message("tool.createDir")
                lower == "movefile" -> UcuBundle.message("tool.moveFile")
                lower == "copyfile" -> UcuBundle.message("tool.copyFile")
                lower in setOf("skill", "useskill", "runskill", "run_skill", "execute_skill") -> UcuBundle.message("tool.skill")
                lower == "exitplanmode" -> UcuBundle.message("tool.exitPlanMode")
                lower.startsWith("mcp__") -> UcuBundle.message("tool.mcp")
                else -> toolName
            }
        }

        // Icon mapping
        private val TOOL_ICONS: Map<String, Icon> = mapOf(
            "bash" to AllIcons.Debugger.Console,
            "run_terminal_cmd" to AllIcons.Debugger.Console,
            "execute_command" to AllIcons.Debugger.Console,
            "executecommand" to AllIcons.Debugger.Console,
            "shell_command" to AllIcons.Debugger.Console,
            "write" to AllIcons.Actions.Edit,
            "write_to_file" to AllIcons.Actions.Edit,
            "save-file" to AllIcons.Actions.Edit,
            "create_file" to AllIcons.Actions.Edit,
            "edit" to AllIcons.Actions.Edit,
            "edit_file" to AllIcons.Actions.Edit,
            "replace_string" to AllIcons.Actions.Replace,
            "read" to AllIcons.Actions.Preview,
            "read_file" to AllIcons.Actions.Preview,
            "grep" to AllIcons.Actions.Find,
            "search" to AllIcons.Actions.Find,
            "glob" to AllIcons.Nodes.Folder,
            "find" to AllIcons.Nodes.Folder,
            "list" to AllIcons.Nodes.Folder,
            "listfiles" to AllIcons.Nodes.Folder,
            "task" to AllIcons.Nodes.ConfigFolder,
            "taskoutput" to AllIcons.Nodes.ConfigFolder,
            "webfetch" to AllIcons.General.Web,
            "websearch" to AllIcons.Actions.Search,
            "delete" to AllIcons.Actions.GC,
            "todowrite" to AllIcons.Actions.Checked,
            "update_plan" to AllIcons.Actions.Checked,
            "updateplan" to AllIcons.Actions.Checked,
            "explore" to AllIcons.Actions.Find,
            "augmentcontextengine" to AllIcons.Nodes.Class,
            "createdirectory" to AllIcons.Actions.NewFolder,
            "movefile" to AllIcons.Actions.Forward,
            "copyfile" to AllIcons.Actions.Copy,
            "notebookedit" to AllIcons.Actions.Edit,
            "skill" to AllIcons.Actions.Lightning,
            "useskill" to AllIcons.Actions.Lightning,
            "runskill" to AllIcons.Actions.Lightning,
            "run_skill" to AllIcons.Actions.Lightning,
            "execute_skill" to AllIcons.Actions.Lightning,
            "exitplanmode" to AllIcons.Actions.Checked,
        )

        // Tools that have expandable content
        private val EXPANDABLE_TOOLS = setOf(
            "edit", "edit_file", "replace_string",
            "write", "write_to_file", "create_file", "save-file",
            "bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command",
            "task",
        )

        // Colors sourced from ThemeColors
        private val BG get() = ThemeColors.surfacePrimary
        private val BORDER_COLOR get() = ThemeColors.borderNormal
        private val HOVER_BG get() = ThemeColors.surfaceHover
        private val TITLE_COLOR get() = ThemeColors.textPrimary
        private val SUMMARY_COLOR get() = ThemeColors.textSecondary
        private val DETAIL_BG get() = ThemeColors.surfaceSecondary
        private val STATUS_SUCCESS get() = ThemeColors.statusSuccess
        private val STATUS_WARNING get() = ThemeColors.statusWarning
        private val STATUS_ERROR get() = ThemeColors.statusError
        private val DIFF_ADD_MARKER get() = ThemeColors.diffAddFg
        private val DIFF_DEL_MARKER get() = ThemeColors.diffDelFg
        private val DIFF_ADD_BG_EDITOR get() = ThemeColors.diffAddBg
        private val DIFF_DEL_BG_EDITOR get() = ThemeColors.diffDelBg
        private val LINK_COLOR get() = ThemeColors.accent
        private val ICON_HOVER_BG get() = ThemeColors.iconHoverBg

        // Tools that support interactive diff (file-editing tools)
        private val FILE_EDIT_TOOLS = setOf(
            "edit", "edit_file", "replace_string"
        )
        private val FILE_WRITE_TOOLS = setOf(
            "write", "write_to_file", "create_file"
        )

        private fun getStr(obj: JsonObject, key: String): String? {
            val el = obj[key] ?: return null
            return (el as? JsonPrimitive)?.content
        }
    }

    var status: Status = initialStatus
        set(value) {
            field = value
            if (value != Status.PENDING) {
                breathingTimer.stop()
            }
            headerPanel.repaint()
        }

    private var expanded = false
    private var hover = false
    private var breathingAlpha = 1.0f
    private var breathingDirection = -1

    // Action icon hit areas (for edit/write tools)
    private val hasActionIcons = (toolName.lowercase() in FILE_EDIT_TOOLS || toolName.lowercase() in FILE_WRITE_TOOLS) && project != null
    private var diffIconBounds: Rectangle? = null
    private var rejectIconBounds: Rectangle? = null
    private var hoveredIcon: String? = null

    // File path link in summary
    private val filePath: String? = getStr(input, "file_path") ?: getStr(input, "path") ?: getStr(input, "target_file")
    private var summaryBounds: Rectangle? = null
    private var hoveredSummary = false

    private val breathingTimer = Timer(30) {
        breathingAlpha += breathingDirection * 0.02f
        if (breathingAlpha <= 0.4f) {
            breathingAlpha = 0.4f
            breathingDirection = 1
        } else if (breathingAlpha >= 1.0f) {
            breathingAlpha = 1.0f
            breathingDirection = -1
        }
        headerPanel.repaint()
    }

    private val isExpandable: Boolean = toolName.lowercase() in EXPANDABLE_TOOLS && hasExpandableContent()
    private val isEditTool: Boolean = toolName.lowercase() in FILE_EDIT_TOOLS
    private val isWriteTool: Boolean = toolName.lowercase() in FILE_WRITE_TOOLS
    private var reverted = false

    // ── Header (custom-painted) ──────────────────────────────────────────────

    private val headerPanel = object : JPanel() {
        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(HEADER_HEIGHT))
            minimumSize = Dimension(0, JBUI.scale(HEADER_HEIGHT))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(HEADER_HEIGHT))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) {
                    hover = false
                    if (hoveredIcon != null) { hoveredIcon = null }
                    if (hoveredSummary) { hoveredSummary = false }
                    repaint()
                }
                override fun mouseClicked(e: MouseEvent) {
                    // Check icon clicks first
                    if (hasActionIcons && status == Status.COMPLETED && !reverted) {
                        diffIconBounds?.let { if (it.contains(e.point)) { onShowDiff(); return } }
                        rejectIconBounds?.let { if (it.contains(e.point)) { onReject(); return } }
                    }
                    // Check file path link click
                    if (filePath != null && summaryBounds?.contains(e.point) == true) {
                        onOpenFile(); return
                    }
                    if (isExpandable) toggleExpanded()
                }
            })
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    var needRepaint = false

                    // Track action icon hover
                    if (hasActionIcons && status == Status.COMPLETED && !reverted) {
                        val newHover = when {
                            diffIconBounds?.contains(e.point) == true -> "diff"
                            rejectIconBounds?.contains(e.point) == true -> "reject"
                            else -> null
                        }
                        if (newHover != hoveredIcon) {
                            hoveredIcon = newHover
                            needRepaint = true
                        }
                    }

                    // Track file path summary hover
                    val overSummary = filePath != null && summaryBounds?.contains(e.point) == true
                    if (overSummary != hoveredSummary) {
                        hoveredSummary = overSummary
                        needRepaint = true
                    }

                    // Update cursor
                    cursor = when {
                        hoveredIcon != null -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        hoveredSummary -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        isExpandable -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        else -> Cursor.getDefaultCursor()
                    }

                    if (needRepaint) repaint()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val padding = JBUI.scale(12)
            var x = padding

            // Expand/collapse chevron for expandable tools
            if (isExpandable) {
                val chevron = if (expanded) "\u25BC" else "\u25B6"
                val chevronFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                g2.font = chevronFont
                g2.color = SUMMARY_COLOR
                val cfm = g2.fontMetrics
                g2.drawString(chevron, x, (height + cfm.ascent - cfm.descent) / 2)
                x += cfm.stringWidth(chevron) + JBUI.scale(6)
            }

            // Icon
            val icon = getToolIcon()
            val iconY = (height - icon.iconHeight) / 2
            icon.paintIcon(this, g2, x, iconY)
            x += icon.iconWidth + JBUI.scale(6)

            // Title text
            val titleFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())
            g2.font = titleFont
            g2.color = TITLE_COLOR
            val fm = g2.fontMetrics
            val textY = (height + fm.ascent - fm.descent) / 2
            val displayName = getToolDisplayName(toolName)
            g2.drawString(displayName, x, textY)
            x += fm.stringWidth(displayName) + JBUI.scale(8)

            // ── Right side: [●] [reject-icon] [diff-icon] ──

            val dotSize = JBUI.scale(8).toFloat()
            val dotX = width - padding - dotSize
            var rightEdge = dotX.toInt() - JBUI.scale(8) // space before dot

            // Action icons (for edit/write tools when completed)
            val showIcons = hasActionIcons && status == Status.COMPLETED && !reverted
            if (showIcons) {
                val btnSize = JBUI.scale(20)
                val iconSz = JBUI.scale(16)
                val iconOff = (btnSize - iconSz) / 2
                val gap = JBUI.scale(2)

                // Reject icon (rightmost action icon)
                val rejectBtnX = rightEdge - btnSize
                val rejectBtnY = (height - btnSize) / 2
                rejectIconBounds = Rectangle(rejectBtnX, rejectBtnY, btnSize, btnSize)
                if (hoveredIcon == "reject") {
                    g2.color = ICON_HOVER_BG
                    g2.fillRoundRect(rejectBtnX, rejectBtnY, btnSize, btnSize, JBUI.scale(4), JBUI.scale(4))
                }
                AllIcons.Actions.Rollback.paintIcon(this, g2, rejectBtnX + iconOff, rejectBtnY + iconOff)

                // Diff icon
                val diffBtnX = rejectBtnX - gap - btnSize
                val diffBtnY = rejectBtnY
                diffIconBounds = Rectangle(diffBtnX, diffBtnY, btnSize, btnSize)
                if (hoveredIcon == "diff") {
                    g2.color = ICON_HOVER_BG
                    g2.fillRoundRect(diffBtnX, diffBtnY, btnSize, btnSize, JBUI.scale(4), JBUI.scale(4))
                }
                AllIcons.Actions.Diff.paintIcon(this, g2, diffBtnX + iconOff, diffBtnY + iconOff)

                rightEdge = diffBtnX - JBUI.scale(6)
            } else {
                diffIconBounds = null
                rejectIconBounds = null
            }

            // Pre-calculate diff badge width to reserve space
            val lower = toolName.lowercase()
            var diffBadgeWidth = 0
            var diffInfo: Pair<Int, Int>? = null
            if (lower in setOf("edit", "edit_file", "replace_string")) {
                diffInfo = computeDiffInfo()
                if (diffInfo != null && (diffInfo.first > 0 || diffInfo.second > 0)) {
                    val badgeFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                    val bfm = getFontMetrics(badgeFont)
                    if (diffInfo.first > 0) diffBadgeWidth += bfm.stringWidth("+${diffInfo.first}") + JBUI.scale(4)
                    if (diffInfo.second > 0) diffBadgeWidth += bfm.stringWidth("-${diffInfo.second}") + JBUI.scale(4)
                    diffBadgeWidth += JBUI.scale(4) // gap before badge
                }
            }

            // Summary text (truncated to fit available space minus diff badge)
            if (summary.isNotEmpty()) {
                val isLink = filePath != null
                val baseSummaryFont = titleFont.deriveFont(Font.PLAIN)
                val summaryFont = if (isLink && hoveredSummary) {
                    baseSummaryFont.deriveFont(mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON))
                } else {
                    baseSummaryFont
                }
                g2.font = summaryFont
                g2.color = if (isLink) LINK_COLOR else SUMMARY_COLOR
                val sfm = g2.fontMetrics
                val maxSummaryWidth = rightEdge - x - JBUI.scale(4) - diffBadgeWidth
                if (maxSummaryWidth > JBUI.scale(30)) {
                    val truncated = truncateText(summary, sfm, maxSummaryWidth)
                    g2.drawString(truncated, x, textY)
                    val textWidth = sfm.stringWidth(truncated)
                    summaryBounds = Rectangle(x, 0, textWidth, height)
                    x += textWidth + JBUI.scale(4)
                } else {
                    summaryBounds = null
                }
            } else {
                summaryBounds = null
            }

            // Diff badge for edit tools (after summary)
            if (diffInfo != null && (diffInfo.first > 0 || diffInfo.second > 0)) {
                val badgeFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                g2.font = badgeFont
                val bfm = g2.fontMetrics

                if (diffInfo.first > 0) {
                    val addText = "+${diffInfo.first}"
                    g2.color = DIFF_ADD_MARKER
                    g2.drawString(addText, x, textY)
                    x += bfm.stringWidth(addText) + JBUI.scale(4)
                }
                if (diffInfo.second > 0) {
                    val delText = "-${diffInfo.second}"
                    g2.color = DIFF_DEL_MARKER
                    g2.drawString(delText, x, textY)
                    x += bfm.stringWidth(delText) + JBUI.scale(4)
                }
            }

            // Status dot
            val dotY = (height - dotSize) / 2
            val dotColor = when (status) {
                Status.PENDING -> STATUS_WARNING
                Status.COMPLETED -> STATUS_SUCCESS
                Status.ERROR -> STATUS_ERROR
            }
            if (status == Status.PENDING) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, breathingAlpha)
            }
            g2.color = dotColor
            g2.fill(Ellipse2D.Float(dotX, dotY, dotSize, dotSize))
            g2.composite = AlphaComposite.SrcOver
        }
    }

    // ── Actions (Open file / Diff / Reject) ────────────────────────────────

    private fun onOpenFile() {
        val proj = project ?: return
        val path = filePath ?: return
        val file = File(path)
        ApplicationManager.getApplication().executeOnPooledThread {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(proj).openFile(vf, true)
            }
        }
    }

    private fun onShowDiff() {
        val proj = project ?: return
        val filePath = getStr(input, "file_path") ?: getStr(input, "path") ?: getStr(input, "target_file") ?: return
        val lower = toolName.lowercase()

        when {
            lower in FILE_EDIT_TOOLS -> {
                val oldStr = getStr(input, "old_string") ?: getStr(input, "oldString") ?: ""
                val newStr = getStr(input, "new_string") ?: getStr(input, "newString") ?: ""
                InteractiveDiffManager.showEditDiff(proj, filePath, oldStr, newStr)
            }
            lower in FILE_WRITE_TOOLS -> {
                val content = getStr(input, "content") ?: ""
                InteractiveDiffManager.showWriteDiff(proj, filePath, content)
            }
        }
    }

    private fun onReject() {
        if (reverted) return
        val proj = project ?: return
        val filePath = getStr(input, "file_path") ?: getStr(input, "path") ?: getStr(input, "target_file") ?: return
        val lower = toolName.lowercase()

        val success = when {
            lower in FILE_EDIT_TOOLS -> {
                val oldStr = getStr(input, "old_string") ?: getStr(input, "oldString") ?: ""
                val newStr = getStr(input, "new_string") ?: getStr(input, "newString") ?: ""
                InteractiveDiffManager.revertEdit(filePath, oldStr, newStr)
            }
            lower in FILE_WRITE_TOOLS -> {
                InteractiveDiffManager.revertWrite(proj, filePath)
            }
            else -> false
        }

        if (success) {
            reverted = true
            status = Status.ERROR  // Visual indicator that it was reverted
            headerPanel.repaint()
        }
    }

    // ── Details panel (expandable content) ───────────────────────────────────

    private var detailsPanel: JPanel? = null
    private var resultPanel: JPanel? = null

    // ── Streaming output (live tail of last 3 lines during execution) ─────
    private val streamingTail = ArrayDeque<Pair<String, Boolean>>()   // (text, isError), last 3
    private val streamingAllLines = ArrayDeque<Pair<String, Boolean>>() // full buffer, last 500
    private val streamingLabel = javax.swing.JLabel("").apply {
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(11).toFloat())
    }
    private val streamingPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(2, 12, 4, 12)
        isVisible = false
        add(streamingLabel)
    }

    private val bodyPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(headerPanel)
        add(streamingPanel)
    }

    init {
        isOpaque = false
        add(bodyPanel, BorderLayout.CENTER)

        if (initialStatus == Status.PENDING) {
            breathingTimer.start()
        }
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val arc = JBUI.scale(ARC).toFloat()
        val shape = RoundRectangle2D.Float(
            0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), arc, arc
        )

        // Background
        g2.color = if (hover && !expanded) HOVER_BG else BG
        g2.fill(shape)
    }

    override fun paintChildren(g: Graphics) {
        // Clip children to rounded shape so they don't overflow corners
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(ARC).toFloat()
        g2.clip(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
        super.paintChildren(g2)
        g2.dispose()
    }

    override fun paintBorder(g: Graphics) {
        // Border drawn on top of everything
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(ARC).toFloat()
        g2.color = BORDER_COLOR
        g2.stroke = BasicStroke(1f)
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), arc, arc))
    }

    private fun toggleExpanded() {
        expanded = !expanded

        // Lazy-create details panel on first expand
        if (expanded && detailsPanel == null && isExpandable) {
            detailsPanel = createDetailsPanel().also { dp ->
                // Insert before resultPanel so command shows above output
                val resultIdx = resultPanel?.let { bodyPanel.components.indexOf(it) } ?: -1
                if (resultIdx >= 0) {
                    bodyPanel.add(dp, resultIdx)
                } else {
                    bodyPanel.add(dp)
                }
            }
        }

        detailsPanel?.isVisible = expanded
        resultPanel?.isVisible = expanded
        revalidate()
        repaint()
        parent?.revalidate()
        parent?.repaint()
    }

    // ── Streaming output API ─────────────────────────────────────────────

    /** Append a streaming output line during tool execution. */
    fun appendStreamingLine(text: String, isError: Boolean = false) {
        if (text.isBlank()) return
        streamingTail.addLast(text to isError)
        while (streamingTail.size > 3) streamingTail.removeFirst()
        streamingAllLines.addLast(text to isError)
        while (streamingAllLines.size > 500) streamingAllLines.removeFirst()
        updateStreamingDisplay()
    }

    /** Called when tool completes — hides streaming panel. */
    fun onStreamingComplete() {
        streamingPanel.isVisible = false
        bodyPanel.revalidate()
        bodyPanel.repaint()
    }

    private fun updateStreamingDisplay() {
        val html = buildString {
            append("<html><body style='width: 400px'>")
            streamingTail.forEachIndexed { idx, (line, isError) ->
                val safe = line.take(200)
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")
                val color = if (isError) "#ff5555" else toHex(SUMMARY_COLOR)
                append("<span style='color:$color'>$safe</span>")
                if (idx < streamingTail.size - 1) append("<br>")
            }
            append("</body></html>")
        }
        streamingLabel.text = html
        streamingPanel.isVisible = true
        bodyPanel.revalidate()
        bodyPanel.repaint()
    }

    private fun toHex(c: java.awt.Color): String {
        return "#%02x%02x%02x".format(c.red, c.green, c.blue)
    }

    /** Set the tool result content (e.g. bash stdout/stderr). Shown in expandable area. */
    fun setResultContent(content: String) {
        if (content.isBlank()) return
        val isBashTool = toolName.lowercase() in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command")
        if (!isBashTool) return

        val panel = createResultPanel(content)
        resultPanel = panel
        panel.isVisible = expanded
        bodyPanel.add(panel)
        bodyPanel.revalidate()
        bodyPanel.repaint()
    }

    private fun createResultPanel(content: String): JPanel {
        val panel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                g.color = BORDER_COLOR
                g.fillRect(0, 0, width, 1)
                g.color = DETAIL_BG
                g.fillRect(0, 1, width, height - 1)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 0, 0, 0)
        }

        val monoFont = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(12).toFloat())
        val displayContent = if (content.length > 2000) content.take(2000) + "\n..." else content

        val textArea = JBTextArea(displayContent).apply {
            isEditable = false
            lineWrap = false
            isOpaque = false
            font = monoFont
            foreground = SUMMARY_COLOR
            border = JBUI.Borders.empty(8, 12, 8, 12)
        }

        val scrollPane = JBScrollPane(textArea).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }

        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    private fun hasExpandableContent(): Boolean {
        val lower = toolName.lowercase()
        return when {
            lower in setOf("edit", "edit_file", "replace_string") -> {
                getStr(input, "old_string") != null || getStr(input, "new_string") != null
            }
            lower in setOf("write", "write_to_file", "create_file") -> {
                getStr(input, "content") != null
            }
            lower in setOf("bash", "run_terminal_cmd", "execute_command", "shell_command") -> {
                getStr(input, "command") != null
            }
            lower == "task" -> {
                getStr(input, "prompt") != null
            }
            else -> false
        }
    }

    private fun createDetailsPanel(): JPanel {
        val lower = toolName.lowercase()
        return when {
            lower in setOf("edit", "edit_file", "replace_string") -> createDiffPanel()
            lower in setOf("write", "write_to_file", "create_file") -> createContentPanel(UcuBundle.message("tool.section.content"), getStr(input, "content") ?: "")
            lower in setOf("bash", "run_terminal_cmd", "execute_command", "shell_command") -> createContentPanel(UcuBundle.message("tool.section.command"), getStr(input, "command") ?: "")
            lower == "task" -> createTaskDetailsPanel()
            else -> JPanel()
        }
    }

    // ── Diff panel for edit tools (custom-painted, no EditorEx) ─────────────

    private fun createDiffPanel(): JPanel {
        val oldStr = getStr(input, "old_string") ?: getStr(input, "oldString") ?: ""
        val newStr = getStr(input, "new_string") ?: getStr(input, "newString") ?: ""
        val diffLines = computeDiff(oldStr, newStr)

        val monoFont = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(12).toFloat())
        val markerFont = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.BOLD).deriveFont(JBUI.scale(11).toFloat())

        val linesPanel = object : JPanel() {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }
        }

        val maxLines = diffLines.size.coerceAtMost(50)
        for (i in 0 until maxLines) {
            val diffLine = diffLines[i]
            val linePanel = object : JPanel(BorderLayout()) {
                override fun paintComponent(g: Graphics) {
                    super.paintComponent(g)
                    when (diffLine.type) {
                        DiffLineType.ADDED -> {
                            g.color = DIFF_ADD_BG_EDITOR
                            g.fillRect(0, 0, width, height)
                        }
                        DiffLineType.DELETED -> {
                            g.color = DIFF_DEL_BG_EDITOR
                            g.fillRect(0, 0, width, height)
                        }
                        DiffLineType.UNCHANGED -> {} // transparent
                    }
                }
            }.apply {
                isOpaque = false
                border = JBUI.Borders.empty(0, 8, 0, 8)
            }

            // Marker (+/-/space)
            val marker = when (diffLine.type) {
                DiffLineType.ADDED -> "+"
                DiffLineType.DELETED -> "-"
                DiffLineType.UNCHANGED -> " "
            }
            val markerColor = when (diffLine.type) {
                DiffLineType.ADDED -> DIFF_ADD_MARKER
                DiffLineType.DELETED -> DIFF_DEL_MARKER
                DiffLineType.UNCHANGED -> SUMMARY_COLOR
            }
            val markerLabel = javax.swing.JLabel(marker).apply {
                font = markerFont
                foreground = markerColor
                preferredSize = Dimension(JBUI.scale(16), preferredSize.height)
                horizontalAlignment = javax.swing.SwingConstants.CENTER
            }

            // Content
            val contentLabel = javax.swing.JLabel(diffLine.content.replace("&", "&amp;").replace("<", "&lt;")).apply {
                font = monoFont
                foreground = TITLE_COLOR
            }

            linePanel.add(markerLabel, BorderLayout.WEST)
            linePanel.add(contentLabel, BorderLayout.CENTER)
            linePanel.maximumSize = Dimension(Int.MAX_VALUE, linePanel.preferredSize.height)
            linesPanel.add(linePanel)
        }

        if (diffLines.size > maxLines) {
            val moreLabel = javax.swing.JLabel("... ${diffLines.size - maxLines} more lines").apply {
                font = monoFont
                foreground = SUMMARY_COLOR
                border = JBUI.Borders.empty(2, 24, 2, 8)
            }
            linesPanel.add(moreLabel)
        }

        val wrapper = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                g.color = BORDER_COLOR
                g.fillRect(0, 0, width, 1)
                g.color = DETAIL_BG
                g.fillRect(0, 1, width, height - 1)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(3, 0, 4, 0)
        }

        val scrollPane = JBScrollPane(linesPanel).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }

        wrapper.add(scrollPane, BorderLayout.CENTER)
        return wrapper
    }

    // ── Content panel (for write/bash) ───────────────────────────────────────

    private fun createContentPanel(label: String, content: String): JPanel {
        val panel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                g.color = BORDER_COLOR
                g.fillRect(0, 0, width, 1)
                g.color = DETAIL_BG
                g.fillRect(0, 1, width, height - 1)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 0, 0, 0)
        }

        val monoFont = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(12).toFloat())

        // Truncate long content
        val displayContent = if (content.length > 2000) content.take(2000) + "\n..." else content

        val textArea = JBTextArea(displayContent).apply {
            isEditable = false
            lineWrap = false
            isOpaque = false
            font = monoFont
            foreground = TITLE_COLOR
            border = JBUI.Borders.empty(8, 12, 8, 12)
        }

        val scrollPane = JBScrollPane(textArea).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }

        panel.add(scrollPane, BorderLayout.CENTER)
        return panel
    }

    // ── Task details panel (shows prompt) ───────────────────────────────────

    private fun createTaskDetailsPanel(): JPanel {
        val prompt = getStr(input, "prompt") ?: ""

        val panel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                g.color = BORDER_COLOR
                g.fillRect(0, 0, width, 1)
                g.color = DETAIL_BG
                g.fillRect(0, 1, width, height - 1)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 0, 0, 0)
        }

        val monoFont = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN).deriveFont(JBUI.scale(12).toFloat())

        // Label
        val label = com.intellij.ui.components.JBLabel(UcuBundle.message("tool.section.prompt")).apply {
            font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            foreground = SUMMARY_COLOR
            border = JBUI.Borders.empty(6, 12, 2, 12)
        }

        // Prompt text (truncate very long prompts)
        val displayPrompt = if (prompt.length > 3000) prompt.take(3000) + "\n..." else prompt
        val textArea = JBTextArea(displayPrompt).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            font = monoFont
            foreground = TITLE_COLOR
            border = JBUI.Borders.empty(4, 12, 8, 12)
        }

        val inner = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(label)
            add(textArea)
        }

        panel.add(inner, BorderLayout.CENTER)
        return panel
    }

    // ── Diff algorithm ───────────────────────────────────────────────────────

    private enum class DiffLineType { ADDED, DELETED, UNCHANGED }
    private data class DiffLine(val type: DiffLineType, val content: String)

    private fun computeDiff(oldStr: String, newStr: String): List<DiffLine> {
        val oldLines = oldStr.split("\n")
        val newLines = newStr.split("\n")

        // LCS-based diff
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

        // Backtrack to build diff
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

    private fun computeDiffInfo(): Pair<Int, Int>? {
        val oldStr = getStr(input, "old_string") ?: getStr(input, "oldString") ?: return null
        val newStr = getStr(input, "new_string") ?: getStr(input, "newString") ?: return null
        val diff = computeDiff(oldStr, newStr)
        val additions = diff.count { it.type == DiffLineType.ADDED }
        val deletions = diff.count { it.type == DiffLineType.DELETED }
        return additions to deletions
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private fun getToolIcon(): Icon {
        val lower = toolName.lowercase()
        return TOOL_ICONS[lower]
            ?: if (lower.startsWith("mcp__")) AllIcons.Nodes.Plugin else AllIcons.Actions.Execute
    }

    private fun truncateText(text: String, fm: FontMetrics, maxWidth: Int): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        val ellipsis = "..."
        val ellipsisWidth = fm.stringWidth(ellipsis)
        var end = text.length
        while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidth) {
            end--
        }
        return if (end > 0) text.substring(0, end) + ellipsis else ellipsis
    }

    fun removeFromParentAndDispose() {
        breathingTimer.stop()
        parent?.remove(this)
    }
}
