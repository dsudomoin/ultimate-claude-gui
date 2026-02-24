# Slash-команды в чате с автокомплитом

## Контекст

Нужно добавить поддержку slash-команд в чате: `/clear`, `/init`, `/compact`, `/help` и др. Когда пользователь набирает `/` — появляется popup с автокомплитом. Скиллы тоже должны работать. SDK-команды (`/init`, `/compact`, `/review`) уже работают если отправить текст — SDK обрабатывает их нативно. Нужно добавить UI-слой: автокомплит, локальную обработку, маршрутизацию.

---

## Шаг 1: Реестр команд — NEW `command/SlashCommand.kt`

Создать файл с определениями всех доступных команд:

```kotlin
package ru.dsudomoin.claudecodegui.command

enum class CommandCategory { LOCAL, SDK }

data class SlashCommand(
    val name: String,           // "/clear"
    val descKey: String,        // "cmd.clear.desc" → bundle key
    val category: CommandCategory
)

object SlashCommandRegistry {
    private val commands = listOf(
        // Локальные (обрабатываются в плагине)
        SlashCommand("/clear", "cmd.clear.desc", CommandCategory.LOCAL),
        SlashCommand("/help", "cmd.help.desc", CommandCategory.LOCAL),

        // SDK (отправляются как текст в SDK)
        SlashCommand("/init", "cmd.init.desc", CommandCategory.SDK),
        SlashCommand("/compact", "cmd.compact.desc", CommandCategory.SDK),
        SlashCommand("/review", "cmd.review.desc", CommandCategory.SDK),
        SlashCommand("/model", "cmd.model.desc", CommandCategory.SDK),
    )

    fun all(): List<SlashCommand> = commands
    fun filter(prefix: String): List<SlashCommand> =
        commands.filter { it.name.startsWith(prefix, ignoreCase = true) }
    fun find(name: String): SlashCommand? =
        commands.find { it.name.equals(name, ignoreCase = true) }
}
```

---

## Шаг 2: Popup автокомплита — NEW `ui/input/SlashCommandPopup.kt`

Popup на основе `JPopupMenu`, стилизованный так же как `showModelPopup()` / `showModePopup()` в `ChatInputPanel.kt` (строки 1000-1056) — те же цвета `DROPDOWN_BG`, `DROPDOWN_BORDER`, `DROPDOWN_HOVER`, `createTwoLinePopupItem()`.

Логика:
- Показывается **над** текстовым полем (как model/mode popup)
- Каждый элемент: имя команды (bold) + описание (gray)
- Навигация: Up/Down для перемещения, Enter/Tab для выбора, Esc для закрытия
- При выборе — вставляет команду в текстовое поле и отправляет

```kotlin
class SlashCommandPopup(
    private val parent: JComponent,
    private val onSelect: (SlashCommand) -> Unit
) {
    private var popup: JPopupMenu? = null
    private var selectedIndex = 0
    private var currentItems: List<SlashCommand> = emptyList()

    fun show(commands: List<SlashCommand>, anchor: JComponent) { ... }
    fun hide() { ... }
    fun isVisible(): Boolean = popup?.isVisible == true
    fun moveUp() { ... }
    fun moveDown() { ... }
    fun selectCurrent() { ... }
}
```

---

## Шаг 3: Интеграция в `ChatInputPanel.kt` — MODIFY

### 3a. DocumentListener на textArea
Добавить `DocumentListener`, который при каждом изменении текста проверяет: если текст начинается с `/` и курсор после `/` — показать popup с фильтрованными командами.

```kotlin
textArea.document.addDocumentListener(object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent) = checkSlashPrefix()
    override fun removeUpdate(e: DocumentEvent) = checkSlashPrefix()
    override fun changedUpdate(e: DocumentEvent) = checkSlashPrefix()
})
```

`checkSlashPrefix()`:
- Берёт текст из textArea
- Если начинается с `/` → `SlashCommandRegistry.filter(text)` → показать popup
- Если нет `/` или список пуст → скрыть popup

### 3b. KeyListener перехват для popup навигации
В существующем `KeyAdapter` (строки 424-441) добавить проверку: если popup открыт, перехватить Up/Down/Tab/Enter/Esc:

```kotlin
// Перед существующими when-ветками:
if (slashPopup.isVisible()) {
    when (e.keyCode) {
        VK_UP -> { e.consume(); slashPopup.moveUp() }
        VK_DOWN -> { e.consume(); slashPopup.moveDown() }
        VK_TAB, VK_ENTER -> { e.consume(); slashPopup.selectCurrent() }
        VK_ESCAPE -> { e.consume(); slashPopup.hide() }
    }
    return
}
```

### 3c. Callback при выборе команды
При выборе команды из popup:
- Вставить полное имя команды в textArea
- Вызвать `sendMessage()` (отправить как обычное сообщение)

---

## Шаг 4: Маршрутизация в `ChatPanel.kt` — MODIFY

В `onSendMessage()` (строка 311) добавить перехват slash-команд **перед** текущей логикой:

```kotlin
private fun onSendMessage(text: String) {
    // Slash command routing
    val trimmed = text.trim()
    if (trimmed.startsWith("/")) {
        val cmdName = trimmed.split(" ").first()
        val cmd = SlashCommandRegistry.find(cmdName)
        if (cmd != null && cmd.category == CommandCategory.LOCAL) {
            executeLocalCommand(cmd, trimmed)
            return
        }
        // SDK commands and unknown commands — fall through to doSendMessage()
    }

    // Existing logic...
    if (currentJob?.isActive == true) { ... }
    doSendMessage(text)
}
```

### Локальные команды:
```kotlin
private fun executeLocalCommand(cmd: SlashCommand, fullText: String) {
    when (cmd.name) {
        "/clear" -> {
            newChat()
            addSystemMessage(MyMessageBundle.message("cmd.clear.done"))
        }
        "/help" -> {
            addSystemMessage(buildHelpText())
        }
    }
}
```

### Системные сообщения:
Добавить `addSystemMessage(text)` в `MessageListPanel` — визуально отличный от user/assistant блок (мелкий серый текст по центру, как разделитель).

### SDK-команды:
Пропускаются в `doSendMessage()` как обычный текст. В `doSendMessage()` добавить проверку: если текст начинается с `/` — **не добавлять IDE контекст** (file context не нужен для команд):

```kotlin
// В doSendMessage(), строка 331:
if (!text.startsWith("/") && !text.startsWith("From `") && ...) {
    val ctx = getActiveFileContext()
    ...
}
```

---

## Шаг 5: Bundle ключи — MODIFY

### `MyMessageBundle.properties`:
```properties
# Slash commands
cmd.clear.desc=Clear chat history
cmd.help.desc=Show available commands
cmd.init.desc=Generate CLAUDE.md for your project
cmd.compact.desc=Compact conversation context
cmd.review.desc=Review recent code changes
cmd.model.desc=Switch Claude model
cmd.clear.done=Chat cleared.
cmd.help.title=Available commands:
```

### `MyMessageBundle_ru.properties`:
```properties
# Slash commands
cmd.clear.desc=Очистить историю чата
cmd.help.desc=Показать доступные команды
cmd.init.desc=Сгенерировать CLAUDE.md для проекта
cmd.compact.desc=Сжать контекст разговора
cmd.review.desc=Ревью последних изменений
cmd.model.desc=Сменить модель Claude
cmd.clear.done=Чат очищен.
cmd.help.title=Доступные команды:
```

---

## Файлы — сводка

| Действие | Файл |
|----------|------|
| NEW | `command/SlashCommand.kt` — реестр команд |
| NEW | `ui/input/SlashCommandPopup.kt` — popup автокомплита |
| MODIFY | `ui/input/ChatInputPanel.kt` — DocumentListener + KeyListener + popup |
| MODIFY | `ui/chat/ChatPanel.kt` — маршрутизация + executeLocalCommand |
| MODIFY | `ui/chat/MessageListPanel.kt` — addSystemMessage() |
| MODIFY | `resources/messages/MyMessageBundle.properties` — ключи команд |
| MODIFY | `resources/messages/MyMessageBundle_ru.properties` — ключи команд |

---

## Верификация

1. `./gradlew build` — компиляция без ошибок
2. `./gradlew runIde`:
   - Ввести `/` → появляется popup со всеми командами
   - Ввести `/cl` → popup фильтруется до `/clear`
   - Up/Down навигация в popup
   - Enter/Tab — выбор и отправка
   - Esc — закрытие popup
   - `/clear` — чат очищается, показывается системное сообщение
   - `/help` — показывается список команд
   - `/init` — отправляется в SDK, генерируется CLAUDE.md
   - `/compact` — отправляется в SDK, контекст сжимается
   - Обычный текст — отправляется как раньше, popup не появляется
