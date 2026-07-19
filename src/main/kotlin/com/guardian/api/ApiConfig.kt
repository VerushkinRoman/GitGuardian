package com.guardian.api

import java.io.File
import java.util.Properties

object ApiConfig {
    private val properties: Properties by lazy {
        Properties().apply {
            val stream = ApiConfig::class.java.classLoader.getResourceAsStream("keys.properties")
            if (stream != null) {
                stream.use { load(it) }
            } else {
                val f = File("keys.properties")
                if (f.exists()) f.inputStream().use { load(it) }
            }
        }
    }

    fun getBaseUrl(): String =
        properties.getProperty("llm_base_url") ?: "https://alcoserver.ru:4001"

    fun getLlmUser(): String =
        properties.getProperty("llm_user") ?: "llm_user"

    fun getLlmUserPassword(): String =
        properties.getProperty("llm_user_pwd")
            ?: error("llm_user_pwd not found in keys.properties")
}
