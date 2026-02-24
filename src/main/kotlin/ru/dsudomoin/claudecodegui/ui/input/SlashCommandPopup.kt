package ru.dsudomoin.claudecodegui.ui.input

import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.command.SlashCommand
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Non-focusable autocomplete popup for slash commands.
 * Uses JWindow with focusableWindowState=false so the text area keeps focus.
 */
class SlashCommandPopup(
    private val onSelect: (SlashCommand) -> Unit
) {
    private var window: JWindow? = null
    private var selectedIndex = 0
    private var currentItems: List<SlashCommand> = emptyList()
    private val itemPanels = mutableListOf<CommandItemPanel>()

    fun show(commands: List<SlashCommand>, anchor: JComponent) {
        if (commands.isEmpty()) {
            hide()
            return
        }

        // If same commands and already visible, just return
        if (window?.isVisible == true && currentItems == commands) return

        currentItems = commands
        selectedIndex = 0
        itemPanels.clear()

        // Find the parent window for JWindow
        val ownerWindow = SwingUtilities.getWindowAncestor(anchor) ?: return

        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = ThemeColors.dropdownBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.dropdownBorder, 1),
                JBUI.Borders.empty(4, 0)
            )
        }

        commands.forEachIndexed { index, cmd ->
            val item = CommandItemPanel(cmd, index == selectedIndex) {
                onSelect(cmd)
                hide()
            }
            itemPanels.add(item)
            contentPanel.add(item)
        }

        window?.dispose()
        val w = JWindow(ownerWindow).apply {
            focusableWindowState = false
            contentPane = contentPanel
            pack()
        }

        // Position above the anchor
        val anchorLocation = anchor.locationOnScreen
        val popupHeight = w.height
        w.setLocation(anchorLocation.x, anchorLocation.y - popupHeight - JBUI.scale(4))
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
        updateSelection((selectedIndex - 1 + currentItems.size) % currentItems.size)
    }

    fun moveDown() {
        if (currentItems.isEmpty()) return
        updateSelection((selectedIndex + 1) % currentItems.size)
    }

    fun selectCurrent() {
        if (currentItems.isEmpty()) return
        val cmd = currentItems[selectedIndex]
        onSelect(cmd)
        hide()
    }

    private fun updateSelection(newIndex: Int) {
        if (newIndex == selectedIndex) return
        itemPanels.getOrNull(selectedIndex)?.setSelected(false)
        selectedIndex = newIndex
        itemPanels.getOrNull(selectedIndex)?.setSelected(true)
    }

    private class CommandItemPanel(
        private val cmd: SlashCommand,
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
            val descFont = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            val fmName = getFontMetrics(nameFont)
            val fmDesc = getFontMetrics(descFont)
            val w = maxOf(fmName.stringWidth(cmd.name), fmDesc.stringWidth(cmd.description)) +
                    insets.left + insets.right + JBUI.scale(8)
            val h = fmName.height + fmDesc.height + JBUI.scale(2) + insets.top + insets.bottom
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

            val nameFont = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
            val descFont = font.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())

            // Command name
            g2.font = nameFont
            g2.color = ThemeColors.textPrimary
            val fmName = g2.fontMetrics
            val nameY = insets.top + fmName.ascent
            g2.drawString(cmd.name, insets.left, nameY)

            // Description
            g2.font = descFont
            g2.color = ThemeColors.textSecondary
            val fmDesc = g2.fontMetrics
            val descY = nameY + fmName.descent + JBUI.scale(2) + fmDesc.ascent
            g2.drawString(cmd.description, insets.left, descY)
        }
    }
}
