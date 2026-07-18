package com.guardian.api

object ClientFactory {
    private val cloudClient = ApiClient()
    private val ollamaClient = OllamaClient()

    @Volatile
    var useOllama: Boolean = false

    fun create(): RouterClient = object : RouterClient {
        override suspend fun sendRequest(request: RouterRequest): RouterResponse {
            return if (useOllama) ollamaClient.sendRequest(request)
            else cloudClient.sendRequest(request)
        }
    }
}
