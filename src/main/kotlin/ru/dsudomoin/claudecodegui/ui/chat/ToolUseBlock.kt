package ru.dsudomoin.claudecodegui.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.dsudomoin.claudecodegui.MyMessageBundle
import ru.dsudomoin.claudecodegui.ui.diff.InteractiveDiffManager
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
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
    private val toolName: String,
    private val summary: String = "",
    initialStatus: Status = Status.PENDING,
    private val input: JsonObject = JsonObject(emptyMap()),
    private val project: Project? = null
) : JPanel(BorderLayout()) {

    enum class Status { PENDING, COMPLETED, ERROR }

    companion object {
        private const val ARC = 10
        private const val HEADER_HEIGHT = 36

        fun getToolDisplayName(toolName: String): String {
            val lower = toolName.lowercase()
            return when {
                lower in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand", "shell_command") -> MyMessageBundle.message("tool.bash")
                lower in setOf("write", "write_to_file", "save-file") -> MyMessageBundle.message("tool.write")
                lower == "create_file" -> MyMessageBundle.message("tool.create")
                lower in setOf("edit", "edit_file") -> MyMessageBundle.message("tool.edit")
                lower == "replace_string" -> MyMessageBundle.message("tool.replace")
                lower in setOf("read", "read_file") -> MyMessageBundle.message("tool.read")
                lower in setOf("grep", "search") -> MyMessageBundle.message("tool.grep")
                lower in setOf("glob", "find") -> MyMessageBundle.message("tool.glob")
                lower in setOf("list", "listfiles") -> MyMessageBundle.message("tool.listFiles")
                lower == "task" -> MyMessageBundle.message("tool.task")
                lower == "taskoutput" -> MyMessageBundle.message("tool.taskOutput")
                lower == "webfetch" -> MyMessageBundle.message("tool.webfetch")
                lower == "websearch" -> MyMessageBundle.message("tool.websearch")
                lower == "delete" -> MyMessageBundle.message("tool.delete")
                lower == "notebookedit" -> MyMessageBundle.message("tool.notebook")
                lower == "todowrite" -> MyMessageBundle.message("tool.todowrite")
                lower in setOf("update_plan", "updateplan") -> MyMessageBundle.message("tool.updatePlan")
                lower == "explore" -> MyMessageBundle.message("tool.explore")
                lower == "augmentcontextengine" -> MyMessageBundle.message("tool.contextEngine")
                lower == "createdirectory" -> MyMessageBundle.message("tool.createDir")
                lower == "movefile" -> MyMessageBundle.message("tool.moveFile")
                lower == "copyfile" -> MyMessageBundle.message("tool.copyFile")
                lower in setOf("skill", "useskill", "runskill", "run_skill", "execute_skill") -> MyMessageBundle.message("tool.skill")
                lower == "exitplanmode" -> MyMessageBundle.message("tool.exitPlanMode")
                lower.startsWith("mcp__") -> MyMessageBundle.message("tool.mcp")
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
        )

        // Colors
        private val BG = JBColor(Color(0xFF, 0xFF, 0xFF), Color(0x1E, 0x1E, 0x1E))
        private val BORDER_COLOR = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x33, 0x33, 0x33))
        private val HOVER_BG = JBColor(Color(0xF3, 0xF3, 0xF3), Color(0x25, 0x25, 0x26))
        private val TITLE_COLOR = JBColor(Color(0x1A, 0x1A, 0x1A), Color(0xE0, 0xE0, 0xE0))
        private val SUMMARY_COLOR = JBColor(Color(0x66, 0x66, 0x66), Color(0x85, 0x85, 0x85))
        private val DETAIL_BG = JBColor(Color(0xF5, 0xF5, 0xF5), Color(0x25, 0x25, 0x26))

        private val STATUS_SUCCESS = JBColor(Color(0x10, 0x7C, 0x10), Color(0x4C, 0xAF, 0x50))
        private val STATUS_WARNING = JBColor(Color(0xF7, 0x63, 0x0C), Color(0xFF, 0x98, 0x00))
        private val STATUS_ERROR = JBColor(Color(0xE8, 0x11, 0x23), Color(0xF4, 0x43, 0x36))

        // Diff colors
        private val DIFF_ADD_BG = JBColor(Color(20, 80, 20, 77), Color(20, 80, 20, 77))
        private val DIFF_DEL_BG = JBColor(Color(80, 20, 20, 77), Color(80, 20, 20, 77))
        private val DIFF_ADD_MARKER = JBColor(Color(0x10, 0x7C, 0x10), Color(0x89, 0xD1, 0x85))
        private val DIFF_DEL_MARKER = JBColor(Color(0xE8, 0x11, 0x23), Color(0xFF, 0x6B, 0x6B))
        private val LINK_COLOR = JBColor(Color(0x00, 0x78, 0xD4), Color(0x58, 0x9D, 0xF6))
        private val ICON_HOVER_BG = JBColor(Color(0x00, 0x00, 0x00, 0x15), Color(0xFF, 0xFF, 0xFF, 0x15))

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
                    repaint()
                }
                override fun mouseClicked(e: MouseEvent) {
                    // Check icon clicks first
                    if (hasActionIcons && status == Status.COMPLETED && !reverted) {
                        diffIconBounds?.let { if (it.contains(e.point)) { onShowDiff(); return } }
                        rejectIconBounds?.let { if (it.contains(e.point)) { onReject(); return } }
                    }
                    if (isExpandable) toggleExpanded()
                }
            })
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    if (hasActionIcons && status == Status.COMPLETED && !reverted) {
                        val newHover = when {
                            diffIconBounds?.contains(e.point) == true -> "diff"
                            rejectIconBounds?.contains(e.point) == true -> "reject"
                            else -> null
                        }
                        if (newHover != hoveredIcon) {
                            hoveredIcon = newHover
                            cursor = if (newHover != null)
                                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            else if (isExpandable)
                                Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                            else
                                Cursor.getDefaultCursor()
                            repaint()
                        }
                    }
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

            // Diff badge for edit tools
            val lower = toolName.lowercase()
            if (lower in setOf("edit", "edit_file", "replace_string")) {
                val diffInfo = computeDiffInfo()
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
                        x += bfm.stringWidth(delText) + JBUI.scale(6)
                    }

                    g2.font = titleFont
                }
            }

            // ── Right side: [summary...] [diff-icon] [reject-icon] [●] ──

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

            // Summary text (truncated to fit available space)
            if (summary.isNotEmpty()) {
                val summaryFont = titleFont.deriveFont(Font.PLAIN)
                g2.font = summaryFont
                g2.color = SUMMARY_COLOR
                val sfm = g2.fontMetrics
                val maxSummaryWidth = rightEdge - x - JBUI.scale(4)
                if (maxSummaryWidth > JBUI.scale(30)) {
                    val truncated = truncateText(summary, sfm, maxSummaryWidth)
                    g2.drawString(truncated, x, textY)
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

    // ── Actions (Diff / Reject) ────────────────────────────────────────────

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

    private val detailsPanel: JPanel? = if (isExpandable) createDetailsPanel() else null

    init {
        isOpaque = false

        // Stack: header → details (all in a vertical wrapper)
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(headerPanel)
            detailsPanel?.let {
                it.isVisible = false
                add(it)
            }
        }
        add(contentPanel, BorderLayout.CENTER)

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
        detailsPanel?.isVisible = expanded
        revalidate()
        repaint()
        // Scroll parent to show expanded content
        parent?.revalidate()
        parent?.repaint()
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
            else -> false
        }
    }

    private fun createDetailsPanel(): JPanel {
        val lower = toolName.lowercase()
        return when {
            lower in setOf("edit", "edit_file", "replace_string") -> createDiffPanel()
            lower in setOf("write", "write_to_file", "create_file") -> createContentPanel(MyMessageBundle.message("tool.section.content"), getStr(input, "content") ?: "")
            lower in setOf("bash", "run_terminal_cmd", "execute_command", "shell_command") -> createContentPanel(MyMessageBundle.message("tool.section.command"), getStr(input, "command") ?: "")
            else -> JPanel()
        }
    }

    // ── Diff panel for edit tools ────────────────────────────────────────────

    private fun createDiffPanel(): JPanel {
        val oldStr = getStr(input, "old_string") ?: getStr(input, "oldString") ?: ""
        val newStr = getStr(input, "new_string") ?: getStr(input, "newString") ?: ""

        val diffLines = computeDiff(oldStr, newStr)

        val panel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                // Separator line at the top
                g.color = BORDER_COLOR
                g.fillRect(0, 0, width, 1)
                // Background
                g.color = DETAIL_BG
                g.fillRect(0, 1, width, height - 1)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(1, 0, 0, 0)
        }

        val linesPanel = object : JPanel(GridBagLayout()) {
            init { isOpaque = false }
        }

        val monoFont = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.WEST
            gridy = 0
        }

        for (line in diffLines) {
            // Marker column
            gbc.gridx = 0
            gbc.weightx = 0.0
            gbc.insets = Insets(0, JBUI.scale(8), 0, 0)
            val markerLabel = object : JPanel() {
                init {
                    preferredSize = Dimension(JBUI.scale(20), JBUI.scale(20))
                    minimumSize = preferredSize
                    maximumSize = preferredSize
                    isOpaque = false
                }
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.font = monoFont
                    val fm = g2.fontMetrics
                    val marker = when (line.type) {
                        DiffLineType.ADDED -> "+"
                        DiffLineType.DELETED -> "-"
                        DiffLineType.UNCHANGED -> " "
                    }
                    g2.color = when (line.type) {
                        DiffLineType.ADDED -> DIFF_ADD_MARKER
                        DiffLineType.DELETED -> DIFF_DEL_MARKER
                        DiffLineType.UNCHANGED -> SUMMARY_COLOR
                    }
                    g2.drawString(marker, (width - fm.stringWidth(marker)) / 2, (height + fm.ascent - fm.descent) / 2)
                }
            }
            linesPanel.add(markerLabel, gbc)

            // Content column
            gbc.gridx = 1
            gbc.weightx = 1.0
            gbc.insets = Insets(0, JBUI.scale(4), 0, JBUI.scale(8))
            val contentPanel = object : JPanel(BorderLayout()) {
                init {
                    isOpaque = true
                    background = when (line.type) {
                        DiffLineType.ADDED -> DIFF_ADD_BG
                        DiffLineType.DELETED -> DIFF_DEL_BG
                        DiffLineType.UNCHANGED -> Color(0, 0, 0, 0)
                    }
                }
            }
            val textArea = JBTextArea(line.content).apply {
                isEditable = false
                lineWrap = false
                isOpaque = false
                font = monoFont
                foreground = TITLE_COLOR
                border = JBUI.Borders.empty(1, 4, 1, 4)
            }
            contentPanel.add(textArea, BorderLayout.CENTER)
            linesPanel.add(contentPanel, gbc)

            gbc.gridy++
        }

        val scrollPane = JBScrollPane(linesPanel).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }
        panel.add(scrollPane, BorderLayout.CENTER)
        panel.border = JBUI.Borders.empty(4, 0, 6, 0)
        return panel
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

        val monoFont = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(12))

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
