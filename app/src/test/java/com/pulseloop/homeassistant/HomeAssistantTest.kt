package com.pulseloop.homeassistant

import com.pulseloop.coach.local.LocalEndpoint
import com.pulseloop.data.entity.ActivityDailyEntity
import com.pulseloop.data.entity.MeasurementEntity
import com.pulseloop.data.entity.SleepSessionEntity
import com.pulseloop.ring.MeasurementKind
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId

class HomeAssistantTest {

    // ── Endpoint ──────────────────────────────────────────────────────────────

    @Test
    fun `LAN http base builds the registration and webhook urls`() {
        assertEquals(
            "http://192.168.1.200:8123/api/mobile_app/registrations",
            HomeAssistantEndpoint.registrationUrl("192.168.1.200:8123/"),
        )
        assertEquals(
            "http://192.168.1.200:8123/api/webhook/abc",
            HomeAssistantEndpoint.webhookUrl("192.168.1.200:8123/", "abc"),
        )
    }

    @Test
    fun `a pasted full webhook url is reduced to its base`() {
        assertEquals(
            "https://ha.example.com",
            HomeAssistantEndpoint.normalizeBase("https://ha.example.com/api/webhook/xyz"),
        )
    }

    @Test
    fun `public cleartext is refused`() {
        assertEquals(LocalEndpoint.Problem.PUBLIC_CLEARTEXT, HomeAssistantEndpoint.validateBase("http://ha.example.com"))
        assertNull(HomeAssistantEndpoint.webhookUrl("http://ha.example.com", "abc"))
    }

    @Test
    fun `a webhook id that could escape the path is refused`() {
        assertNull(HomeAssistantEndpoint.webhookUrl("http://10.0.0.2:8123", "../api/states"))
        assertNull(HomeAssistantEndpoint.webhookUrl("http://10.0.0.2:8123", "a b"))
        assertNull(HomeAssistantEndpoint.webhookUrl("http://10.0.0.2:8123", ""))
    }

    // ── Snapshot ──────────────────────────────────────────────────────────────

    private val zone = ZoneId.of("Europe/Moscow")
    private val now = 1_763_200_000_000L // 2025-11-15 ~12:46 MSK

    private fun m(kind: MeasurementKind, value: Double, ageMs: Long) = MeasurementEntity(
        kindRaw = kind.name, value = value, unit = kind.unit, timestamp = now - ageMs, sourceRaw = "history",
    )

    @Test
    fun `stale readings are dropped and fresh ones carry their own time`() {
        val snap = HomeAssistantSnapshot.from(
            now = now, zone = zone, device = null,
            latest = mapOf(
                MeasurementKind.HEART_RATE to m(MeasurementKind.HEART_RATE, 64.0, 60_000),
                MeasurementKind.SPO2 to m(MeasurementKind.SPO2, 97.0, HomeAssistantSnapshot.MAX_READING_AGE_MS + 1),
            ),
            restingHeartRate = 52.5, today = null, preferPhoneSteps = false, lastSleep = null,
        )
        assertEquals(64.0, snap.heartRate!!, 0.0)
        assertTrue(snap.heartRateAt!!.endsWith("+03:00"))
        assertNull(snap.spo2)
        assertNull(snap.spo2At)
        assertEquals(52.5, snap.restingHeartRate!!, 0.0)
    }

    @Test
    fun `demo activity and sleep never leave the app`() {
        val snap = HomeAssistantSnapshot.from(
            now = now, zone = zone, device = null, latest = emptyMap(), restingHeartRate = null,
            today = ActivityDailyEntity(date = 0, steps = 9000, source = "demo"),
            preferPhoneSteps = false,
            lastSleep = SleepSessionEntity(date = 0, startAt = 0, endAt = 1, totalMinutes = 420, sourceRaw = "demo"),
        )
        assertNull(snap.stepsToday)
        assertNull(snap.sleepMinutes)
    }

    @Test
    fun `phone step preference is honoured`() {
        val day = ActivityDailyEntity(date = 0, steps = 4000, phoneSteps = 5200, source = "ring")
        val snap = HomeAssistantSnapshot.from(
            now = now, zone = zone, device = null, latest = emptyMap(), restingHeartRate = null,
            today = day, preferPhoneSteps = true, lastSleep = null,
        )
        assertEquals(5200, snap.stepsToday)
    }

    @Test
    fun `main sleep is the newest day's longest session, not its nap`() {
        val night = SleepSessionEntity(date = 2, startAt = 0, endAt = 1, totalMinutes = 410)
        val nap = SleepSessionEntity(date = 2, startAt = 2, endAt = 3, totalMinutes = 35)
        val older = SleepSessionEntity(date = 1, startAt = 0, endAt = 1, totalMinutes = 500)
        assertEquals(night, HomeAssistantSnapshot.mainSleep(listOf(nap, older, night)))
    }

    private fun emptySnap() = HomeAssistantSnapshot.from(
        now = now, zone = zone, device = null, latest = emptyMap(), restingHeartRate = null,
        today = null, preferPhoneSteps = false, lastSleep = null,
    )

    // ── Sensor payloads ───────────────────────────────────────────────────────

    @Test
    fun `unique ids are distinct and register body carries metadata`() {
        val ids = HomeAssistantSensors.all.map { it.uniqueId }
        assertEquals(ids.size, ids.toSet().size)
        val temp = HomeAssistantSensors.all.first { it.uniqueId == "temperature" }
        val body = HomeAssistantSensors.registerBody(temp, emptySnap()).toString()
        assertTrue(body, body.contains("\"type\":\"register_sensor\""))
        assertTrue(body, body.contains("\"device_class\":\"temperature\""))
        assertTrue(body, body.contains("\"state\":null"))
    }

    @Test
    fun `update body sends every sensor with its state`() {
        val snap = emptySnap().copy(heartRate = 61.0)
        val body = HomeAssistantSensors.updateBody(snap)
        val data = body["data"]!!.jsonArray
        assertEquals(HomeAssistantSensors.all.size, data.size)
        val hr = data.first { it.jsonObject["unique_id"]!!.jsonPrimitive.content == "heart_rate" }.jsonObject
        assertEquals(61.0, hr["state"]!!.jsonPrimitive.double, 0.0)
    }

    // ── Sync against a mock HA ────────────────────────────────────────────────

    private class FakeStore(override val baseUrl: String, override var webhookId: String?) : HomeAssistantRegistrationStore {
        override var registeredSchema = 0
        var cleared = false
        override fun clearRegistration() { cleared = true; webhookId = null; registeredSchema = 0 }
    }

    private fun MockWebServer.base() = url("/").toString().trimEnd('/')

    @Test
    fun `first push registers every sensor then updates`() {
        MockWebServer().use { server ->
            repeat(HomeAssistantSensors.all.size) { server.enqueue(MockResponse().setResponseCode(201).setBody("{\"success\":true}")) }
            server.enqueue(MockResponse().setBody("{\"heart_rate\":{\"success\":true}}"))
            server.start()
            val store = FakeStore(server.base(), "abc123")
            assertEquals(HomeAssistantSync.Outcome.Ok, HomeAssistantSync(store).push(emptySnap()))
            assertEquals(HomeAssistantSensors.SCHEMA, store.registeredSchema)
            assertEquals(HomeAssistantSensors.all.size + 1, server.requestCount)
            assertEquals("/api/webhook/abc123", server.takeRequest().path)
        }
    }

    @Test
    fun `sensor deleted in HA is registered again`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"spo2\":{\"success\":false,\"error\":{\"code\":\"not_registered\"}},\"hrv\":{\"success\":true}}"))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            server.enqueue(MockResponse().setBody("{}"))
            server.start()
            val store = FakeStore(server.base(), "abc").apply { registeredSchema = HomeAssistantSensors.SCHEMA }
            assertEquals(HomeAssistantSync.Outcome.Ok, HomeAssistantSync(store).push(emptySnap()))
            server.takeRequest()
            val reRegister = server.takeRequest().body.readUtf8()
            assertTrue(reRegister, reRegister.contains("\"unique_id\":\"spo2\""))
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `410 clears the registration`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(410))
            server.start()
            val store = FakeStore(server.base(), "abc").apply { registeredSchema = HomeAssistantSensors.SCHEMA }
            assertEquals(HomeAssistantSync.Outcome.Gone, HomeAssistantSync(store).push(emptySnap()))
            assertTrue(store.cleared)
        }
    }

    @Test
    fun `registration sends the token and returns the webhook id`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(201).setBody("{\"webhook_id\":\"deadbeef\",\"secret\":null}"))
            server.start()
            val info = HomeAssistantClient.DeviceInfo("dev-1", "PulseLoop", "2.7.0", "Google", "Pixel 8a", "16")
            val r = HomeAssistantClient().register(HomeAssistantEndpoint.registrationUrl(server.base())!!, "tok", info)
            assertEquals(HomeAssistantClient.Result.Ok("deadbeef"), r)
            val req = server.takeRequest()
            assertEquals("/api/mobile_app/registrations", req.path)
            assertEquals("Bearer tok", req.getHeader("Authorization"))
            assertTrue(req.body.readUtf8().contains("\"app_id\":\"com.pulseloop\""))
        }
    }

    @Test
    fun `a 2xx without webhook id is not treated as registered`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>router login</html>"))
            server.start()
            val info = HomeAssistantClient.DeviceInfo("d", "PulseLoop", "1", "m", "m", "1")
            val r = HomeAssistantClient().register(HomeAssistantEndpoint.registrationUrl(server.base())!!, "t", info)
            assertEquals(HomeAssistantClient.Result.HttpError(HomeAssistantClient.INVALID_RESPONSE), r)
        }
    }

    @Test
    fun `client does not follow redirects`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "http://example.com/"))
            server.start()
            val r = HomeAssistantClient().webhook(server.url("/api/webhook/x").toString(), kotlinx.serialization.json.JsonObject(emptyMap()))
            assertEquals(HomeAssistantClient.Result.HttpError(307), r)
            assertEquals(1, server.requestCount)
        }
    }
}
