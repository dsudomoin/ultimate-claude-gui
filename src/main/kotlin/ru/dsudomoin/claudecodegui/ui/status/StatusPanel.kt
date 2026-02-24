package ru.dsudomoin.claudecodegui.ui.status

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.diff.InteractiveDiffManager
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.font.TextAttribute
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * StatusPanel: collapsible panel above the chat input area.
 * Shows three tabs: Todos, File Changes, Subagents.
 * Custom-painted for a modern, polished look.
 */
class StatusPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        private val BG get() = ThemeColors.surfaceSecondary
        private val SURFACE_PRIMARY get() = ThemeColors.surfacePrimary
        private val SURFACE_TERTIARY get() = ThemeColors.surfaceTertiary
        private val BORDER_COLOR get() = ThemeColors.borderNormal
        private val HOVER_BG get() = ThemeColors.surfaceHover
        private val TEXT_PRIMARY get() = ThemeColors.textPrimary
        private val TEXT_SECONDARY get() = ThemeColors.textSecondary
        private val ACCENT get() = ThemeColors.accent
        private val ADD_COLOR get() = ThemeColors.diffAddFg
        private val DEL_COLOR get() = ThemeColors.diffDelFg
        private val STATUS_PENDING get() = ThemeColors.statusPending
        private val STATUS_PROGRESS get() = ThemeColors.statusProgress
        private val STATUS_DONE get() = ThemeColors.statusSuccess
        private val STATUS_ERROR get() = ThemeColors.statusError
        private val ICON_HOVER_BG get() = ThemeColors.iconHoverBg

        private const val MAX_CONTENT_HEIGHT = 180
        private const val TAB_BAR_HEIGHT = 34
        private const val TAB_ARC = 8
        private const val ROW_HEIGHT = 32
        private const val ROW_ARC = 6
        private const val ICON_SIZE = 14
    }

    private var selectedTab = 0  // 0=Todos, 1=Files, 2=Agents
    private var expanded = true

    // Data
    private val todos = mutableListOf<TodoItem>()
    private val fileChanges = mutableMapOf<String, FileChangeSummary>()
    private val subagents = mutableMapOf<String, SubagentInfo>()

    // Spinning animation
    private var spinAngle = 0
    private val spinTimer = Timer(50) {
        spinAngle = (spinAngle + 15) % 360
        repaintSpinners()
    }

    // ── UI Components ────────────────────────────────────────────────────────

    private val tabBar = TabBarPanel()

    private val todosPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 4)
    }

    private val batchActionsBar = BatchActionsBar()

    private val filesListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 4)
    }

    private val filesPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(batchActionsBar, BorderLayout.NORTH)
        add(filesListPanel, BorderLayout.CENTER)
    }

    private val agentsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 4)
    }

    private val contentPanel = JPanel(CardLayout()).apply {
        isOpaque = false
    }

    private val scrollPane = object : JBScrollPane(contentPanel) {
        override fun getPreferredSize(): Dimension {
            val contentHeight = contentPanel.preferredSize.height
            val maxH = JBUI.scale(MAX_CONTENT_HEIGHT)
            return Dimension(super.getPreferredSize().width, minOf(contentHeight, maxH))
        }
    }.apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
    }

    /** Thin collapsed strip shown when user manually hides the panel. */
    private val collapsedStrip = CollapsedStripPanel()

    /** Wrapper holding tabBar + scrollPane — hidden when collapsed. */
    private val expandedPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(tabBar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 0, 8)

        contentPanel.add(todosPanel, "todos")
        contentPanel.add(filesPanel, "files")
        contentPanel.add(agentsPanel, "agents")

        add(expandedPanel, BorderLayout.CENTER)
        add(collapsedStrip, BorderLayout.NORTH)
        collapsedStrip.isVisible = false

        batchActionsBar.isVisible = false
        isVisible = false
    }

    // ── Public API (unchanged) ───────────────────────────────────────────────

    fun updateTodos(newTodos: List<TodoItem>) {
        todos.clear()
        todos.addAll(newTodos)
        rebuildTodosContent()
        tabBar.repaint()
        updateSpinState()
        autoShow()
    }

    fun trackFileChange(toolName: String, filePath: String, oldString: String, newString: String) {
        val fileName = filePath.substringAfterLast('/')
        val addLines = newString.lines().size
        val delLines = oldString.lines().size
        val additions = (addLines - delLines).coerceAtLeast(0)
        val deletions = (delLines - addLines).coerceAtLeast(0)

        val op = EditOperation(toolName, oldString, newString, additions, deletions)

        val existing = fileChanges[filePath]
        if (existing != null) {
            existing.operations.add(op)
            fileChanges[filePath] = existing.copy(
                additions = existing.additions + additions,
                deletions = existing.deletions + deletions
            )
        } else {
            val changeType = if (oldString.isEmpty()) FileChangeType.ADDED else FileChangeType.MODIFIED
            fileChanges[filePath] = FileChangeSummary(filePath, fileName, changeType, additions, deletions, mutableListOf(op))
        }

        rebuildFilesContent()
        tabBar.repaint()
        autoShow()
    }

    fun trackFileWrite(filePath: String, content: String, isNew: Boolean) {
        val fileName = filePath.substringAfterLast('/')
        val additions = content.lines().size
        val op = EditOperation("write", "", content, additions, 0)

        fileChanges[filePath] = FileChangeSummary(
            filePath, fileName,
            if (isNew) FileChangeType.ADDED else FileChangeType.MODIFIED,
            additions, 0, mutableListOf(op)
        )

        rebuildFilesContent()
        tabBar.repaint()
        autoShow()
    }

    fun trackSubagent(id: String, type: String, description: String) {
        subagents[id] = SubagentInfo(id, type, description, SubagentStatus.RUNNING)
        rebuildAgentsContent()
        tabBar.repaint()
        updateSpinState()
        autoShow()
    }

    fun completeSubagent(id: String, error: Boolean = false) {
        subagents[id]?.let {
            subagents[id] = it.copy(status = if (error) SubagentStatus.ERROR else SubagentStatus.COMPLETED)
        }
        rebuildAgentsContent()
        tabBar.repaint()
        updateSpinState()
    }

    fun clear() {
        todos.clear()
        fileChanges.clear()
        subagents.clear()
        rebuildTodosContent()
        rebuildFilesContent()
        rebuildAgentsContent()
        tabBar.repaint()
        updateSpinState()
        isVisible = false
    }

    fun dispose() {
        spinTimer.stop()
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private val hasContent: Boolean
        get() = todos.isNotEmpty() || fileChanges.isNotEmpty() || subagents.isNotEmpty()

    private fun autoShow() {
        if (!isVisible && hasContent) {
            isVisible = true
            revalidate()
            repaint()
        }
    }

    private fun autoHide() {
        if (isVisible && !hasContent) {
            isVisible = false
            revalidate()
            repaint()
        }
    }

    private fun showTab(index: Int) {
        selectedTab = index
        val cl = contentPanel.layout as CardLayout
        cl.show(contentPanel, listOf("todos", "files", "agents")[index])
        tabBar.repaint()
    }

    private fun toggleExpanded() {
        expanded = !expanded
        expandedPanel.isVisible = expanded
        collapsedStrip.isVisible = !expanded
        collapsedStrip.repaint()
        revalidate()
        repaint()
    }

    private fun updateSpinState() {
        val hasSpinning = todos.any { it.status == TodoStatus.IN_PROGRESS }
                || subagents.values.any { it.status == SubagentStatus.RUNNING }
        if (hasSpinning && !spinTimer.isRunning) spinTimer.start()
        else if (!hasSpinning && spinTimer.isRunning) { spinTimer.stop(); spinAngle = 0 }
    }

    private fun repaintSpinners() {
        tabBar.repaint()
        when (selectedTab) {
            0 -> todosPanel.components.filterIsInstance<TodoRowPanel>()
                .filter { it.item.status == TodoStatus.IN_PROGRESS }
                .forEach { it.repaint() }
            2 -> agentsPanel.components.filterIsInstance<AgentRowPanel>()
                .filter { it.agent.status == SubagentStatus.RUNNING }
                .forEach { it.repaint() }
        }
    }

    private fun paintSpinner(g2: Graphics2D, cx: Int, cy: Int, size: Int, color: Color) {
        val prevStroke = g2.stroke
        g2.color = color
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.drawArc(cx, cy, size, size, spinAngle, 270)
        g2.stroke = prevStroke
    }

    private fun todosSubtitle(): String {
        if (todos.isEmpty()) return ""
        val done = todos.count { it.status == TodoStatus.COMPLETED }
        return "$done/${todos.size}"
    }

    private fun filesSubtitle(): Pair<Int, Int> {
        val totalAdd = fileChanges.values.sumOf { it.additions }
        val totalDel = fileChanges.values.sumOf { it.deletions }
        return totalAdd to totalDel
    }

    private fun agentsSubtitle(): String {
        if (subagents.isEmpty()) return ""
        val done = subagents.values.count { it.status != SubagentStatus.RUNNING }
        return "$done/${subagents.size}"
    }

    private fun truncateText(text: String, fm: FontMetrics, maxWidth: Int): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        val ellipsis = "\u2026"
        val ellipsisWidth = fm.stringWidth(ellipsis)
        var end = text.length
        while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisWidth > maxWidth) end--
        return if (end > 0) text.substring(0, end) + ellipsis else ellipsis
    }

    // ── Collapsed Strip ───────────────────────────────────────────────────────

    private inner class CollapsedStripPanel : JPanel() {
        private var hover = false

        init {
            isOpaque = false
            val h = JBUI.scale(24)
            preferredSize = Dimension(0, h)
            minimumSize = Dimension(0, h)
            maximumSize = Dimension(Int.MAX_VALUE, h)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                override fun mouseClicked(e: MouseEvent) { toggleExpanded() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val arc = JBUI.scale(TAB_ARC).toFloat()
            val shape = RoundRectangle2D.Float(0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), arc, arc)

            g2.color = if (hover) HOVER_BG else SURFACE_PRIMARY
            g2.fill(shape)
            g2.color = BORDER_COLOR
            g2.stroke = BasicStroke(1f)
            g2.draw(shape)

            var x = JBUI.scale(8)
            val cy = height / 2

            // Expand arrow
            val arrowIcon = AllIcons.General.ArrowRight
            arrowIcon.paintIcon(this, g2, x, cy - arrowIcon.iconHeight / 2)
            x += arrowIcon.iconWidth + JBUI.scale(6)

            // Brief stats summary
            val labelFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            g2.font = labelFont
            val fm = g2.fontMetrics
            val textY = cy + (fm.ascent - fm.descent) / 2

            val parts = mutableListOf<Pair<String, Color>>()
            val todoSub = todosSubtitle()
            if (todoSub.isNotEmpty()) parts.add("Todos $todoSub" to TEXT_SECONDARY)
            val (adds, dels) = filesSubtitle()
            if (adds > 0 || dels > 0) {
                val filesText = buildString {
                    append("Files")
                    if (adds > 0) append(" +$adds")
                    if (dels > 0) append(" -$dels")
                }
                parts.add(filesText to TEXT_SECONDARY)
            }
            val agentSub = agentsSubtitle()
            if (agentSub.isNotEmpty()) parts.add("Agents $agentSub" to TEXT_SECONDARY)

            for ((i, pair) in parts.withIndex()) {
                if (i > 0) {
                    g2.color = BORDER_COLOR
                    g2.drawString(" · ", x, textY)
                    x += fm.stringWidth(" · ")
                }
                g2.color = pair.second
                g2.drawString(pair.first, x, textY)
                x += fm.stringWidth(pair.first)
            }

            // Spinner if anything active
            if (todos.any { it.status == TodoStatus.IN_PROGRESS } || subagents.values.any { it.status == SubagentStatus.RUNNING }) {
                x += JBUI.scale(4)
                paintSpinner(g2, x, cy - JBUI.scale(5), JBUI.scale(10), STATUS_PROGRESS)
            }
        }
    }

    // ── Tab Bar ──────────────────────────────────────────────────────────────

    private inner class TabBarPanel : JPanel() {
        private var hoveredTab = -1
        private var hoveredCollapse = false
        private val tabBounds = arrayOf(Rectangle(), Rectangle(), Rectangle())
        private val collapseBounds = Rectangle()

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(TAB_BAR_HEIGHT))
            minimumSize = preferredSize
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(TAB_BAR_HEIGHT))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    for (i in 0..2) {
                        if (tabBounds[i].contains(e.point)) { showTab(i); return }
                    }
                    if (collapseBounds.contains(e.point)) toggleExpanded()
                }
                override fun mouseExited(e: MouseEvent) {
                    if (hoveredTab != -1 || hoveredCollapse) {
                        hoveredTab = -1; hoveredCollapse = false; repaint()
                    }
                }
            })
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val newTab = (0..2).firstOrNull { tabBounds[it].contains(e.point) } ?: -1
                    val newCollapse = collapseBounds.contains(e.point)
                    if (newTab != hoveredTab || newCollapse != hoveredCollapse) {
                        hoveredTab = newTab; hoveredCollapse = newCollapse; repaint()
                    }
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            val pad = JBUI.scale(4)
            val arc = JBUI.scale(TAB_ARC).toFloat()
            val barY = pad
            val barH = height - pad * 2
            val barW = width

            // Outer rounded container
            val outerShape = RoundRectangle2D.Float(0.5f, barY.toFloat(), (barW - 1).toFloat(), barH.toFloat(), arc, arc)
            g2.color = SURFACE_PRIMARY
            g2.fill(outerShape)

            // Clip to outer shape for tab backgrounds
            val prevClip = g2.clip
            g2.clip(outerShape)

            // Collapse toggle width
            val collapseW = JBUI.scale(28)
            val tabAreaW = barW - collapseW
            val tabW = tabAreaW / 3

            // Compute tab bounds
            for (i in 0..2) {
                tabBounds[i].setBounds(i * tabW, barY, if (i == 2) tabAreaW - 2 * tabW else tabW, barH)
            }
            collapseBounds.setBounds(tabAreaW, barY, collapseW, barH)

            // Paint tabs
            for (i in 0..2) {
                val tb = tabBounds[i]
                val isActive = i == selectedTab
                val isHover = i == hoveredTab && !isActive

                // Tab background
                if (isActive) {
                    g2.color = SURFACE_TERTIARY
                    g2.fillRect(tb.x, tb.y, tb.width, tb.height)
                } else if (isHover) {
                    g2.color = HOVER_BG
                    g2.fillRect(tb.x, tb.y, tb.width, tb.height)
                }

                // Divider (right edge, except last tab)
                if (i < 2) {
                    g2.color = BORDER_COLOR
                    g2.fillRect(tb.x + tb.width - 1, tb.y + JBUI.scale(6), 1, tb.height - JBUI.scale(12))
                }

                // Tab content: [icon] [label] [stats/spinner]
                val textColor = if (isActive || isHover) TEXT_PRIMARY else TEXT_SECONDARY
                var x = tb.x + JBUI.scale(8)
                val cy = tb.y + tb.height / 2

                // Icon
                val icon = when (i) {
                    0 -> AllIcons.Actions.Checked
                    1 -> AllIcons.Actions.Edit
                    else -> AllIcons.Nodes.ConfigFolder
                }
                icon.paintIcon(this, g2, x, cy - icon.iconHeight / 2)
                x += icon.iconWidth + JBUI.scale(4)

                // Label
                val labelFont = (font ?: g2.font).deriveFont(
                    if (isActive) Font.BOLD else Font.PLAIN,
                    JBUI.scale(11).toFloat()
                )
                g2.font = labelFont
                g2.color = textColor
                val fm = g2.fontMetrics
                val textY = cy + (fm.ascent - fm.descent) / 2
                val label = when (i) {
                    0 -> "Todos"
                    1 -> "Files"
                    else -> "Agents"
                }
                g2.drawString(label, x, textY)
                x += fm.stringWidth(label) + JBUI.scale(4)

                // Stats
                val statsFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                g2.font = statsFont
                val sfm = g2.fontMetrics
                val statsY = cy + (sfm.ascent - sfm.descent) / 2

                when (i) {
                    0 -> {
                        val sub = todosSubtitle()
                        if (sub.isNotEmpty()) {
                            g2.color = if (isActive) TEXT_PRIMARY else TEXT_SECONDARY
                            g2.drawString(sub, x, statsY)
                            x += sfm.stringWidth(sub) + JBUI.scale(4)
                        }
                        // Spinner if in-progress
                        if (todos.any { it.status == TodoStatus.IN_PROGRESS }) {
                            paintSpinner(g2, x, cy - JBUI.scale(5), JBUI.scale(10), STATUS_PROGRESS)
                        }
                    }
                    1 -> {
                        val (adds, dels) = filesSubtitle()
                        if (adds > 0) {
                            g2.color = ADD_COLOR
                            val addText = "+$adds"
                            g2.drawString(addText, x, statsY)
                            x += sfm.stringWidth(addText) + JBUI.scale(3)
                        }
                        if (dels > 0) {
                            g2.color = DEL_COLOR
                            val delText = "-$dels"
                            g2.drawString(delText, x, statsY)
                        }
                    }
                    2 -> {
                        val sub = agentsSubtitle()
                        if (sub.isNotEmpty()) {
                            g2.color = if (isActive) TEXT_PRIMARY else TEXT_SECONDARY
                            g2.drawString(sub, x, statsY)
                            x += sfm.stringWidth(sub) + JBUI.scale(4)
                        }
                        if (subagents.values.any { it.status == SubagentStatus.RUNNING }) {
                            paintSpinner(g2, x, cy - JBUI.scale(5), JBUI.scale(10), STATUS_PROGRESS)
                        }
                    }
                }
            }

            g2.clip = prevClip

            // Collapse toggle (always shows "collapse" since tab bar is only visible when expanded)
            val collapseIcon = AllIcons.General.ArrowDown
            val cix = collapseBounds.x + (collapseBounds.width - collapseIcon.iconWidth) / 2
            val ciy = collapseBounds.y + (collapseBounds.height - collapseIcon.iconHeight) / 2
            if (hoveredCollapse) {
                g2.color = HOVER_BG
                g2.fillRoundRect(
                    collapseBounds.x + JBUI.scale(2), collapseBounds.y + JBUI.scale(4),
                    collapseBounds.width - JBUI.scale(4), collapseBounds.height - JBUI.scale(8),
                    JBUI.scale(4), JBUI.scale(4)
                )
            }
            collapseIcon.paintIcon(this, g2, cix, ciy)

            // Outer border
            g2.color = BORDER_COLOR
            g2.stroke = BasicStroke(1f)
            g2.draw(outerShape)
        }
    }

    // ── Empty State ─────────────────────────────────────────────────────────

    private fun createEmptyLabel(messageKey: String): JPanel {
        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(60))
            add(object : JPanel() {
                init { isOpaque = false }
                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                    g2.font = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
                    g2.color = TEXT_SECONDARY
                    val text = UcuBundle.message(messageKey)
                    val fm = g2.fontMetrics
                    g2.drawString(text, (width - fm.stringWidth(text)) / 2, (height + fm.ascent - fm.descent) / 2)
                }
                override fun getPreferredSize() = Dimension(JBUI.scale(200), JBUI.scale(30))
            })
        }
    }

    // ── Todos Content ────────────────────────────────────────────────────────

    private fun rebuildTodosContent() {
        todosPanel.removeAll()
        if (todos.isEmpty()) {
            todosPanel.add(createEmptyLabel("status.noTodos"))
        } else {
            for (todo in todos) {
                todosPanel.add(TodoRowPanel(todo))
            }
        }
        todosPanel.add(Box.createVerticalGlue())
        todosPanel.revalidate()
        todosPanel.repaint()
    }

    private inner class TodoRowPanel(val item: TodoItem) : JPanel() {
        private var hover = false

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(ROW_HEIGHT))
            minimumSize = Dimension(0, JBUI.scale(ROW_HEIGHT))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ROW_HEIGHT))

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            if (hover) {
                g2.color = HOVER_BG
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(ROW_ARC), JBUI.scale(ROW_ARC))
            }

            val padX = JBUI.scale(8)
            var x = padX
            val cy = height / 2
            val iconSz = JBUI.scale(ICON_SIZE)

            // Status icon
            when (item.status) {
                TodoStatus.PENDING -> {
                    g2.color = STATUS_PENDING
                    g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
                    g2.drawOval(x + 1, cy - iconSz / 2 + 1, iconSz - 2, iconSz - 2)
                }
                TodoStatus.IN_PROGRESS -> {
                    paintSpinner(g2, x, cy - iconSz / 2, iconSz, STATUS_PROGRESS)
                }
                TodoStatus.COMPLETED -> {
                    val checkIcon = AllIcons.Actions.Checked
                    checkIcon.paintIcon(this, g2, x, cy - checkIcon.iconHeight / 2)
                }
            }
            x += iconSz + JBUI.scale(8)

            // Content text
            val baseFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            val textFont = if (item.status == TodoStatus.COMPLETED) {
                baseFont.deriveFont(mapOf(TextAttribute.STRIKETHROUGH to TextAttribute.STRIKETHROUGH_ON))
            } else baseFont
            g2.font = textFont
            g2.color = if (item.status == TodoStatus.COMPLETED) TEXT_SECONDARY else TEXT_PRIMARY
            val fm = g2.fontMetrics
            val textY = (height + fm.ascent - fm.descent) / 2
            val maxW = width - x - padX
            if (maxW > JBUI.scale(20)) {
                val truncated = truncateText(item.content, fm, maxW)
                g2.drawString(truncated, x, textY)
            }
        }
    }

    // ── Files Content ────────────────────────────────────────────────────────

    private fun rebuildFilesContent() {
        filesListPanel.removeAll()
        batchActionsBar.isVisible = fileChanges.isNotEmpty()
        if (fileChanges.isEmpty()) {
            filesListPanel.add(createEmptyLabel("status.noFiles"))
        } else {
            for ((_, fc) in fileChanges) {
                filesListPanel.add(FileRowPanel(fc))
            }
        }
        filesListPanel.add(Box.createVerticalGlue())
        filesListPanel.revalidate()
        filesListPanel.repaint()
    }

    private inner class FileRowPanel(private val fc: FileChangeSummary) : JPanel() {
        private var hover = false
        private var hoveredIcon: String? = null
        private val diffBtnBounds = Rectangle()
        private val undoBtnBounds = Rectangle()
        private val fileNameBounds = Rectangle()
        private var fileNameHover = false

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(ROW_HEIGHT))
            minimumSize = Dimension(0, JBUI.scale(ROW_HEIGHT))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ROW_HEIGHT))

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; hoveredIcon = null; fileNameHover = false; repaint() }
                override fun mouseClicked(e: MouseEvent) {
                    if (diffBtnBounds.contains(e.point)) { showFileDiff(fc); return }
                    if (undoBtnBounds.contains(e.point)) { undoFileChange(fc); return }
                    if (fileNameBounds.contains(e.point)) { openFile(fc.filePath); return }
                }
            })
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val newIcon = when {
                        diffBtnBounds.contains(e.point) -> "diff"
                        undoBtnBounds.contains(e.point) -> "undo"
                        else -> null
                    }
                    val newNameHover = fileNameBounds.contains(e.point)
                    if (newIcon != hoveredIcon || newNameHover != fileNameHover) {
                        hoveredIcon = newIcon; fileNameHover = newNameHover; repaint()
                    }
                    cursor = if (newIcon != null || newNameHover)
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    else Cursor.getDefaultCursor()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            if (hover) {
                g2.color = HOVER_BG
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(ROW_ARC), JBUI.scale(ROW_ARC))
            }

            val padX = JBUI.scale(8)
            var x = padX
            val cy = height / 2

            // A/M badge
            val badgeFont = (font ?: g2.font).deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            g2.font = badgeFont
            val bfm = g2.fontMetrics
            val badgeText = if (fc.changeType == FileChangeType.ADDED) "A" else "M"
            val badgeColor = if (fc.changeType == FileChangeType.ADDED) STATUS_DONE else ACCENT
            val badgeW = bfm.stringWidth(badgeText) + JBUI.scale(8)
            val badgeH = bfm.height + JBUI.scale(2)
            val badgeY = cy - badgeH / 2
            // Badge background (translucent)
            g2.color = Color(badgeColor.red, badgeColor.green, badgeColor.blue, 25)
            g2.fillRoundRect(x, badgeY, badgeW, badgeH, JBUI.scale(3), JBUI.scale(3))
            g2.color = badgeColor
            g2.drawString(badgeText, x + JBUI.scale(4), badgeY + bfm.ascent + JBUI.scale(1))
            x += badgeW + JBUI.scale(6)

            // File type icon
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fc.fileName)
            val fileIcon = fileType.icon
            if (fileIcon != null) {
                fileIcon.paintIcon(this, g2, x, cy - fileIcon.iconHeight / 2)
                x += fileIcon.iconWidth + JBUI.scale(4)
            }

            // Right side: [+N -N] [diff] [undo]
            val btnSize = JBUI.scale(20)
            val iconSz = JBUI.scale(14)
            val iconOff = (btnSize - iconSz) / 2
            var rx = width - padX

            // Undo button
            rx -= btnSize
            undoBtnBounds.setBounds(rx, (height - btnSize) / 2, btnSize, btnSize)
            if (hoveredIcon == "undo") {
                g2.color = ICON_HOVER_BG
                g2.fillRoundRect(rx, (height - btnSize) / 2, btnSize, btnSize, JBUI.scale(4), JBUI.scale(4))
            }
            AllIcons.Actions.Rollback.paintIcon(this, g2, rx + iconOff, (height - iconSz) / 2)
            rx -= JBUI.scale(2)

            // Diff button
            rx -= btnSize
            diffBtnBounds.setBounds(rx, (height - btnSize) / 2, btnSize, btnSize)
            if (hoveredIcon == "diff") {
                g2.color = ICON_HOVER_BG
                g2.fillRoundRect(rx, (height - btnSize) / 2, btnSize, btnSize, JBUI.scale(4), JBUI.scale(4))
            }
            AllIcons.Actions.Diff.paintIcon(this, g2, rx + iconOff, (height - iconSz) / 2)
            rx -= JBUI.scale(6)

            // Stats (+N -N)
            val statsFont = (font ?: g2.font).deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            g2.font = statsFont
            val sfm = g2.fontMetrics
            val statsY = (height + sfm.ascent - sfm.descent) / 2
            if (fc.deletions > 0) {
                val t = "-${fc.deletions}"
                rx -= sfm.stringWidth(t)
                g2.color = DEL_COLOR
                g2.drawString(t, rx, statsY)
                rx -= JBUI.scale(4)
            }
            if (fc.additions > 0) {
                val t = "+${fc.additions}"
                rx -= sfm.stringWidth(t)
                g2.color = ADD_COLOR
                g2.drawString(t, rx, statsY)
                rx -= JBUI.scale(6)
            }

            // File name (clickable)
            val nameFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            val actualNameFont = if (fileNameHover) {
                nameFont.deriveFont(mapOf(TextAttribute.UNDERLINE to TextAttribute.UNDERLINE_ON))
            } else nameFont
            g2.font = actualNameFont
            g2.color = TEXT_PRIMARY
            val nfm = g2.fontMetrics
            val nameY = (height + nfm.ascent - nfm.descent) / 2
            val maxNameW = rx - x - JBUI.scale(4)
            if (maxNameW > JBUI.scale(20)) {
                val truncated = truncateText(fc.fileName, nfm, maxNameW)
                g2.drawString(truncated, x, nameY)
                val nameW = nfm.stringWidth(truncated)
                fileNameBounds.setBounds(x, 0, nameW, height)
            } else {
                fileNameBounds.setBounds(0, 0, 0, 0)
            }
        }
    }

    // ── Batch Actions Bar ────────────────────────────────────────────────────

    private inner class BatchActionsBar : JPanel() {
        private var discardHover = false
        private var keepHover = false
        private val discardBounds = Rectangle()
        private val keepBounds = Rectangle()

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(30))
            minimumSize = Dimension(0, JBUI.scale(30))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (discardBounds.contains(e.point)) discardAllFiles()
                    if (keepBounds.contains(e.point)) keepAllFiles()
                }
                override fun mouseExited(e: MouseEvent) {
                    if (discardHover || keepHover) {
                        discardHover = false; keepHover = false; repaint()
                    }
                }
            })
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val newDiscard = discardBounds.contains(e.point)
                    val newKeep = keepBounds.contains(e.point)
                    if (newDiscard != discardHover || newKeep != keepHover) {
                        discardHover = newDiscard; keepHover = newKeep; repaint()
                    }
                    cursor = if (newDiscard || newKeep)
                        Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    else Cursor.getDefaultCursor()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // Background
            g2.color = SURFACE_TERTIARY
            g2.fillRect(0, 0, width, height)
            // Bottom border
            g2.color = BORDER_COLOR
            g2.fillRect(0, height - 1, width, 1)

            val btnFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
            g2.font = btnFont
            val fm = g2.fontMetrics
            val textY = (height + fm.ascent - fm.descent) / 2
            val btnH = JBUI.scale(22)
            val btnY = (height - btnH) / 2
            var rx = width - JBUI.scale(8)

            // "Keep All" button
            val keepText = "Keep All"
            val keepW = fm.stringWidth(keepText) + JBUI.scale(16)
            rx -= keepW
            keepBounds.setBounds(rx, btnY, keepW, btnH)
            g2.color = if (keepHover) HOVER_BG else Color(0, 0, 0, 0)
            g2.fillRoundRect(rx, btnY, keepW, btnH, JBUI.scale(4), JBUI.scale(4))
            g2.color = BORDER_COLOR
            g2.drawRoundRect(rx, btnY, keepW, btnH, JBUI.scale(4), JBUI.scale(4))
            g2.color = TEXT_PRIMARY
            g2.drawString(keepText, rx + JBUI.scale(8), textY)
            rx -= JBUI.scale(6)

            // "Discard All" button
            val discardText = "Discard All"
            val discardW = fm.stringWidth(discardText) + JBUI.scale(16)
            rx -= discardW
            discardBounds.setBounds(rx, btnY, discardW, btnH)
            val discardBg = if (discardHover) Color(STATUS_ERROR.red, STATUS_ERROR.green, STATUS_ERROR.blue, 25) else Color(0, 0, 0, 0)
            g2.color = discardBg
            g2.fillRoundRect(rx, btnY, discardW, btnH, JBUI.scale(4), JBUI.scale(4))
            g2.color = if (discardHover) STATUS_ERROR else BORDER_COLOR
            g2.drawRoundRect(rx, btnY, discardW, btnH, JBUI.scale(4), JBUI.scale(4))
            g2.color = if (discardHover) STATUS_ERROR else TEXT_SECONDARY
            g2.drawString(discardText, rx + JBUI.scale(8), textY)
        }
    }

    private fun discardAllFiles() {
        for ((path, fc) in fileChanges) {
            for (op in fc.operations.reversed()) {
                if (op.toolName in setOf("write", "write_to_file", "create_file", "save-file")) {
                    InteractiveDiffManager.revertWrite(project, path)
                } else {
                    InteractiveDiffManager.revertEdit(path, op.oldString, op.newString)
                }
            }
        }
        fileChanges.clear()
        rebuildFilesContent()
        tabBar.repaint()
        autoHide()
    }

    private fun keepAllFiles() {
        fileChanges.clear()
        rebuildFilesContent()
        tabBar.repaint()
        autoHide()
    }

    // ── Agents Content ───────────────────────────────────────────────────────

    private fun rebuildAgentsContent() {
        agentsPanel.removeAll()
        if (subagents.isEmpty()) {
            agentsPanel.add(createEmptyLabel("status.noAgents"))
        } else {
            for ((_, agent) in subagents) {
                agentsPanel.add(AgentRowPanel(agent))
            }
        }
        agentsPanel.add(Box.createVerticalGlue())
        agentsPanel.revalidate()
        agentsPanel.repaint()
    }

    private inner class AgentRowPanel(val agent: SubagentInfo) : JPanel() {
        private var hover = false

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(ROW_HEIGHT))
            minimumSize = Dimension(0, JBUI.scale(ROW_HEIGHT))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ROW_HEIGHT))

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            if (hover) {
                g2.color = HOVER_BG
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(ROW_ARC), JBUI.scale(ROW_ARC))
            }

            val padX = JBUI.scale(8)
            var x = padX
            val cy = height / 2
            val iconSz = JBUI.scale(ICON_SIZE)

            // Status icon
            when (agent.status) {
                SubagentStatus.RUNNING -> {
                    paintSpinner(g2, x, cy - iconSz / 2, iconSz, STATUS_PROGRESS)
                }
                SubagentStatus.COMPLETED -> {
                    val icon = AllIcons.Actions.Checked
                    icon.paintIcon(this, g2, x, cy - icon.iconHeight / 2)
                }
                SubagentStatus.ERROR -> {
                    val icon = AllIcons.General.Error
                    icon.paintIcon(this, g2, x, cy - icon.iconHeight / 2)
                }
            }
            x += iconSz + JBUI.scale(8)

            // Type badge
            val badgeFont = (font ?: g2.font).deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            g2.font = badgeFont
            val bfm = g2.fontMetrics
            val badgeText = agent.type
            val badgeW = bfm.stringWidth(badgeText) + JBUI.scale(12)
            val badgeH = bfm.height + JBUI.scale(4)
            val badgeY = cy - badgeH / 2
            g2.color = SURFACE_TERTIARY
            g2.fillRoundRect(x, badgeY, badgeW, badgeH, JBUI.scale(4), JBUI.scale(4))
            g2.color = TEXT_SECONDARY
            g2.drawString(badgeText, x + JBUI.scale(6), badgeY + bfm.ascent + JBUI.scale(2))
            x += badgeW + JBUI.scale(8)

            // Description
            val descFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            g2.font = descFont
            g2.color = TEXT_PRIMARY
            val dfm = g2.fontMetrics
            val descY = (height + dfm.ascent - dfm.descent) / 2
            val maxW = width - x - padX
            if (maxW > JBUI.scale(20)) {
                val truncated = truncateText(agent.description, dfm, maxW)
                g2.drawString(truncated, x, descY)
            }
        }
    }

    // ── File Actions ─────────────────────────────────────────────────────────

    private fun openFile(filePath: String) {
        val vf = LocalFileSystem.getInstance().findFileByPath(filePath)
        if (vf != null) {
            FileEditorManager.getInstance(project).openFile(vf, true)
        }
    }

    private fun showFileDiff(fc: FileChangeSummary) {
        if (fc.operations.isEmpty()) return
        val file = java.io.File(fc.filePath)
        if (!file.exists()) return

        val currentContent = file.readText()
        var originalContent = currentContent
        for (op in fc.operations.reversed()) {
            if (op.newString.isNotEmpty() && originalContent.contains(op.newString)) {
                val idx = originalContent.indexOf(op.newString)
                originalContent = originalContent.substring(0, idx) + op.oldString + originalContent.substring(idx + op.newString.length)
            }
        }
        InteractiveDiffManager.showDiff(project, fc.filePath, originalContent, currentContent)
    }

    private fun undoFileChange(fc: FileChangeSummary) {
        for (op in fc.operations.reversed()) {
            if (op.toolName in setOf("write", "write_to_file", "create_file", "save-file")) {
                InteractiveDiffManager.revertWrite(project, fc.filePath)
            } else {
                InteractiveDiffManager.revertEdit(fc.filePath, op.oldString, op.newString)
            }
        }
        fileChanges.remove(fc.filePath)
        rebuildFilesContent()
        tabBar.repaint()
        autoHide()
    }
}
