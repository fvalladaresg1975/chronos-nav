# Chronos Companion

A from-scratch Android companion app for smartwatches running [fbiego's ChronosESP32](https://github.com/fbiego/chronos-esp32) firmware (or a compatible fork), built as an open-source alternative to the official closed-source Chronos app.

It talks to the watch over Bluetooth Low Energy using the same protocol the ChronosESP32 firmware library expects, reverse-engineered from the firmware's own receive-side parsing code (no official protocol documentation exists).

## Features

- **Notifications** — forwards WhatsApp, SMS, Gmail, Telegram, and other app notifications to the watch.
- **Google Maps navigation** — sends turn-by-turn directions and the maneuver icon while navigating.
- **Music control** — play/pause/next/previous/volume from the watch, dispatched to whichever app is currently playing media.
- **Weather** — current conditions + 7-day forecast, high/low, UV index, pressure, and city name, fetched from [Open-Meteo](https://open-meteo.com/) (free, no API key) using the phone's location.
- **Time sync** — keeps the watch's clock accurate.
- **Device management** — scan for nearby compatible watches, switch between multiple boards, or forget the current one.
- A persistent notification with a **Stop** action to end the BLE connection on demand.

## Requirements

- A watch running ChronosESP32-based firmware (tested against a custom fork targeting a Waveshare ESP32-S3-Touch-AMOLED-1.75).
- Android 8.0 (API 26) or later.

## Building

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`. Open the project in Android Studio, or install directly via `adb install -r app/build/outputs/apk/debug/app-debug.apk` with a device connected.

## Permissions

| Permission | Why |
|---|---|
| Bluetooth (scan/connect) | Talk to the watch over BLE. |
| Notification access | Read notifications from other apps to forward to the watch. |
| Coarse location | Fetch weather for the phone's current location. |
| Foreground service | Keep the BLE connection alive while backgrounded. |
| Internet | Fetch weather data. |

No data leaves the phone except weather requests to Open-Meteo (by coordinates) and reverse-geocoding via Android's built-in `Geocoder`.

## License

MIT — see [LICENSE](LICENSE).

This project is not affiliated with or endorsed by fbiego/ChronosESP32 or the official Chronos app.
