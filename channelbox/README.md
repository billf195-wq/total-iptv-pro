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

## Release signing

Release APKs use one keystore so a build from any machine installs over the last release. The keystore file and its passwords stay outside git. Do not commit them.

Create the key once. Back it up outside this repo (a password manager or an encrypted copy). Losing the key means you cannot update installs that were signed with it.

```bash
mkdir -p "$HOME/keys"
keytool -genkeypair -v \
  -storetype PKCS12 \
  -keystore "$HOME/keys/total-iptv-pro-release.jks" \
  -alias totaliptvpro \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keytool` asks for the store password and key password. Leave the file under `$HOME/keys` (or another path outside the repo).

Build signed TV and phone release APKs:

```bash
export TIP_KEYSTORE_PATH="$HOME/keys/total-iptv-pro-release.jks"
export TIP_KEYSTORE_PASSWORD="the store password"
export TIP_KEY_ALIAS="totaliptvpro"
export TIP_KEY_PASSWORD="the key password"
cd channelbox
./gradlew :app:assembleTvRelease :app:assemblePhoneRelease
```

The same four values can live in `channelbox/keystore.properties`, which is gitignored. Environment variables override the file.

```
storeFile=/absolute/path/to/total-iptv-pro-release.jks
storePassword=the store password
keyAlias=totaliptvpro
keyPassword=the key password
```

`storeFile` may be absolute, or relative to `channelbox/`.

If the keystore is missing, the release build still finishes and Gradle prints a warning. Those APKs are signed with the debug key, so they will not install over a release signed on another machine.

Release output:

- `app/build/outputs/apk/tv/release/TotalIPTVPro-android-tv-<ver>-release.apk`
- `app/build/outputs/apk/phone/release/TotalIPTVPro-android-phone-<ver>-release.apk`

The phone `versionName` ends in `-phone`. The file name uses the numeric version only. The in-app updater accepts both `TotalIPTVPro-android-*-<ver>-debug.apk` and `TotalIPTVPro-android-*-<ver>-release.apk`.

## Layout

```
app/src/main/   # shared data, player, onboarding
app/src/tv/     # Shield / Android TV UI (classic + desktop-style panes)
app/src/phone/  # phone UI
```

Developed by Bill Foster.
