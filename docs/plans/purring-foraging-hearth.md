# Локализация: замена хардкод-строк на message bundle

## Контекст
Все UI-строки (русские и английские) захардкожены прямо в Kotlin-файлах. Нужно вынести их в `messages/MyMessageBundle.properties` (уже есть bundle-класс `MyMessageBundle.kt` с `DynamicBundle`). Это даст единое место для всех строк и возможность добавить другие языки в будущем.

## Подход
Использовать существующий `MyMessageBundle.message("key")`. Ключи в формате `section.element`.

## Файлы для изменения

### 1. `src/main/resources/messages/MyMessageBundle.properties` — все ключи

```properties
# Tool display names
tool.bash=Выполняю команду
tool.write=Записываю файл
tool.create=Создаю файл
tool.edit=Редактирую файл
tool.replace=Заменяю строку
tool.read=Читаю файл
tool.grep=Поиск
tool.glob=Поиск файлов
tool.task=Задача
tool.webfetch=Загрузка URL
tool.websearch=Веб-поиск
tool.delete=Удаление
tool.notebook=Редактирую notebook
tool.todowrite=Список задач
tool.updatePlan=Обновление плана
tool.section.content=СОДЕРЖИМОЕ
tool.section.command=КОМАНДА

# Models
model.sonnet.desc=Модель по умолчанию
model.opus.desc=Новейшая и самая мощная
model.opus1m.desc=Opus 4.6 для длинных чатов
model.haiku.desc=Самая быстрая для быстрых ответов

# Permission modes
mode.default.name=Обычный
mode.default.full=Обычный режим
mode.default.desc=Требует ручного подтверждения каждой операции, подходит для осторожной работы
mode.plan.name=План
mode.plan.full=Режим планирования
mode.plan.desc=Использует только инструменты чтения, генерирует план для утверждения
mode.agent.name=Агент
mode.agent.full=Агентный режим
mode.agent.desc=Автоматически принимает создание/редактирование файлов, уменьшая кол-во запросов
mode.auto.name=Авто-режим
mode.auto.full=Авто-режим
mode.auto.desc=Полностью автоматический, пропускает все проверки разрешений

# Settings toggles
settings.streaming=Потоковый вывод
settings.thinking=Размышляю

# Chat
chat.placeholder=Ask AI Assistant, use @mentions
chat.roleUser=You
chat.roleAssistant=Claude
chat.newChat=New Chat
chat.tab=Chat

# Thinking panel
thinking.active=Thinking\u2026
thinking.done=Thinking (done)

# Input buttons
input.send=Send message (Enter)
input.stop=Stop generation (Esc)
input.attach=Attach image (or Ctrl+V)
input.settings=Settings
input.removeContext=Remove file context
input.chooseImage=Выберите изображение
input.openInEditor=Open in editor
input.remove=Remove

# Permission dialogs
permission.title=Permission Request
permission.allow=Allow
permission.deny=Deny
permission.wantsToUse=Claude wants to use: {0} \u2014 {1}
permission.current=Current
permission.proposed=Proposed by Claude

# Question selection
question.otherLabel=Другой вариант
question.otherDesc=Ввести свой ответ
question.otherAnswer=Ответ: {0}
question.cancel=Отмена
question.submit=Ответить
question.next=Далее

# History
history.back=Back to chat
history.title=History
history.refresh=Refresh
history.hint=Double-click to load session
history.deleteTitle=Delete Session

# Tool window
toolwindow.newChat=New Chat
toolwindow.newChatDesc=Start a new conversation
toolwindow.history=History
toolwindow.historyDesc=Show chat history

# Commit action
commit.notFound=Панель commit message не найдена
commit.noChanges=Нет изменений для коммита
commit.generating=Генерация commit message...
commit.success=Commit message сгенерирован
commit.error=Ошибка: {0}
commit.emptyResponse=Пустой ответ от Claude

# Settings page
settings.permDefault=Default (ask for permissions)
settings.permPlan=Plan (read-only, no writes)
settings.permBypass=Bypass Permissions (auto-approve all)
settings.loggedIn=Logged in via {0}
settings.expired=Session expired \u2014 run 'claude login' in terminal
settings.notLoggedIn=Not logged in \u2014 run 'claude login' in terminal
settings.authStatus=Auth Status:
```

### 2. Файлы с заменами (11 файлов)

| # | Файл | Что меняется |
|---|------|-------------|
| 1 | `ui/chat/ToolUseBlock.kt` | `TOOL_DISPLAY_NAMES` map → функция `getToolDisplayName()`, секции СОДЕРЖИМОЕ/КОМАНДА |
| 2 | `ui/input/ChatInputPanel.kt` | MODELS desc, PERMISSION_MODES строки, placeholder, тултипы, toggle labels, "Выберите изображение" |
| 3 | `ui/chat/MessageBubble.kt` | "You" / "Claude" |
| 4 | `ui/chat/ThinkingPanel.kt` | "Thinking…" / "Thinking (done)" |
| 5 | `ui/dialog/PermissionDialog.kt` | title, OK/Cancel |
| 6 | `ui/dialog/DiffPermissionDialog.kt` | title, OK/Cancel, diff side labels |
| 7 | `ui/dialog/QuestionSelectionPanel.kt` | "Другой вариант", "Ввести свой ответ", "Ответ:", "Отмена", "Ответить", "Далее" |
| 8 | `ui/toolwindow/ClaudeToolWindowFactory.kt` | "New Chat", "History", "Chat" |
| 9 | `ui/history/HistoryPanel.kt` | "Back to chat", "History", "Refresh", hint, "Delete Session" |
| 10 | `action/GenerateCommitAction.kt` | уведомления и сообщения об ошибках |
| 11 | `settings/ClaudeSettingsConfigurable.kt` | permission labels, auth status строки |

### 3. Паттерн замены

`TOOL_DISPLAY_NAMES` — из статического `mapOf` в функцию (bundle нельзя вызвать в companion init):
```kotlin
// Было:
private val TOOL_DISPLAY_NAMES = mapOf("bash" to "Выполняю команду", ...)

// Стало:
private fun getToolDisplayName(toolName: String): String = when (toolName) {
    "bash", "run_terminal_cmd", "execute_command", "shell_command" -> MyMessageBundle.message("tool.bash")
    "write", "write_to_file" -> MyMessageBundle.message("tool.write")
    ...
    else -> toolName
}
```

Аналогично для MODELS и PERMISSION_MODES в ChatInputPanel — desc поля станут вычисляемыми.

Простые строки:
```kotlin
// Было:
"Выберите изображение"
// Стало:
MyMessageBundle.message("input.chooseImage")
```

С параметрами:
```kotlin
// Было:
"Ошибка: ${ex.message}"
// Стало:
MyMessageBundle.message("commit.error", ex.message)
```

## Верификация
1. `./gradlew compileKotlin` — компиляция
2. Визуально убедиться, что все строки отображаются как раньше
