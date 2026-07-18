package com.guardian.api

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

class ApiClient(
    private val baseUrl: String = ApiConfig.getBaseUrl()
) : RouterClient {

    private val json = Json {
        prettyPrint = true
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val fallbackModels = ModelList.chatModels

    override suspend fun sendRequest(request: RouterRequest): RouterResponse {
        return sendWithFallback(request, 0)
    }

    private suspend fun sendWithFallback(
        request: RouterRequest,
        fallbackIndex: Int,
        retryCount: Int = 0,
    ): RouterResponse {
        val model = if (fallbackIndex == 0) request.model
        else fallbackModels.getOrNull(fallbackIndex) ?: run {
            println("[retry] Все модели исчерпаны, начинаю новый цикл")
            delay(CYCLES_DELAY)
            return sendWithFallback(request, 0)
        }

        println("[api] $baseUrl/chat/completions | model=$model")

        val client = HttpClient {
            install(ContentNegotiation) { json(this@ApiClient.json) }
            install(Auth) {
                basic {
                    credentials {
                        BasicAuthCredentials(
                            username = ApiConfig.getLlmUser(),
                            password = ApiConfig.getLlmUserPassword(),
                        )
                    }
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT
                connectTimeoutMillis = CONNECT_TIMEOUT
                socketTimeoutMillis = SOCKET_TIMEOUT
            }
        }

        return try {
            client.use { c ->
                val response = c.post("$baseUrl/chat/completions") {
                    contentType(ContentType.Application.Json)
                    setBody(request.copy(model = model))
                }

                if (response.status.value == HTTP_429) {
                    if (retryCount < MAX_RETRIES) {
                        println("[retry] 429 Too Many Requests, попытка $retryCount через ${RATE_LIMIT_DELAY.inWholeSeconds}с")
                        delay(RATE_LIMIT_DELAY)
                        return sendWithFallback(request, fallbackIndex, retryCount + 1)
                    }
                    return sendWithFallback(request, fallbackIndex + 1)
                }

                if (!response.status.isSuccess()) {
                    val bodyText = response.bodyAsText()
                    println("[warn] $model | ${response.status} | ${bodyText.take(200)}")

                    if (response.status.value == HTTP_502 && retryCount < 1) {
                        delay(RETRY_502_DELAY)
                        return sendWithFallback(request, fallbackIndex, retryCount + 1)
                    }

                    delay(ERROR_DELAY)
                    return sendWithFallback(request, fallbackIndex + 1)
                }

                val body = response.body<RouterResponse>()
                if (body.error != null) {
                    println("[warn] Модель $model вернула ошибку: ${body.error.message}")
                    delay(ERROR_DELAY)
                    return sendWithFallback(request, fallbackIndex + 1)
                }
                body
            }
        } catch (e: Exception) {
            println("[error] $model: ${e.message}")
            delay(5.seconds)
            return sendWithFallback(request, fallbackIndex + 1)
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT = 300_000L   // 5 минут
        private const val CONNECT_TIMEOUT = 30_000L     // 30 секунд
        private const val SOCKET_TIMEOUT = 300_000L     // 5 минут
        private const val MAX_RETRIES = 3
        private const val HTTP_429 = 429
        private const val HTTP_502 = 502
        private val RATE_LIMIT_DELAY = 5.seconds
        private val RETRY_502_DELAY = 3.seconds
        private val ERROR_DELAY = 2.seconds
        private val CYCLES_DELAY = 10.seconds
    }
}
