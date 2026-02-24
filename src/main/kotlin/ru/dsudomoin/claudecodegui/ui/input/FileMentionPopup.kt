package ru.dsudomoin.claudecodegui.ui.input

import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.service.ProjectFileIndexService
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Non-focusable autocomplete popup for @-file mentions.
 * Modeled on SlashCommandPopup — JWindow with focusableWindowState=false.
 */
class FileMentionPopup(
    private val onSelect: (ProjectFileIndexService.FileEntry) -> Unit
) {
    private var window: JWindow? = null
    private var selectedIndex = 0
    private var currentItems: List<ProjectFileIndexService.FileEntry> = emptyList()
    private val itemPanels = mutableListOf<FileItemPanel>()

    fun show(files: List<ProjectFileIndexService.FileEntry>, anchor: JComponent) {
        if (files.isEmpty()) {
            hide()
            return
        }

        if (window?.isVisible == true && currentItems == files) return

        currentItems = files
        selectedIndex = 0
        itemPanels.clear()

        val ownerWindow = SwingUtilities.getWindowAncestor(anchor) ?: return

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = ThemeColors.dropdownBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.dropdownBorder, 1),
                JBUI.Borders.empty(4, 0)
            )
        }

        val visibleFiles = files.take(MAX_VISIBLE_ITEMS)
        visibleFiles.forEachIndexed { index, entry ->
            val item = FileItemPanel(entry, index == selectedIndex) {
                onSelect(entry)
                hide()
            }
            itemPanels.add(item)
            contentPanel.add(item)
        }

        window?.dispose()
        val scrollPane = JBScrollPane(contentPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        val maxH = JBUI.scale(300)
        val naturalH = contentPanel.preferredSize.height + JBUI.scale(8)
        val popupW = JBUI.scale(400).coerceAtLeast(anchor.width)

        val w = JWindow(ownerWindow).apply {
            focusableWindowState = false
            contentPane = scrollPane
            setSize(popupW, minOf(naturalH, maxH))
        }

        val anchorLocation = anchor.locationOnScreen
        w.setLocation(anchorLocation.x, anchorLocation.y - w.height - JBUI.scale(4))
        w.isVisible = true
        window = w
    }

    fun hide() {
        window?.dispose()
        window = null
        itemPanels.clear()
        currentItems = emptyList()
    }

    fun isVisible(): Boolean = window?.isVisible == true

    fun moveUp() {
        if (currentItems.isEmpty()) return
        updateSelection((selectedIndex - 1 + currentItems.size.coerceAtMost(MAX_VISIBLE_ITEMS)) %
                currentItems.size.coerceAtMost(MAX_VISIBLE_ITEMS))
    }

    fun moveDown() {
        if (currentItems.isEmpty()) return
        updateSelection((selectedIndex + 1) % currentItems.size.coerceAtMost(MAX_VISIBLE_ITEMS))
    }

    fun selectCurrent() {
        val visibleCount = currentItems.size.coerceAtMost(MAX_VISIBLE_ITEMS)
        if (visibleCount == 0) return
        val entry = currentItems[selectedIndex.coerceIn(0, visibleCount - 1)]
        onSelect(entry)
        hide()
    }

    private fun updateSelection(newIndex: Int) {
        if (newIndex == selectedIndex) return
        itemPanels.getOrNull(selectedIndex)?.setSelected(false)
        selectedIndex = newIndex
        itemPanels.getOrNull(selectedIndex)?.let { panel ->
            panel.setSelected(true)
            // Scroll the selected item into view within the JBScrollPane
            val rect = java.awt.Rectangle(0, 0, panel.width, panel.height)
            panel.scrollRectToVisible(rect)
        }
    }

    private class FileItemPanel(
        private val entry: ProjectFileIndexService.FileEntry,
        initiallySelected: Boolean,
        private val onClick: () -> Unit
    ) : JPanel(BorderLayout()) {
        private var hover = false
        private var selected = initiallySelected

        init {
            isOpaque = false
            border = JBUI.Borders.empty(6, 12, 6, 12)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                override fun mouseClicked(e: MouseEvent) { onClick() }
            })
        }

        fun setSelected(value: Boolean) {
            selected = value
            repaint()
        }

        override fun getPreferredSize(): Dimension {
            val nameFont = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
            val pathFont = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            val fmName = getFontMetrics(nameFont)
            val fmPath = getFontMetrics(pathFont)
            val iconSpace = JBUI.scale(20) // space for file icon
            val pathText = if (entry.source == ProjectFileIndexService.FileSource.LIBRARY) {
                entry.libraryName ?: entry.relativePath
            } else {
                entry.relativePath
            }
            val w = maxOf(
                fmName.stringWidth(entry.fileName),
                fmPath.stringWidth(pathText)
            ) + insets.left + insets.right + iconSpace + JBUI.scale(8)
            val h = fmName.height + fmPath.height + JBUI.scale(2) + insets.top + insets.bottom
            return Dimension(w, h)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            // Background
            g2.color = ThemeColors.dropdownBg
            g2.fillRect(0, 0, width, height)

            if (selected) {
                g2.color = ThemeColors.chipSelectedBg
                g2.fillRect(0, 0, width, height)
            } else if (hover) {
                g2.color = ThemeColors.hoverOverlay
                g2.fillRect(0, 0, width, height)
            }

            // File icon
            val icon = entry.virtualFile.fileType.icon
            val iconX = insets.left
            val iconSize = JBUI.scale(16)
            val iconY = (height - iconSize) / 2
            icon?.paintIcon(this, g2, iconX, iconY)

            val textX = insets.left + iconSize + JBUI.scale(6)

            val nameFont = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
            val pathFont = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())

            // Filename
            g2.font = nameFont
            g2.color = ThemeColors.textPrimary
            val fmName = g2.fontMetrics
            val nameY = insets.top + fmName.ascent
            g2.drawString(entry.fileName, textX, nameY)

            // Relative path or library name
            g2.font = pathFont
            g2.color = ThemeColors.textSecondary
            val fmPath = g2.fontMetrics
            val pathY = nameY + fmName.descent + JBUI.scale(2) + fmPath.ascent
            val pathText = if (entry.source == ProjectFileIndexService.FileSource.LIBRARY) {
                entry.libraryName ?: entry.relativePath
            } else {
                entry.relativePath
            }
            g2.drawString(pathText, textX, pathY)
        }
    }

    companion object {
        private const val MAX_VISIBLE_ITEMS = 10
    }
}
