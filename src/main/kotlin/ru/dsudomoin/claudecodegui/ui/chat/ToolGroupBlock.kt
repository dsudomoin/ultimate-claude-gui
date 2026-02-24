package ru.dsudomoin.claudecodegui.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.chat.ToolUseBlock.ToolCategory
import ru.dsudomoin.claudecodegui.ui.common.SwingAnimations
import ru.dsudomoin.claudecodegui.ui.diff.InteractiveDiffManager
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Collapsible group block that combines consecutive tool uses of the same category.
 *
 * Collapsed:
 * ┌──────────────────────────────────────────┐
 * │ ▶ [icon] Read (5)                    ●   │
 * └──────────────────────────────────────────┘
 *
 * Expanded:
 * ┌──────────────────────────────────────────┐
 * │ ▼ [icon] Read (5)                    ●   │
 * ├──────────────────────────────────────────┤
 * │  📄 file1.kt               L10-20   ●   │
 * │  📄 file2.kt                         ●   │
 * │  📄 file3.kt               L1-50    ●   │
 * └──────────────────────────────────────────┘
 */
class ToolGroupBlock(
    val category: ToolCategory,
    private val project: Project? = null
) : JPanel(BorderLayout()) {

    data class GroupItem(
        val id: String,
        val toolName: String,
        val summary: String,
        val input: JsonObject,
        var status: ToolUseBlock.Status = ToolUseBlock.Status.PENDING,
        var resultContent: String? = null
    )

    val items = mutableListOf<GroupItem>()
    private val itemRows = mutableMapOf<String, ItemRowPanel>()

    private var expanded = false
    private var hover = false
    private var breathingAlpha = 1.0f
    private var breathingDirection = -1

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

    // ── Colors ───────────────────────────────────────────────────────────────
    companion object {
        private const val ARC = 16
        private const val HEADER_HEIGHT = 36
        private const val ITEM_HEIGHT = 28
        private const val MAX_VISIBLE_ITEMS = 3

        private val BG get() = ThemeColors.surfacePrimary
        private val BORDER_COLOR get() = ThemeColors.borderNormal
        private val HOVER_BG get() = ThemeColors.surfaceHover
        private val TITLE_COLOR get() = ThemeColors.textPrimary
        private val SUMMARY_COLOR get() = ThemeColors.textSecondary
        private val STATUS_SUCCESS get() = ThemeColors.statusSuccess
        private val STATUS_WARNING get() = ThemeColors.statusWarning
        private val STATUS_ERROR get() = ThemeColors.statusError
        private val DIFF_ADD_MARKER get() = ThemeColors.diffAddFg
        private val DIFF_DEL_MARKER get() = ThemeColors.diffDelFg
        private val LINK_COLOR get() = ThemeColors.accent
        private val ICON_HOVER_BG get() = ThemeColors.iconHoverBg

        private fun getStr(obj: JsonObject, key: String): String? {
            val el = obj[key] ?: return null
            return (el as? JsonPrimitive)?.content
        }
    }

    // ── Header ───────────────────────────────────────────────────────────────

    private val headerPanel = object : JPanel() {
        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(HEADER_HEIGHT))
            minimumSize = Dimension(0, JBUI.scale(HEADER_HEIGHT))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(HEADER_HEIGHT))
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

            val padding = JBUI.scale(12)
            var x = padding

            // Chevron
            val chevron = if (expanded) "\u25BC" else "\u25B6"
            val chevronFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
            g2.font = chevronFont
            g2.color = SUMMARY_COLOR
            val cfm = g2.fontMetrics
            g2.drawString(chevron, x, (height + cfm.ascent - cfm.descent) / 2)
            x += cfm.stringWidth(chevron) + JBUI.scale(6)

            // Category icon
            val icon = getCategoryIcon()
            val iconY = (height - icon.iconHeight) / 2
            icon.paintIcon(this, g2, x, iconY)
            x += icon.iconWidth + JBUI.scale(6)

            // Title with count
            val titleFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())
            g2.font = titleFont
            g2.color = TITLE_COLOR
            val fm = g2.fontMetrics
            val textY = (height + fm.ascent - fm.descent) / 2
            val title = getCategoryTitle()
            g2.drawString(title, x, textY)
            x += fm.stringWidth(title) + JBUI.scale(8)

            // Aggregate diff stats for edit groups
            if (category == ToolCategory.EDIT) {
                val (totalAdd, totalDel) = computeAggregateDiff()
                if (totalAdd > 0 || totalDel > 0) {
                    val badgeFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                    g2.font = badgeFont
                    val bfm = g2.fontMetrics

                    if (totalAdd > 0) {
                        g2.color = DIFF_ADD_MARKER
                        val addText = "+$totalAdd"
                        g2.drawString(addText, x, textY)
                        x += bfm.stringWidth(addText) + JBUI.scale(4)
                    }
                    if (totalDel > 0) {
                        g2.color = DIFF_DEL_MARKER
                        val delText = "-$totalDel"
                        g2.drawString(delText, x, textY)
                        x += bfm.stringWidth(delText) + JBUI.scale(6)
                    }
                }
            }

            // Status dot (right side)
            val dotSize = JBUI.scale(8).toFloat()
            val dotX = width - padding - dotSize
            val dotY = (height - dotSize) / 2
            val aggStatus = aggregateStatus()
            val dotColor = when (aggStatus) {
                ToolUseBlock.Status.PENDING -> STATUS_WARNING
                ToolUseBlock.Status.COMPLETED -> STATUS_SUCCESS
                ToolUseBlock.Status.ERROR -> STATUS_ERROR
            }
            if (aggStatus == ToolUseBlock.Status.PENDING) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, breathingAlpha)
            }
            g2.color = dotColor
            g2.fill(Ellipse2D.Float(dotX, dotY, dotSize, dotSize))
            g2.composite = AlphaComposite.SrcOver
        }
    }

    // ── Items container ──────────────────────────────────────────────────────

    private val itemsListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val scrollPane = JBScrollPane(itemsListPanel).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    private val itemsContainer = object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            // Separator line at top
            g.color = BORDER_COLOR
            g.fillRect(0, 0, width, 1)
        }
    }.apply {
        isOpaque = false
        isVisible = false
        add(scrollPane, BorderLayout.CENTER)
    }

    private val bodyPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(headerPanel)
        add(itemsContainer)
    }

    init {
        isOpaque = false
        add(bodyPanel, BorderLayout.CENTER)
        breathingTimer.start()
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun addItem(id: String, toolName: String, summary: String, input: JsonObject) {
        val item = GroupItem(id, toolName, summary, input)
        items.add(item)

        val row = ItemRowPanel(item)
        itemRows[id] = row
        itemsListPanel.add(row)

        updateItemsContainerHeight()
        headerPanel.repaint()

        // Auto-scroll to bottom
        SwingUtilities.invokeLater {
            val scrollBar = scrollPane.verticalScrollBar
            scrollBar.value = scrollBar.maximum
        }
    }

    fun completeItem(id: String, content: String?) {
        val item = items.find { it.id == id } ?: return
        item.status = ToolUseBlock.Status.COMPLETED
        item.resultContent = content
        itemRows[id]?.repaint()
        updateBreathingState()
        headerPanel.repaint()
    }

    fun errorItem(id: String, content: String?) {
        val item = items.find { it.id == id } ?: return
        item.status = ToolUseBlock.Status.ERROR
        item.resultContent = content
        itemRows[id]?.repaint()
        updateBreathingState()
        headerPanel.repaint()
    }

    /** Forward streaming output line to the last item's status display. */
    fun appendStreamingLine(id: String, text: String, isError: Boolean = false) {
        // For grouped items we don't have individual streaming panels;
        // show the latest line in the group header's summary area.
        val item = items.find { it.id == id } ?: return
        item.resultContent = text.take(100)
        itemRows[id]?.repaint()
    }

    fun containsItem(id: String): Boolean = items.any { it.id == id }

    val itemCount: Int get() = items.size

    // ── Private helpers ──────────────────────────────────────────────────────

    private var animating = false

    private fun toggleExpanded() {
        if (animating) return
        expanded = !expanded
        headerPanel.repaint() // update chevron immediately

        if (expanded) {
            val targetHeight = scrollPane.preferredSize.height + 1 // +1 for separator line
            itemsContainer.preferredSize = Dimension(itemsContainer.width.coerceAtLeast(1), 0)
            itemsContainer.isVisible = true
            revalidate()
            animating = true
            SwingAnimations.animateHeight(itemsContainer, targetHeight) {
                itemsContainer.preferredSize = null
                animating = false
                revalidate()
                repaint()
                parent?.revalidate()
                parent?.repaint()
            }
        } else {
            animating = true
            SwingAnimations.animateHeight(itemsContainer, 0) {
                itemsContainer.isVisible = false
                itemsContainer.preferredSize = null
                animating = false
                revalidate()
                repaint()
                parent?.revalidate()
                parent?.repaint()
            }
        }
    }

    private fun updateItemsContainerHeight() {
        val visibleCount = items.size.coerceAtMost(MAX_VISIBLE_ITEMS)
        val h = JBUI.scale(ITEM_HEIGHT) * visibleCount + JBUI.scale(4) // padding
        scrollPane.preferredSize = Dimension(0, h)
        scrollPane.maximumSize = Dimension(Int.MAX_VALUE, h)
        if (itemsContainer.isVisible) {
            itemsContainer.revalidate()
            itemsContainer.repaint()
        }
    }

    private fun aggregateStatus(): ToolUseBlock.Status {
        if (items.any { it.status == ToolUseBlock.Status.ERROR }) return ToolUseBlock.Status.ERROR
        if (items.any { it.status == ToolUseBlock.Status.PENDING }) return ToolUseBlock.Status.PENDING
        return ToolUseBlock.Status.COMPLETED
    }

    private fun updateBreathingState() {
        val hasPending = items.any { it.status == ToolUseBlock.Status.PENDING }
        if (!hasPending && breathingTimer.isRunning) {
            breathingTimer.stop()
            breathingAlpha = 1.0f
        } else if (hasPending && !breathingTimer.isRunning) {
            breathingTimer.start()
        }
    }

    private fun getCategoryIcon(): Icon = when (category) {
        ToolCategory.READ -> AllIcons.Actions.Preview
        ToolCategory.EDIT -> AllIcons.Actions.Edit
        ToolCategory.BASH -> AllIcons.Debugger.Console
        ToolCategory.SEARCH -> AllIcons.Actions.Find
        ToolCategory.OTHER -> AllIcons.Actions.Execute
    }

    private fun getCategoryTitle(): String {
        val key = when (category) {
            ToolCategory.READ -> "tool.group.read"
            ToolCategory.EDIT -> "tool.group.edit"
            ToolCategory.BASH -> "tool.group.bash"
            ToolCategory.SEARCH -> "tool.group.search"
            ToolCategory.OTHER -> "tool.group.read"
        }
        return UcuBundle.message(key, items.size)
    }

    private fun computeAggregateDiff(): Pair<Int, Int> {
        var totalAdd = 0
        var totalDel = 0
        for (item in items) {
            val oldStr = getStr(item.input, "old_string") ?: getStr(item.input, "oldString") ?: continue
            val newStr = getStr(item.input, "new_string") ?: getStr(item.input, "newString") ?: continue
            val oldLines = oldStr.split("\n")
            val newLines = newStr.split("\n")
            // Simple line-count diff approximation
            totalAdd += (newLines.size - oldLines.size).coerceAtLeast(0)
            totalDel += (oldLines.size - newLines.size).coerceAtLeast(0)
            // Count changed lines
            if (oldLines.size == newLines.size) {
                val changed = oldLines.zip(newLines).count { (a, b) -> a != b }
                totalAdd += changed
                totalDel += changed
            }
        }
        return totalAdd to totalDel
    }

    // ── Painting ─────────────────────────────────────────────────────────────

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(ARC).toFloat()
        val shape = RoundRectangle2D.Float(0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), arc, arc)
        g2.color = if (hover && !expanded) HOVER_BG else BG
        g2.fill(shape)
    }

    override fun paintChildren(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(ARC).toFloat()
        g2.clip(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
        super.paintChildren(g2)
        g2.dispose()
    }

    override fun paintBorder(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val arc = JBUI.scale(ARC).toFloat()
        g2.color = BORDER_COLOR
        g2.stroke = BasicStroke(1f)
        g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, (width - 1).toFloat(), (height - 1).toFloat(), arc, arc))
    }

    fun dispose() {
        breathingTimer.stop()
    }

    // ── Item row ─────────────────────────────────────────────────────────────

    /**
     * A lightweight row inside the group, showing: icon + name/summary + status dot.
     * For edit items, also shows diff stats and action buttons.
     */
    inner class ItemRowPanel(private val item: GroupItem) : JPanel() {
        private var rowHover = false
        private var hoveredIcon: String? = null
        private var diffBtnBounds: Rectangle? = null
        private var rejectBtnBounds: Rectangle? = null

        private val isEditItem = item.toolName.lowercase() in setOf("edit", "edit_file", "replace_string")
        private val isWriteItem = item.toolName.lowercase() in setOf("write", "write_to_file", "create_file", "save-file")
        private val hasActions = (isEditItem || isWriteItem) && project != null
        private val itemFilePath: String? = getStr(item.input, "file_path") ?: getStr(item.input, "path") ?: getStr(item.input, "target_file")

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(ITEM_HEIGHT))
            minimumSize = Dimension(0, JBUI.scale(ITEM_HEIGHT))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ITEM_HEIGHT))

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { rowHover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { rowHover = false; hoveredIcon = null; repaint() }
                override fun mouseClicked(e: MouseEvent) {
                    if (hasActions && item.status == ToolUseBlock.Status.COMPLETED) {
                        diffBtnBounds?.let { if (it.contains(e.point)) { onShowDiff(item); return } }
                        rejectBtnBounds?.let { if (it.contains(e.point)) { onReject(item); return } }
                    }
                    // Click on file path -> open file
                    if (itemFilePath != null) {
                        openFile(itemFilePath)
                    }
                }
            })
            addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    if (hasActions && item.status == ToolUseBlock.Status.COMPLETED) {
                        val newHover = when {
                            diffBtnBounds?.contains(e.point) == true -> "diff"
                            rejectBtnBounds?.contains(e.point) == true -> "reject"
                            else -> null
                        }
                        if (newHover != hoveredIcon) {
                            hoveredIcon = newHover
                            repaint()
                        }
                    }
                    cursor = when {
                        hoveredIcon != null -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        itemFilePath != null -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                        else -> Cursor.getDefaultCursor()
                    }
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // Hover background
            if (rowHover) {
                g2.color = HOVER_BG
                g2.fillRoundRect(JBUI.scale(4), 0, width - JBUI.scale(8), height, JBUI.scale(6), JBUI.scale(6))
            }

            val padding = JBUI.scale(12)
            var x = padding

            // Tool-specific icon
            val icon = ToolUseBlock.getToolDisplayName(item.toolName).let {
                getCategoryIcon()  // Use category icon for consistency
            }
            val iconY = (height - icon.iconHeight) / 2
            icon.paintIcon(this, g2, x, iconY)
            x += icon.iconWidth + JBUI.scale(6)

            // Summary text (file path or command description)
            val titleFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            g2.font = titleFont
            g2.color = if (itemFilePath != null) LINK_COLOR else TITLE_COLOR
            val fm = g2.fontMetrics
            val textY = (height + fm.ascent - fm.descent) / 2

            // Status dot (right side)
            val dotSize = JBUI.scale(6).toFloat()
            val rightPad = padding
            val dotX = width - rightPad - dotSize
            var rightEdge = dotX.toInt() - JBUI.scale(6)

            // Action buttons for edit tools
            if (hasActions && item.status == ToolUseBlock.Status.COMPLETED) {
                val btnSize = JBUI.scale(18)
                val iconSz = JBUI.scale(14)
                val iconOff = (btnSize - iconSz) / 2
                val gap = JBUI.scale(2)

                // Reject button
                val rejectX = rightEdge - btnSize
                val btnY = (height - btnSize) / 2
                rejectBtnBounds = Rectangle(rejectX, btnY, btnSize, btnSize)
                if (hoveredIcon == "reject") {
                    g2.color = ICON_HOVER_BG
                    g2.fillRoundRect(rejectX, btnY, btnSize, btnSize, JBUI.scale(4), JBUI.scale(4))
                }
                AllIcons.Actions.Rollback.paintIcon(this, g2, rejectX + iconOff, btnY + iconOff)

                // Diff button
                val diffX = rejectX - gap - btnSize
                diffBtnBounds = Rectangle(diffX, btnY, btnSize, btnSize)
                if (hoveredIcon == "diff") {
                    g2.color = ICON_HOVER_BG
                    g2.fillRoundRect(diffX, btnY, btnSize, btnSize, JBUI.scale(4), JBUI.scale(4))
                }
                AllIcons.Actions.Diff.paintIcon(this, g2, diffX + iconOff, btnY + iconOff)
                rightEdge = diffX - JBUI.scale(4)
            } else {
                diffBtnBounds = null
                rejectBtnBounds = null
            }

            // Diff stats for edit items
            if (isEditItem) {
                val oldStr = getStr(item.input, "old_string") ?: getStr(item.input, "oldString")
                val newStr = getStr(item.input, "new_string") ?: getStr(item.input, "newString")
                if (oldStr != null && newStr != null) {
                    val oldLen = oldStr.split("\n").size
                    val newLen = newStr.split("\n").size
                    val addCount = (newLen - oldLen).coerceAtLeast(0)
                    val delCount = (oldLen - newLen).coerceAtLeast(0)
                    if (addCount > 0 || delCount > 0) {
                        val badgeFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                        g2.font = badgeFont
                        val bfm = g2.fontMetrics
                        if (delCount > 0) {
                            val t = "-$delCount"
                            rightEdge -= bfm.stringWidth(t) + JBUI.scale(4)
                            g2.color = DIFF_DEL_MARKER
                            g2.drawString(t, rightEdge, textY)
                        }
                        if (addCount > 0) {
                            val t = "+$addCount"
                            rightEdge -= bfm.stringWidth(t) + JBUI.scale(4)
                            g2.color = DIFF_ADD_MARKER
                            g2.drawString(t, rightEdge, textY)
                        }
                        g2.font = titleFont
                    }
                }
            }

            // Summary text (truncated)
            val summaryText = item.summary.ifEmpty { ToolUseBlock.getToolDisplayName(item.toolName) }
            val maxTextW = rightEdge - x - JBUI.scale(4)
            g2.color = if (itemFilePath != null) LINK_COLOR else TITLE_COLOR
            if (maxTextW > JBUI.scale(20)) {
                val truncated = truncateText(summaryText, fm, maxTextW)
                g2.drawString(truncated, x, textY)
            }

            // Status dot
            val dotY = (height - dotSize) / 2
            val dotColor = when (item.status) {
                ToolUseBlock.Status.PENDING -> STATUS_WARNING
                ToolUseBlock.Status.COMPLETED -> STATUS_SUCCESS
                ToolUseBlock.Status.ERROR -> STATUS_ERROR
            }
            g2.composite = AlphaComposite.SrcOver
            g2.color = dotColor
            g2.fill(Ellipse2D.Float(dotX, dotY, dotSize, dotSize))
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun openFile(path: String) {
        val proj = project ?: return
        val file = java.io.File(path)
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByIoFile(file) ?: return
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(proj).openFile(vf, true)
    }

    private fun onShowDiff(item: GroupItem) {
        val proj = project ?: return
        val filePath = getStr(item.input, "file_path") ?: getStr(item.input, "path") ?: getStr(item.input, "target_file") ?: return
        val lower = item.toolName.lowercase()
        when {
            lower in setOf("edit", "edit_file", "replace_string") -> {
                val oldStr = getStr(item.input, "old_string") ?: getStr(item.input, "oldString") ?: ""
                val newStr = getStr(item.input, "new_string") ?: getStr(item.input, "newString") ?: ""
                InteractiveDiffManager.showEditDiff(proj, filePath, oldStr, newStr)
            }
            lower in setOf("write", "write_to_file", "create_file", "save-file") -> {
                val content = getStr(item.input, "content") ?: ""
                InteractiveDiffManager.showWriteDiff(proj, filePath, content)
            }
        }
    }

    private fun onReject(item: GroupItem) {
        val proj = project ?: return
        val filePath = getStr(item.input, "file_path") ?: getStr(item.input, "path") ?: getStr(item.input, "target_file") ?: return
        val lower = item.toolName.lowercase()
        val success = when {
            lower in setOf("edit", "edit_file", "replace_string") -> {
                val oldStr = getStr(item.input, "old_string") ?: getStr(item.input, "oldString") ?: ""
                val newStr = getStr(item.input, "new_string") ?: getStr(item.input, "newString") ?: ""
                InteractiveDiffManager.revertEdit(filePath, oldStr, newStr)
            }
            lower in setOf("write", "write_to_file", "create_file", "save-file") -> {
                InteractiveDiffManager.revertWrite(proj, filePath)
            }
            else -> false
        }
        if (success) {
            item.status = ToolUseBlock.Status.ERROR
            itemRows[item.id]?.repaint()
            headerPanel.repaint()
        }
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
}
