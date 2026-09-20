# Total IPTV Pro 2 (Android TV / NVIDIA Shield)

Second Shield/Android TV app that mirrors the **Ubuntu desktop** Total IPTV Pro look (dark `#0B0F14`, amber/gold accents) and Xtream Codes features. Installs **beside** the original Total IPTV Pro (`com.totaliptv.pro`) — this app uses **`com.totaliptv.pro2`**.

Does **not** modify ChannelBox / `com.totaliptv.pro` (that app lives in [`channelbox/`](../channelbox/)).

## Package

| | |
|---|---|
| applicationId | `com.totaliptv.pro2` |
| App name | Total IPTV Pro 2 |
| Launcher | Leanback (`LEANBACK_LAUNCHER`) + standard launcher |
| Banner | `res/drawable/tv_banner.png` (from desktop `app_banner.png`) |

## Features

- Sidebar: Home · Live · Movies · Series · TV Guide · Settings
- Top banner with logo + name
- Home: Continue watching + Top rated new American movies
- Movies/Series: poster grid 5–6 columns, search, categories, sort A–Z / Z–A / Most recent
- Live channel list with logos
- TV Guide with Xtream EPG (`get_short_epg` / `get_simple_data_table`)
- Settings: poster columns, Update (reload catalog), Change source
- Xtream Codes login persisted in app SharedPreferences
- Playback via Media3 ExoPlayer (D-pad friendly)
- Focus outlines for Shield remote

## Build (Ubuntu)

```bash
export JAVA_HOME=~/.jdks/temurin-21
cd ~/TotalIptvPro2-Android
./gradlew assembleDebug
```

Debug APK:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Install on NVIDIA Shield (sideload)

1. Enable **Developer options** → **USB debugging** (and/or network debugging).
2. Connect Shield to the same network / USB.
3. From this PC:

```bash
adb connect <shield-ip>   # if wireless
adb install -r ~/TotalIptvPro2-Android/app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to USB storage and install with a file manager / Downloader.

The original **Total IPTV Pro** remains installed; both appear under Apps.

## First run

Enter Xtream server URL (`http://host:port`), username, and password. Prefs survive restarts. Use **Update** to reload Live/Movies/Series without re-entering credentials. **Change source** returns to onboarding.

## In-app app updates (Pro 2 shelf)

Settings → **Check for update** fetches Pro-2-only metadata (never the original Total IPTV Pro `version.json`):

- `http://192.168.4.39:8766/version-pro2.json` (primary Ubuntu apk-shelf)
- also tries `…/pro2/version.json`
- fallbacks: `http://10.0.2.2:8766/` (emulator→host), `http://192.168.4.37:8765/` (only if Pro 2 files exist there)

Shape:

```json
{"versionCode":2,"versionName":"1.0.1","apk":"TotalIptvPro2.apk"}
```

If remote `versionCode` > installed → download `TotalIptvPro2.apk` → Install via FileProvider (requests unknown-sources / install-packages on TV as needed). Catalog login is untouched.

Serve shelf:

```bash
cd ~/apk-shelf && python3 -m http.server 8766 --bind 0.0.0.0
```

Shield / devices on LAN: use **http://192.168.4.39:8766/**

## Gaps vs Ubuntu desktop


- No M3U playlist source (Xtream only on this build).
- No light theme toggle (dark amber UI only).
- Playback is in-app ExoPlayer (desktop uses external VLC/mpv).
- Continue-watching tracks last opened title; scrub position only when ExoPlayer reports it (not persisted mid-stream yet).
- Series “top rated” row from desktop Home is omitted (movies row is present).

## Project layout

```
app/src/main/java/com/totaliptv/pro2/
  MainActivity.kt
  AppViewModel.kt
  data/     # Models, XtreamApi, prefs, resume, repository
  ui/       # Theme, screens, panes, components
  player/   # ExoPlayer PlayerActivity
```


Developed by Bill Foster.
