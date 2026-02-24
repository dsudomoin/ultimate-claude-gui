package ru.dsudomoin.claudecodegui.ui.common

import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import java.awt.Graphics2D

/**
 * Theme-aware diff colors and badge text formatting helpers.
 *
 * Delegates to [ThemeColors] so colours update with user customisation.
 */
object ChangeColors {
    val inserted get() = ThemeColors.diffAddFg
    val deleted get() = ThemeColors.diffDelFg
    val modified get() = ThemeColors.diffModifiedFg
}

data class DiffBadgeText(
    val inserted: String,
    val deleted: String,
    val changed: String,
    val summary: String
)

fun diffBadgeText(inserted: Int, deleted: Int, changed: Int, spaced: Boolean = true): DiffBadgeText {
    val sep = if (spaced) " " else ""
    return DiffBadgeText(
        inserted = "[+$inserted]$sep",
        deleted = "[-$deleted]$sep",
        changed = "[~$changed]",
        summary = "[+$inserted]${sep}[-$deleted]${sep}[~$changed]"
    )
}

/**
 * Line-level diff statistics between two texts using LCS.
 * @return Triple(insertions, deletions, changed) — changed is always 0 (line-level granularity).
 */
fun lineDiffStats(before: String, after: String): Triple<Int, Int, Int> {
    if (before == after) return Triple(0, 0, 0)
    val a = before.split('\n')
    val b = after.split('\n')
    val lcs = lcsLength(a, b)
    val deletions = (a.size - lcs).coerceAtLeast(0)
    val insertions = (b.size - lcs).coerceAtLeast(0)
    return Triple(insertions, deletions, 0)
}

/** Draw [text] centred within the given [width]×[height] area. */
fun drawCenteredText(g2: Graphics2D, text: String, width: Int, height: Int) {
    val metrics = g2.fontMetrics
    val x = (width - metrics.stringWidth(text)) / 2
    val y = (height - metrics.height) / 2 + metrics.ascent
    g2.drawString(text, x, y)
}

// ── helpers ────────────────────────────────────────────────────

private fun lcsLength(a: List<String>, b: List<String>): Int {
    val m = a.size
    val n = b.size
    var prev = IntArray(n + 1)
    var curr = IntArray(n + 1)
    for (i in 1..m) {
        for (j in 1..n) {
            curr[j] = if (a[i - 1] == b[j - 1]) {
                prev[j - 1] + 1
            } else {
                maxOf(prev[j], curr[j - 1])
            }
        }
        val tmp = prev; prev = curr; curr = tmp
        curr.fill(0)
    }
    return prev[n]
}
