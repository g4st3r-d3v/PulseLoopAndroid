package com.pulseloop.homeassistant

import com.pulseloop.data.DisplaySteps
import com.pulseloop.data.PulseLoopDatabase
import com.pulseloop.data.entity.ActivityDailyEntity
import com.pulseloop.data.entity.DeviceEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.ring.MeasurementKind
import com.pulseloop.service.DailyCalorieEstimator
import com.pulseloop.util.TimeUtil
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What one push tells Home Assistant: the latest value of each vital and today's totals, mapped
 * onto sensors by [HomeAssistantSensors]. Each reading carries its own `…At`, because a
 * 30-minute-old SpO₂ and a 30-second-old heart rate arrive in the same push and HA should be able
 * to tell them apart.
 *
 * **Real rows only** (`DemoDataPolicy`): this leaves the app and is recorded in HA's history, so
 * a seeded 56 bpm must never reach it. A field with no real reading is null, which HA shows as
 * "unknown" instead of a stale or invented number.
 */
data class HomeAssistantSnapshot(
    val sentAt: String,

    val deviceName: String? = null,
    val deviceModel: String? = null,
    val deviceState: String? = null,
    val battery: Int? = null,
    val firmware: String? = null,
    val lastSyncAt: String? = null,

    val heartRate: Double? = null,
    val heartRateAt: String? = null,
    val restingHeartRate: Double? = null,
    val spo2: Double? = null,
    val spo2At: String? = null,
    val hrv: Double? = null,
    val hrvAt: String? = null,
    val stress: Double? = null,
    val stressAt: String? = null,
    val temperature: Double? = null,
    val temperatureAt: String? = null,
    val respiratoryRate: Double? = null,
    val respiratoryRateAt: String? = null,

    val stepsToday: Int? = null,
    val distanceTodayMeters: Double? = null,
    val activeCaloriesToday: Double? = null,

    val sleepDate: String? = null,
    val sleepStart: String? = null,
    val sleepEnd: String? = null,
    val sleepMinutes: Int? = null,
    val sleepScore: Int? = null,
) {
    companion object {
        /** A reading older than this is not "current" and is left out rather than shown as now. */
        const val MAX_READING_AGE_MS = 7 * 24 * 3600_000L

        /** Pure assembly from already-loaded rows, so the selection rules are testable. */
        fun from(
            now: Long,
            zone: ZoneId,
            device: DeviceEntity?,
            latest: Map<MeasurementKind, MeasurementEntity?>,
            restingHeartRate: Double?,
            today: ActivityDailyEntity?,
            preferPhoneSteps: Boolean,
            lastSleep: SleepSessionEntity?,
        ): HomeAssistantSnapshot {
            fun iso(ts: Long?) = ts?.let { DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(it).atZone(zone)) }
            fun fresh(kind: MeasurementKind) = latest[kind]?.takeIf { now - it.timestamp in 0..MAX_READING_AGE_MS }

            val hr = fresh(MeasurementKind.HEART_RATE)
            val spo2 = fresh(MeasurementKind.SPO2)
            val hrv = fresh(MeasurementKind.HRV)
            val stress = fresh(MeasurementKind.STRESS)
            val temp = fresh(MeasurementKind.TEMPERATURE)
            val resp = fresh(MeasurementKind.RESPIRATORY_RATE)

            val realToday = today?.takeIf { it.source !in DEMO_SOURCES }
            val sleep = lastSleep?.takeIf { it.sourceRaw !in DEMO_SOURCES && it.totalMinutes > 0 }

            return HomeAssistantSnapshot(
                sentAt = iso(now)!!,
                deviceName = device?.advertisedName ?: device?.name,
                deviceModel = device?.wearableModelID ?: device?.deviceTypeRaw,
                deviceState = device?.stateRaw?.lowercase(),
                battery = device?.batteryPercent,
                firmware = device?.firmwareVersion,
                lastSyncAt = iso(device?.lastFullSyncAt ?: device?.lastSyncAt),
                heartRate = hr?.value, heartRateAt = iso(hr?.timestamp),
                restingHeartRate = restingHeartRate,
                spo2 = spo2?.value, spo2At = iso(spo2?.timestamp),
                hrv = hrv?.value, hrvAt = iso(hrv?.timestamp),
                stress = stress?.value, stressAt = iso(stress?.timestamp),
                temperature = temp?.value, temperatureAt = iso(temp?.timestamp),
                respiratoryRate = resp?.value, respiratoryRateAt = iso(resp?.timestamp),
                stepsToday = DisplaySteps.count(realToday, preferPhoneSteps),
                distanceTodayMeters = realToday?.distanceMeters,
                activeCaloriesToday = realToday?.let { DailyCalorieEstimator.effectiveActiveCalories(it) },
                sleepDate = sleep?.let { Instant.ofEpochMilli(it.date).atZone(zone).toLocalDate().toString() },
                sleepStart = iso(sleep?.startAt),
                sleepEnd = iso(sleep?.endAt),
                sleepMinutes = sleep?.totalMinutes,
                sleepScore = sleep?.score,
            )
        }

        /** Reads the rows [from] needs. Every query here is a real-only one. */
        suspend fun load(
            db: PulseLoopDatabase,
            preferPhoneSteps: Boolean,
            now: Long = System.currentTimeMillis(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): HomeAssistantSnapshot {
            val kinds = listOf(
                MeasurementKind.HEART_RATE, MeasurementKind.SPO2, MeasurementKind.HRV,
                MeasurementKind.STRESS, MeasurementKind.TEMPERATURE, MeasurementKind.RESPIRATORY_RATE,
            )
            val m = db.measurementDao()
            val latest = kinds.associateWith { m.latestReal(it.name, now) }
            return from(
                now = now,
                zone = zone,
                device = db.deviceDao().currentReal(),
                latest = latest,
                restingHeartRate = db.userProfileDao().get()?.hrRestingBaseline,
                today = db.activityDailyDao().byDay(TimeUtil.startOfDayLocal(now, zone)),
                preferPhoneSteps = preferPhoneSteps,
                lastSleep = mainSleep(db.sleepSessionDao().recentReal(5)),
            )
        }

        /** The newest day's longest session — a nap shares its date with the night, and the
         *  night is the one to report (same rule as `SleepSessionDao.byDay`). */
        internal fun mainSleep(recent: List<SleepSessionEntity>): SleepSessionEntity? {
            val newest = recent.maxOfOrNull { it.date } ?: return null
            return recent.filter { it.date == newest }.maxByOrNull { it.totalMinutes }
        }

        private val DEMO_SOURCES = setOf("demo", "mock")
    }
}
