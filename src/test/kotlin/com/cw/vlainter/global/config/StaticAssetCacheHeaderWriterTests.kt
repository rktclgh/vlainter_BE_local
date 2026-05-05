package com.cw.vlainter.global.config

import jakarta.servlet.http.HttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class StaticAssetCacheHeaderWriterTests {
    private val headerWriter = StaticAssetCacheHeaderWriter()

    @Test
    fun `GET fingerprint asset uses immutable cache`() {
        val response = writeHeaders("GET", "/assets/index-a1B2c3D4.js")

        assertEquals("public, max-age=31536000, immutable", response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertNull(response.getHeader(HttpHeaders.PRAGMA))
        assertNull(response.getHeader(HttpHeaders.EXPIRES))
    }

    @Test
    fun `HEAD fingerprint asset uses immutable cache`() {
        val response = writeHeaders("HEAD", "/assets/jspdf-vendor-a1B2c3D4.js")

        assertEquals("public, max-age=31536000, immutable", response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertNull(response.getHeader(HttpHeaders.PRAGMA))
        assertNull(response.getHeader(HttpHeaders.EXPIRES))
    }

    @Test
    fun `not modified fingerprint asset uses immutable cache`() {
        val response = writeHeaders("GET", "/assets/index-a1B2c3D4.js", HttpStatus.NOT_MODIFIED.value())

        assertEquals("public, max-age=31536000, immutable", response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertNull(response.getHeader(HttpHeaders.PRAGMA))
        assertNull(response.getHeader(HttpHeaders.EXPIRES))
    }

    @Test
    fun `missing fingerprint asset keeps no store headers`() {
        val response = writeHeaders("GET", "/assets/index-a1B2c3D4.js", HttpStatus.NOT_FOUND.value())

        assertNoStoreHeaders(response)
    }

    @Test
    fun `failed fingerprint asset keeps no store headers`() {
        val response = writeHeaders("GET", "/assets/index-a1B2c3D4.js", HttpStatus.INTERNAL_SERVER_ERROR.value())

        assertNoStoreHeaders(response)
    }

    @Test
    fun `non GET or HEAD fingerprint asset keeps no store headers`() {
        val response = writeHeaders("POST", "/assets/index-a1B2c3D4.js")

        assertNoStoreHeaders(response)
    }

    @Test
    fun `non fingerprint asset keeps no store headers`() {
        val response = writeHeaders("GET", "/assets/logo.svg")

        assertNoStoreHeaders(response)
    }

    @Test
    fun `index html keeps no store headers`() {
        val response = writeHeaders("GET", "/index.html")

        assertNoStoreHeaders(response)
    }

    @Test
    fun `api response keeps no store headers`() {
        val response = writeHeaders("GET", "/api/users/me")

        assertNoStoreHeaders(response)
    }

    @Test
    fun `api response overwrites existing cache headers with no store headers`() {
        val response = writeHeaders(
            method = "GET",
            requestUri = "/api/users/me",
            existingCacheControl = "private, no-cache"
        )

        assertNoStoreHeaders(response)
    }

    private fun writeHeaders(
        method: String,
        requestUri: String,
        status: Int = HttpStatus.OK.value(),
        existingCacheControl: String? = null
    ): MockHttpServletResponse {
        val request = MockHttpServletRequest(method, requestUri)
        val response = MockHttpServletResponse()
        response.status = status
        existingCacheControl?.let { response.setHeader(HttpHeaders.CACHE_CONTROL, it) }

        headerWriter.writeHeaders(request, response)

        return response
    }

    private fun assertNoStoreHeaders(response: HttpServletResponse) {
        assertEquals("no-cache, no-store, max-age=0, must-revalidate", response.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals("no-cache", response.getHeader(HttpHeaders.PRAGMA))
        assertEquals("0", response.getHeader(HttpHeaders.EXPIRES))
    }
}
