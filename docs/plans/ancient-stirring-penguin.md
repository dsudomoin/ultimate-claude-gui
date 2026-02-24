# Plan: Tool Use Grouping (Collapsing Consecutive Same-Type Tools)

## Context

Сейчас каждый вызов инструмента (Read, Edit, Bash, Grep, Glob) рендерится как отдельный `ToolUseBlock`. Когда Claude делает 5 Read подряд — в чате появляется 5 отдельных блоков, что визуально шумно. Нужно схлопывать их в один групповой блок, как в референсном плагине (`idea-claude-code-gui`).

**Цель:** Группировка 2+ последовательных tool_use одного типа в один компактный коллапсируемый блок с заголовком "Read (5)" и списком файлов/команд внутри.

## Approach

Вся логика группировки — в `MessageBubble`. Ни `ChatPanel`, ни `MessageListPanel` не меняются.

### Новые файлы
- `ToolGroupBlock.kt` — Swing-компонент для группы инструментов

### Изменяемые файлы
- `ToolUseBlock.kt` — добавить enum `ToolCategory` + классификатор; сделать `toolName`, `summary`, `input` не-private
- `MessageBubble.kt` — логика группировки в streaming и history путях; изменения в `addToolBlock()`, `completeToolBlock()`, `errorToolBlock()`
- `MyMessageBundle.properties` / `MyMessageBundle_ru.properties` — ключи локализации для групп

## Step 1: ToolCategory + classifier в ToolUseBlock.kt

Добавить в `companion object` класса `ToolUseBlock`:

```kotlin
enum class ToolCategory { READ, EDIT, BASH, SEARCH, OTHER }

fun getToolCategory(toolName: String): ToolCategory {
    val lower = toolName.lowercase()
    return when {
        lower in setOf("read", "read_file") -> ToolCategory.READ
        lower in setOf("edit", "edit_file", "replace_string", "write", "write_to_file",
                        "create_file", "save-file") -> ToolCategory.EDIT
        lower in setOf("bash", "run_terminal_cmd", "execute_command", "executecommand",
                        "shell_command") -> ToolCategory.BASH
        lower in setOf("grep", "search", "glob", "find", "list", "listfiles") -> ToolCategory.SEARCH
        else -> ToolCategory.OTHER
    }
}
```

Сделать `toolName`, `summary`, `input` видимыми (убрать `private`).

## Step 2: Создать ToolGroupBlock.kt

Новый Swing-компонент — коллапсируемый блок, содержащий N tool items.

**Структура:**
```
ToolGroupBlock (JPanel, BorderLayout, rounded corners как у ToolUseBlock)
├── headerPanel (custom-painted, 36px)
│   [chevron] [icon] "Read (5)"  [aggregate +N -N для edit] [status dot]
└── itemsContainer (VerticalFlowLayout, visible при expanded)
    ├── ToolGroupItemRow — icon + filename/command + status dot
    ├── ToolGroupItemRow — ...
    └── ...
```

**API:**
```kotlin
class ToolGroupBlock(category: ToolCategory, project: Project?) {
    fun addItem(id: String, toolName: String, summary: String, input: JsonObject)
    fun completeItem(id: String, content: String?)
    fun errorItem(id: String, content: String?)
    fun containsItem(id: String): Boolean
    val itemCount: Int
}
```

**Поведение:**
- По умолчанию **свёрнут** (только заголовок)
- Клик по заголовку — toggle expand/collapse
- Breathing animation пока есть PENDING элементы
- Max 3 видимых строк, дальше scroll
- Auto-scroll к последнему элементу при стриминге
- Каждая строка: иконка файла + имя + line info/diff stats + status dot
- Для edit-группы: суммарные +N -N в заголовке; кнопки diff/refresh per item

## Step 3: Streaming path — MessageBubble.addToolBlock()

Добавить состояние:
```kotlin
private var lastToolCategory: ToolCategory? = null
private val toolGroups = mutableMapOf<String, ToolGroupBlock>()  // toolId → group
private var activeGroup: ToolGroupBlock? = null
```

**Алгоритм при добавлении нового tool:**

```
1. Определить category = getToolCategory(toolName)
2. Проверить: последний текстовый сегмент пуст? (lastSegmentEmpty)
3. IF category != OTHER && category == lastToolCategory && lastSegmentEmpty:
   a. IF activeGroup != null (уже есть группа):
      → activeGroup.addItem(id, toolName, summary, input)
      → toolGroups[id] = activeGroup
      → Добавить пустой text segment, revalidate
   b. ELSE (предыдущий был standalone ToolUseBlock того же типа):
      → Создать ToolGroupBlock(category)
      → Найти предыдущий ToolUseBlock в contentFlowContainer, заменить его на группу
      → Перенести данные предыдущего блока в группу
      → Добавить текущий tool в группу
      → activeGroup = новая группа
      → toolGroups[prevId] = group; toolGroups[id] = group
4. ELSE:
   → activeGroup = null (сброс активной группы)
   → Создать обычный ToolUseBlock (текущее поведение)
   → lastToolCategory = category
```

## Step 4: Status update path — MessageBubble

Изменить `completeToolBlock()` и `errorToolBlock()`:
```kotlin
fun completeToolBlock(id: String, resultContent: String?) {
    toolBlocks[id]?.let { block ->
        block.status = ToolUseBlock.Status.COMPLETED
        if (resultContent != null) block.setResultContent(resultContent)
        return
    }
    toolGroups[id]?.completeItem(id, resultContent)
}
```

Аналогично для `errorToolBlock()`.

## Step 5: History path — MessageBubble init (non-streaming)

В блоке `message.role == Role.ASSISTANT && project != null` (строки 152-191):

Заменить последовательный рендер на аккумулятор:
- Вести `currentGroup: ToolGroupBlock?` и `currentGroupCategory`
- При встрече ToolUse той же категории — добавлять в группу
- При смене категории или Text-блоке — flush группу в layout
- При flush группы из 1 элемента — рендерить как обычный ToolUseBlock
- ToolResult сопоставлять по предыдущему ToolUse (как сейчас) + через toolResultMap

## Step 6: Localization

```properties
# MyMessageBundle.properties
tool.group.read=Read ({0})
tool.group.edit=Edit ({0})
tool.group.bash=Commands ({0})
tool.group.search=Search ({0})

# MyMessageBundle_ru.properties
tool.group.read=Чтение ({0})
tool.group.edit=Редактирование ({0})
tool.group.bash=Команды ({0})
tool.group.search=Поиск ({0})
```

## Verification

1. **Streaming:** Начать чат, попросить Claude исследовать проект — увидеть как Read-блоки схлопываются в группу по мере прихода
2. **History:** Перезагрузить окно, открыть сохранённую сессию — группы должны отрисоваться для завершённых сообщений
3. **Одиночные тулы:** Одиночный Read/Edit/Bash — должен рендериться как обычный ToolUseBlock (без группы)
4. **Смешанные:** Read, Read, Text, Read — первые два в группу, третий отдельно (текст разрывает группу)
5. **Статусы:** Pending (breathing), Completed (зелёный), Error (красный) — для каждого элемента в группе
6. **Expand/Collapse:** Клик по заголовку группы переключает видимость списка
7. **Build:** `./gradlew buildPlugin` без ошибок
