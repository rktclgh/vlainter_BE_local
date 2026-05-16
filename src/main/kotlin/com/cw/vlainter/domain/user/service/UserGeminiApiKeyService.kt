package com.cw.vlainter.domain.user.service

import com.cw.vlainter.domain.user.entity.User
import org.springframework.stereotype.Service

@Service
class UserGeminiApiKeyService {
    fun <T> withUserApiKey(userId: Long, block: () -> T): T {
        return block()
    }

    fun hasGeminiApiKey(user: User): Boolean {
        return true
    }

    fun assertGeminiApiKeyConfigured(userId: Long) {
        // GeminiApiClient now resolves the server-side GEMINI_API_KEY from application properties.
    }
}
