# Clickable File Paths in Chat Messages

## Problem

Chat messages from Claude often contain file paths (e.g., `src/main/kotlin/File.kt`, `/Users/.../File.kt:42`), but they render as plain text. Users cannot click on them to navigate to the file in the IDE.

## Solution

A custom CommonMark `PostProcessor` extension for Jewel Markdown that detects file paths in text nodes and converts them to clickable links. Clicking opens the file in the IDE editor, optionally navigating to a specific line.

## Approach: CommonMark PostProcessor Extension (Autolink Pattern)

Uses the same pattern as Jewel's built-in `AutolinkProcessorExtension` — a parser-only extension with no custom renderer. Jewel already renders `Link` nodes as clickable elements via `onUrlClick`.

### Components

#### 1. `FilePathPostProcessor` (CommonMark `PostProcessor`)

Walks the AST after parsing. For each `Text` node:

1. Scans for file path patterns using regex
2. Splits the text node into segments: plain text + link nodes
3. Creates `Link` nodes with `ide-file://` URI scheme

**Regex pattern:**

```
(?:(?:/[\w.\-@]+)+|(?:[\w.\-@]+/)+[\w.\-@]+)(?:\.\w+)(?::\d+)?
```

Matches:
- Absolute paths: `/Users/user/project/src/File.kt`, `/Users/user/project/src/File.kt:42`
- Relative paths: `src/main/kotlin/File.kt`, `doc/gamer.md`, `shared/gamer/gamer-service/.../GamerServiceImpl.kt`
- With line numbers: `File.kt:42`, `src/main/File.kt:123`

Does NOT match:
- Bare filenames without path separator: `File.kt`
- URLs: `http://example.com/path` (contain `://` — excluded by negative lookbehind or pre-check)
- Already-linked text (inside existing `Link` nodes)
- Content inside `FencedCodeBlock`, `IndentedCodeBlock`

**URI format:** `ide-file:///absolute/path/to/file?line=42`

- Absolute paths: used as-is
- Relative paths: resolved at click time against `project.basePath`

#### 2. `FilePathProcessorExtension` (Jewel `MarkdownProcessorExtension`)

```kotlin
object FilePathProcessorExtension : MarkdownProcessorExtension {
    override val parserExtension = FilePathParserExtension  // registers PostProcessor
    override val textRendererExtension = null
    override val blockProcessorExtension = null
}
```

Simple singleton. Only provides `parserExtension` — no block processing or custom rendering needed.

#### 3. `FilePathLinkHandler`

Utility that handles `onUrlClick` routing:

- `ide-file://` scheme → parse path and line → `OpenFileDescriptor(project, vFile, line - 1, 0).navigate(true)`
- `http://` / `https://` → `BrowserUtil.browse(url)`
- Relative path resolution: `project.basePath + "/" + relativePath`
- VirtualFile lookup: `LocalFileSystem.getInstance().findFileByPath(absolutePath)`

### Data Flow

```
Markdown: "Modified src/main/File.kt:42"
  │
  ▼ CommonMark Parser
AST: Paragraph → Text("Modified src/main/File.kt:42")
  │
  ▼ FilePathPostProcessor
AST: Paragraph → Text("Modified ") → Link(dest="ide-file:///abs/src/main/File.kt?line=42", text="src/main/File.kt:42")
  │
  ▼ Jewel MarkdownBlockRenderer
Compose: Text("Modified ") + ClickableLink("src/main/File.kt:42")
  │
  ▼ User click → onUrlClick("ide-file:///abs/src/main/File.kt?line=42")
  │
  ▼ FilePathLinkHandler
FileEditorManager → opens File.kt at line 42
```

### Files Changed

| File | Change |
|------|--------|
| **NEW** `ui/compose/markdown/FilePathProcessorExtension.kt` | PostProcessor + Extension object + ParserExtension |
| **NEW** `ui/compose/markdown/FilePathLinkHandler.kt` | URI parsing + file opening logic |
| `ui/compose/common/ComposePanelHost.kt` | Add `FilePathProcessorExtension` to processor extensions list |
| `ui/compose/chat/ComposeMessageBubble.kt` | Wire `onUrlClick` callback with routing through `FilePathLinkHandler` |

### Edge Cases

1. **Path doesn't exist on disk** — link renders but click does nothing (or shows notification)
2. **Ambiguous paths** — `File.kt:42` could be a path or just text; we require at least one `/` to avoid false positives
3. **Paths inside fenced code blocks** — PostProcessor skips `FencedCodeBlock` and `IndentedCodeBlock` nodes
4. **Already-linked text** — PostProcessor skips children of `Link` nodes
5. **Paths with spaces** — not supported initially (rare in code projects)
6. **Truncated paths** — `shared/gamer/.../File.kt` — the `...` portion is skipped; match the surrounding segments

### Testing Strategy

- Unit tests for regex pattern matching (positive and negative cases)
- Unit tests for `FilePathPostProcessor` AST transformation
- Unit tests for URI parsing in `FilePathLinkHandler`
- Manual testing in sandbox IDE with real chat messages
