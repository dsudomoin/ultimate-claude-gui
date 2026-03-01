package ru.dsudomoin.claudecodegui.ui.compose.markdown

import org.commonmark.node.Link
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FilePathPostProcessorTest {

    private val parser = Parser.builder()
        .postProcessor(FilePathPostProcessor())
        .build()

    private fun parseInline(markdown: String): Paragraph {
        val doc = parser.parse(markdown)
        return doc.firstChild as Paragraph
    }

    @Test
    fun `converts relative path to link`() {
        val para = parseInline("See src/main/File.kt for details")
        // Expected: Text("See ") -> Link("src/main/File.kt") -> Text(" for details")
        val text1 = para.firstChild
        assertIs<Text>(text1)
        assertEquals("See ", text1.literal)

        val link = text1.next
        assertIs<Link>(link)
        assert(link.destination.startsWith("ide-file://"))
        assert(link.destination.contains("src/main/File.kt"))

        val linkText = link.firstChild
        assertIs<Text>(linkText)
        assertEquals("src/main/File.kt", linkText.literal)
    }

    @Test
    fun `converts absolute path to link`() {
        val para = parseInline("Open /Users/user/project/File.kt now")
        val text1 = para.firstChild
        assertIs<Text>(text1)
        assertEquals("Open ", text1.literal)

        val link = text1.next
        assertIs<Link>(link)
        assert(link.destination.contains("/Users/user/project/File.kt"))
    }

    @Test
    fun `converts path with line number`() {
        val para = parseInline("Error at src/main/File.kt:42")
        val link = para.firstChild?.next
        assertIs<Link>(link)
        assert(link.destination.contains("line=42"))

        val linkText = link.firstChild
        assertIs<Text>(linkText)
        assertEquals("src/main/File.kt:42", linkText.literal)
    }

    @Test
    fun `does not convert bare filename without slash`() {
        val para = parseInline("See File.kt for details")
        // Entire text should remain a single Text node
        val text = para.firstChild
        assertIs<Text>(text)
        assertEquals("See File.kt for details", text.literal)
    }

    @Test
    fun `does not convert URLs`() {
        val para = parseInline("Visit https://example.com/path/file.js now")
        // Should not convert the URL to ide-file link
        var node = para.firstChild
        while (node != null) {
            if (node is Link) {
                assert(!node.destination.startsWith("ide-file://")) {
                    "URL should not be converted to ide-file link"
                }
            }
            node = node.next
        }
    }

    @Test
    fun `handles multiple paths in one text`() {
        val para = parseInline("Changed src/a/Foo.kt and src/b/Bar.kt")
        var linkCount = 0
        var node = para.firstChild
        while (node != null) {
            if (node is Link && node.destination.startsWith("ide-file://")) linkCount++
            node = node.next
        }
        assertEquals(2, linkCount)
    }

    @Test
    fun `skips content inside existing links`() {
        val doc = parser.parse("[src/main/File.kt](https://github.com)")
        val para = doc.firstChild as Paragraph
        val link = para.firstChild
        assertIs<Link>(link)
        assertEquals("https://github.com", link.destination) // Original link preserved
    }

    @Test
    fun `handles path with dots and hyphens`() {
        val para = parseInline("See shared/gamer/gamer-service/src/GamerServiceImpl.kt here")
        val link = para.firstChild?.next
        assertIs<Link>(link)
        assert(link.destination.contains("GamerServiceImpl.kt"))
    }
}
