package com.pulseloop.homeassistant

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One push: make sure this device's sensors exist in Home Assistant, then send their states.
 *
 * Kept free of Android types (store and client are injected) so the protocol handling is covered by
 * plain unit tests against a mock server.
 */
class HomeAssistantSync(
    private val store: HomeAssistantRegistrationStore,
    private val client: HomeAssistantClient = HomeAssistantClient(),
) {
    sealed interface Outcome {
        data object Ok : Outcome
        /** No registration, or a stored one whose URL no longer validates. */
        data object NotRegistered : Outcome
        /** HA answered 410: the device was deleted there. The registration is cleared. */
        data object Gone : Outcome
        data class HttpError(val code: Int) : Outcome
        data class NetworkError(val message: String) : Outcome
    }

    fun push(snapshot: HomeAssistantSnapshot): Outcome {
        val id = store.webhookId ?: return Outcome.NotRegistered
        val url = HomeAssistantEndpoint.webhookUrl(store.baseUrl, id) ?: return Outcome.NotRegistered

        if (store.registeredSchema < HomeAssistantSensors.SCHEMA) {
            register(url, HomeAssistantSensors.all, snapshot)?.let { return it }
            store.registeredSchema = HomeAssistantSensors.SCHEMA
        }

        val first = update(url, snapshot)
        if (first !is Step.Missing) return first.outcome
        // The user deleted some entities in HA, or HA lost them. Re-create just those, once.
        register(url, first.specs, snapshot)?.let { return it }
        return update(url, snapshot).outcome
    }

    private sealed interface Step {
        val outcome: Outcome
        data class Done(override val outcome: Outcome) : Step
        data class Missing(val specs: List<HomeAssistantSensors.Spec>) : Step {
            override val outcome: Outcome get() = Outcome.Ok
        }
    }

    private fun update(url: String, snapshot: HomeAssistantSnapshot): Step {
        return when (val r = client.webhook(url, HomeAssistantSensors.updateBody(snapshot))) {
            is HomeAssistantClient.Result.Ok -> {
                val missing = notRegistered(r.value as? JsonObject)
                if (missing.isEmpty()) Step.Done(Outcome.Ok)
                else Step.Missing(HomeAssistantSensors.all.filter { it.uniqueId in missing })
            }
            else -> Step.Done(failure(r))
        }
    }

    /** Registers [specs]; null on success, the failure otherwise. */
    private fun register(url: String, specs: List<HomeAssistantSensors.Spec>, snapshot: HomeAssistantSnapshot): Outcome? {
        for (spec in specs) {
            val r = client.webhook(url, HomeAssistantSensors.registerBody(spec, snapshot))
            if (r !is HomeAssistantClient.Result.Ok) return failure(r)
        }
        return null
    }

    private fun failure(r: HomeAssistantClient.Result<*>): Outcome = when (r) {
        is HomeAssistantClient.Result.HttpError ->
            if (r.code == 410) { store.clearRegistration(); Outcome.Gone } else Outcome.HttpError(r.code)
        is HomeAssistantClient.Result.NetworkError -> Outcome.NetworkError(r.message)
        is HomeAssistantClient.Result.Ok -> Outcome.Ok
    }

    companion object {
        /**
         * `update_sensor_states` answers per unique id: `{"heart_rate": {"success": true}, ...}`,
         * with `error.code == "not_registered"` for one HA doesn't know.
         */
        internal fun notRegistered(reply: JsonObject?): Set<String> =
            reply.orEmpty().filter { (_, v) ->
                runCatching {
                    v.jsonObject["error"]?.jsonObject?.get("code")?.jsonPrimitive?.contentOrNull == "not_registered"
                }.getOrDefault(false)
            }.keys
    }
}
