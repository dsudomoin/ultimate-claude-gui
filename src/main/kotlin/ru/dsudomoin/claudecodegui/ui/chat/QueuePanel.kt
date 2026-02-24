package ru.dsudomoin.claudecodegui.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.util.ui.JBUI
import ru.dsudomoin.claudecodegui.MyMessageBundle
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.*

/**
 * Displays queued messages in a compact list above the chat input area.
 * Each row shows: queue badge, image thumbnails (if any), truncated message text, remove button.
 * Hidden when the queue is empty.
 */
class QueuePanel(
    private val onRemove: (Int) -> Unit
) : JPanel() {

    companion object {
        private const val THUMB_SIZE = 24
        private const val ROW_HEIGHT = 36
        private const val HEADER_HEIGHT = 24
        private const val MAX_THUMBS = 3
    }

    data class QueuedItem(
        val text: String,
        val images: List<File>,
        val index: Int
    )

    private val items = mutableListOf<QueuedItem>()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(4, 8, 0, 8)
        isVisible = false
    }

    fun rebuild(queue: List<ChatPanel.QueuedMessage>) {
        items.clear()
        queue.forEachIndexed { index, qm ->
            items.add(QueuedItem(qm.text, qm.images, index))
        }
        removeAll()
        if (items.isEmpty()) {
            isVisible = false
        } else {
            add(HeaderRow(items.size))
            for (item in items) {
                add(QueueItemRow(item))
            }
            isVisible = true
        }
        revalidate()
        repaint()
    }

    // ── Header: "В очереди (N)" ──────────────────────────────────────────

    private inner class HeaderRow(private val count: Int) : JPanel() {
        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(HEADER_HEIGHT))
            minimumSize = preferredSize
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(HEADER_HEIGHT))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = (font ?: g2.font).deriveFont(Font.BOLD, JBUI.scale(11).toFloat())
            g2.color = ThemeColors.textSecondary
            val fm = g2.fontMetrics
            val textY = (height + fm.ascent - fm.descent) / 2
            g2.drawString(MyMessageBundle.message("queue.header", count), JBUI.scale(4), textY)
        }
    }

    // ── Queue item row ───────────────────────────────────────────────────

    private inner class QueueItemRow(private val item: QueuedItem) : JPanel() {
        private var hover = false
        private var removeHover = false
        private val removeBounds = Rectangle()
        private val thumbnailCache: List<BufferedImage?> = item.images.take(MAX_THUMBS).map { loadThumbnail(it) }

        init {
            isOpaque = false
            preferredSize = Dimension(0, JBUI.scale(ROW_HEIGHT))
            minimumSize = Dimension(0, JBUI.scale(ROW_HEIGHT))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ROW_HEIGHT))

            addMouseListener(object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: MouseEvent) { hover = false; removeHover = false; repaint() }
                override fun mouseClicked(e: MouseEvent) {
                    if (removeBounds.contains(e.point)) {
                        onRemove(item.index)
                    }
                }
            })
            addMouseMotionListener(object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val newHover = removeBounds.contains(e.point)
                    if (newHover != removeHover) {
                        removeHover = newHover
                        repaint()
                    }
                    cursor = if (newHover) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    else Cursor.getDefaultCursor()
                }
            })
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

            // Hover background
            if (hover) {
                g2.color = ThemeColors.surfaceHover
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
            }

            val padX = JBUI.scale(8)
            var x = padX
            val cy = height / 2

            // ── Badge #N ──
            val badgeFont = (font ?: g2.font).deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
            g2.font = badgeFont
            val bfm = g2.fontMetrics
            val badgeText = "#${item.index + 1}"
            val badgeW = bfm.stringWidth(badgeText) + JBUI.scale(8)
            val badgeH = bfm.height + JBUI.scale(2)
            val badgeY = cy - badgeH / 2

            val accentColor = ThemeColors.accent
            g2.color = Color(accentColor.red, accentColor.green, accentColor.blue, 25)
            g2.fillRoundRect(x, badgeY, badgeW, badgeH, JBUI.scale(4), JBUI.scale(4))
            g2.color = accentColor
            g2.drawString(badgeText, x + JBUI.scale(4), badgeY + bfm.ascent + JBUI.scale(1))
            x += badgeW + JBUI.scale(6)

            // ── Image thumbnails ──
            val thumbSz = JBUI.scale(THUMB_SIZE)
            val thumbArc = JBUI.scale(4)
            for (thumb in thumbnailCache) {
                if (thumb != null) {
                    // Clip to rounded rect
                    val clip = g2.clip
                    g2.setClip(java.awt.geom.RoundRectangle2D.Float(
                        x.toFloat(), (cy - thumbSz / 2).toFloat(),
                        thumbSz.toFloat(), thumbSz.toFloat(),
                        thumbArc.toFloat(), thumbArc.toFloat()
                    ))
                    g2.drawImage(thumb, x, cy - thumbSz / 2, thumbSz, thumbSz, null)
                    g2.clip = clip
                } else {
                    // Fallback icon for unreadable image
                    val icon = AllIcons.FileTypes.Image
                    icon.paintIcon(this, g2, x + (thumbSz - icon.iconWidth) / 2, cy - icon.iconHeight / 2)
                }
                x += thumbSz + JBUI.scale(3)
            }
            if (item.images.size > MAX_THUMBS) {
                val moreFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(10).toFloat())
                g2.font = moreFont
                g2.color = ThemeColors.textSecondary
                val moreText = "+${item.images.size - MAX_THUMBS}"
                g2.drawString(moreText, x, cy + g2.fontMetrics.ascent / 2)
                x += g2.fontMetrics.stringWidth(moreText) + JBUI.scale(4)
            }

            // ── Remove button (right side) ──
            val btnSize = JBUI.scale(18)
            val rx = width - padX - btnSize
            val ry = (height - btnSize) / 2
            removeBounds.setBounds(rx, ry, btnSize, btnSize)
            if (removeHover) {
                g2.color = ThemeColors.iconHoverBg
                g2.fillRoundRect(rx, ry, btnSize, btnSize, JBUI.scale(4), JBUI.scale(4))
            }
            val closeIcon = AllIcons.Actions.Close
            val iconOff = (btnSize - closeIcon.iconWidth) / 2
            closeIcon.paintIcon(this, g2, rx + iconOff, ry + iconOff)

            // ── Message text (truncated) ──
            val textFont = (font ?: g2.font).deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
            g2.font = textFont
            g2.color = ThemeColors.textPrimary
            val fm = g2.fontMetrics
            val textY = (height + fm.ascent - fm.descent) / 2
            val maxW = rx - x - JBUI.scale(8)
            if (maxW > JBUI.scale(20)) {
                val displayText = item.text.replace('\n', ' ').trim()
                val truncated = truncateText(displayText, fm, maxW)
                g2.drawString(truncated, x, textY)
            }
        }

        private fun truncateText(text: String, fm: FontMetrics, maxWidth: Int): String {
            if (fm.stringWidth(text) <= maxWidth) return text
            val ellipsis = "\u2026"
            val ellipsisW = fm.stringWidth(ellipsis)
            var end = text.length
            while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellipsisW > maxWidth) end--
            return if (end > 0) text.substring(0, end) + ellipsis else ellipsis
        }

        private fun loadThumbnail(file: File): BufferedImage? {
            return try {
                val orig = ImageIO.read(file) ?: return null
                val sz = JBUI.scale(THUMB_SIZE)
                val scale = minOf(sz.toDouble() / orig.width, sz.toDouble() / orig.height)
                val w = (orig.width * scale).toInt().coerceAtLeast(1)
                val h = (orig.height * scale).toInt().coerceAtLeast(1)
                val scaled = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
                val g = scaled.createGraphics()
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(orig, 0, 0, w, h, null)
                g.dispose()
                scaled
            } catch (_: Exception) {
                null
            }
        }
    }
}
