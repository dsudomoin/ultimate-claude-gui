package ru.dsudomoin.claudecodegui.ui.approval

import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.UcuBundle
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Base class for inline approval panels embedded in the chat flow.
 *
 * Subclasses populate the centre area; the base handles
 * the compound border, action links, and self-removal.
 */
abstract class InlineApprovalPanel(
    protected val project: Project,
    protected val request: ToolApprovalRequest,
    protected val onApprove: (autoApproveSession: Boolean) -> Unit,
    protected val onReject: () -> Unit
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        border = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(4, 0),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ThemeColors.borderNormal, 1),
                JBUI.Borders.empty(1)
            )
        )
    }

    // ── Actions ────────────────────────────────────────────────

    protected fun approve(auto: Boolean) {
        onApprove(auto)
        removeSelf()
    }

    protected fun reject() {
        onReject()
        removeSelf()
    }

    protected fun removeSelf() {
        isVisible = false
        parent?.remove(this)
        parent?.revalidate()
        parent?.repaint()
    }

    // ── Shared UI builders ─────────────────────────────────────

    /**
     * A row of action links:  Allow | Always allow | Deny
     */
    protected fun createActionRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
        }

        row.add(actionLink(UcuBundle.message("permission.allow")) { approve(false) })
        row.add(separator())
        row.add(actionLink(UcuBundle.message("permission.alwaysAllow")) { approve(true) })
        row.add(separator())
        row.add(actionLink(UcuBundle.message("permission.deny")) { reject() })

        return row
    }

    private fun actionLink(text: String, action: () -> Unit): JLabel {
        return JLabel(text).apply {
            foreground = ThemeColors.accent
            cursor = Cursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) = action()
                override fun mouseEntered(e: MouseEvent?) {
                    foreground = ThemeColors.accent.brighter()
                }
                override fun mouseExited(e: MouseEvent?) {
                    foreground = ThemeColors.accent
                }
            })
        }
    }

    private fun separator(): JLabel = JLabel(" | ").apply {
        foreground = Color(ThemeColors.textSecondary.rgb and 0x00FFFFFF or (0x80 shl 24), true)
    }
}
