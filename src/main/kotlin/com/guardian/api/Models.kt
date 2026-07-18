package com.guardian.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class RouterRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stop: List<String>? = null,
    val temperature: Double? = null
)

@Serializable
data class RouterResponse(
    val choices: List<Choice>? = null,
    val error: ErrorResponse? = null
)

@Serializable
data class Choice(
    val message: ResponseMessage?,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class ResponseMessage(
    val content: String?
)

@Serializable
data class ErrorResponse(
    val message: String,
    val code: Int? = null
)
