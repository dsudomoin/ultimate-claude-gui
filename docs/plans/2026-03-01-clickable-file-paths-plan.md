# Clickable File Paths Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make file paths in chat messages clickable, opening the corresponding file in the IDE editor at the correct line.

**Architecture:** CommonMark `PostProcessor` scans the AST for `Text` nodes containing file paths, replaces them with `Link` nodes using `ide-file://` URI scheme. Jewel renders links natively; `onUrlClick` callback routes `ide-file://` to `FileEditorManager`. No custom Jewel renderer needed.

**Tech Stack:** CommonMark-java (bundled with Jewel), Jewel Markdown extensions API (`MarkdownProcessorExtension`), IntelliJ `FileEditorManager` + `OpenFileDescriptor`

---

### Task 1: FilePathLinkHandler — URI parsing and file opening

**Files:**
- Create: `src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/markdown/FilePathLinkHandler.kt`
- Test: `src/test/kotlin/ru/dsudomoin/claudecodegui/ui/compose/markdown/FilePathLinkHandlerTest.kt`

**Step 1: Write the failing tests**

Create the test file:

```kotlin
package ru.dsudomoin.claudecodegui.ui.compose.markdown

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FilePathLinkHandlerTest {

    @Test
    fun `parse ide-file URI with absolute path and line`() {
        val result = FilePathLinkHandler.parseIdeFileUri("ide-file:///Users/user/project/src/File.kt?line=42")
        assertEquals("/Users/user/project/src/File.kt", result?.path)
        assertEquals(42, result?.line)
    }

    @Test
    fun `parse ide-file URI with absolute path no line`() {
        val result = FilePathLinkHandler.parseIdeFileUri("ide-file:///Users/user/project/src/File.kt")
        assertEquals("/Users/user/project/src/File.kt", result?.path)
        assertNull(result?.line)
    }

    @Test
    fun `parse ide-file URI with relative path`() {
        val result = FilePathLinkHandler.parseIdeFileUri("ide-file://relative/src/main/File.kt?line=10")
        assertEquals("src/main/File.kt", result?.path)
        assertEquals(10, result?.line)
    }

    @Test
    fun `parse ide-file URI with relative path no line`() {
        val result = FilePathLinkHandler.parseIdeFileUri("ide-file://relative/src/main/File.kt")
        assertEquals("src/main/File.kt", result?.path)
        assertNull(result?.line)
    }

    @Test
    fun `returns null for http URLs`() {
        val result = FilePathLinkHandler.parseIdeFileUri("https://example.com/path")
        assertNull(result)
    }

    @Test
    fun `returns null for non-ide-file scheme`() {
        val result = FilePathLinkHandler.parseIdeFileUri("file:///some/path.kt")
        assertNull(result)
    }

    @Test
    fun `isIdeFileUri returns true for ide-file scheme`() {
        assertEquals(true, FilePathLinkHandler.isIdeFileUri("ide-file:///path/to/file.kt"))
    }

    @Test
    fun `isIdeFileUri returns false for http`() {
        assertEquals(false, FilePathLinkHandler.isIdeFileUri("https://example.com"))
    }

    @Test
    fun `resolveAbsolutePath returns absolute path unchanged`() {
        val resolved = FilePathLinkHandler.resolveAbsolutePath("/Users/user/File.kt", "/project")
        assertEquals("/Users/user/File.kt", resolved)
    }

    @Test
    fun `resolveAbsolutePath resolves relative against basePath`() {
        val resolved = FilePathLinkHandler.resolveAbsolutePath("src/main/File.kt", "/Users/user/project")
        assertEquals("/Users/user/project/src/main/File.kt", resolved)
    }
}
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "ru.dsudomoin.claudecodegui.ui.compose.markdown.FilePathLinkHandlerTest" -i`
Expected: FAIL — class `FilePathLinkHandler` does not exist

**Step 3: Write the implementation**

```kotlin
package ru.dsudomoin.claudecodegui.ui.compose.markdown

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Handles `ide-file://` URIs produced by [FilePathPostProcessor].
 *
 * URI format:
 * - Absolute: `ide-file:///absolute/path/to/file.kt?line=42`
 * - Relative: `ide-file://relative/src/main/file.kt?line=10`
 *
 * Also routes `http://` / `https://` URLs to the system browser.
 */
object FilePathLinkHandler {

    private const val SCHEME = "ide-file://"
    private const val ABSOLUTE_PREFIX = "ide-file:///"
    private const val RELATIVE_PREFIX = "ide-file://relative/"

    data class ParsedFileUri(
        val path: String,
        val line: Int? = null,
        val isAbsolute: Boolean = true,
    )

    fun isIdeFileUri(url: String): Boolean = url.startsWith(SCHEME)

    fun parseIdeFileUri(url: String): ParsedFileUri? {
        if (!isIdeFileUri(url)) return null

        val isAbsolute = url.startsWith(ABSOLUTE_PREFIX) && !url.startsWith(RELATIVE_PREFIX)
        val pathAndQuery = if (isAbsolute) {
            url.removePrefix("ide-file://")
        } else {
            url.removePrefix(RELATIVE_PREFIX)
        }

        val queryIndex = pathAndQuery.indexOf('?')
        val path = if (queryIndex >= 0) pathAndQuery.substring(0, queryIndex) else pathAndQuery
        val line = if (queryIndex >= 0) {
            val query = pathAndQuery.substring(queryIndex + 1)
            query.split('&')
                .firstOrNull { it.startsWith("line=") }
                ?.removePrefix("line=")
                ?.toIntOrNull()
        } else null

        return ParsedFileUri(path = path, line = line, isAbsolute = isAbsolute)
    }

    fun resolveAbsolutePath(path: String, projectBasePath: String?): String {
        if (path.startsWith("/")) return path
        val base = projectBasePath ?: return path
        return "$base/$path"
    }

    /**
     * Handle a URL click from Jewel Markdown.
     * Routes `ide-file://` to the IDE editor, everything else to the browser.
     */
    fun handleUrlClick(url: String, project: Project) {
        val parsed = parseIdeFileUri(url)
        if (parsed != null) {
            val absolutePath = resolveAbsolutePath(parsed.path, project.basePath)
            val vFile = LocalFileSystem.getInstance().findFileByPath(absolutePath) ?: return
            if (parsed.line != null) {
                OpenFileDescriptor(project, vFile, parsed.line - 1, 0).navigate(true)
            } else {
                OpenFileDescriptor(project, vFile).navigate(true)
            }
        } else if (url.startsWith("http://") || url.startsWith("https://")) {
            BrowserUtil.browse(url)
        }
    }
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "ru.dsudomoin.claudecodegui.ui.compose.markdown.FilePathLinkHandlerTest" -i`
Expected: All 11 tests PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/markdown/FilePathLinkHandler.kt \
        src/test/kotlin/ru/dsudomoin/claudecodegui/ui/compose/markdown/FilePathLinkHandlerTest.kt
git commit -m "feat: add FilePathLinkHandler for ide-file:// URI parsing and navigation"
```

---

### Task 2: FilePathPostProcessor — CommonMark AST transformation

**Files:**
- Create: `src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/markdown/FilePathProcessorExtension.kt`
- Test: `src/test/kotlin/ru/dsudomoin/claudecodegui/ui/compose/markdown/FilePathPostProcessorTest.kt`

**Step 1: Write the failing tests**

```kotlin
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
        // Expected: Text("See ") → Link("src/main/File.kt") → Text(" for details")
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
```

**Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "ru.dsudomoin.claudecodegui.ui.compose.markdown.FilePathPostProcessorTest" -i`
Expected: FAIL — class `FilePathPostProcessor` does not exist

**Step 3: Write the implementation**

```kotlin
@file:OptIn(ExperimentalJewelApi::class)

package ru.dsudomoin.claudecodegui.ui.compose.markdown

import org.commonmark.node.*
import org.commonmark.parser.Parser
import org.commonmark.parser.PostProcessor
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.markdown.processing.MarkdownProcessorExtension

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
         * Does not match URLs (excluded via negative lookbehind for `://`).
         */
        val FILE_PATH_REGEX = Regex(
            """(?<![:/])(?:(?:/[\w.\-@]+)+|(?:[\w.\-@]+/)+[\w.\-@]+)(?:\.\w+)(?::\d+)?"""
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
            val parent = text.parent ?: return
            var lastEnd = 0

            for (match in matches) {
                // Text before the match
                if (match.range.first > lastEnd) {
                    val before = Text(literal.substring(lastEnd, match.range.first))
                    parent.insertBefore(before, text)
                }

                // The file path as a link
                val pathText = match.value
                val link = createFilePathLink(pathText)
                parent.insertBefore(link, text)

                lastEnd = match.range.last + 1
            }

            // Text after the last match
            if (lastEnd < literal.length) {
                val after = Text(literal.substring(lastEnd))
                parent.insertBefore(after, text)
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

        private fun Node.insertBefore(newNode: Node, refNode: Node) {
            refNode.insertBefore(newNode)
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

    // No text rendering, block processing, or inline processing needed
    override val textRendererExtension = null
    override val blockProcessorExtension = null
}
```

**Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "ru.dsudomoin.claudecodegui.ui.compose.markdown.FilePathPostProcessorTest" -i`
Expected: All 8 tests PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/markdown/FilePathProcessorExtension.kt \
        src/test/kotlin/ru/dsudomoin/claudecodegui/ui/compose/markdown/FilePathPostProcessorTest.kt
git commit -m "feat: add FilePathPostProcessor to auto-linkify file paths in markdown"
```

---

### Task 3: Wire up the extension in ComposePanelHost

**Files:**
- Modify: `src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/common/ComposePanelHost.kt:98` (processor line)

**Step 1: Add FilePathProcessorExtension to the processor extensions list**

In `ComposePanelHost.kt`, find line 98:
```kotlin
val processor = remember { MarkdownProcessor(listOf(GitHubTableProcessorExtension)) }
```

Change to:
```kotlin
val processor = remember { MarkdownProcessor(listOf(GitHubTableProcessorExtension, FilePathProcessorExtension)) }
```

Add import at the top of the file:
```kotlin
import ru.dsudomoin.claudecodegui.ui.compose.markdown.FilePathProcessorExtension
```

**Step 2: Build to verify no compilation errors**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/common/ComposePanelHost.kt
git commit -m "feat: register FilePathProcessorExtension in markdown pipeline"
```

---

### Task 4: Wire up onUrlClick in the chat message rendering

**Files:**
- Modify: `src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/toolwindow/ComposeChatContainer.kt:121` (ChatCallbacks)
- Modify: `src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/chat/ComposeChatPanel.kt` (add onUrlClick param, pass down)
- Modify: `src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/chat/ComposeMessageList.kt` (add onUrlClick param, pass down)
- Modify: `src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/chat/ComposeMessageBubble.kt` (pass onUrlClick to ComposeMarkdownContent)
- Modify: `src/main/kotlin/ru/dsudomoin/claudecodegui/ui/toolwindow/ClaudeToolWindowFactory.kt:264` (add onUrlClick callback)

The callback chain: `ClaudeToolWindowFactory` → `ChatCallbacks.onUrlClick` → `ComposeChatContainer` → `ComposeChatPanel` → `ComposeMessageList` → `ComposeMessageBubble` → `ComposeMarkdownContent(onUrlClick = ...)`.

**Step 1: Add `onUrlClick` to ChatCallbacks**

In `ComposeChatContainer.kt`, add to `ChatCallbacks` data class after `onFileClick`:
```kotlin
val onUrlClick: ((String) -> Unit)? = null,
```

**Step 2: Pass `onUrlClick` through ComposeChatContainer → ComposeChatPanel**

In `ComposeChatContainer.kt`, add to `ComposeChatPanel(...)` call:
```kotlin
onUrlClick = callbacks.onUrlClick,
```

**Step 3: Add `onUrlClick` param to ComposeChatPanel**

In `ComposeChatPanel.kt`, add parameter:
```kotlin
onUrlClick: ((String) -> Unit)? = null,
```

Pass it to `ComposeMessageList(...)`:
```kotlin
onUrlClick = onUrlClick,
```

**Step 4: Add `onUrlClick` param to ComposeMessageList**

In `ComposeMessageList.kt`, add parameter to both `ComposeMessageList` and inner composables, pass to `ComposeMessageBubble(...)`:
```kotlin
onUrlClick = onUrlClick,
```

**Step 5: Wire `onUrlClick` in ComposeMessageBubble**

In `ComposeMessageBubble.kt`, add `onUrlClick: ((String) -> Unit)? = null` parameter.

Pass to every `ComposeMarkdownContent(...)` call:
```kotlin
ComposeMarkdownContent(
    markdown = item.text,
    onUrlClick = onUrlClick ?: {},
    selectable = true,
)
```

Update all `ComposeMarkdownContent` call sites:
- `StreamingAssistantContent` (lines ~275, 279, 324): pass `onUrlClick`
- `FinishedAssistantContent` (line ~365): pass `onUrlClick`

**Step 6: Create the callback in ClaudeToolWindowFactory**

In `ClaudeToolWindowFactory.kt`, add to `callbacks = ChatCallbacks(...)` (after `onFileClick`):
```kotlin
onUrlClick = { url -> FilePathLinkHandler.handleUrlClick(url, project) },
```

Add import:
```kotlin
import ru.dsudomoin.claudecodegui.ui.compose.markdown.FilePathLinkHandler
```

**Step 7: Build**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

**Step 8: Commit**

```bash
git add src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/toolwindow/ComposeChatContainer.kt \
        src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/chat/ComposeChatPanel.kt \
        src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/chat/ComposeMessageList.kt \
        src/main/kotlin/ru/dsudomoin/claudecodegui/ui/compose/chat/ComposeMessageBubble.kt \
        src/main/kotlin/ru/dsudomoin/claudecodegui/ui/toolwindow/ClaudeToolWindowFactory.kt
git commit -m "feat: wire onUrlClick through chat UI for clickable file path links"
```

---

### Task 5: Full build and manual smoke test

**Step 1: Run full build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

**Step 2: Run all tests**

Run: `./gradlew test`
Expected: All tests PASS

**Step 3: Launch sandbox IDE**

Run: `./gradlew runIde`

Manual test checklist:
- [ ] Send a message that triggers Claude to mention a file path
- [ ] File paths in the response render as clickable links (blue/underline)
- [ ] Clicking a relative path opens the file in the editor
- [ ] Clicking a path with `:42` suffix navigates to line 42
- [ ] Absolute paths work
- [ ] Regular `http://` links still open in the browser
- [ ] Content inside fenced code blocks is NOT linkified
- [ ] Existing markdown links are not affected

**Step 4: Final commit (if adjustments needed)**

```bash
git add -A
git commit -m "fix: adjustments after smoke testing clickable file paths"
```
