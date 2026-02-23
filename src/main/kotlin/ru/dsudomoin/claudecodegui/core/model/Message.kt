package ru.dsudomoin.claudecodegui.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class Role {
    @SerialName("user")
    USER,

    @SerialName("assistant")
    ASSISTANT
}

@Serializable
data class Message(
    val role: Role,
    val content: List<ContentBlock>,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        fun user(text: String): Message = Message(
            role = Role.USER,
            content = listOf(ContentBlock.Text(text))
        )

        fun user(text: String, images: List<File>): Message {
            val blocks = mutableListOf<ContentBlock>()
            images.forEach { file ->
                val ext = file.extension.lowercase()
                val mediaType = when (ext) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "gif" -> "image/gif"
                    "webp" -> "image/webp"
                    "bmp" -> "image/bmp"
                    else -> "image/png"
                }
                blocks.add(ContentBlock.Image(file.absolutePath, mediaType))
            }
            if (text.isNotBlank()) {
                blocks.add(ContentBlock.Text(text))
            }
            return Message(role = Role.USER, content = blocks)
        }

        fun assistant(text: String): Message = Message(
            role = Role.ASSISTANT,
            content = listOf(ContentBlock.Text(text))
        )

        fun assistant(blocks: List<ContentBlock>): Message = Message(
            role = Role.ASSISTANT,
            content = blocks
        )
    }

    val textContent: String
        get() = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text }

    val imageFiles: List<File>
        get() = content.filterIsInstance<ContentBlock.Image>().map { File(it.source) }
}
