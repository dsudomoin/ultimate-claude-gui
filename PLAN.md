# Plan: Syntax highlighting in diff view

## Changes

### 1. `ExpandableContent.Diff` — add `filePath`
Add `filePath: String? = null` to determine the language for syntax highlighting.

### 2. New: `highlightCodeLine()` utility in `ComposeToolUseBlock.kt`
Use IntelliJ's `SyntaxHighlighterFactory` + `Lexer` API to tokenize each diff line and build `AnnotatedString` with proper colors from `EditorColorsManager.globalScheme`.

```
FileTypeManager.getInstance().getFileTypeByFileName(fileName)
→ SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, null, null)
→ highlighter.highlightingLexer.start(lineText)
→ iterate tokens, resolve colors via scheme.getAttributes(key)
→ buildAnnotatedString with SpanStyle(color = ...)
```

Use `java.awt.Color.toComposeColor()` from existing `ColorExtensions.kt`.

### 3. Update `DiffContentPanel` composable
- Get `SyntaxHighlighter` from file path (cached via `remember`)
- For each diff line, render `AnnotatedString` instead of plain `Text`
- Fallback to `colors.textPrimary` when no highlighter available

### 4. Pass `filePath` when creating `ExpandableContent.Diff`
Update 3 places:
- `ChatController.buildExpandableContent()`
- `ComposeMessageBubble.buildFinishedExpandable()`
- Extract from tool input: `file_path` / `path` / `target_file`

## Files to modify
- `ComposeToolUseBlock.kt` — diff renderer + highlighting utility
- `ChatController.kt` — pass filePath to Diff
- `ComposeMessageBubble.kt` — pass filePath to Diff
