package com.cw.vlainter.domain.interview.ai

import com.cw.vlainter.global.config.properties.AiProperties
import com.cw.vlainter.global.config.properties.AiProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmbeddingProviderRouterTests {

    @Test
    fun `generation provider가 Hermes여도 embedding provider는 Gemini를 사용할 수 있다`() {
        val gemini = RecordingEmbeddingProviderClient(AiProvider.GEMINI)
        val router = EmbeddingProviderRouter(
            aiProperties = AiProperties(
                provider = AiProvider.HERMES,
                embeddingProvider = AiProvider.GEMINI
            ),
            providers = listOf(gemini)
        )

        val result = router.embedText("이력서 문장")

        assertThat(result.model).isEqualTo("GEMINI")
        assertThat(result.values).containsExactly(0.1, 0.2)
        assertThat(gemini.callCount).isEqualTo(1)
    }
}

private class RecordingEmbeddingProviderClient(
    override val provider: AiProvider,
    private val enabled: Boolean = true
) : EmbeddingProviderClient {
    var callCount: Int = 0
        private set

    override fun isEnabled(): Boolean = enabled

    override fun embedText(text: String): EmbeddingGenerationResult {
        callCount += 1
        return EmbeddingGenerationResult(
            model = provider.name,
            modelVersion = "test",
            values = listOf(0.1, 0.2)
        )
    }
}
