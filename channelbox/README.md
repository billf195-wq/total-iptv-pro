# Total IPTV Pro (ChannelBox)

Original **phone + NVIDIA Shield / Android TV** app.

| | |
|---|---|
| Folder nickname | `channelbox/` (Bill’s ChannelBox tree) |
| applicationId | `com.totaliptv.pro` (phone flavor: `com.totaliptv.pro.phone`) |
| App name | Total IPTV Pro |

This tree is **separate from** [`android/`](../android/) **Total IPTV Pro 2** (`com.totaliptv.pro2`). Both can be installed on the same Shield. Do not mix their catalogs, prefs, or update shelves.

Lean source snapshot imported from Bill Foster’s Bigboybill PC (main + phone + tv Kotlin, Gradle). Guide and Live share `LiveChannelMapping` so rows line up on `stream_id` (never provider `num`).

## Features

- M3U URL or Xtream Codes login
- Phone and TV (Leanback) flavors
- Live categories with logos, movies/series posters
- Live TV Guide (Xtream `get_short_epg` / `get_simple_data_table`)
- Favorites, continue watching, Media3 ExoPlayer
- Personal DVR (1.4.57 / phone 1.4.34): Record Live/Guide (HLS/.ts capture + EPG schedule), download a movie or series episode from detail / player, library with type (Live / Movie / Series) on **this device** (`Android/data/…/Movies/TotalIptvPro/Recordings`)
- Game Day split screen on Live TV and the Guide (two built-in players, one side of audio)
- In-app update check: GitHub Releases first, then the LAN shelf at `http://192.168.4.33:8765/` (phone: `/phone/`)

## Build

```bash
cd channelbox
./gradlew :app:assembleTvDebug :app:assemblePhoneDebug
```

APKs:

- `app/build/outputs/apk/tv/debug/app-tv-debug.apk`
- `app/build/outputs/apk/phone/debug/app-phone-debug.apk`

## Layout

```
app/src/main/   # shared data, player, onboarding
app/src/tv/     # Shield / Android TV UI (classic + desktop-style panes)
app/src/phone/  # phone UI
```

Developed by Bill Foster.
