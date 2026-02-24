# Переход на CLI-хранилище сессий (~/.claude/projects/)

## Context
Сейчас плагин хранит сессии в собственном `~/.claude-code-gui/sessions/` с отдельными `.meta` файлами. Это дублирование — CLI уже хранит всё в `~/.claude/projects/`. Нужно переделать как в референсе: читать сессии напрямую из CLI-хранилища. Тогда:
- Сессии CLI видны в плагине
- Resume работает корректно (общий sessionId)
- Нет двойного хранения

## CLI session format

Путь: `~/.claude/projects/<sanitized-path>/<sessionId>.jsonl`
- Sanitization: все не-alphanumeric символы → `-` (например `/Users/foo/bar` → `-Users-foo-bar`)
- Каждая строка — JSON объект с `type`: `user`, `assistant`, `queue-operation`
- Поля: `uuid`, `sessionId`, `timestamp` (ISO 8601), `message.role`, `message.content`, `slug`
- `slug` — человекочитаемое имя (e.g. "peaceful-growing-wombat")
- `queue-operation` — пропускать (технические маркеры enqueue/dequeue)

## Файлы для изменения

### 1. `core/session/SessionStorage.kt` — полная переделка

Вместо `~/.claude-code-gui/sessions/<hash>/` → читать из `~/.claude/projects/<sanitized-path>/`.

```kotlin
object SessionStorage {
    private val claudeDir = File(System.getProperty("user.home"), ".claude/projects")

    // Sanitize: /Users/foo/bar → -Users-foo-bar
    fun projectDir(projectPath: String): File {
        val sanitized = projectPath.replace(Regex("[^a-zA-Z0-9]"), "-")
        return File(claudeDir, sanitized)
    }

    // List sessions: scan .jsonl files, extract metadata from content
    fun listSessions(projectPath: String): List<SessionInfo> {
        // Сканировать .jsonl файлы (не рекурсивно, agent-*.jsonl пропускать)
        // Для каждого файла: прочитать первые строки для title/slug,
        // последнюю строку для timestamp
        // Фильтр: пропускать сессии с < 2 сообщениями
    }

    // Load session: parse CLI JSONL → List<Message>
    fun load(projectPath: String, sessionId: String): List<Message>? {
        // Парсить каждую строку:
        // - type=user → Role.USER, message.content → ContentBlock
        // - type=assistant → Role.ASSISTANT, message.content → ContentBlock
        // - type=queue-operation → пропустить
        // Конвертировать content: [{type:"text", text:"..."}, {type:"thinking",...}, {type:"tool_use",...}]
    }

    // Delete: удалить .jsonl файл + директорию сессии (subagents, tool-results)
    fun delete(projectPath: String, sessionId: String) { ... }

    // Save и updateTitle — УБРАТЬ (SDK сохраняет сам)
}
```

**Убрать:** `save()`, `updateTitle()`, `getTitle()`, `getUsedTokens()`, `SessionMeta`, `.meta` файлы.

**Парсинг title:** из `slug` поля (если есть) или из первого user message `message.content[0].text`.

**SessionInfo:** обновить:
```kotlin
data class SessionInfo(
    val sessionId: String,
    val title: String,
    val lastTimestamp: Long,    // вместо createdAt — timestamp последнего сообщения
    val messageCount: Int,      // только user + assistant (без queue-operation)
    val slug: String? = null    // для отображения
)
```

### 2. `core/session/SessionManager.kt` — упрощение

```kotlin
@Service(Service.Level.PROJECT)
class SessionManager(private val project: Project) {
    private val projectPath get() = project.basePath ?: project.name

    // Убрать createSession() — SDK создаёт сессии
    // Убрать save() — SDK сохраняет
    // Убрать updateTitle() — нет .meta файлов в CLI

    fun listSessions() = SessionStorage.listSessions(projectPath)
    fun load(sessionId: String) = SessionStorage.load(projectPath, sessionId)
    fun delete(sessionId: String) = SessionStorage.delete(projectPath, sessionId)
    fun getTitle(sessionId: String): String? = SessionStorage.getTitle(projectPath, sessionId)
}
```

### 3. `provider/claude/ClaudeProvider.kt` — добавить метод для resume

Сейчас `sessionId` имеет private set. Нужно добавить:
```kotlin
// Для resume из истории: установить sessionId до отправки сообщения
fun setResumeSessionId(id: String) {
    sessionId = id
}
```

### 4. `ui/chat/ChatPanel.kt` — убрать save, починить resume

- **Убрать** `saveSession()` — два вызова (после StreamEnd и после CancellationException)
- **Убрать** `sessionId = sessionManager.createSession()` из конструктора
- **`sessionId`** — nullable, берётся из `ClaudeProvider.sessionId` после первого ответа SDK
- **`loadSession()`** — загрузить сообщения из CLI + вызвать `provider.setResumeSessionId(sessionId)` для resume
- **`newChat()`** — `provider.resetSession()` (уже есть)
- **`sessionTitle`** — использовать slug из SessionInfo или первое сообщение

### 5. `ui/history/HistoryPanel.kt` — минимальные изменения

- `createdAt` → `lastTimestamp` в отображении (сортировка и relative time)
- Убрать то, что зависит от `SessionManager.save/updateTitle`
- `deleteSelected()` — работает (SessionStorage.delete удаляет CLI файл)

## Парсинг CLI JSONL → Message

Ключевая функция конвертации (в SessionStorage):

```kotlin
private fun parseCliLine(line: String): Pair<Message?, CliMeta?> {
    val json = Json.parseToJsonElement(line).jsonObject
    val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return null to null

    when (type) {
        "user", "assistant" -> {
            val role = if (type == "user") Role.USER else Role.ASSISTANT
            val timestamp = parseIsoTimestamp(json["timestamp"])
            val slug = json["slug"]?.jsonPrimitive?.contentOrNull
            val messageObj = json["message"]?.jsonObject ?: return null to null
            val contentArray = messageObj["content"]?.jsonArray ?: return null to null

            val blocks = contentArray.mapNotNull { block ->
                val blockObj = block.jsonObject
                when (blockObj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> ContentBlock.Text(blockObj["text"]?.jsonPrimitive?.content ?: "")
                    "thinking" -> ContentBlock.Thinking(blockObj["thinking"]?.jsonPrimitive?.content ?: "")
                    "tool_use" -> ContentBlock.ToolUse(
                        id = blockObj["id"]?.jsonPrimitive?.content ?: "",
                        name = blockObj["name"]?.jsonPrimitive?.content ?: "",
                        input = blockObj["input"]?.jsonObject ?: JsonObject(emptyMap())
                    )
                    "tool_result" -> null // пропускаем tool results (они в user messages)
                    else -> null
                }
            }

            return Message(role, blocks, timestamp) to CliMeta(slug)
        }
        "queue-operation" -> return null to null
        else -> return null to null
    }
}
```

## Верификация
1. `./gradlew build -x test` — компиляция
2. History → показывает сессии из `~/.claude/projects/` (те же что CLI)
3. Открыть сессию из истории → сообщения отображаются корректно
4. Отправить сообщение в открытой сессии → resume работает (контекст сохраняется)
5. New Chat → новая сессия → sessionId приходит от SDK
6. Удалить сессию → файл удаляется из `~/.claude/projects/`
