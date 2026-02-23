package ru.dsudomoin.claudecodegui.provider.claude

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// --- Request ---

@Serializable
data class ClaudeRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<ClaudeMessage>,
    val stream: Boolean = true,
    val system: String? = null
)

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: String
)

// --- SSE Response Events ---

@Serializable
data class SseMessageStart(
    val type: String,
    val message: SseMessageInfo
)

@Serializable
data class SseMessageInfo(
    val id: String,
    val type: String,
    val role: String,
    val model: String,
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: SseUsage? = null
)

@Serializable
data class SseContentBlockStart(
    val type: String,
    val index: Int,
    @SerialName("content_block") val contentBlock: SseContentBlock
)

@Serializable
data class SseContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null
)

@Serializable
data class SseContentBlockDelta(
    val type: String,
    val index: Int,
    val delta: SseDelta
)

@Serializable
data class SseDelta(
    val type: String,
    val text: String? = null,
    @SerialName("partial_json") val partialJson: String? = null
)

@Serializable
data class SseMessageDelta(
    val type: String,
    val delta: SseMessageDeltaInfo,
    val usage: SseUsage? = null
)

@Serializable
data class SseMessageDeltaInfo(
    @SerialName("stop_reason") val stopReason: String? = null,
    @SerialName("stop_sequence") val stopSequence: String? = null
)

@Serializable
data class SseUsage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0
)
