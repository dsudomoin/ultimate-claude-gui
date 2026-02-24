package ru.dsudomoin.claudecodegui.ui.common

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import ru.dsudomoin.claudecodegui.ui.theme.ThemeColors
import com.intellij.util.ui.JBUI
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import com.intellij.openapi.ui.VerticalFlowLayout
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.*
import javax.swing.text.html.HTMLEditorKit
import javax.swing.text.html.StyleSheet

/**
 * Renders Markdown text into a tree of Swing components.
 *
 * - Text paragraphs → JEditorPane with HTML
 * - Fenced code blocks → IntelliJ Editor with syntax highlighting
 * - Inline code → monospace styled HTML
 * - Lists → vertical panels with bullet markers
 * - Headings → bold labels
 */
object MarkdownRenderer {

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    private val htmlRenderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    /** Convert markdown to HTML string (without wrapper tags). */
    fun renderToHtml(markdown: String): String {
        val document = parser.parse(markdown)
        return htmlRenderer.render(document)
    }

    /** Create a JEditorPane pre-configured with themed HTML styles for streaming display. */
    fun createStyledEditorPane(): JEditorPane {
        val kit = createStyledHtmlKit()

        return JEditorPane().apply {
            editorKit = kit
            contentType = "text/html"
            text = ""
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(2)
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            highlighter = null
        }
    }

    fun render(project: Project, markdown: String): JComponent {
        val document = parser.parse(markdown)
        val panel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, JBUI.scale(4), true, false)).apply {
            isOpaque = false
        }

        var node = document.firstChild
        while (node != null) {
            val component = renderNode(project, node)
            if (component != null) {
                panel.add(component)
            }
            node = node.next
        }

        return panel
    }

    /**
     * Renders markdown as a single cohesive HTML block using JEditorPane.
     * Ideal for plans and other content that should not be split into separate components.
     */
    fun renderHtml(markdown: String): JComponent {
        val document = parser.parse(markdown)
        val html = htmlRenderer.render(document)

        val kit = createStyledHtmlKit()

        return JEditorPane().apply {
            editorKit = kit
            contentType = "text/html"
            text = "<html><body>$html</body></html>"
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty()
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            highlighter = null
        }
    }

    private fun createStyledHtmlKit(): HTMLEditorKit {
        val fg = colorToHex(JBColor.foreground())
        val codeBg = colorToHex(ThemeColors.codeBg)
        val borderColor = colorToHex(ThemeColors.borderNormal)
        val quoteBorder = colorToHex(ThemeColors.quoteBorder)
        val linkColor = colorToHex(ThemeColors.accent)
        val fontSize = JBUI.scale(13)

        // Fonts from IDE settings
        val uiFontFamily = UIManager.getFont("Label.font")?.family ?: "sans-serif"
        val editorScheme = EditorColorsManager.getInstance().globalScheme
        val monoFontFamily = editorScheme.editorFontName

        val kit = HTMLEditorKit()
        val styleSheet = StyleSheet().apply {
            addRule("body { font-family: '$uiFontFamily', system-ui, sans-serif; font-size: ${fontSize}px; color: #$fg; margin: 0; padding: 0; }")
            addRule("h1 { font-weight: bold; color: #$fg; margin: ${JBUI.scale(8)}px 0 ${JBUI.scale(4)}px 0; }")
            addRule("h2 { font-weight: bold; color: #$fg; margin: ${JBUI.scale(6)}px 0 ${JBUI.scale(3)}px 0; }")
            addRule("h3 { font-weight: bold; color: #$fg; margin: ${JBUI.scale(4)}px 0 ${JBUI.scale(2)}px 0; }")
            addRule("p { margin: ${JBUI.scale(3)}px 0; }")
            addRule("ul, ol { margin: ${JBUI.scale(2)}px 0 ${JBUI.scale(2)}px ${JBUI.scale(16)}px; }")
            addRule("li { margin: ${JBUI.scale(1)}px 0; }")
            addRule("code { font-family: '$monoFontFamily', monospace; font-size: ${fontSize - JBUI.scale(2)}px; color: #$fg; background-color: #$codeBg; padding: 1px 4px; }")
            addRule("pre { background-color: #$codeBg; padding: ${JBUI.scale(8)}px; border: 1px solid #$borderColor; margin: ${JBUI.scale(4)}px 0; font-family: '$monoFontFamily', monospace; font-size: ${JBUI.scale(12)}px; }")
            addRule("pre code { color: #$fg; padding: 0; background-color: transparent; }")
            addRule("blockquote { border-left: 3px solid #$quoteBorder; padding-left: ${JBUI.scale(8)}px; margin: ${JBUI.scale(4)}px 0; color: #$quoteBorder; }")
            addRule("a { color: #$linkColor; }")
            addRule("table { border-collapse: collapse; margin: ${JBUI.scale(6)}px 0; width: auto; }")
            addRule("th, td { border: 1px solid #$borderColor; padding: ${JBUI.scale(6)}px ${JBUI.scale(10)}px; text-align: left; }")
            addRule("th { background-color: #$codeBg; font-weight: bold; color: #$fg; }")
            addRule("td { color: #$fg; }")
            addRule("hr { border: none; border-top: 1px solid #$borderColor; margin: ${JBUI.scale(8)}px 0; }")
            addRule("strong { font-weight: bold; }")
            addRule("em { font-style: italic; }")
        }
        kit.styleSheet = styleSheet
        return kit
    }

    private fun colorToHex(color: Color): String {
        return String.format("%02x%02x%02x", color.red, color.green, color.blue)
    }

    private fun renderNode(project: Project, node: Node): JComponent? = when (node) {
        is Paragraph -> renderParagraph(node)
        is FencedCodeBlock -> renderCodeBlock(project, node.literal.trimEnd(), node.info?.trim())
        is IndentedCodeBlock -> renderCodeBlock(project, node.literal.trimEnd(), null)
        is Heading -> renderHeading(node)
        is BulletList -> renderList(project, node, ordered = false)
        is OrderedList -> renderList(project, node, ordered = true)
        is BlockQuote -> renderBlockQuote(project, node)
        is TableBlock -> renderTable(node)
        is ThematicBreak -> JSeparator().apply {
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(2))
        }
        else -> {
            // Fallback: render children as plain text
            val text = collectText(node)
            if (text.isNotBlank()) JBTextArea(text).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                isOpaque = false
                border = JBUI.Borders.empty(1, 0)
                font = UIManager.getFont("Label.font") ?: font
                highlighter = null
            } else null
        }
    }

    private fun renderParagraph(paragraph: Paragraph): JComponent {
        val html = htmlRenderer.render(paragraph)
        val kit = createStyledHtmlKit()
        return JEditorPane().apply {
            editorKit = kit
            contentType = "text/html"
            text = "<html><body>$html</body></html>"
            isEditable = false
            isOpaque = false
            border = JBUI.Borders.empty(1, 0)
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            highlighter = null
        }
    }

    private fun renderHeading(heading: Heading): JComponent {
        val text = collectInlineText(heading)
        return JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD)
            border = JBUI.Borders.emptyTop(4)
        }
    }

    private fun renderCodeBlock(@Suppress("UNUSED_PARAMETER") project: Project, code: String, language: String?): JComponent {
        val lang = language?.takeIf { it.isNotBlank() }
        val codeBgColor = ThemeColors.codeBg
        val headerBgColor = ThemeColors.surfaceHover
        val monoFont = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = codeBgColor
            border = JBUI.Borders.empty(0)

            // Header with language label and copy button
            val header = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = headerBgColor
                border = JBUI.Borders.empty(2, 8)

                add(JBLabel(lang ?: "code").apply {
                    font = monoFont.deriveFont(Font.PLAIN, JBUI.scale(11).toFloat())
                    foreground = ThemeColors.textSecondary
                }, BorderLayout.WEST)

                add(JButton("Copy").apply {
                    font = font.deriveFont(JBUI.scale(10).toFloat())
                    isFocusable = false
                    putClientProperty("JButton.buttonType", "borderless")
                    addActionListener {
                        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                        clipboard.setContents(StringSelection(code), null)
                        text = "Copied!"
                        Timer(1500) { text = "Copy" }.apply { isRepeats = false; start() }
                    }
                }, BorderLayout.EAST)
            }
            add(header, BorderLayout.NORTH)

            // Code content as JBTextArea
            val textArea = JBTextArea(code).apply {
                isEditable = false
                lineWrap = false
                isOpaque = false
                font = monoFont.deriveFont(Font.PLAIN, JBUI.scale(12).toFloat())
                foreground = ThemeColors.textPrimary
                border = JBUI.Borders.empty(4, 8)
            }

            val scrollPane = JBScrollPane(textArea).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            }

            add(scrollPane, BorderLayout.CENTER)
        }
    }

    private fun renderList(project: Project, list: Node, ordered: Boolean): JComponent {
        val panel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyLeft(16)
        }

        var index = 1
        var item = list.firstChild
        while (item != null) {
            if (item is ListItem) {
                val marker = if (ordered) "${index++}." else "\u2022"
                val itemPanel = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(JBLabel(marker).apply {
                        border = JBUI.Borders.emptyRight(6)
                        verticalAlignment = SwingConstants.TOP
                    }, BorderLayout.WEST)

                    val contentPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)).apply {
                        isOpaque = false
                    }
                    var child = item.firstChild
                    while (child != null) {
                        val comp = renderNode(project, child)
                        if (comp != null) {
                            contentPanel.add(comp)
                        }
                        child = child.next
                    }
                    add(contentPanel, BorderLayout.CENTER)
                }
                panel.add(itemPanel)
            }
            item = item.next
        }

        return panel
    }

    private fun renderTable(table: TableBlock): JComponent {
        data class CellInfo(val text: String, val isHeader: Boolean)

        val tableRows = mutableListOf<List<CellInfo>>()
        val colAlignments = mutableListOf<TableCell.Alignment?>()

        var section = table.firstChild
        while (section != null) {
            val isHeader = section is TableHead
            if (section is TableHead || section is TableBody) {
                var rowNode = section.firstChild
                while (rowNode != null) {
                    if (rowNode is TableRow) {
                        val cells = mutableListOf<CellInfo>()
                        var colIdx = 0
                        var cellNode = rowNode.firstChild
                        while (cellNode != null) {
                            if (cellNode is TableCell) {
                                cells.add(CellInfo(collectInlineText(cellNode), isHeader))
                                if (colAlignments.size <= colIdx) {
                                    colAlignments.add(cellNode.alignment)
                                }
                                colIdx++
                            }
                            cellNode = cellNode.next
                        }
                        tableRows.add(cells)
                    }
                    rowNode = rowNode.next
                }
            }
            section = section.next
        }

        if (tableRows.isEmpty()) return JPanel()

        val colCount = tableRows.maxOf { it.size }
        while (colAlignments.size < colCount) colAlignments.add(null)

        val padH = JBUI.scale(10)
        val padV = JBUI.scale(6)
        val arc = JBUI.scale(10).toFloat()
        val minColW = JBUI.scale(48)

        val tableComponent = object : JComponent() {
            init { isOpaque = false }

            private fun headerFont(): Font = (font ?: UIManager.getFont("Label.font"))
                .deriveFont(Font.BOLD, JBUI.scale(13).toFloat())

            private fun bodyFont(): Font = (font ?: UIManager.getFont("Label.font"))
                .deriveFont(Font.PLAIN, JBUI.scale(13).toFloat())

            private fun calcLayout(): Pair<IntArray, Int> {
                val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
                val g2 = img.createGraphics()
                try {
                    val hfm = g2.getFontMetrics(headerFont())
                    val bfm = g2.getFontMetrics(bodyFont())

                    val colWidths = IntArray(colCount)
                    for (row in tableRows) {
                        for ((c, cell) in row.withIndex()) {
                            val fm = if (cell.isHeader) hfm else bfm
                            colWidths[c] = maxOf(colWidths[c], fm.stringWidth(cell.text) + padH * 2)
                        }
                    }
                    for (i in colWidths.indices) colWidths[i] = colWidths[i].coerceAtLeast(minColW)

                    val rowH = maxOf(hfm.height, bfm.height) + padV * 2
                    return colWidths to rowH
                } finally {
                    g2.dispose()
                }
            }

            override fun getPreferredSize(): Dimension {
                val (cw, rh) = calcLayout()
                return Dimension(cw.sum(), rh * tableRows.size)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

                val (colWidths, rowH) = calcLayout()
                val totalW = colWidths.sum()
                val totalH = rowH * tableRows.size

                val shape = RoundRectangle2D.Float(
                    0f, 0f, totalW.toFloat(), totalH.toFloat(), arc, arc
                )
                val oldClip = g2.clip
                g2.clip(shape)

                // ── Row backgrounds ──
                var y = 0
                for ((r, row) in tableRows.withIndex()) {
                    val isHdr = row.any { it.isHeader }
                    when {
                        isHdr -> {
                            g2.color = ThemeColors.codeBg
                            g2.fillRect(0, y, totalW, rowH)
                        }
                        r % 2 != 0 -> {
                            g2.color = ThemeColors.surfaceHover
                            g2.fillRect(0, y, totalW, rowH)
                        }
                    }
                    y += rowH
                }

                // ── Inner grid lines ──
                g2.color = ThemeColors.borderNormal
                for (r in 1 until tableRows.size) {
                    val ly = r * rowH
                    g2.drawLine(0, ly, totalW, ly)
                }
                var x = 0
                for (i in 0 until colCount - 1) {
                    x += colWidths[i]
                    g2.drawLine(x, 0, x, totalH)
                }

                // ── Cell text ──
                val hFont = headerFont()
                val bFont = bodyFont()
                y = 0
                for (row in tableRows) {
                    x = 0
                    for ((c, cell) in row.withIndex()) {
                        val cw = colWidths.getOrElse(c) { minColW }
                        val f = if (cell.isHeader) hFont else bFont
                        val fm = g2.getFontMetrics(f)
                        g2.font = f
                        g2.color = ThemeColors.textPrimary

                        val textY = y + (rowH + fm.ascent - fm.descent) / 2
                        val maxTw = cw - padH * 2
                        val display = if (fm.stringWidth(cell.text) > maxTw) {
                            truncCell(cell.text, fm, maxTw)
                        } else {
                            cell.text
                        }
                        val tw = fm.stringWidth(display)

                        val textX = when (colAlignments.getOrNull(c)) {
                            TableCell.Alignment.CENTER -> x + (cw - tw) / 2
                            TableCell.Alignment.RIGHT -> x + cw - padH - tw
                            else -> x + padH
                        }

                        g2.drawString(display, textX, textY)
                        x += cw
                    }
                    y += rowH
                }

                g2.clip = oldClip

                // ── Rounded outer border ──
                g2.color = ThemeColors.borderNormal
                g2.stroke = BasicStroke(1f)
                g2.draw(RoundRectangle2D.Float(
                    0.5f, 0.5f, (totalW - 1).toFloat(), (totalH - 1).toFloat(), arc, arc
                ))
            }

            private fun truncCell(text: String, fm: FontMetrics, maxW: Int): String {
                val ell = "\u2026"
                val ellW = fm.stringWidth(ell)
                var end = text.length
                while (end > 0 && fm.stringWidth(text.substring(0, end)) + ellW > maxW) end--
                return if (end > 0) text.substring(0, end) + ell else ell
            }
        }

        // Wrap in scroll pane for wide tables
        return JScrollPane(tableComponent).apply {
            border = JBUI.Borders.empty(2, 0)
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER

            // Forward vertical wheel events to parent scroll pane (prevent scroll trapping)
            for (l in mouseWheelListeners) removeMouseWheelListener(l)
            addMouseWheelListener { e ->
                if (e.isShiftDown && horizontalScrollBar.isVisible) {
                    // Shift+wheel → horizontal scroll within table
                    horizontalScrollBar.value += e.wheelRotation * horizontalScrollBar.unitIncrement
                } else {
                    // Normal wheel → forward to chat scroll pane
                    parent?.dispatchEvent(
                        SwingUtilities.convertMouseEvent(e.component, e, parent)
                    )
                }
            }
        }
    }

    private fun renderBlockQuote(project: Project, quote: BlockQuote): JComponent {
        val inner = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 0, true, false)).apply {
            isOpaque = false
        }

        var child = quote.firstChild
        while (child != null) {
            val comp = renderNode(project, child)
            if (comp != null) {
                inner.add(comp)
            }
            child = child.next
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, JBColor.namedColor("Claude.QuoteBorder", JBColor.GRAY)),
                JBUI.Borders.emptyLeft(8)
            )
            add(inner, BorderLayout.CENTER)
        }
    }

    // ── Inline rendering helpers ─────────────────────────────────────────────

    /**
     * Converts inline Markdown nodes to HTML for display in JBLabel.
     */
    private fun inlineToHtml(node: Node): String {
        val sb = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            sb.append(inlineNodeToHtml(child))
            child = child.next
        }
        return sb.toString()
    }

    private fun inlineNodeToHtml(node: Node): String = when (node) {
        is Text -> escapeHtml(node.literal)
        is Code -> "<code>${escapeHtml(node.literal)}</code>"
        is Emphasis -> "<i>${inlineChildren(node)}</i>"
        is StrongEmphasis -> "<b>${inlineChildren(node)}</b>"
        is Link -> "<a href='${node.destination}'>${inlineChildren(node)}</a>"
        is SoftLineBreak -> " "
        is HardLineBreak -> "<br>"
        else -> collectInlineText(node)
    }

    private fun inlineChildren(node: Node): String {
        val sb = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            sb.append(inlineNodeToHtml(child))
            child = child.next
        }
        return sb.toString()
    }

    private fun collectText(node: Node): String {
        val sb = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            if (child is Text) sb.append(child.literal)
            else sb.append(collectText(child))
            child = child.next
        }
        return sb.toString()
    }

    private fun collectInlineText(node: Node): String {
        val sb = StringBuilder()
        var child = node.firstChild
        while (child != null) {
            when (child) {
                is Text -> sb.append(child.literal)
                is Code -> sb.append(child.literal)
                is SoftLineBreak -> sb.append(" ")
                is HardLineBreak -> sb.append("\n")
                else -> sb.append(collectInlineText(child))
            }
            child = child.next
        }
        return sb.toString()
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

}
