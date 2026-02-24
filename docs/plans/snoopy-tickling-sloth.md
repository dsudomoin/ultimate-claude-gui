# Подсветка синтаксиса в Edit-блоках

## Context

Edit-блоки в чате показывают diff (old_string / new_string) простым моноширинным текстом без подсветки синтаксиса. Нужно добавить подсветку, используя IntelliJ EditorEx API, который уже используется в проекте для блоков кода в MarkdownRenderer.

## Подход

Заменить текущую grid-based реализацию diff-панели (JBTextArea на каждую строку) на **единый EditorEx viewer** с:
- Подсветкой синтаксиса на основе расширения файла из `file_path`
- Line-level фоновой подсветкой через `MarkupModel` для added/deleted строк
- Gutter-рендерером для маркеров `+`/`-`

### Почему один EditorEx, а не по одному на строку

Создание EditorEx — дорогая операция. Один editor на весь diff — оптимальный баланс между качеством подсветки и производительностью. Хотя в document будут и старые, и новые строки одновременно, лексер работает построчно, поэтому подсветка будет корректной для каждой отдельной строки.

## Файлы для изменения

### 1. `src/main/kotlin/ru/dsudomoin/claudecodegui/ui/chat/ToolUseBlock.kt`

**Метод `createDiffPanel()` (строки ~628-729)** — полностью переписать:

1. Вычислить diff через существующий `computeDiff(oldStr, newStr)`
2. Собрать текст документа из всех `DiffLine.content` (без маркеров `+`/`-`), соединённых `\n`
3. Определить `FileType` из `filePath`:
   ```kotlin
   val fileType = filePath?.let {
       FileTypeManager.getInstance().getFileTypeByFileName(it)
   } ?: PlainTextFileType.INSTANCE
   ```
4. Создать `EditorEx` viewer (по образцу `MarkdownRenderer.renderCodeBlock`):
   ```kotlin
   val document = EditorFactory.getInstance().createDocument(diffText)
   val editor = EditorFactory.getInstance().createViewer(document, project)
   (editor as? EditorEx)?.apply {
       highlighter = EditorHighlighterFactory.getInstance()
           .createEditorHighlighter(project, fileType)
       settings.apply {
           isLineNumbersShown = false
           isFoldingOutlineShown = false
           additionalLinesCount = 0
           additionalColumnsCount = 0
           isCaretRowShown = false
           isRightMarginShown = false
       }
       setVerticalScrollbarVisible(false)
       setHorizontalScrollbarVisible(false)
   }
   ```
5. Добавить line-level подсветку через `MarkupModel`:
   ```kotlin
   val markupModel = editor.markupModel
   for ((lineIdx, diffLine) in diffLines.withIndex()) {
       val attr = when (diffLine.type) {
           DiffLineType.ADDED -> TextAttributes().apply { backgroundColor = DIFF_ADD_BG_SOLID }
           DiffLineType.DELETED -> TextAttributes().apply { backgroundColor = DIFF_DEL_BG_SOLID }
           DiffLineType.UNCHANGED -> null
       }
       if (attr != null) {
           markupModel.addLineHighlighter(lineIdx, HighlighterLayer.SELECTION - 1, attr)
       }
   }
   ```
6. Добавить gutter renderer для маркеров `+`/`-`/` ` через `LineMarkerRenderer` или `GutterIconRenderer`
7. Добавить `HierarchyListener` для освобождения editor (как в MarkdownRenderer, строки 245-253)
8. Сохранить ссылку на editor в поле класса для dispose

**Новые цвета** — добавить непрозрачные варианты для EditorEx (текущие с alpha 77 не подойдут для TextAttributes):
```kotlin
private val DIFF_ADD_BG_EDITOR = JBColor(Color(0xE6, 0xFF, 0xEC), Color(0x1A, 0x3A, 0x1A))
private val DIFF_DEL_BG_EDITOR = JBColor(Color(0xFF, 0xE6, 0xE6), Color(0x3A, 0x1A, 0x1A))
```

**Метод `removeFromParentAndDispose()`** — добавить освобождение editor:
```kotlin
fun removeFromParentAndDispose() {
    breathingTimer.stop()
    diffEditor?.let { EditorFactory.getInstance().releaseEditor(it) }
    parent?.remove(this)
}
```

### Переиспользуемый код

- `MarkdownRenderer.renderCodeBlock()` — паттерн создания EditorEx viewer (строки 211-253)
- `FileTypeManager.getInstance().getFileTypeByFileName()` — определение типа файла
- `ToolUseBlock.filePath` (строка 196) — уже извлекается из input
- `ToolUseBlock.computeDiff()` — существующий алгоритм LCS-diff (строки ~777-817)

## Верификация

1. Запустить `./gradlew compileKotlin` — убедиться что компилируется
2. Запустить плагин (Run Plugin) и отправить запрос на редактирование файла
3. Проверить что edit-блок показывает diff с подсветкой синтаксиса
4. Проверить что добавленные строки подсвечены зелёным фоном, удалённые — красным
5. Проверить что блок корректно разворачивается/сворачивается
6. Проверить что при закрытии чата нет утечек editor-ов
