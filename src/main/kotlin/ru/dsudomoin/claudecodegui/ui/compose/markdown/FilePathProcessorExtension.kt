@file:OptIn(ExperimentalJewelApi::class)

package ru.dsudomoin.claudecodegui.ui.compose.markdown

import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.extensions.MarkdownProcessorExtension

/**
 * CommonMark [PostProcessor] that scans [Text] nodes for file path patterns
 * and replaces them with [Link] nodes using the `ide-file://` URI scheme.
 *
 * Skips content inside:
 * - [FencedCodeBlock], [IndentedCodeBlock] — code blocks
 * - [Link] — already-linked text
 * - [Code] — inline code (treated as literal)
 *
 * File paths must contain at least one `/` to avoid false positives.
 */
class FilePathPostProcessor : PostProcessor {

    companion object {
        /**
         * Matches file paths like:
         * - `/Users/user/project/src/File.kt:42`
         * - `src/main/kotlin/File.kt`
         * - `shared/gamer/gamer-service/.../GamerServiceImpl.kt`
         *
         * Requires at least one `/` and a file extension.
         * Optionally ends with `:lineNumber`.
         * Does not match URLs (excluded via negative lookbehind for `://`, word characters, and `.`).
         */
        val FILE_PATH_REGEX = Regex(
            """(?<![:/\w.])(?:(?:/[\w.\-@]+)+|(?:[\w.\-@]+/)+[\w.\-@]+)(?:\.\w+)(?::\d+)?"""
        )
    }

    override fun process(node: Node): Node {
        val visitor = FilePathVisitor()
        node.accept(visitor)
        return node
    }

    private class FilePathVisitor : AbstractVisitor() {
        override fun visit(text: Text) {
            // Skip text inside Link nodes
            if (isInsideLink(text)) return

            val literal = text.literal ?: return
            val matches = FILE_PATH_REGEX.findAll(literal).toList()
            if (matches.isEmpty()) return

            // Replace the Text node with interleaved Text and Link nodes
            var lastEnd = 0

            for (match in matches) {
                // Text before the match
                if (match.range.first > lastEnd) {
                    val before = Text(literal.substring(lastEnd, match.range.first))
                    text.insertBefore(before)
                }

                // The file path as a link
                val pathText = match.value
                val link = createFilePathLink(pathText)
                text.insertBefore(link)

                lastEnd = match.range.last + 1
            }

            // Text after the last match
            if (lastEnd < literal.length) {
                val after = Text(literal.substring(lastEnd))
                text.insertBefore(after)
            }

            // Remove the original Text node
            text.unlink()
        }

        // Skip code blocks entirely — don't visit their children
        override fun visit(fencedCodeBlock: FencedCodeBlock) {}
        override fun visit(indentedCodeBlock: IndentedCodeBlock) {}
        override fun visit(code: Code) {}

        private fun isInsideLink(node: Node): Boolean {
            var parent = node.parent
            while (parent != null) {
                if (parent is Link) return true
                parent = parent.parent
            }
            return false
        }

        private fun createFilePathLink(pathWithLine: String): Link {
            val colonIndex = pathWithLine.lastIndexOf(':')
            val hasLineNumber = colonIndex > 0 &&
                    pathWithLine.substring(colonIndex + 1).all { it.isDigit() } &&
                    pathWithLine.substring(colonIndex + 1).isNotEmpty()

            val filePath = if (hasLineNumber) pathWithLine.substring(0, colonIndex) else pathWithLine
            val lineNumber = if (hasLineNumber) pathWithLine.substring(colonIndex + 1).toIntOrNull() else null

            val isAbsolute = filePath.startsWith("/")
            val uri = if (isAbsolute) {
                val lineQuery = if (lineNumber != null) "?line=$lineNumber" else ""
                "ide-file://$filePath$lineQuery"
            } else {
                val lineQuery = if (lineNumber != null) "?line=$lineNumber" else ""
                "ide-file://relative/$filePath$lineQuery"
            }

            val link = Link(uri, null)
            link.appendChild(Text(pathWithLine))
            return link
        }
    }
}

/**
 * Jewel [MarkdownProcessorExtension] that registers [FilePathPostProcessor]
 * to auto-linkify file paths in markdown content.
 *
 * Add to the extensions list in `ComposePanelHost.ConfiguredMarkdownStyling()`.
 */
object FilePathProcessorExtension : MarkdownProcessorExtension {
    override val parserExtension: Parser.ParserExtension = object : Parser.ParserExtension {
        override fun extend(parserBuilder: Parser.Builder) {
            parserBuilder.postProcessor(FilePathPostProcessor())
        }
    }
}
