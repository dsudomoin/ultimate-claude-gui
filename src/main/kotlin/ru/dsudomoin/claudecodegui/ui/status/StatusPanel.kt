package ru.dsudomoin.claudecodegui.ui.status

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.ui.diff.InteractiveDiffManager
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * StatusPanel: collapsible panel above the chat input area.
 * Shows three tabs: Todos, File Changes, Subagents.
 *
 * ┌─[ Todos (3/5) ]─[ Files (+12 -4) ]─[ Agents (1/2) ]──────── ▾ ─┐
 * │ content for selected tab                                         │
 * └──────────────────────────────────────────────────────────────────┘
 */
class StatusPanel(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        private val BG = JBColor(Color(0xFA, 0xFA, 0xFA), Color(0x1E, 0x1F, 0x22))
        private val BORDER_COLOR = JBColor(Color(0xD0, 0xD0, 0xD0), Color(0x33, 0x33, 0x33))
        private val TAB_ACTIVE = JBColor(Color(0x00, 0x78, 0xD4), Color(0x58, 0x9D, 0xF6))
        private val TAB_INACTIVE = JBColor(Color(0x66, 0x66, 0x66), Color(0x88, 0x88, 0x88))
        private val TEXT_PRIMARY = JBColor(Color(0x1A, 0x1A, 0x1A), Color(0xE0, 0xE0, 0xE0))
        private val TEXT_SECONDARY = JBColor(Color(0x66, 0x66, 0x66), Color(0x88, 0x88, 0x88))
        private val ADD_COLOR = JBColor(Color(0x10, 0x7C, 0x10), Color(0x4C, 0xAF, 0x50))
        private val DEL_COLOR = JBColor(Color(0xE8, 0x11, 0x23), Color(0xF4, 0x43, 0x36))
        private val STATUS_PENDING = JBColor(Color(0xB0, 0xB0, 0xB0), Color(0x66, 0x66, 0x66))
        private val STATUS_PROGRESS = JBColor(Color(0x00, 0x78, 0xD4), Color(0x58, 0x9D, 0xF6))
        private val STATUS_DONE = JBColor(Color(0x10, 0x7C, 0x10), Color(0x4C, 0xAF, 0x50))
        private val STATUS_ERROR = JBColor(Color(0xE8, 0x11, 0x23), Color(0xF4, 0x43, 0x36))

        private const val MAX_CONTENT_HEIGHT = 180
    }

    private var selectedTab = 0  // 0=Todos, 1=Files, 2=Agents
    private var expanded = true

    // Data
    private val todos = mutableListOf<TodoItem>()
    private val fileChanges = mutableMapOf<String, FileChangeSummary>() // filePath → summary
    private val subagents = mutableMapOf<String, SubagentInfo>()       // id → info

    // ── Tab header ──────────────────────────────────────────────────────────

    private val tabHeader = object : JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)) {
        init {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 0, 8)
        }
    }

    private val contentPanel = JPanel(CardLayout()).apply {
        isOpaque = false
    }

    private val todosPanel = createTodosContent()
    private val filesPanel = createFilesContent()
    private val agentsPanel = createAgentsContent()

    private val scrollPane = JScrollPane(contentPanel).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(MAX_CONTENT_HEIGHT))
        preferredSize = Dimension(0, JBUI.scale(MAX_CONTENT_HEIGHT))
    }

    init {
        isOpaque = true
        background = BG
        border = JBUI.Borders.customLine(BORDER_COLOR, 0, 0, 1, 0)

        contentPanel.add(todosPanel, "todos")
        contentPanel.add(filesPanel, "files")
        contentPanel.add(agentsPanel, "agents")

        rebuildTabHeader()

        add(tabHeader, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        // Start hidden — will show when data arrives
        isVisible = false
    }

    // ── Public API ──────────────────────────────────────────────────────────

    fun updateTodos(newTodos: List<TodoItem>) {
        todos.clear()
        todos.addAll(newTodos)
        rebuildTodosContent()
        rebuildTabHeader()
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
        rebuildTabHeader()
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
        rebuildTabHeader()
        autoShow()
    }

    fun trackSubagent(id: String, type: String, description: String) {
        subagents[id] = SubagentInfo(id, type, description, SubagentStatus.RUNNING)
        rebuildAgentsContent()
        rebuildTabHeader()
        autoShow()
    }

    fun completeSubagent(id: String, error: Boolean = false) {
        subagents[id]?.let {
            subagents[id] = it.copy(status = if (error) SubagentStatus.ERROR else SubagentStatus.COMPLETED)
        }
        rebuildAgentsContent()
        rebuildTabHeader()
    }

    fun clear() {
        todos.clear()
        fileChanges.clear()
        subagents.clear()
        rebuildTodosContent()
        rebuildFilesContent()
        rebuildAgentsContent()
        rebuildTabHeader()
        isVisible = false
    }

    private fun autoShow() {
        if (todos.isNotEmpty() || fileChanges.isNotEmpty() || subagents.isNotEmpty()) {
            isVisible = true
            revalidate()
            repaint()
        }
    }

    // ── Tab header ──────────────────────────────────────────────────────────

    private fun rebuildTabHeader() {
        tabHeader.removeAll()

        val todosLabel = buildTabLabel(0, "Todos", todosSubtitle())
        val filesLabel = buildTabLabel(1, "Files", filesSubtitle())
        val agentsLabel = buildTabLabel(2, "Agents", agentsSubtitle())

        tabHeader.add(todosLabel)
        tabHeader.add(filesLabel)
        tabHeader.add(agentsLabel)

        // Collapse/expand toggle
        val toggle = object : JLabel(if (expanded) "▾" else "▸") {
            init {
                font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                foreground = TAB_INACTIVE
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(4, 8)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        expanded = !expanded
                        scrollPane.isVisible = expanded
                        text = if (expanded) "▾" else "▸"
                        revalidate()
                        repaint()
                    }
                })
            }
        }
        tabHeader.add(toggle)

        tabHeader.revalidate()
        tabHeader.repaint()
    }

    private fun buildTabLabel(index: Int, name: String, subtitle: String): JPanel {
        val active = index == selectedTab
        return object : JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(4, 8, 4, 8)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        selectedTab = index
                        val cl = contentPanel.layout as CardLayout
                        cl.show(contentPanel, listOf("todos", "files", "agents")[index])
                        rebuildTabHeader()
                    }
                })
            }

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (active) {
                    val g2 = g as Graphics2D
                    g2.color = TAB_ACTIVE
                    g2.fillRect(0, height - JBUI.scale(2), width, JBUI.scale(2))
                }
            }
        }.apply {
            val nameLabel = JLabel(name).apply {
                font = font.deriveFont(if (active) Font.BOLD else Font.PLAIN, JBUI.scale(11).toFloat())
                foreground = if (active) TAB_ACTIVE else TAB_INACTIVE
            }
            add(nameLabel)
            if (subtitle.isNotEmpty()) {
                val subLabel = JLabel(subtitle).apply {
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                    foreground = TEXT_SECONDARY
                }
                add(subLabel)
            }
        }
    }

    private fun todosSubtitle(): String {
        if (todos.isEmpty()) return ""
        val done = todos.count { it.status == TodoStatus.COMPLETED }
        return "($done/${todos.size})"
    }

    private fun filesSubtitle(): String {
        if (fileChanges.isEmpty()) return ""
        val totalAdd = fileChanges.values.sumOf { it.additions }
        val totalDel = fileChanges.values.sumOf { it.deletions }
        return buildString {
            if (totalAdd > 0) append("+$totalAdd")
            if (totalDel > 0) { if (isNotEmpty()) append(" "); append("-$totalDel") }
            if (isEmpty()) append("${fileChanges.size}")
        }
    }

    private fun agentsSubtitle(): String {
        if (subagents.isEmpty()) return ""
        val done = subagents.values.count { it.status != SubagentStatus.RUNNING }
        return "($done/${subagents.size})"
    }

    // ── Todos content ───────────────────────────────────────────────────────

    private fun createTodosContent(): JPanel = JPanel().apply {
        layout = java.awt.GridBagLayout()
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
    }

    private fun rebuildTodosContent() {
        todosPanel.removeAll()
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
        }

        for (todo in todos) {
            val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 0)
            }

            // Status icon
            val icon = when (todo.status) {
                TodoStatus.PENDING -> "○"
                TodoStatus.IN_PROGRESS -> "◉"
                TodoStatus.COMPLETED -> "✓"
            }
            val iconColor = when (todo.status) {
                TodoStatus.PENDING -> STATUS_PENDING
                TodoStatus.IN_PROGRESS -> STATUS_PROGRESS
                TodoStatus.COMPLETED -> STATUS_DONE
            }
            row.add(JLabel(icon).apply {
                foreground = iconColor
                font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            })

            row.add(JLabel(todo.content).apply {
                foreground = if (todo.status == TodoStatus.COMPLETED) TEXT_SECONDARY else TEXT_PRIMARY
                font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            })

            todosPanel.add(row, gbc)
            gbc.gridy++
        }

        // Filler to push content to top
        gbc.weighty = 1.0
        todosPanel.add(JPanel().apply { isOpaque = false }, gbc)
        todosPanel.revalidate()
        todosPanel.repaint()
    }

    // ── Files content ───────────────────────────────────────────────────────

    private fun createFilesContent(): JPanel = JPanel().apply {
        layout = GridBagLayout()
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
    }

    private fun rebuildFilesContent() {
        filesPanel.removeAll()
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
        }

        for ((_, fc) in fileChanges) {
            val row = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 0)
            }

            // Left: status badge + icon + filename
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                isOpaque = false
            }

            // A/M badge
            val badge = JLabel(if (fc.changeType == FileChangeType.ADDED) "A" else "M").apply {
                foreground = if (fc.changeType == FileChangeType.ADDED) ADD_COLOR else STATUS_PROGRESS
                font = font.deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            }
            leftPanel.add(badge)

            // File type icon
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fc.fileName)
            if (fileType.icon != null) {
                leftPanel.add(JLabel(fileType.icon))
            }

            // Filename (clickable)
            val nameLabel = JLabel(fc.fileName).apply {
                foreground = TEXT_PRIMARY
                font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = fc.filePath
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        val vf = LocalFileSystem.getInstance().findFileByPath(fc.filePath)
                        if (vf != null) {
                            FileEditorManager.getInstance(project).openFile(vf, true)
                        }
                    }
                })
            }
            leftPanel.add(nameLabel)

            row.add(leftPanel, BorderLayout.CENTER)

            // Right: +N -N + actions
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(6), 0)).apply {
                isOpaque = false
            }

            if (fc.additions > 0) {
                rightPanel.add(JLabel("+${fc.additions}").apply {
                    foreground = ADD_COLOR
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                })
            }
            if (fc.deletions > 0) {
                rightPanel.add(JLabel("-${fc.deletions}").apply {
                    foreground = DEL_COLOR
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                })
            }

            // Show Diff button
            val diffBtn = JLabel(AllIcons.Actions.Diff).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                toolTipText = "Show Diff"
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        showFileDiff(fc)
                    }
                })
            }
            rightPanel.add(diffBtn)

            row.add(rightPanel, BorderLayout.EAST)

            filesPanel.add(row, gbc)
            gbc.gridy++
        }

        gbc.weighty = 1.0
        filesPanel.add(JPanel().apply { isOpaque = false }, gbc)
        filesPanel.revalidate()
        filesPanel.repaint()
    }

    private fun showFileDiff(fc: FileChangeSummary) {
        if (fc.operations.isEmpty()) return
        // Aggregate: show full file diff (original vs current)
        val file = java.io.File(fc.filePath)
        if (!file.exists()) return

        val currentContent = file.readText()
        // Reconstruct original by reverse-applying all operations
        var originalContent = currentContent
        for (op in fc.operations.reversed()) {
            if (op.newString.isNotEmpty() && originalContent.contains(op.newString)) {
                val idx = originalContent.indexOf(op.newString)
                originalContent = originalContent.substring(0, idx) + op.oldString + originalContent.substring(idx + op.newString.length)
            }
        }

        InteractiveDiffManager.showDiff(project, fc.filePath, originalContent, currentContent)
    }

    // ── Agents content ──────────────────────────────────────────────────────

    private fun createAgentsContent(): JPanel = JPanel().apply {
        layout = GridBagLayout()
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
    }

    private fun rebuildAgentsContent() {
        agentsPanel.removeAll()
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.NORTHWEST
        }

        for ((_, agent) in subagents) {
            val row = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 0)
            }

            // Status icon
            val icon = when (agent.status) {
                SubagentStatus.RUNNING -> "⟳"
                SubagentStatus.COMPLETED -> "✓"
                SubagentStatus.ERROR -> "✗"
            }
            val iconColor = when (agent.status) {
                SubagentStatus.RUNNING -> STATUS_PROGRESS
                SubagentStatus.COMPLETED -> STATUS_DONE
                SubagentStatus.ERROR -> STATUS_ERROR
            }
            row.add(JLabel(icon).apply {
                foreground = iconColor
                font = font.deriveFont(Font.BOLD, JBUI.scale(12).toFloat())
            })

            // Type badge
            row.add(JLabel(agent.type).apply {
                foreground = TAB_ACTIVE
                font = font.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                border = JBUI.Borders.empty(0, 2)
            })

            // Description
            val desc = if (agent.description.length > 60) agent.description.take(57) + "..." else agent.description
            row.add(JLabel(desc).apply {
                foreground = TEXT_PRIMARY
                font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            })

            agentsPanel.add(row, gbc)
            gbc.gridy++
        }

        gbc.weighty = 1.0
        agentsPanel.add(JPanel().apply { isOpaque = false }, gbc)
        agentsPanel.revalidate()
        agentsPanel.repaint()
    }
}
