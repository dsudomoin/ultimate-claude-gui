package ru.dsudomoin.claudecodegui.ui.dialog

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.MyMessageBundle
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Replaces the input area when Claude sends ExitPlanMode.
 * Full-width action bar with description text and prominent Approve / Reject buttons.
 */
class PlanActionPanel(
    private val onApprove: () -> Unit,
    private val onDeny: () -> Unit
) : JPanel(BorderLayout()) {

    companion object {
        private val BAR_BG get() = ThemeColors.planBarBg
        private val BAR_TOP_BORDER get() = ThemeColors.planBarBorder
        private val APPROVE_BG get() = ThemeColors.approveBg
        private val APPROVE_HOVER get() = ThemeColors.approveHover
        private val DENY_BG get() = ThemeColors.denyBg
        private val DENY_HOVER get() = ThemeColors.denyHover
        private val DENY_BORDER get() = ThemeColors.denyBorder
        private val TEXT_PRIMARY get() = ThemeColors.textPrimary
        private val TEXT_SECONDARY get() = ThemeColors.textSecondary
        private val ICON_COLOR get() = ThemeColors.approveBg
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty()

        // ── Top border line ──
        val topBorder = object : JPanel() {
            init {
                isOpaque = false
                preferredSize = Dimension(0, 1)
            }
            override fun paintComponent(g: Graphics) {
                g.color = BAR_TOP_BORDER
                g.fillRect(0, 0, width, height)
            }
        }

        // ── Content panel (description + buttons) ──
        val contentPanel = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                g.color = BAR_BG
                g.fillRect(0, 0, width, height)
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(12, 16, 12, 16)
        }

        // Left side: icon + description text
        val leftPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
        }

        val iconLabel = JLabel(AllIcons.Actions.Execute).apply {
            foreground = ICON_COLOR
        }

        val descLabel = JLabel(MyMessageBundle.message("permission.planDescription")).apply {
            font = font.deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())
            foreground = TEXT_SECONDARY
        }

        leftPanel.add(iconLabel, BorderLayout.WEST)
        leftPanel.add(descLabel, BorderLayout.CENTER)

        // Right side: buttons
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
        }

        val denyBtn = createButton(
            text = MyMessageBundle.message("permission.planDeny"),
            accent = false,
            onClick = onDeny
        )

        val approveBtn = createButton(
            text = MyMessageBundle.message("permission.planApprove"),
            accent = true,
            onClick = onApprove
        )

        buttonsPanel.add(denyBtn)
        buttonsPanel.add(approveBtn)

        contentPanel.add(leftPanel, BorderLayout.CENTER)
        contentPanel.add(buttonsPanel, BorderLayout.EAST)

        add(topBorder, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
    }

    private fun createButton(text: String, accent: Boolean, onClick: () -> Unit): JPanel {
        return object : JPanel() {
            private var hover = false

            init {
                isOpaque = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(7, 20, 7, 20)

                addMouseListener(object : MouseAdapter() {
                    override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                    override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
                    override fun mouseClicked(e: MouseEvent) { onClick() }
                })
            }

            override fun getPreferredSize(): Dimension {
                val fm = getFontMetrics(font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat()))
                val textW = fm.stringWidth(text)
                val insets = insets
                return Dimension(
                    textW + insets.left + insets.right,
                    fm.height + insets.top + insets.bottom
                )
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val arc = JBUI.scale(6).toFloat()
                val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc)

                if (accent) {
                    g2.color = if (hover) APPROVE_HOVER else APPROVE_BG
                    g2.fill(shape)
                } else {
                    g2.color = if (hover) DENY_HOVER else DENY_BG
                    g2.fill(shape)
                    g2.color = DENY_BORDER
                    g2.stroke = BasicStroke(1f)
                    g2.draw(shape)
                }

                val btnFont = font.deriveFont(Font.BOLD, JBUI.scale(13).toFloat())
                g2.font = btnFont
                g2.color = if (accent) Color.WHITE else TEXT_PRIMARY
                val fm = g2.fontMetrics
                val tx = (width - fm.stringWidth(text)) / 2
                val ty = (height + fm.ascent - fm.descent) / 2
                g2.drawString(text, tx, ty)
            }
        }
    }
}
