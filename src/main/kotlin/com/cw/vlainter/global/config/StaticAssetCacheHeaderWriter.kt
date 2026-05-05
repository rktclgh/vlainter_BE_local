package com.cw.vlainter.global.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.web.header.HeaderWriter

class StaticAssetCacheHeaderWriter : HeaderWriter {
    override fun writeHeaders(request: HttpServletRequest, response: HttpServletResponse) {
        if (isImmutableFingerprintAsset(request) && isSuccessfulOrNotModified(response.status)) {
            response.setHeader(HttpHeaders.CACHE_CONTROL, IMMUTABLE_CACHE_CONTROL)
            return
        }

        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE_CACHE_CONTROL)
        response.setHeader(HttpHeaders.PRAGMA, "no-cache")
        response.setHeader(HttpHeaders.EXPIRES, "0")
    }

    private fun isImmutableFingerprintAsset(request: HttpServletRequest): Boolean {
        if (request.method != "GET" && request.method != "HEAD") {
            return false
        }

        val path = request.requestURI.removePrefix(request.contextPath)
        return FINGERPRINT_ASSET_PATH.matches(path)
    }

    private companion object {
        const val IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable"
        const val NO_STORE_CACHE_CONTROL = "no-cache, no-store, max-age=0, must-revalidate"
        val FINGERPRINT_ASSET_PATH = Regex("^/assets/[^/]+-[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9][A-Za-z0-9.]*$")

        fun isSuccessfulOrNotModified(status: Int): Boolean {
            return status in HttpStatus.OK.value()..HttpStatus.IM_USED.value() ||
                status == HttpStatus.NOT_MODIFIED.value()
        }
    }
}
