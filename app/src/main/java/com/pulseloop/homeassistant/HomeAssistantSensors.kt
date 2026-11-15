package com.pulseloop.homeassistant

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The sensors PulseLoop registers with Home Assistant's `mobile_app` integration, and the two
 * webhook bodies that carry them (`register_sensor`, `update_sensor_states`).
 *
 * HA names an entity after the device plus the sensor name, so with the device registered as
 * "PulseLoop" these come out as `sensor.pulseloop_heart_rate` and so on. [uniqueId]s are
 * permanent: changing one orphans the old entity and its history in HA.
 */
object HomeAssistantSensors {

    /**
     * Bump when a sensor is added or its metadata changes. A push under a newer schema than the
     * stored one re-registers everything first, since `update_sensor_states` can only change
     * state, icon and attributes.
     */
    const val SCHEMA = 1

    data class Spec(
        val uniqueId: String,
        val name: String,
        val unit: String? = null,
        val deviceClass: String? = null,
        val stateClass: String? = null,
        val icon: String,
        val diagnostic: Boolean = false,
        val state: (HomeAssistantSnapshot) -> Any?,
        val attributes: (HomeAssistantSnapshot) -> Map<String, String?> = { emptyMap() },
    )

    private fun measuredAt(at: (HomeAssistantSnapshot) -> String?): (HomeAssistantSnapshot) -> Map<String, String?> =
        { mapOf("measured_at" to at(it)) }

    val all: List<Spec> = listOf(
        Spec("heart_rate", "Heart Rate", "bpm", stateClass = "measurement", icon = "mdi:heart-pulse",
            state = { it.heartRate }, attributes = measuredAt { it.heartRateAt }),
        Spec("resting_heart_rate", "Resting Heart Rate", "bpm", stateClass = "measurement", icon = "mdi:heart-outline",
            state = { it.restingHeartRate }),
        Spec("spo2", "SpO2", "%", stateClass = "measurement", icon = "mdi:water-percent",
            state = { it.spo2 }, attributes = measuredAt { it.spo2At }),
        Spec("hrv", "HRV", "ms", stateClass = "measurement", icon = "mdi:pulse",
            state = { it.hrv }, attributes = measuredAt { it.hrvAt }),
        Spec("stress", "Stress", stateClass = "measurement", icon = "mdi:head-snowflake-outline",
            state = { it.stress }, attributes = measuredAt { it.stressAt }),
        Spec("temperature", "Temperature", "°C", "temperature", "measurement", icon = "mdi:thermometer",
            state = { it.temperature }, attributes = measuredAt { it.temperatureAt }),
        Spec("respiratory_rate", "Respiratory Rate", "breaths/min", stateClass = "measurement", icon = "mdi:lungs",
            state = { it.respiratoryRate }, attributes = measuredAt { it.respiratoryRateAt }),
        Spec("steps_today", "Steps Today", "steps", stateClass = "total_increasing", icon = "mdi:shoe-print",
            state = { it.stepsToday }),
        Spec("distance_today", "Distance Today", "m", "distance", "total_increasing", icon = "mdi:map-marker-distance",
            state = { it.distanceTodayMeters }),
        Spec("active_calories_today", "Active Calories Today", "kcal", stateClass = "total_increasing", icon = "mdi:fire",
            state = { it.activeCaloriesToday }),
        Spec("sleep", "Sleep", "min", "duration", "measurement", icon = "mdi:sleep",
            state = { it.sleepMinutes },
            attributes = { mapOf("date" to it.sleepDate, "start" to it.sleepStart, "end" to it.sleepEnd) }),
        Spec("sleep_score", "Sleep Score", stateClass = "measurement", icon = "mdi:star-outline",
            state = { it.sleepScore }),
        Spec("ring_battery", "Ring Battery", "%", "battery", "measurement", icon = "mdi:battery",
            diagnostic = true, state = { it.battery }),
        Spec("ring_state", "Ring State", icon = "mdi:ring", diagnostic = true,
            state = { it.deviceState },
            attributes = { mapOf("ring" to it.deviceName, "model" to it.deviceModel, "firmware" to it.firmware) }),
        Spec("last_sync", "Last Ring Sync", deviceClass = "timestamp", icon = "mdi:sync", diagnostic = true,
            state = { it.lastSyncAt }),
    )

    private fun value(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun attributes(spec: Spec, snap: HomeAssistantSnapshot): JsonObject = buildJsonObject {
        spec.attributes(snap).forEach { (k, v) -> put(k, v?.let(::JsonPrimitive) ?: JsonNull) }
    }

    fun registerBody(spec: Spec, snap: HomeAssistantSnapshot): JsonObject = buildJsonObject {
        put("type", "register_sensor")
        put("data", buildJsonObject {
            put("type", "sensor")
            put("unique_id", spec.uniqueId)
            put("name", spec.name)
            put("icon", spec.icon)
            spec.unit?.let { put("unit_of_measurement", it) }
            spec.deviceClass?.let { put("device_class", it) }
            spec.stateClass?.let { put("state_class", it) }
            if (spec.diagnostic) put("entity_category", "diagnostic")
            put("state", value(spec.state(snap)))
            put("attributes", attributes(spec, snap))
        })
    }

    fun updateBody(snap: HomeAssistantSnapshot, specs: List<Spec> = all): JsonObject = buildJsonObject {
        put("type", "update_sensor_states")
        put("data", buildJsonArray {
            specs.forEach { spec ->
                add(buildJsonObject {
                    put("type", "sensor")
                    put("unique_id", spec.uniqueId)
                    put("icon", spec.icon)
                    put("state", value(spec.state(snap)))
                    put("attributes", attributes(spec, snap))
                })
            }
        })
    }
}
