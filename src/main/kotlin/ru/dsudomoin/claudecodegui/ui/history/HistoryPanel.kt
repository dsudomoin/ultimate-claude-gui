package ru.dsudomoin.claudecodegui.ui.history

import ru.dsudomoin.claudecodegui.MyMessageBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.core.session.SessionInfo
import ru.dsudomoin.claudecodegui.core.session.SessionManager
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Panel showing session history — matches reference design:
 *   ← Назад
 *   stats | refresh | search
 *   session list with icon, title, relative time, message count, session id
 */
class HistoryPanel(
    private val project: Project,
    private val onLoadSession: (String) -> Unit,
    private val onBack: () -> Unit
) : JPanel(BorderLayout()) {

    companion object {
        private val BG get() = ThemeColors.surfaceSecondary
        private val CARD_BG get() = ThemeColors.cardBg
        private val CARD_HOVER get() = ThemeColors.cardHover
        private val BORDER_COLOR get() = ThemeColors.borderNormal
        private val TEXT_PRIMARY get() = ThemeColors.textPrimary
        private val TEXT_SECONDARY get() = ThemeColors.textSecondary
        private val ACCENT get() = ThemeColors.historyAccent
        private val SEARCH_BG get() = ThemeColors.surfacePrimary
        private val SEARCH_BORDER get() = ThemeColors.dropdownBorder
    }

    private val sessionManager = SessionManager.getInstance(project)
    private var allSessions = listOf<SessionInfo>()
    private val listModel = DefaultListModel<SessionInfo>()
    private val sessionList = JBList(listModel)

    init {
        isOpaque = true
        background = BG
        border = JBUI.Borders.empty()

        // ── Header: ← Назад ──
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 12, 6, 12)
            val backBtn = JButton(MyMessageBundle.message("history.back")).apply {
                icon = AllIcons.Actions.Back
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusPainted = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                font = font.deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())
                foreground = TEXT_PRIMARY
                addActionListener { onBack() }
            }
            add(backBtn)
        }

        // ── Stats bar + refresh + search ──
        val statsLabel = JBLabel("").apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            foreground = TEXT_SECONDARY
        }

        val refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = MyMessageBundle.message("history.refresh")
            addActionListener { refresh() }
        }

        val searchField = JBTextField().apply {
            emptyText.text = MyMessageBundle.message("history.search")
            preferredSize = Dimension(JBUI.scale(180), JBUI.scale(28))
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(SEARCH_BORDER, 1),
                JBUI.Borders.empty(2, 6)
            )
            background = SEARCH_BG
            document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = filterSessions(text)
                override fun removeUpdate(e: DocumentEvent) = filterSessions(text)
                override fun changedUpdate(e: DocumentEvent) = filterSessions(text)
            })
        }

        val toolbar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 8, 12)
            val left = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                isOpaque = false
                add(statsLabel)
                add(refreshBtn)
            }
            val right = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                isOpaque = false
                add(searchField)
            }
            add(left, BorderLayout.WEST)
            add(right, BorderLayout.EAST)
        }

        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(header, BorderLayout.NORTH)
            add(toolbar, BorderLayout.SOUTH)
        }
        add(topPanel, BorderLayout.NORTH)

        // ── Session list ──
        sessionList.apply {
            cellRenderer = SessionCardRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            border = JBUI.Borders.empty(0, 12, 12, 12)
            isOpaque = false
            fixedCellHeight = JBUI.scale(62)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val idx = sessionList.locationToIndex(e.point)
                        if (idx >= 0) {
                            onLoadSession(listModel.getElementAt(idx).sessionId)
                        }
                    }
                }
            })
        }

        val scrollPane = JBScrollPane(sessionList).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }
        add(scrollPane, BorderLayout.CENTER)

        refresh()

        // Update stats after refresh
        updateStats(statsLabel)
    }

    fun refresh() {
        allSessions = sessionManager.listSessions()
        filterSessions("")
    }

    private fun filterSessions(query: String) {
        listModel.clear()
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allSessions
        else allSessions.filter { it.title.lowercase().contains(q) || it.sessionId.contains(q) }
        filtered.forEach { listModel.addElement(it) }

        // Update stats label if accessible
        val toolbar = (getComponent(0) as? JPanel)?.getComponent(1) as? JPanel
        val leftPanel = toolbar?.getComponent(0) as? JPanel
        val statsLabel = leftPanel?.getComponent(0) as? JBLabel
        if (statsLabel != null) updateStats(statsLabel)
    }

    private fun updateStats(label: JBLabel) {
        val totalChats = allSessions.size
        val totalMessages = allSessions.sumOf { it.messageCount }
        label.text = MyMessageBundle.message("history.stats", totalChats, totalMessages)
    }

    /** Delete selected session. */
    fun deleteSelected() {
        val selected = sessionList.selectedValue ?: return
        val confirm = JOptionPane.showConfirmDialog(
            this,
            "Delete session \"${selected.title}\"?",
            MyMessageBundle.message("history.deleteTitle"),
            JOptionPane.YES_NO_OPTION
        )
        if (confirm == JOptionPane.YES_OPTION) {
            sessionManager.delete(selected.sessionId)
            refresh()
        }
    }

    // ── Relative time formatting ──

    private fun relativeTime(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val seconds = diff / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            seconds < 60 -> MyMessageBundle.message("history.secondsAgo", seconds)
            minutes < 60 -> MyMessageBundle.message("history.minutesAgo", minutes)
            hours < 24 -> MyMessageBundle.message("history.hoursAgo", hours)
            days < 30 -> MyMessageBundle.message("history.daysAgo", days)
            else -> {
                val sdf = java.text.SimpleDateFormat("dd MMM yyyy")
                sdf.format(java.util.Date(timestamp))
            }
        }
    }

    // ── Cell renderer ──

    private inner class SessionCardRenderer : ListCellRenderer<SessionInfo> {

        override fun getListCellRendererComponent(
            list: JList<out SessionInfo>,
            value: SessionInfo,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            return object : JPanel(BorderLayout()) {
                init {
                    isOpaque = false
                    border = JBUI.Borders.empty(2, 0)
                }

                override fun paintComponent(g: Graphics) {
                    val g2 = g as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val arc = JBUI.scale(8).toFloat()
                    val shape = RoundRectangle2D.Float(
                        0f, 0f, width.toFloat(), height.toFloat(), arc, arc
                    )
                    g2.color = if (isSelected) CARD_HOVER else CARD_BG
                    g2.fill(shape)
                    g2.color = BORDER_COLOR
                    g2.stroke = BasicStroke(0.5f)
                    g2.draw(shape)
                }
            }.apply {
                val innerPanel = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.empty(8, 10, 8, 10)
                }

                // Left icon
                val iconLabel = JLabel("\u2733").apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(16).toFloat())
                    foreground = ACCENT
                    border = JBUI.Borders.emptyRight(8)
                    verticalAlignment = SwingConstants.TOP
                }
                innerPanel.add(iconLabel, BorderLayout.WEST)

                // Center: title + time on top row, messages + session id on bottom
                val centerPanel = JPanel(GridBagLayout()).apply { isOpaque = false }
                val gbc = GridBagConstraints()

                // Title (bold, truncated)
                val displayTitle = if (value.title.length > 24) "${value.title.take(21)}\u2026" else value.title
                gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.WEST; gbc.fill = GridBagConstraints.HORIZONTAL
                centerPanel.add(JLabel(displayTitle).apply {
                    font = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
                    foreground = TEXT_PRIMARY
                }, gbc)

                // Relative time (right-aligned)
                gbc.gridx = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST; gbc.fill = GridBagConstraints.NONE
                gbc.insets = Insets(0, JBUI.scale(8), 0, 0)
                centerPanel.add(JLabel(relativeTime(value.lastTimestamp)).apply {
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                    foreground = TEXT_SECONDARY
                }, gbc)

                // Message count (bottom left)
                gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 1.0; gbc.anchor = GridBagConstraints.WEST; gbc.fill = GridBagConstraints.HORIZONTAL
                gbc.insets = Insets(JBUI.scale(3), 0, 0, 0)
                centerPanel.add(JLabel(MyMessageBundle.message("history.messages", value.messageCount)).apply {
                    font = font.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                    foreground = TEXT_SECONDARY
                }, gbc)

                // Session ID hash (bottom right)
                gbc.gridx = 1; gbc.weightx = 0.0; gbc.anchor = GridBagConstraints.EAST; gbc.fill = GridBagConstraints.NONE
                gbc.insets = Insets(JBUI.scale(3), JBUI.scale(8), 0, 0)
                centerPanel.add(JLabel(value.sessionId.take(8)).apply {
                    font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(10))
                    foreground = TEXT_SECONDARY
                }, gbc)

                innerPanel.add(centerPanel, BorderLayout.CENTER)
                add(innerPanel, BorderLayout.CENTER)
            }
        }
    }
}
