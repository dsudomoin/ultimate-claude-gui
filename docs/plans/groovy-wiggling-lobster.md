# Отображение очереди сообщений с поддержкой скриншотов

## Context

Когда пользователь отправляет сообщение во время стриминга, оно добавляется в `messageQueue`, но:
1. **Баг**: изображения теряются — `consumeAttachedImages()` вызывается только при `doSendMessage`, а не при постановке в очередь
2. **Нет UI**: пользователь не видит, что сообщения в очереди, и не может их удалить

## Изменения

### 1. Модель данных очереди — `ChatPanel.kt`

Заменить `ConcurrentLinkedQueue<String>` на:

```kotlin
data class QueuedMessage(val text: String, val images: List<File>)

private val messageQueue = ConcurrentLinkedQueue<QueuedMessage>()
```

### 2. Новый файл `QueuePanel.kt`

Панель отображается между `statusPanel` и `inputContainer`. Скрыта, когда очередь пуста.

**Структура каждой строки:**
```
[#1 badge] [thumb 24px] [thumb] "Текст сообщения обрезан…" [✕]
```

- Заголовок "В очереди (N)" — жирный мелкий шрифт
- Badge с номером — accent color с прозрачным фоном
- Thumbnails 24x24 — до 3 шт, потом "+N"
- Текст — однострочный, обрезанный с `…`
- Кнопка удаления — `AllIcons.Actions.Close` с hover

Рисуется через `paintComponent` (custom painting), как `StatusPanel` и `ToolGroupBlock`.

### 3. Изменения в `ChatPanel.kt`

**`onSendMessage`** — захватывать изображения при постановке в очередь:
```kotlin
private fun onSendMessage(text: String) {
    if (currentJob?.isActive == true) {
        val images = inputPanel.consumeAttachedImages()
        messageQueue.add(QueuedMessage(text, images))
        queuePanel.rebuild(messageQueue.toList())
        return
    }
    doSendMessage(text)
}
```

**`doSendMessage`** — принимать опциональные изображения из очереди:
```kotlin
private fun doSendMessage(text: String, preAttachedImages: List<File>? = null) {
    val images = preAttachedImages ?: inputPanel.consumeAttachedImages()
    // ... остальное без изменений
}
```

**`processQueue`** — передавать изображения и обновлять панель:
```kotlin
private fun processQueue() {
    val next = messageQueue.poll() ?: return
    queuePanel.rebuild(messageQueue.toList())
    doSendMessage(next.text, next.images)
}
```

**`removeFromQueue(index)`** — удаление по клику:
```kotlin
fun removeFromQueue(index: Int) {
    val list = messageQueue.toMutableList()
    if (index in list.indices) {
        list.removeAt(index)
        messageQueue.clear()
        list.forEach { messageQueue.add(it) }
        queuePanel.rebuild(messageQueue.toList())
    }
}
```

**`newChat`** — очистить панель очереди.

### 4. Layout — вставка QueuePanel в southPanel

```kotlin
val southPanel = JPanel(BorderLayout()).apply {
    isOpaque = false
    val topSection = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        add(statusPanel)
        add(queuePanel)
    }
    add(topSection, BorderLayout.NORTH)
    add(inputContainer, BorderLayout.CENTER)
}
```

### 5. i18n строки

**MyMessageBundle.properties:**
```
queue.header=Queued ({0})
```

**MyMessageBundle_ru.properties:**
```
queue.header=В очереди ({0})
```

## Файлы

| Файл | Действие |
|------|----------|
| `ui/chat/ChatPanel.kt` | Изменить: QueuedMessage, onSendMessage, doSendMessage, processQueue, layout |
| `ui/chat/QueuePanel.kt` | Создать: панель очереди с thumbnails и удалением |
| `messages/MyMessageBundle.properties` | Добавить queue.header |
| `messages/MyMessageBundle_ru.properties` | Добавить queue.header |

## Проверка

1. Запустить плагин, начать чат
2. Во время стриминга отправить 2-3 сообщения (с и без картинок)
3. Убедиться: панель очереди появляется, показывает текст + thumbnails
4. Удалить элемент из очереди — проверить что он не отправится
5. Дождаться окончания стриминга — сообщения отправляются по порядку с изображениями
6. Панель скрывается когда очередь пуста
