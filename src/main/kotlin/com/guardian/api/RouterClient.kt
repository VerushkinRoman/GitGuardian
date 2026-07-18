package com.guardian.api

interface RouterClient {
    suspend fun sendRequest(request: RouterRequest): RouterResponse
}
