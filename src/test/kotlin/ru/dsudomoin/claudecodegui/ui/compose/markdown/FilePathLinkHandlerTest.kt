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

    // ── parsePathAndLine tests ─────────────────────────────────────────────

    @Test
    fun `parsePathAndLine extracts line number`() {
        val (path, line) = FilePathLinkHandler.parsePathAndLine("src/main/File.kt:42")
        assertEquals("src/main/File.kt", path)
        assertEquals(42, line)
    }

    @Test
    fun `parsePathAndLine with no line number`() {
        val (path, line) = FilePathLinkHandler.parsePathAndLine("src/main/File.kt")
        assertEquals("src/main/File.kt", path)
        assertNull(line)
    }

    @Test
    fun `parsePathAndLine with bare filename and line`() {
        val (path, line) = FilePathLinkHandler.parsePathAndLine("File.kt:10")
        assertEquals("File.kt", path)
        assertEquals(10, line)
    }

    @Test
    fun `parsePathAndLine ignores colon without digits`() {
        val (path, line) = FilePathLinkHandler.parsePathAndLine("File.kt:")
        assertEquals("File.kt:", path)
        assertNull(line)
    }

    @Test
    fun `parsePathAndLine with absolute path and line`() {
        val (path, line) = FilePathLinkHandler.parsePathAndLine("/Users/user/project/File.kt:100")
        assertEquals("/Users/user/project/File.kt", path)
        assertEquals(100, line)
    }
}
