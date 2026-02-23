package ru.dsudomoin.claudecodegui.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
sealed interface ContentBlock {

    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock

    @Serializable
    @SerialName("code")
    data class Code(val code: String, val language: String? = null) : ContentBlock

    @Serializable
    @SerialName("thinking")
    data class Thinking(val text: String) : ContentBlock

    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject
    ) : ContentBlock

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        val toolUseId: String,
        val content: String,
        val isError: Boolean = false
    ) : ContentBlock

    @Serializable
    @SerialName("image")
    data class Image(
        val source: String,
        val mediaType: String = "image/png"
    ) : ContentBlock
}
