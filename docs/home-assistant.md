# Home Assistant integration

PulseLoop registers with Home Assistant's built-in
[`mobile_app` integration](https://developers.home-assistant.io/docs/api/native-app-integration),
the same API HA Companion uses. It appears in HA as a device named **PulseLoop** with its own
sensors. Nothing is installed or configured on the HA side: `mobile_app` is part of
`default_config`.

## Setup

1. In HA: profile → **Security** → **Long-lived access tokens** → create one.
2. In the app: **Settings → Home Assistant**. Enter the HA URL (`http://<lan-ip>:8123`) and the
   token, then tap **Connect**.

The token is used for that one `POST /api/mobile_app/registrations` and is not stored. The
registration returns a webhook id, and every later request goes to `/api/webhook/<id>` without the
token. **Disconnect** only forgets the registration locally. The device stays in HA until it is
deleted there (Devices & services → Mobile App).

## Sensors

`HomeAssistantSensors.all`. HA names entities `<device>_<sensor>`, so they come out as
`sensor.pulseloop_heart_rate` etc.

| unique_id | Unit | Notes |
| --- | --- | --- |
| `heart_rate`, `spo2`, `hrv`, `stress`, `temperature`, `respiratory_rate` | bpm, %, ms, -, °C, breaths/min | Latest **real** reading. Attribute `measured_at`. Older than 7 days → `unknown` |
| `resting_heart_rate` | bpm | `UserProfile.hrRestingBaseline` (30-day p10) |
| `steps_today`, `distance_today`, `active_calories_today` | steps, m, kcal | Today. Steps follow the ring/phone step-source preference (`DisplaySteps`) |
| `sleep` | min | Newest waking day's main session, time asleep. Attributes `date`, `start`, `end` |
| `sleep_score` | - | |
| `ring_battery`, `ring_state`, `last_sync` | %, -, timestamp | Diagnostic. `ring_state` carries ring name, model and firmware |

Demo and mock rows are never sent (`DemoDataPolicy`): HA's recorder would keep a seeded value long
after the demo data behind it was cleared.

`unique_id`s are permanent. Renaming one orphans the old entity and its history. To add a sensor
or change its metadata, bump `HomeAssistantSensors.SCHEMA`, and the next push re-registers
everything (`update_sensor_states` can only change state, icon and attributes).

## When it pushes

On the same triggers as the Health Connect export: a completed foreground sync
(`SyncProgress("done")`, `EventPersistenceSubscriber`) and the background `RingSyncWorker` pass
(about every 30 min). `HomeAssistantPushWorker` debounces them by 10 s and needs a network. A
network error or 5xx retries up to 3 times with backoff.

`HomeAssistantSync` handles the protocol:
- **First push under a schema:** one `register_sensor` per sensor, then `update_sensor_states`.
- **`not_registered` in the update reply** (a user deleted an entity): only those are registered
  again, then the update is retried once.
- **410 Gone:** the device was deleted in HA. The registration is cleared and the user has to
  connect again.

## Security

- `http://` is allowed only for private/LAN hosts. The rule is `LocalEndpoint.validate`, shared
  with the local coach provider. Anything public must be `https://`.
- Redirects are not followed, so a redirect cannot carry the body to an unvetted host.
- The webhook id is the remaining credential. It is stored in EncryptedSharedPreferences and is
  validated before it goes into a URL path.
- `supports_encryption: false`. Payload encryption (libsodium) is left out. Over LAN `http://`, the
  readings travel unencrypted inside the home network, just as they do for HA Companion without
  encryption.
