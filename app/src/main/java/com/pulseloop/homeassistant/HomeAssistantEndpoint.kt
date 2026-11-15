package com.pulseloop.homeassistant

import com.pulseloop.coach.local.LocalEndpoint
import java.net.URI

/**
 * Turns what the user typed into the webhook URL a push goes to.
 *
 * The cleartext rule is [LocalEndpoint]'s, deliberately shared rather than re-stated: the app
 * permits cleartext app-wide (`network_security_config.xml`), so this check is what keeps health
 * readings off the public internet in the clear. `http://192.168.1.10:8123` is fine;
 * `http://my-ha.example.com` must be `https://`.
 */
object HomeAssistantEndpoint {

    /** Webhook ids HA generates are hex; anything else must not be spliced into a URL path. */
    private val WEBHOOK_ID = Regex("^[A-Za-z0-9_-]{1,128}$")

    /** `scheme://host[:port][/path]` with no trailing slash, or null if unparseable. */
    fun normalizeBase(raw: String): String? {
        var text = raw.trim()
        if (text.isEmpty()) return null
        if (!text.contains("://")) text = "http://$text"
        val uri = try { URI(text) } catch (_: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        // Tolerate a pasted full webhook URL or a trailing /api.
        var path = (uri.path ?: "").trimEnd('/')
        path.indexOf("/api/webhook").takeIf { it >= 0 }?.let { path = path.substring(0, it) }
        if (path.endsWith("/api")) path = path.dropLast(4)
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://$host$port${path.trimEnd('/')}"
    }

    /** The reason [rawBase] can't be used, or null when it can. */
    fun validateBase(rawBase: String): LocalEndpoint.Problem? {
        val base = normalizeBase(rawBase) ?: return if (rawBase.isBlank()) {
            LocalEndpoint.Problem.BLANK
        } else {
            LocalEndpoint.Problem.MALFORMED
        }
        return LocalEndpoint.validate(base)
    }

    /** `POST` target that registers this install with the `mobile_app` integration. */
    fun registrationUrl(rawBase: String): String? =
        if (validateBase(rawBase) != null) null else "${normalizeBase(rawBase)}/api/mobile_app/registrations"

    /** The registration's webhook. The id comes from HA, but is checked before it goes in a path. */
    fun webhookUrl(rawBase: String, webhookId: String): String? {
        if (validateBase(rawBase) != null) return null
        val id = webhookId.trim()
        if (!WEBHOOK_ID.matches(id)) return null
        return "${normalizeBase(rawBase)}/api/webhook/$id"
    }
}
