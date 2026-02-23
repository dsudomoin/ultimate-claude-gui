package ru.dsudomoin.claudecodegui.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Conversation(
    val sessionId: String = UUID.randomUUID().toString(),
    val messages: List<Message> = emptyList(),
    val title: String = "New Chat",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun addMessage(message: Message): Conversation =
        copy(messages = messages + message)

    fun updateTitle(newTitle: String): Conversation =
        copy(title = newTitle)
}
