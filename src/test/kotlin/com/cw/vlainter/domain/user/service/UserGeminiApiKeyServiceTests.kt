package com.cw.vlainter.domain.user.service

import com.cw.vlainter.domain.user.entity.User
import com.cw.vlainter.domain.user.entity.UserRole
import com.cw.vlainter.domain.user.entity.UserStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class UserGeminiApiKeyServiceTests {
    @Test
    fun `withUserApiKey는 사용자 키 없이 서버 환경 키 경로로 실행한다`() {
        val result = service().withUserApiKey(1L) { "ok" }

        assertThat(result).isEqualTo("ok")
    }

    @Test
    fun `assertGeminiApiKeyConfigured는 사용자 키 등록을 요구하지 않는다`() {
        service().assertGeminiApiKeyConfigured(1L)
    }

    @Test
    fun `hasGeminiApiKey는 레거시 프로필 호환을 위해 항상 true를 반환한다`() {
        val user = User(
            id = 1L,
            email = "user@vlainter.com",
            password = "encoded",
            name = "User",
            status = UserStatus.ACTIVE,
            role = UserRole.USER,
            geminiApiKeyEncrypted = null
        )

        assertThat(service().hasGeminiApiKey(user)).isTrue()
    }

    private fun service(): UserGeminiApiKeyService {
        return UserGeminiApiKeyService()
    }
}
