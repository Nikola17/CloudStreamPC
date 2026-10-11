package com.cloudstreampc.sync

import com.cloudstreampc.core.mapper
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** An HTTP answer of a tracking service: status code and JSON body (missing node if not JSON). */
class ApiResponse(val code: Int, val body: JsonNode, val text: String) {
    val ok get() = code in 200..299
}

class ApiException(val code: Int, message: String) : Exception(message)

/** Small JSON/form client on the app's HTTP client (so the DNS over HTTPS setting applies). */
object Http {
    private val json = "application/json".toMediaType()

    fun jsonBody(value: Any): RequestBody = com.cloudstreampc.core.compactJson(value).toRequestBody(json)

    fun formBody(values: Map<String, String>): RequestBody =
        FormBody.Builder().apply { values.forEach { (k, v) -> add(k, v) } }.build()

    suspend fun call(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): ApiResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            method(method, body ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null))
        }.build()
        app.baseClient.newCall(request).execute().use { response ->
            val text = response.body.string()
            val node = runCatching { mapper.readTree(text) }.getOrNull() ?: mapper.missingNode()
            ApiResponse(response.code, node, text)
        }
    }

    /** Like [call] but throws when the service answers with an error. */
    suspend fun expect(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
    ): JsonNode {
        val response = call(method, url, headers, body)
        if (!response.ok) {
            val message = response.body.path("error_description").asText().ifBlank {
                response.body.path("message").asText().ifBlank { response.body.path("error").asText() }
            }.ifBlank { response.text.take(200) }
            throw ApiException(response.code, "Erreur ${response.code} : $message")
        }
        return response.body
    }
}
