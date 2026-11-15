package com.pulseloop.homeassistant

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pulseloop.BuildConfig
import com.pulseloop.R
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.settings.StepSourcePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Pushes the current snapshot to Home Assistant.
 *
 * Triggered from the same two places as the Health Connect export, a completed foreground sync
 * (`SyncProgress("done")`) and the background `RingSyncWorker`, and debounced the same way, so a
 * burst of sync events becomes one push. The network constraint lets WorkManager hold a push made
 * while the phone is offline. A LAN URL is still unreachable on mobile data, and that failure is
 * recorded and retried with backoff.
 */
class HomeAssistantPushWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = HomeAssistantSettings(applicationContext)
        if (!settings.enabled || !settings.isRegistered) return Result.success()
        return when (val outcome = pushNow(applicationContext)) {
            is HomeAssistantSync.Outcome.NetworkError ->
                if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            // HA restarting answers 5xx; a 4xx will not fix itself.
            is HomeAssistantSync.Outcome.HttpError ->
                if (outcome.code >= 500 && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    companion object {
        private const val TAG = "HomeAssistantPush"
        private const val WORK_NAME = "home_assistant_push"
        private const val DEBOUNCE_SECONDS = 10L
        private const val MAX_RETRIES = 3

        /** HA names entities `<device>_<sensor>`, so this gives `sensor.pulseloop_heart_rate`. */
        private const val DEVICE_NAME = "PulseLoop"

        /** Registers with `mobile_app`, then pushes once so the sensors appear immediately. */
        suspend fun connect(context: Context, rawBase: String, token: String): HomeAssistantSync.Outcome =
            withContext(Dispatchers.IO) {
                val settings = HomeAssistantSettings(context)
                val url = HomeAssistantEndpoint.registrationUrl(rawBase)
                    ?: return@withContext HomeAssistantSync.Outcome.NotRegistered
                val info = HomeAssistantClient.DeviceInfo(
                    deviceId = settings.deviceId,
                    deviceName = DEVICE_NAME,
                    appVersion = BuildConfig.VERSION_NAME,
                    manufacturer = Build.MANUFACTURER.orEmpty(),
                    model = Build.MODEL.orEmpty(),
                    osVersion = Build.VERSION.RELEASE.orEmpty(),
                )
                when (val r = HomeAssistantClient().register(url, token, info)) {
                    is HomeAssistantClient.Result.Ok -> {
                        settings.saveRegistration(HomeAssistantEndpoint.normalizeBase(rawBase)!!, r.value, DEVICE_NAME)
                        settings.enabled = true
                        pushNow(context)
                    }
                    is HomeAssistantClient.Result.HttpError -> HomeAssistantSync.Outcome.HttpError(r.code)
                        .also { settings.recordFailure(message(context, it)) }
                    is HomeAssistantClient.Result.NetworkError -> HomeAssistantSync.Outcome.NetworkError(r.message)
                        .also { settings.recordFailure(message(context, it)) }
                }
            }

        /** Builds and sends one snapshot, recording the outcome. Also the screen's "Send now". */
        suspend fun pushNow(context: Context): HomeAssistantSync.Outcome = withContext(Dispatchers.IO) {
            val settings = HomeAssistantSettings(context)
            val snapshot = HomeAssistantSnapshot.load(
                db = PulseLoopDatabase.getInstance(context),
                preferPhoneSteps = StepSourcePrefs(context).preferPhone(),
            )
            val outcome = HomeAssistantSync(settings).push(snapshot)
            Log.i(TAG, "push → $outcome")
            if (outcome == HomeAssistantSync.Outcome.Ok) settings.recordSuccess()
            else settings.recordFailure(message(context, outcome))
            outcome
        }

        fun message(context: Context, outcome: HomeAssistantSync.Outcome): String = when (outcome) {
            HomeAssistantSync.Outcome.Ok -> context.getString(R.string.ha_sent_ok)
            HomeAssistantSync.Outcome.NotRegistered -> context.getString(R.string.ha_error_not_connected)
            HomeAssistantSync.Outcome.Gone -> context.getString(R.string.ha_error_gone)
            is HomeAssistantSync.Outcome.HttpError -> when (outcome.code) {
                401, 403 -> context.getString(R.string.ha_error_token)
                404 -> context.getString(R.string.ha_error_no_mobile_app)
                HomeAssistantClient.INVALID_RESPONSE -> context.getString(R.string.ha_error_not_ha)
                else -> context.getString(R.string.ha_error_http, outcome.code)
            }
            is HomeAssistantSync.Outcome.NetworkError -> context.getString(R.string.ha_error_network, outcome.message)
        }

        /** Debounced enqueue. Safe to call on every trigger, and a no-op while off. */
        fun enqueue(context: Context) {
            val app = context.applicationContext
            val settings = HomeAssistantSettings(app)
            if (!settings.enabled || !settings.isRegistered) return
            val request = OneTimeWorkRequestBuilder<HomeAssistantPushWorker>()
                .setInitialDelay(DEBOUNCE_SECONDS, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
