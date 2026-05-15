package com.cw.vlainter.global.config.properties

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.ai.hermes")
data class HermesProperties(
    val baseUrl: String = "",
    val endpoint: String = "",
    val endpointPath: String = "/generate",
    val apiKey: String = "",
    val model: String = "hermes",
    val profile: String = "",
    val temperature: Double = 0.2,
    val maxTokens: Int = 2048,
    val connectTimeoutSeconds: Long = 5,
    val readTimeoutSeconds: Long = 45
)
