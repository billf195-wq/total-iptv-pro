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
 *         Settings can turn that off. Game Day split screen is unchanged.
 */
object AppVersion {
    const val VERSION_CODE: Int = 34
    const val VERSION_NAME: String = "1.2.22"
}
