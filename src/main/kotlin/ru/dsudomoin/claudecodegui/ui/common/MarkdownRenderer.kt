package ru.dsudomoin.claudecodegui.ui.common

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.*
import org.commonmark.parser.Parser
import com.intellij.openapi.ui.VerticalFlowLayout
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*

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

    private fun renderNode(project: Project, node: Node): JComponent? = when (node) {
        is Paragraph -> renderParagraph(node)
        is FencedCodeBlock -> renderCodeBlock(project, node.literal.trimEnd(), node.info?.trim())
        is IndentedCodeBlock -> renderCodeBlock(project, node.literal.trimEnd(), null)
        is Heading -> renderHeading(node)
        is BulletList -> renderList(project, node, ordered = false)
        is OrderedList -> renderList(project, node, ordered = true)
        is BlockQuote -> renderBlockQuote(project, node)
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
            } else null
        }
    }

    private fun renderParagraph(paragraph: Paragraph): JComponent {
        val text = collectInlineText(paragraph)
        return JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty(1, 0)
            font = UIManager.getFont("Label.font") ?: font
        }
    }

    private fun renderHeading(heading: Heading): JComponent {
        val text = collectInlineText(heading)
        val fontSize = when (heading.level) {
            1 -> 18f
            2 -> 16f
            3 -> 14f
            else -> 13f
        }
        return JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, fontSize)
            border = JBUI.Borders.emptyTop(4)
        }
    }

    private fun renderCodeBlock(project: Project, code: String, language: String?): JComponent {
        val lang = language?.takeIf { it.isNotBlank() }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor.namedColor("Claude.CodeBackground", JBColor(0xF6F8FA, 0x1E1F22))
            border = JBUI.Borders.empty(0)

            // Header with language label and copy button
            val header = JPanel(BorderLayout()).apply {
                isOpaque = true
                background = JBColor.namedColor("Claude.CodeHeader", JBColor(0xEAECF0, 0x2B2D30))
                border = JBUI.Borders.empty(2, 8)

                add(JBLabel(lang ?: "code").apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    foreground = JBColor.GRAY
                }, BorderLayout.WEST)

                add(JButton("Copy").apply {
                    font = font.deriveFont(10f)
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

            // Editor with syntax highlighting
            try {
                val fileType = lang?.let {
                    FileTypeManager.getInstance().getFileTypeByExtension(mapLanguageToExtension(it))
                } ?: PlainTextFileType.INSTANCE

                val document = EditorFactory.getInstance().createDocument(code)
                val editor = EditorFactory.getInstance().createViewer(document, project)

                (editor as? EditorEx)?.apply {
                    highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(project, fileType)
                    settings.apply {
                        isLineNumbersShown = code.lines().size > 3
                        isFoldingOutlineShown = false
                        additionalLinesCount = 0
                        additionalColumnsCount = 0
                        isCaretRowShown = false
                        isRightMarginShown = false
                    }
                    setVerticalScrollbarVisible(false)
                    setHorizontalScrollbarVisible(code.lines().any { it.length > 80 })
                    setBorder(JBUI.Borders.empty(4, 8))
                }

                val editorComponent = editor.component.apply {
                    // Set preferred height based on content
                    val lineCount = code.lines().size.coerceIn(1, 30)
                    val lineHeight = editor.lineHeight
                    preferredSize = Dimension(0, lineHeight * lineCount + JBUI.scale(8))
                }

                add(editorComponent, BorderLayout.CENTER)

                // We can't easily register with Disposer here without a parent Disposable,
                // so we use a component listener to release the editor when removed
                addHierarchyListener { e ->
                    if ((e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong()) != 0L) {
                        if (!isShowing) {
                            try {
                                EditorFactory.getInstance().releaseEditor(editor)
                            } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: Exception) {
                // Fallback: plain text area if editor creation fails
                add(JTextArea(code).apply {
                    isEditable = false
                    font = Font("JetBrains Mono", Font.PLAIN, 12)
                    border = JBUI.Borders.empty(4, 8)
                    isOpaque = false
                }, BorderLayout.CENTER)
            }
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

    private fun mapLanguageToExtension(language: String): String = when (language.lowercase()) {
        "kotlin", "kt" -> "kt"
        "java" -> "java"
        "python", "py" -> "py"
        "javascript", "js" -> "js"
        "typescript", "ts" -> "ts"
        "tsx" -> "tsx"
        "jsx" -> "jsx"
        "rust", "rs" -> "rs"
        "go" -> "go"
        "c" -> "c"
        "cpp", "c++" -> "cpp"
        "csharp", "cs", "c#" -> "cs"
        "ruby", "rb" -> "rb"
        "swift" -> "swift"
        "php" -> "php"
        "html" -> "html"
        "css" -> "css"
        "scss" -> "scss"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "xml" -> "xml"
        "sql" -> "sql"
        "bash", "sh", "shell", "zsh" -> "sh"
        "dockerfile" -> "dockerfile"
        "toml" -> "toml"
        "markdown", "md" -> "md"
        "groovy" -> "groovy"
        "gradle" -> "gradle"
        "proto", "protobuf" -> "proto"
        else -> language
    }
}
