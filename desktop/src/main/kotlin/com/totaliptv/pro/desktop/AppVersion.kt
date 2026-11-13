package com.totaliptv.pro.desktop

/**
 * Local app version — must match build.gradle.kts version / packageVersion
 * and the shelf version.json shipped by publish-update.sh.
 * 1.2.0: next-episode playlist, series resume highlight, 15s logo splash.
 * 1.2.1: Windows VLC multi-URL argv (did not fix Next on Bigboybill).
 * 1.2.2: Windows kills leftover VLC and plays one episode URL; app sequential-next.
 * 1.2.3: In-app Next SxEx shown whenever a following episode exists (not only after resume).
 * 1.2.4: Windows fullscreen Next via Ctrl+Right / Media Next + always-on-top control.
 * 1.2.5: Last-episode UX — overlay/hotkey say "Last episode of this series" instead of looking broken.
 * 1.2.6: Real quit (no overlay restart loop), unfreeze leaving VLC fullscreen, splash version.
 * 1.2.7: Overlay is not a Compose application Window; quit disposes it then halt-exits.
 * 1.2.8: Version in window title + splash chip; Linux series uses one URL and auto-advances.
 * 1.2.9: Guard auto-advance on short VLC exits; Quit kills the player tree then halt;
 *        Linux next episode must change URL (never relaunch the same episode).
 * 1.2.10: Windows live HLS stays up (no --play-and-exit); guide follows OS timezone.
 * 1.2.11: EPG now-line vs blocks — naive times use OS/provider zone (Android);
 *         Windows tzutil so Central is Chicago when the JRE reports UTC.
 * 1.2.12: Personal DVR — Live/Guide capture, movie + series download, library on this PC.
 * 1.2.13: DVR opt-in only; series Record controls; last-episode banner auto-dismiss.
 * 1.2.14: Record stays lit for the active item; last-episode banner 4s dismiss on Windows+Linux.
 * 1.2.15: Prefer English audio on VLC/mpv when a stream has multiple tracks.
 * 1.2.21: Game Day split screen (VLC), resume position, M3U XMLTV guide,
 *         manual guide offset, remembered window, GitHub Releases updater.
 * 1.2.22: Live, movies, and series open the external player full screen.
 *         Settings can turn that off. Game Day windows meet with no gap.
 * 1.2.23: Update check lists GitHub releases and uses the newest one that
 *         has this OS's desktop package, skipping Android-only releases.
 * 1.2.24: Next-episode banner measures its text so the label is not clipped,
 *         including GNOME text scaling and HiDPI.
 * 1.2.25: TV guide fills the measured width (12–24h). Linux playback enters
 *         VLC fullscreen on the app's monitor so the Qt chrome stays hidden.
 * 1.2.26 (personal): Store-edition improvements without Store restrictions —
 *         TV-picture splash with spinning loader after sign-in, series
 *         Stop/Hide/Next + audio menu only on right-click (not mouse move),
 *         no-sound / volume-mixer notes in the README, Classic TV Guide default.
 * 1.2.27: Category/season chip rows scroll with wheel, Shift+wheel and Left/Right
 *         keys, with end arrows; Settings gets a wide visible scroll bar.
 * 1.2.28: "Start in full screen" (on by default) — the app window opens borderless full
 *         screen with no title bar or taskbar; F11 toggles and is remembered; Esc leaves
 *         full screen except while a video plays.
 * 1.2.29: Android 1.4.100 features on desktop — Movies/Series groups in order
 *         Newly added (default), Top rated (TMDB only, 50+ votes), Netflix, Prime Video,
 *         HBO / Max, Apple TV+, Hulu, Disney+, Paramount+, then provider groups;
 *         trademark line in Settings / About; TMDB results cached and resumed across
 *         restarts; background work paused while playing; last screen and group restored.
 * 1.2.30: Stopping playback (including the Windows right-click Stop menu) returns
 *         to the title page in the full-size app window and no longer minimizes it.
 * 1.2.31: After Stop, un-minimize only when the window is actually iconic, repair
 *         collapsed bounds, and force a Compose redraw so the title page is not a
 *         blank white surface.
 */
object AppVersion {
    const val VERSION_CODE: Int = 43
    const val VERSION_NAME: String = "1.2.31"
}
