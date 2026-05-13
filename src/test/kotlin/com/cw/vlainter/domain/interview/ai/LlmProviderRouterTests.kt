package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProperties
import com.cw.vlainter.global.config.properties.AiProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LlmProviderRouterTests {

    @Test
    fun `Hermes가 primary provider이면 Gemini exhaustion 상태여도 Bedrock fallback을 사용하지 않는다`() {
        val routingContextHolder = AiRoutingContextHolder()
        routingContextHolder.markGeminiExhausted()
        val hermes = RecordingLlmProviderClient(AiProvider.HERMES)
        val bedrock = RecordingLlmProviderClient(AiProvider.BEDROCK)
        val router = LlmProviderRouter(
            aiProperties = AiProperties(provider = AiProvider.HERMES),
            aiRoutingContextHolder = routingContextHolder,
            providers = listOf(hermes, bedrock)
        )

        val result = router.generateJson("질문 생성")

        assertThat(result.model).isEqualTo("HERMES")
        assertThat(hermes.callCount).isEqualTo(1)
        assertThat(bedrock.callCount).isZero()
        assertThat(routingContextHolder.snapshot().usedProviders).containsExactly(AiProvider.HERMES)
    }

    @Test
    fun `Hermes transient 오류는 Bedrock fallback으로 넘기지 않는다`() {
        val routingContextHolder = AiRoutingContextHolder()
        val hermes = RecordingLlmProviderClient(
            provider = AiProvider.HERMES,
            transientException = AiProviderTransientException(
                statusCode = 503,
                message = "Hermes unavailable",
                provider = AiProvider.HERMES
            )
        )
        val bedrock = RecordingLlmProviderClient(AiProvider.BEDROCK)
        val router = LlmProviderRouter(
            aiProperties = AiProperties(provider = AiProvider.HERMES),
            aiRoutingContextHolder = routingContextHolder,
            providers = listOf(hermes, bedrock)
        )

        val exception = assertThrows<AiProviderTransientException> {
            router.generateJson("질문 생성")
        }

        assertThat(exception.provider).isEqualTo(AiProvider.HERMES)
        assertThat(hermes.callCount).isEqualTo(1)
        assertThat(bedrock.callCount).isZero()
    }

    @Test
    fun `Gemini transient 오류는 기존처럼 Bedrock fallback을 사용할 수 있다`() {
        val routingContextHolder = AiRoutingContextHolder()
        val gemini = RecordingLlmProviderClient(
            provider = AiProvider.GEMINI,
            transientException = AiProviderTransientException(
                statusCode = 503,
                message = "Gemini overloaded",
                provider = AiProvider.GEMINI
            )
        )
        val bedrock = RecordingLlmProviderClient(AiProvider.BEDROCK)
        val router = LlmProviderRouter(
            aiProperties = AiProperties(provider = AiProvider.GEMINI),
            aiRoutingContextHolder = routingContextHolder,
            providers = listOf(gemini, bedrock)
        )

        val result = router.generateJson("질문 생성")

        assertThat(result.model).isEqualTo("BEDROCK")
        assertThat(gemini.callCount).isEqualTo(1)
        assertThat(bedrock.callCount).isEqualTo(1)
        assertThat(routingContextHolder.isGeminiExhausted()).isTrue()
        assertThat(routingContextHolder.snapshot().usedProviders).containsExactly(
            AiProvider.GEMINI,
            AiProvider.BEDROCK
        )
    }
}

private class RecordingLlmProviderClient(
    override val provider: AiProvider,
    private val enabled: Boolean = true,
    private val transientException: AiProviderTransientException? = null
) : LlmProviderClient {
    var callCount: Int = 0
        private set

    override fun isEnabled(): Boolean = enabled

    override fun generateJson(
        prompt: String,
        temperature: Double?,
        maxOutputTokens: Int?
    ): LlmGenerationResult {
        callCount += 1
        transientException?.let { throw it }
        return LlmGenerationResult(
            model = provider.name,
            modelVersion = "test",
            text = """{"ok":true}"""
        )
    }
}
