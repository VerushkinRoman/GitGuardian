package com.guardian.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class OllamaClient(
    private val baseUrl: String = "http://localhost:11434"
) : RouterClient {

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    override suspend fun sendRequest(request: RouterRequest): RouterResponse {
        val client = HttpClient {
            install(ContentNegotiation) { json(this@OllamaClient.json) }
            install(HttpTimeout) {
                requestTimeoutMillis = 300_000L
                connectTimeoutMillis = 30_000L
                socketTimeoutMillis = 300_000L
            }
        }

        return try {
            client.use { c ->
                val response = c.post("$baseUrl/v1/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
                response.body<RouterResponse>()
            }
        } catch (e: Exception) {
            println("[error] Ollama: ${e.message}")
            RouterResponse(error = ErrorResponse(message = "Ollama error: ${e.message}"))
        }
    }
}
