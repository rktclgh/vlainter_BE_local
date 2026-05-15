package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProvider
import com.cw.vlainter.global.config.properties.HermesProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.web.client.RestTemplateBuilder
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class HermesLlmProviderClientTests {
    private val objectMapper = jacksonObjectMapper()
    private var server: HttpServer? = null
    private var lastRequestBody: String = ""
    private var lastAuthorizationHeader: String? = null

    @AfterEach
    fun tearDown() {
        server?.stop(0)
        server = null
    }

    @Test
    fun `plain text 응답을 생성 텍스트로 사용한다`() {
        val client = clientFor(responseBody = "plain answer")

        val result = client.generateJson("prompt")

        assertThat(result.model).isEqualTo("hermes-test")
        assertThat(result.modelVersion).isEqualTo("hermes")
        assertThat(result.text).isEqualTo("plain answer")
    }

    @Test
    fun `one-shot 요청은 prompt model profile temperature maxTokens 계약으로 전송한다`() {
        val client = clientFor(
            responseBody = """{"text":"ok"}""",
            profile = "local",
            apiKey = "test-token"
        )

        client.generateJson(
            prompt = "질문을 생성해 주세요",
            temperature = 0.3,
            maxOutputTokens = 512
        )

        val requestNode = objectMapper.readTree(lastRequestBody)
        assertThat(requestNode.path("prompt").asText()).isEqualTo("질문을 생성해 주세요")
        assertThat(requestNode.path("model").asText()).isEqualTo("hermes-test")
        assertThat(requestNode.path("profile").asText()).isEqualTo("local")
        assertThat(requestNode.path("temperature").asDouble()).isEqualTo(0.3)
        assertThat(requestNode.path("maxTokens").asInt()).isEqualTo(512)
        assertThat(requestNode.has("messages")).isFalse()
        assertThat(requestNode.has("sessionId")).isFalse()
        assertThat(lastAuthorizationHeader).isEqualTo("Bearer test-token")
    }

    @Test
    fun `대표 JSON envelope 응답을 생성 텍스트로 파싱한다`() {
        assertThat(clientFor(responseBody = """{"text":"text answer"}""").generateJson("prompt").text)
            .isEqualTo("text answer")
        assertThat(clientFor(responseBody = """{"output":"output answer"}""").generateJson("prompt").text)
            .isEqualTo("output answer")
        assertThat(clientFor(responseBody = """{"response":"response answer"}""").generateJson("prompt").text)
            .isEqualTo("response answer")
        assertThat(
            clientFor(
                responseBody = """{"choices":[{"message":{"content":"openai answer"}}]}"""
            ).generateJson("prompt").text
        ).isEqualTo("openai answer")
    }

    @Test
    fun `429와 503 응답은 Hermes transient 예외로 변환한다`() {
        listOf(429, 503).forEach { status ->
            val exception = assertThrows<AiProviderTransientException> {
                clientFor(status = status, responseBody = """{"error":"busy"}""").generateJson("prompt")
            }

            assertThat(exception.statusCode).isEqualTo(status)
            assertThat(exception.provider).isEqualTo(AiProvider.HERMES)
        }
    }

    @Test
    fun `401과 403 응답은 Hermes authorization 예외로 변환한다`() {
        listOf(401, 403).forEach { status ->
            val exception = assertThrows<AiProviderAuthorizationException> {
                clientFor(status = status, responseBody = """{"error":"denied"}""").generateJson("prompt")
            }

            assertThat(exception.statusCode).isEqualTo(status)
            assertThat(exception.provider).isEqualTo(AiProvider.HERMES)
        }
    }

    @Test
    fun `빈 endpoint와 빈 생성 텍스트를 거부한다`() {
        val missingEndpointClient = HermesLlmProviderClient(
            restTemplateBuilder = RestTemplateBuilder(),
            hermesProperties = HermesProperties(endpoint = ""),
            objectMapper = objectMapper
        )

        assertThat(missingEndpointClient.isEnabled()).isFalse()
        assertThrows<IllegalArgumentException> {
            missingEndpointClient.generateJson("prompt")
        }

        assertThrows<IllegalStateException> {
            clientFor(responseBody = """{"text":"   "}""").generateJson("prompt")
        }
    }

    private fun clientFor(
        status: Int = 200,
        responseBody: String,
        profile: String = "",
        apiKey: String = ""
    ): HermesLlmProviderClient {
        val endpoint = startServer(status = status, responseBody = responseBody)
        return HermesLlmProviderClient(
            restTemplateBuilder = RestTemplateBuilder(),
            hermesProperties = HermesProperties(
                endpoint = endpoint,
                apiKey = apiKey,
                model = "hermes-test",
                profile = profile,
                connectTimeoutSeconds = 1,
                readTimeoutSeconds = 1
            ),
            objectMapper = objectMapper
        )
    }

    private fun startServer(status: Int, responseBody: String): String {
        server?.stop(0)
        lastAuthorizationHeader = null
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/generate") { exchange ->
            lastRequestBody = exchange.readRequestBody()
            lastAuthorizationHeader = exchange.requestHeaders.getFirst("Authorization")
            exchange.respond(status, responseBody)
        }
        httpServer.start()
        server = httpServer
        return "http://127.0.0.1:${httpServer.address.port}/generate"
    }
}

private fun HttpExchange.readRequestBody(): String {
    return requestBody.use { String(it.readBytes(), StandardCharsets.UTF_8) }
}

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(StandardCharsets.UTF_8)
    responseHeaders.add("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
