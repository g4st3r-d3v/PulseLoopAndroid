package com.pulseloop.homeassistant

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * What PulseLoop needs to keep talking to Home Assistant's `mobile_app` integration.
 *
 * The long-lived access token is **not** here: it is used once, to register, and dropped. After
 * that the registration's webhook id is the credential, the same model HA Companion uses. It is
 * still a credential (anyone holding it can write this device's sensors), which is why the file is
 * encrypted.
 */
interface HomeAssistantRegistrationStore {
    val baseUrl: String
    val webhookId: String?
    /** [HomeAssistantSensors.SCHEMA] the sensors were last registered under, or 0. */
    var registeredSchema: Int
    fun clearRegistration()
}

class HomeAssistantSettings(context: Context) : HomeAssistantRegistrationStore {
    private val prefs = EncryptedSharedPreferences.create(
        PREFS_NAME,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    override val baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""

    override val webhookId: String?
        get() = prefs.getString(KEY_WEBHOOK_ID, null)

    override var registeredSchema: Int
        get() = prefs.getInt(KEY_SCHEMA, 0)
        set(value) = prefs.edit().putInt(KEY_SCHEMA, value).apply()

    /** HA's own name for this install, shown on the settings screen. */
    val deviceName: String?
        get() = prefs.getString(KEY_DEVICE_NAME, null)

    val isRegistered: Boolean get() = webhookId != null

    /**
     * Stable across re-registrations, so HA recognises a reconnect as the same device instead of
     * growing a "PulseLoop (2)". Cleared only with the rest of the file.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null)
            ?: java.util.UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }

    fun saveRegistration(baseUrl: String, webhookId: String, deviceName: String) {
        prefs.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_WEBHOOK_ID, webhookId)
            .putString(KEY_DEVICE_NAME, deviceName)
            .putInt(KEY_SCHEMA, 0)
            .remove(KEY_LAST_ERROR)
            .apply()
    }

    override fun clearRegistration() {
        prefs.edit()
            .remove(KEY_WEBHOOK_ID)
            .remove(KEY_SCHEMA)
            .remove(KEY_DEVICE_NAME)
            .putBoolean(KEY_ENABLED, false)
            .apply()
    }

    val lastPushAt: Long?
        get() = if (prefs.contains(KEY_LAST_PUSH_AT)) prefs.getLong(KEY_LAST_PUSH_AT, 0L) else null

    val lastError: String?
        get() = prefs.getString(KEY_LAST_ERROR, null)

    fun recordSuccess(at: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_PUSH_AT, at).remove(KEY_LAST_ERROR).apply()
    }

    fun recordFailure(message: String) {
        prefs.edit().putString(KEY_LAST_ERROR, message).apply()
    }

    companion object {
        const val PREFS_NAME = "pulseloop_home_assistant"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_WEBHOOK_ID = "webhook_id"
        private const val KEY_SCHEMA = "registered_schema"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_LAST_PUSH_AT = "last_push_at"
        private const val KEY_LAST_ERROR = "last_error"
    }
}
