package com.pulseloop.homeassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP side of Home Assistant's `mobile_app` integration
 * (https://developers.home-assistant.io/docs/api/native-app-integration).
 *
 * Redirects are off for the same reason as the local coach provider (`network_security_config.xml`):
 * the URL was vetted, a redirect target would not be, and a 307 to a public `http://` host would
 * resend the health readings in the clear.
 */
class HomeAssistantClient(
    private val http: OkHttpClient = defaultClient,
) {
    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class HttpError(val code: Int) : Result<Nothing>
        data class NetworkError(val message: String) : Result<Nothing>
    }

    data class DeviceInfo(
        val deviceId: String,
        val deviceName: String,
        val appVersion: String,
        val manufacturer: String,
        val model: String,
        val osVersion: String,
    )

    /** One-time registration; returns the webhook id. [token] is a long-lived access token and is not kept. */
    fun register(url: String, token: String, info: DeviceInfo): Result<String> {
        val body = buildJsonObject {
            put("device_id", info.deviceId)
            put("app_id", APP_ID)
            put("app_name", "PulseLoop")
            put("app_version", info.appVersion)
            put("device_name", info.deviceName)
            put("manufacturer", info.manufacturer)
            put("model", info.model)
            put("os_name", "Android")
            put("os_version", info.osVersion)
            put("supports_encryption", false)
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${token.trim()}")
            .post(body.toString().toRequestBody(JSON))
            .build()
        return when (val r = execute(request)) {
            is Result.Ok -> {
                val id = runCatching {
                    (json.parseToJsonElement(r.value) as? JsonObject)?.get("webhook_id")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                // A 2xx without a webhook id is not a Home Assistant we understand.
                if (id.isNullOrBlank()) Result.HttpError(INVALID_RESPONSE) else Result.Ok(id)
            }
            is Result.HttpError -> r
            is Result.NetworkError -> r
        }
    }

    /** POST to the registration's webhook. 410 means the device was deleted in HA. */
    fun webhook(url: String, body: JsonElement): Result<JsonElement?> {
        val request = Request.Builder().url(url).post(body.toString().toRequestBody(JSON)).build()
        return when (val r = execute(request)) {
            is Result.Ok -> Result.Ok(runCatching { json.parseToJsonElement(r.value) }.getOrNull())
            is Result.HttpError -> r
            is Result.NetworkError -> r
        }
    }

    private fun execute(request: Request): Result<String> = try {
        http.newCall(request).execute().use { response ->
            if (response.isSuccessful) Result.Ok(response.body?.string().orEmpty())
            else Result.HttpError(response.code)
        }
    } catch (e: IOException) {
        Result.NetworkError(e.message ?: e.javaClass.simpleName)
    }

    companion object {
        const val APP_ID = "com.pulseloop"
        /** Pseudo status for "2xx, but not the reply we expected". */
        const val INVALID_RESPONSE = -1
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        private val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }
    }
}
