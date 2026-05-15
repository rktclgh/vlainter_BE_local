package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider
import com.cw.vlainter.global.config.properties.HermesProperties
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.web.client.RestTemplateBuilder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import org.springframework.web.client.RestTemplate
import java.util.function.Supplier

@Component
class HermesLlmProviderClient(
    restTemplateBuilder: RestTemplateBuilder,
    private val hermesProperties: HermesProperties,
    private val objectMapper: ObjectMapper
) : LlmProviderClient {
    override val provider: AiProvider = AiProvider.HERMES
    private val logger = LoggerFactory.getLogger(javaClass)

    private val restTemplate: RestTemplate = restTemplateBuilder
        .requestFactory(Supplier {
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout((hermesProperties.connectTimeoutSeconds * 1000).toInt())
                setReadTimeout((hermesProperties.readTimeoutSeconds * 1000).toInt())
            }
        })
        .build()

    override fun isEnabled(): Boolean = resolveEndpoint().isNotBlank()

    override fun generateJson(
        prompt: String,
        temperature: Double?,
        maxOutputTokens: Int?
    ): LlmGenerationResult {
        validateMaxOutputTokens(maxOutputTokens)
        val endpoint = resolveEndpoint()
        require(endpoint.isNotBlank()) { "Hermes endpoint is missing." }

        val model = hermesProperties.model.trim().ifBlank { "hermes" }
        val payload = HermesGenerateRequest(
            model = model,
            profile = hermesProperties.profile.trim().ifBlank { null },
            prompt = prompt,
            temperature = temperature ?: hermesProperties.temperature,
            maxTokens = maxOutputTokens ?: hermesProperties.maxTokens
        )

        logger.info(
            "Hermes 호출 시도 endpoint={} model={} profile={} promptLength={} temperature={} maxTokens={}",
            endpoint,
            model,
            hermesProperties.profile.trim().ifBlank { null },
            prompt.length,
            payload.temperature,
            payload.maxTokens
        )

        val response = post(
            url = endpoint,
            entity = HttpEntity(payload, hermesHeaders()),
            responseType = String::class.java
        )
        val body = response.body?.trim().orEmpty()
        val text = extractText(body)
        if (text.isBlank()) {
            logger.warn("Hermes 응답 텍스트가 비어 있습니다. model={} bodyLength={}", model, body.length)
            error("Hermes 응답 텍스트가 비어 있습니다.")
        }

        return LlmGenerationResult(
            model = model,
            modelVersion = "hermes",
            text = text
        ).also {
            logger.info("Hermes 호출 성공 model={} responseLength={}", model, text.length)
        }
    }

    private fun <T> post(
        url: String,
        entity: HttpEntity<*>,
        responseType: Class<T>
    ): ResponseEntity<T> {
        return runCatching {
            restTemplate.postForEntity(url, entity, responseType)
        }.getOrElse { ex ->
            throw translateException(ex)
        }
    }

    private fun translateException(ex: Throwable): RuntimeException {
        val httpEx = ex as? RestClientResponseException
        if (httpEx != null) {
            val statusCode = httpEx.statusCode.value()
            val details = parseErrorDetails(httpEx.responseBodyAsString)
            val message = "Hermes 호출 실패: HTTP $statusCode $details"
            if (statusCode == 429 || statusCode == 503) {
                return AiProviderTransientException(
                    statusCode = statusCode,
                    message = message,
                    cause = httpEx,
                    provider = AiProvider.HERMES
                )
            }
            if (statusCode == 401 || statusCode == 403) {
                return AiProviderAuthorizationException(
                    statusCode = statusCode,
                    message = message,
                    cause = httpEx,
                    provider = AiProvider.HERMES
                )
            }
            return IllegalStateException(message, httpEx)
        }

        if (isTimeoutException(ex)) {
            return AiProviderTransientException(
                statusCode = 503,
                message = "Hermes 호출 실패: ${ex.message}",
                cause = ex,
                provider = AiProvider.HERMES
            )
        }

        return IllegalStateException("Hermes 호출 실패: ${ex.message}", ex)
    }

    private fun extractText(body: String): String {
        if (body.isBlank()) return ""
        val node = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return body.trim()

        return listOf(
            node.path("text").asNonBlankText(),
            node.path("output").asNonBlankText(),
            node.path("response").asNonBlankText(),
            node.path("choices").firstOrNull()
                ?.path("message")
                ?.path("content")
                ?.asNonBlankText(),
            node.path("choices").firstOrNull()
                ?.path("text")
                ?.asNonBlankText()
        ).firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun parseErrorDetails(body: String): String {
        if (body.isBlank()) return ""
        return runCatching { objectMapper.readTree(body).toString() }
            .getOrElse { body }
    }

    private fun resolveEndpoint(): String {
        val explicitEndpoint = hermesProperties.endpoint.trim()
        if (explicitEndpoint.isNotBlank()) return explicitEndpoint

        val baseUrl = hermesProperties.baseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) return ""
        val endpointPath = hermesProperties.endpointPath.trim().ifBlank { "/generate" }
        return "$baseUrl/${endpointPath.trimStart('/')}"
    }

    private fun validateMaxOutputTokens(maxOutputTokens: Int?) {
        require(maxOutputTokens == null || maxOutputTokens > 0) { "maxOutputTokens must be > 0" }
    }

    private fun isTimeoutException(ex: Throwable): Boolean {
        var current: Throwable? = ex
        while (current != null) {
            if (current is java.net.SocketTimeoutException) return true
            if (current is ResourceAccessException && current.message?.contains("timed out", ignoreCase = true) == true) return true
            if (current.message?.contains("timed out", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }

    private fun hermesHeaders(): HttpHeaders {
        return HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.APPLICATION_JSON, MediaType.TEXT_PLAIN)
            hermesProperties.apiKey.trim().takeIf { it.isNotBlank() }?.let {
                setBearerAuth(it)
            }
        }
    }
}

private fun JsonNode.asNonBlankText(): String? {
    if (isMissingNode || isNull) return null
    return asText().trim().takeIf { it.isNotBlank() }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
private data class HermesGenerateRequest(
    val model: String,
    val profile: String? = null,
    val prompt: String,
    val temperature: Double,
    val maxTokens: Int
)
