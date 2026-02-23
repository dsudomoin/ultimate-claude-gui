package ru.dsudomoin.claudecodegui.provider

import kotlinx.coroutines.flow.Flow
import ru.dsudomoin.claudecodegui.core.model.Message
import ru.dsudomoin.claudecodegui.core.model.StreamEvent

interface AiProvider {
    val id: String
    val displayName: String

    fun sendMessage(
        messages: List<Message>,
        model: String,
        maxTokens: Int,
        systemPrompt: String? = null,
        permissionMode: String = "default",
        streaming: Boolean = true
    ): Flow<StreamEvent>

    fun isConfigured(): Boolean
}
