# Total IPTV Pro — Desktop (Ubuntu)

Linux desktop IPTV client for **Ubuntu**. Dark UI, Xtream Codes + M3U support, Live TV / Movies / Series browse with posters, Favorites, movie detail (plot/cast) before play, TV Guide (EPG), Settings, search, and external VLC/mpv playback.

This project is **separate** from the Android Total IPTV Pro / ChannelBox app. Do not mix the two trees.

## Requirements

- Ubuntu 22.04+ (developed on Ubuntu 26.04)
- **JDK 21** (recommended). A portable Temurin 21 can live at `~/.jdks/temurin-21`.
- A media player: **VLC** (recommended), or `mpv`, or `ffplay`  
  `sudo apt install vlc`

## Launch (Bill’s machine)

```bash
cd ~/TotalIptvPro-Desktop
./run.sh
```

Or:

```bash
export JAVA_HOME=~/.jdks/temurin-21
cd ~/TotalIptvPro-Desktop
./gradlew run
```

Desktop menu entry: **Total IPTV Pro** (`~/.local/share/applications/total-iptv-pro.desktop`).

First launch opens onboarding:

1. **Xtream** — server URL (e.g. `http://host:port`), username, password  
2. **M3U URL** — paste a playlist URL  

Prefs are stored at `~/.config/total-iptv-pro/prefs.json` (plus `resume.json` / `favorites.json`) and survive restarts. Use **Update** in the sidebar to reload Live / Movies / Series from the saved login (no re-entry). **Change source** (sidebar or Settings) clears onboarding only when you want a new server/playlist.

### UI layout

- Top **banner** (`app_banner.png`) stays along the top of the window.
- Sidebar: Home · Live TV · Movies · Series · **TV Guide** · **Favorites** · **Settings**, plus Update / Change source.
- **Movies / Series**: poster grid **5 or 6 across** (set in Settings; default 6).
- **Live TV**: list with logos (fits channels better).
- **TV Guide**: category filter, channel list, EPG timeline + schedule; Watch / click a program plays that live stream (Xtream `get_short_epg` / `get_simple_data_table`).
- **Settings**: preferred player, theme (dark/light), poster columns, source info, Change source. Does not force re-login.

Click a **movie** to open detail (plot, cast, Play / Favorite) before playback. Click a **channel** to play live. Open a **series** for episodes (also favoritable). Heart icons mark Favorites. Use **Stop player** to close playback.

### Series next episode (Linux + Windows)

Both platforms launch **one** episode URL. The app starts SxxE(n+1) when the player exits at end of episode after a real play (about 20 seconds; failed/short VLC exits do not auto-advance). **Do not use VLC’s playlist Next** — it replays the same episode. Next never relaunches the same episode id/URL.

Live channels (Xtream HLS `.m3u8`) do **not** use VLC `--play-and-exit`, so a valid sliding live window stays up. The 1.2.9 VOD short-play guard is unchanged.

**TV Guide** clock, hour ticks, program ranges, and the now-line follow the computer timezone (`OsTimeZone` / Windows `tzutil`). Naive Xtream `start`/`end` use that zone (same as Android). Unix `start_timestamp` is a UTC instant when it agrees with the label; if the unix field is local wall mislabeled as UTC, the now-line alignment uses the OS/provider wall time.

EPG fetch is always `get_short_epg?stream_id=` (never `epg_channel_id`). USA Movies-style streams that share one XMLTV id get a **now** overlay from the live channel name when that name does not match the shared listing (Masters of the Universe vs The Godfather).

- **Linux (GTR):** auto-advance at end of episode. Skip with the always-on-top **Next SxEx** control, the in-app Next button, or **Ctrl+Right** / **Media Next** while Total IPTV Pro is focused. Global hotkeys are not registered (they would need root). Fullscreen VLC often keeps keyboard focus — Alt+Tab back to this app, or wait for the episode to finish.
- **Windows:** leftover `vlc.exe` is `taskkill`’d; `--ignore-config` / `--no-one-instance`. Skip with the always-on-top **Next SxEx** control or **Ctrl+Right** / **Media Next** (OS hotkeys while a series is playing).
- On the last episode Next is disabled and shows “Last episode of this series.”
- Each series launch appends one line to `playback-debug.log` (Linux: `~/.config/total-iptv-pro/`, Windows: `%APPDATA%\\total-iptv-pro\\`). Look for `playlist=false` and `reason=auto-advance` after an episode ends.

## Build

```bash
export JAVA_HOME=~/.jdks/temurin-21   # if system Java is older/newer
./gradlew compileKotlin
./gradlew run
./gradlew packageDeb          # optional
./gradlew packageAppImage     # optional
```

`gradle.properties` already points `org.gradle.java.home` at `~/.jdks/temurin-21` on this machine.

## Xtream URLs used

- API: `{base}/player_api.php?username=…&password=…&action=…`
- Actions: `get_live_categories`, `get_live_streams`, `get_vod_categories`, `get_vod_streams`, `get_vod_info`, `get_series_categories`, `get_series`, `get_series_info`, `get_short_epg`, `get_simple_data_table`
- Live: `{base}/live/{user}/{pass}/{stream_id}.m3u8`
- VOD: `{base}/movie/{user}/{pass}/{stream_id}.{ext}`
- Series episode: `{base}/series/{user}/{pass}/{episode_id}.{ext}`

Artwork uses Xtream `stream_icon` / `cover` / `backdrop_path` in grids and episode lists.

Playback always uses **`stream_id` / episode `id`**, never Xtream `num`.

## Project layout

```
src/main/kotlin/com/totaliptv/pro/desktop/
  Main.kt
  data/       # models, prefs, Xtream, M3U, EPG
  ui/         # Compose screens + RemoteArtwork + Guide + Settings
  player/     # VLC / mpv / ffplay launcher
```

## App icon

Same launcher artwork as Android TV (`com.totaliptv.pro` → `res/drawable/ic_launcher.png`):

- `src/main/resources/icon.png` — Compose window / taskbar + linux package icon
- `src/main/resources/app_banner.png` — top banner in the main UI
- `~/.local/share/icons/hicolor/512x512/apps/total-iptv-pro.png` — app menu (`.desktop` uses `Icon=total-iptv-pro`)

## Notes

- Android ChannelBox must remain untouched; this desktop app only mirrors API concepts.
- M3U sources have no Xtream EPG; Guide will show channels but empty schedules.

## In-app updates (LAN shelf)

Mirror of the Shield “App updates” flow for Ubuntu kitchen PCs.

1. Bump `AppVersion.VERSION_CODE` / `VERSION_NAME` and `build.gradle.kts` `version` / `packageVersion` together.
2. Publish: `./publish-update.sh`  
   - runs `createDistributable`, writes `~/TotalIptvPro-Desktop-shelf/version.json` + `TotalIptvPro-linux.tar.gz`  
   - starts `python3 -m http.server 8767` if the port is free (PID in `~/TotalIptvPro-Desktop-shelf/http-server.pid`)
3. On the kitchen PC: Settings → **App updates** → shelf URL default `http://192.168.4.39:8767/` → **Check for update**.
4. After install: **Restart now**, or relaunch:
   - `~/.local/share/total-iptv-pro/app/bin/TotalIptvPro`  
   - or the app menu entry (`~/.local/share/applications/total-iptv-pro.desktop`)

Smoke: `curl http://127.0.0.1:8767/version.json`

Dev run (unchanged): `./run.sh` or `./gradlew run`


Developed by Bill Foster.
