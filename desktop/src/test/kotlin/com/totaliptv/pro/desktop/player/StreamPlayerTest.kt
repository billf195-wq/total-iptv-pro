package com.totaliptv.pro.desktop.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StreamPlayerTest {

    @Test
    fun linuxVlcCommandDisablesOneInstanceAndPlayAndExit() {
        val cmd = StreamPlayer.vlcCommand("/usr/bin/vlc", listOf("http://example.test/ep1.mp4"), windows = false)
        assertEquals("/usr/bin/vlc", cmd.first())
        assertTrue(cmd.contains("--no-one-instance"))
        assertTrue(cmd.contains("--play-and-exit"))
        assertTrue(cmd.contains("--audio-language=eng,en,english"))
        assertTrue(cmd.contains("--no-playlist-enqueue"))
        assertTrue(cmd.contains("--no-repeat"))
        assertTrue(cmd.contains("--no-loop"))
        assertTrue(cmd.contains("http://example.test/ep1.mp4"))
        assertFalse(cmd.contains("--ignore-config"))
        assertFalse(cmd.contains("--no-one-instance-when-started-from-file"))
        assertFalse(cmd.contains("--no-started-from-file"))
        assertFalse(cmd.contains("--rc-quiet"))
        assertTrue(cmd.contains("--extraintf=rc"))
        assertTrue(cmd.contains("--rc-host=127.0.0.1:4214"))
        assertEquals("http://example.test/ep1.mp4", cmd.last())
    }

    @Test
    fun linuxVlcLaunchesOnlyTheFirstEpisodeUrl() {
        val urls = listOf("http://example.test/a.mp4", "http://example.test/b.mp4")
        val cmd = StreamPlayer.vlcCommand("/usr/bin/vlc", urls, windows = false)
        assertEquals(urls[0], cmd.last())
        assertFalse(cmd.contains(urls[1]))
        assertFalse(cmd.any { it.contains(".m3u") })
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", urls.size, windows = false))
    }

    @Test
    fun windowsVlcLaunchesOnlyTheFirstEpisodeUrl() {
        val urls = listOf("http://example.test/s01e01.mkv", "http://example.test/s01e02.mkv")
        val cmd = StreamPlayer.vlcCommand("""C:\Program Files\VideoLAN\VLC\vlc.exe""", urls, windows = true)
        assertEquals("--ignore-config", cmd[1])
        assertTrue(cmd.contains("--no-one-instance"))
        assertTrue(cmd.contains("--play-and-exit"))
        assertTrue(cmd.contains("--audio-language=eng,en,english"))
        assertTrue(cmd.contains("--no-playlist-enqueue"))
        assertTrue(cmd.contains("--no-one-instance-when-started-from-file"))
        assertTrue(cmd.contains("--no-started-from-file"))
        assertTrue(cmd.contains("--rc-quiet"))
        assertTrue(cmd.contains("--extraintf=rc"))
        assertTrue(cmd.contains("--rc-host=127.0.0.1:4214"))
        assertTrue(cmd.contains("--no-repeat"))
        assertTrue(cmd.contains("--no-loop"))
        assertEquals(urls[0], cmd.last())
        assertFalse(cmd.contains(urls[1]))
        assertFalse(cmd.any { it.contains(".m3u") })
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", urls.size, windows = true))
    }

    @Test
    fun launchIsNeverAPlaylistSoAppSequentialNextRuns() {
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", 12, windows = true))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("mpv", 12, windows = true))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("ffplay", 12, windows = false))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", 2, windows = false))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("mpv", 2, windows = false))
        assertFalse(StreamPlayer.treatsLaunchAsPlaylist("vlc", 39, windows = false))
    }

    @Test
    fun windowsQuitKillsVlcMpvAndFfplayTrees() {
        val names = StreamPlayer.windowsQuitImageNames("""C:\Program Files\VideoLAN\VLC\vlc.exe""")
        assertTrue(names.contains("vlc.exe"))
        assertTrue(names.contains("mpv.exe"))
        assertTrue(names.contains("ffplay.exe"))
        assertEquals(
            listOf("taskkill.exe", "/F", "/T", "/IM", "vlc.exe"),
            StreamPlayer.windowsKillCommand("vlc.exe")
        )
    }

    @Test
    fun waitForExitRecordsDurationAndExitCode() {
        StreamPlayer.markLaunch(nowMs = 1_000L)
        StreamPlayer.markExit(exitCode = 0, nowMs = 9_500L)
        assertEquals(0, StreamPlayer.lastExitCode)
        assertEquals(8_500L, StreamPlayer.lastPlaybackDurationMs)
        StreamPlayer.markLaunch(nowMs = 20_000L)
        StreamPlayer.markExit(exitCode = 1, nowMs = 21_200L)
        assertEquals(1, StreamPlayer.lastExitCode)
        assertEquals(1_200L, StreamPlayer.lastPlaybackDurationMs)
    }

    @Test
    fun windowsKillTargetsVlcImageEvenFromProgramFilesPath() {
        val names = StreamPlayer.windowsKillImageNames("""C:\Program Files\VideoLAN\VLC\vlc.exe""")
        assertEquals(listOf("vlc.exe"), names)
        assertEquals(
            listOf("taskkill.exe", "/F", "/T", "/IM", "vlc.exe"),
            StreamPlayer.windowsKillCommand(names.first())
        )
        assertEquals(listOf("vlc.exe"), StreamPlayer.windowsKillImageNames("vlc"))
        assertEquals(listOf("mpv.exe"), StreamPlayer.windowsKillImageNames("mpv.exe"))
    }

    @Test
    fun mpvLaunchesOnlyFirstUrlAndDisablesLoopOnLinuxAndWindows() {
        val urls = listOf("http://example.test/a.mp4", "http://example.test/b.mp4")
        val win = StreamPlayer.mpvCommand("mpv.exe", urls, windows = true)
        assertTrue(win.contains("--loop-file=no"))
        assertTrue(win.contains("--loop-playlist=no"))
        assertTrue(win.contains("--keep-open=no"))
        assertTrue(win.contains("--alang=eng,en,english"))
        assertEquals(urls[0], win.last())
        assertFalse(win.contains(urls[1]))
        val linux = StreamPlayer.mpvCommand("mpv", urls, windows = false)
        assertTrue(linux.contains("--loop-file=no"))
        assertTrue(linux.contains("--loop-playlist=no"))
        assertTrue(linux.contains("--keep-open=no"))
        assertTrue(linux.contains("--alang=eng,en,english"))
        assertEquals(urls[0], linux.last())
        assertFalse(linux.contains(urls[1]))
    }

    @Test
    fun ffplayIsSingleFileOnly() {
        val cmd = StreamPlayer.ffplayCommand("ffplay", "http://example.test/a.mp4")
        assertEquals("ffplay", cmd.first())
        assertTrue(cmd.contains("-autoexit"))
        assertEquals("http://example.test/a.mp4", cmd.last())
    }

    @Test
    fun liveUrlDetectsXtreamHlsAndSkipsVodContainers() {
        assertTrue(StreamPlayer.isLiveStreamUrl("http://hudv.net:80/live/user/pass/3.m3u8"))
        assertTrue(StreamPlayer.isLiveStreamUrl("http://hudv.net/live/u/p/67.ts"))
        assertTrue(StreamPlayer.isLiveStreamUrl("http://cdn.example.test/playlist.m3u8"))
        assertFalse(StreamPlayer.isLiveStreamUrl("http://hudv.net:80/movie/user/pass/825824.mp4"))
        assertFalse(StreamPlayer.isLiveStreamUrl("http://hudv.net:80/series/user/pass/627490.mkv"))
        assertFalse(StreamPlayer.isLiveStreamUrl("http://hudv.net:80/movie/user/pass/99.m3u8"))
    }

    @Test
    fun windowsLiveVlcOmitsPlayAndExitAndKeepsVodGuard() {
        val liveUrl = "http://hudv.net:80/live/user/pass/341.m3u8"
        val vodUrl = "http://hudv.net:80/series/user/pass/825824.mp4"
        val vlc = """C:\Program Files\VideoLAN\VLC\vlc.exe"""
        val live = StreamPlayer.vlcCommand(vlc, listOf(liveUrl), windows = true, live = true)
        assertFalse(live.contains("--play-and-exit"), live.toString())
        assertTrue(live.contains("--network-caching=3000"), live.toString())
        assertTrue(live.contains("--live-caching=3000"), live.toString())
        assertTrue(live.contains("--http-reconnect"), live.toString())
        assertTrue(live.contains("--ignore-config"))
        assertTrue(live.contains("--audio-language=eng,en,english"), live.toString())
        assertEquals(liveUrl, live.last())
        val vod = StreamPlayer.vlcCommand(vlc, listOf(vodUrl), windows = true, live = false)
        assertTrue(vod.contains("--play-and-exit"), vod.toString())
        assertTrue(vod.contains("--audio-language=eng,en,english"), vod.toString())
        assertFalse(vod.contains("--http-reconnect"))
        assertEquals(vodUrl, vod.last())
    }

    @Test
    fun linuxLiveVlcAlsoOmitsPlayAndExit() {
        val live = StreamPlayer.vlcCommand(
            "/usr/bin/vlc",
            listOf("http://hudv.net/live/u/p/3.m3u8"),
            windows = false,
            live = true
        )
        assertFalse(live.contains("--play-and-exit"))
        assertTrue(live.contains("--fullscreen"))
        assertTrue(live.contains("--audio-language=eng,en,english"))
        val vod = StreamPlayer.vlcCommand(
            "/usr/bin/vlc",
            listOf("http://hudv.net/series/u/p/1.mkv"),
            windows = false,
            live = false
        )
        assertTrue(vod.contains("--play-and-exit"))
        assertTrue(vod.contains("--audio-language=eng,en,english"))
    }

    @Test
    fun rcQuietIsWindowsOnlyAndLinuxKeepsResumeRc() {
        val url = "http://example.test/movie.mp4"
        val linux = StreamPlayer.vlcCommand("/usr/bin/vlc", listOf(url), windows = false, live = false)
        assertFalse(linux.contains("--rc-quiet"), linux.toString())
        assertTrue(linux.contains("--extraintf=rc"))
        assertTrue(linux.contains("--rc-host=127.0.0.1:4214"))
        assertFalse(linux.contains("--no-one-instance-when-started-from-file"))
        assertFalse(linux.contains("--no-started-from-file"))

        val windows = StreamPlayer.vlcCommand(
            """C:\Program Files\VideoLAN\VLC\vlc.exe""",
            listOf(url),
            windows = true,
            live = false
        )
        assertTrue(windows.contains("--rc-quiet"), windows.toString())
        assertTrue(windows.contains("--extraintf=rc"))
        assertTrue(windows.contains("--rc-host=127.0.0.1:4214"))
        assertTrue(windows.contains("--no-one-instance-when-started-from-file"))
        assertTrue(windows.contains("--no-started-from-file"))

        val linuxLive = StreamPlayer.vlcCommand("/usr/bin/vlc", listOf(url), windows = false, live = true)
        val windowsLive = StreamPlayer.vlcCommand(
            """C:\Program Files\VideoLAN\VLC\vlc.exe""",
            listOf(url),
            windows = true,
            live = true
        )
        assertFalse(linuxLive.contains("--rc-quiet"))
        assertFalse(windowsLive.contains("--rc-quiet"))

        val linuxSplit = StreamPlayer.splitSideCommand(
            "/usr/bin/vlc",
            url,
            windows = false,
            x = 0,
            y = 0,
            width = 960,
            height = 1080,
            port = 4212,
            title = "Total IPTV Pro — Left"
        )
        assertFalse(linuxSplit.contains("--rc-quiet"), linuxSplit.toString())
        assertTrue(linuxSplit.contains("--extraintf=rc"))
        assertTrue(linuxSplit.contains("--rc-host=127.0.0.1:4212"))
        assertFalse(linuxSplit.contains("--no-one-instance-when-started-from-file"))
        assertFalse(linuxSplit.contains("--no-started-from-file"))

        val windowsSplit = StreamPlayer.splitSideCommand(
            """C:\Program Files\VideoLAN\VLC\vlc.exe""",
            url,
            windows = true,
            x = 960,
            y = 0,
            width = 960,
            height = 1080,
            port = 4213,
            title = "Total IPTV Pro — Right"
        )
        assertTrue(windowsSplit.contains("--rc-quiet"), windowsSplit.toString())
        assertTrue(windowsSplit.contains("--extraintf=rc"))
        assertTrue(windowsSplit.contains("--rc-host=127.0.0.1:4213"))
    }

    @Test
    fun linuxGameDayUsesDummyInterfaceAndHalfZoom() {
        val url = "http://example.test/live/left.m3u8"
        val linux = StreamPlayer.splitSideCommand(
            "/usr/bin/vlc",
            url,
            windows = false,
            x = 1920,
            y = 37,
            width = 960,
            height = 1043,
            port = 4212,
            title = "Total IPTV Pro — Left"
        )
        assertEquals(
            listOf(
                "/usr/bin/vlc",
                "--intf=dummy",
                "--no-one-instance",
                "--no-playlist-enqueue",
                "--no-video-title-show",
                "--no-video-deco",
                "--zoom=0.5",
                "--audio-language=eng,en,english",
                "--width=960",
                "--height=1043",
                "--video-x=1920",
                "--video-y=37",
                "--extraintf=rc",
                "--control=hotkeys",
                "--key-leave-fullscreen=Unset",
                "--key-quit=${StreamPlayer.LINUX_SPLIT_QUIT_KEYS}",
                "--rc-host=127.0.0.1:4212",
                "--meta-title=Total IPTV Pro — Left",
                url
            ),
            linux
        )
        assertEquals("q\tEsc\tCtrl+q", StreamPlayer.LINUX_SPLIT_QUIT_KEYS)
        assertTrue(linux.any { it.startsWith("--key-quit=") && it.contains("\t") })
        assertFalse(linux.contains("--qt-minimal-view"))
        assertFalse(linux.contains("--no-embedded-video"))
        assertFalse(linux.contains("--no-qt-video-autoresize"))
        assertFalse(linux.contains("--qt-continue=0"))
        assertFalse(linux.contains("--no-qt-privacy-ask"))
        assertFalse(linux.contains("--rc-quiet"))
        assertFalse(linux.contains("--ignore-config"))

        val windows = StreamPlayer.splitSideCommand(
            """C:\Program Files\VideoLAN\VLC\vlc.exe""",
            url,
            windows = true,
            x = 0,
            y = 0,
            width = 960,
            height = 1080,
            port = 4213,
            title = "Total IPTV Pro — Right"
        )
        assertEquals(
            listOf(
                """C:\Program Files\VideoLAN\VLC\vlc.exe""",
                "--ignore-config",
                "--no-one-instance",
                "--no-playlist-enqueue",
                "--no-video-title-show",
                "--no-qt-video-autoresize",
                "--no-video-deco",
                "--no-embedded-video",
                "--qt-minimal-view",
                "--qt-continue=0",
                "--no-qt-privacy-ask",
                "--audio-language=eng,en,english",
                "--width=960",
                "--height=1080",
                "--video-x=0",
                "--video-y=0",
                "--extraintf=rc",
                "--rc-host=127.0.0.1:4213",
                "--rc-quiet",
                "--meta-title=Total IPTV Pro — Right",
                url
            ),
            windows
        )
        assertFalse(windows.contains("--intf=dummy"))
        assertFalse(windows.contains("--zoom=0.5"))
        assertFalse(windows.any { it.startsWith("--key-quit") })
        assertFalse(windows.contains("--control=hotkeys"))
        assertFalse(windows.contains("--key-leave-fullscreen=Unset"))
    }

    @Test
    fun splitPartnerStopsWhenEitherSideExits() {
        assertFalse(StreamPlayer.splitPartnerShouldStop(leftAlive = true, rightAlive = true))
        assertTrue(StreamPlayer.splitPartnerShouldStop(leftAlive = false, rightAlive = true))
        assertTrue(StreamPlayer.splitPartnerShouldStop(leftAlive = true, rightAlive = false))
        assertTrue(StreamPlayer.splitPartnerShouldStop(leftAlive = false, rightAlive = false))
    }

    @Test
    fun vlcNeverAsksToContinueAndSkipsThePrivacyDialog() {
        val url = "http://example.test/movie.mp4"
        val single = StreamPlayer.vlcCommand(
            """C:\Program Files\VideoLAN\VLC\vlc.exe""",
            listOf(url),
            windows = true,
            startPositionSeconds = 30
        )
        assertTrue(single.contains("--qt-continue=0"))
        assertTrue(single.contains("--no-qt-privacy-ask"))
        assertFalse(single.contains("--no-qt-error-dialogs"))
        assertFalse(single.any { it.contains("qt-updates-notif") })
        assertTrue(single.contains("--fullscreen"))
        assertTrue(single.contains("--audio-language=eng,en,english"))
        assertTrue(single.contains("--start-time=30"))
        assertEquals(url, single.last())

        val split = StreamPlayer.splitSideCommand(
            """C:\Program Files\VideoLAN\VLC\vlc.exe""",
            url,
            windows = true,
            x = 0,
            y = 0,
            width = 960,
            height = 1080,
            port = 4212,
            title = "Total IPTV Pro — Left"
        )
        assertTrue(split.contains("--qt-continue=0"))
        assertTrue(split.contains("--no-qt-privacy-ask"))
        assertTrue(split.contains("--no-video-deco"))
        assertTrue(split.contains("--no-embedded-video"))
        assertTrue(split.contains("--qt-minimal-view"))
        assertFalse(split.contains("--fullscreen"))
        assertTrue(split.contains("--audio-language=eng,en,english"))
        assertEquals(url, split.last())
        assertFalse(single.contains("--no-video-deco"))
        assertFalse(single.contains("--no-embedded-video"))
        assertFalse(single.contains("--qt-minimal-view"))
    }

    @Test
    fun gameDayHalvesMeetAtTheMiddleOnAnyWidth() {
        val bounds = WindowPositioner.ScreenBounds(0, 0, 1920, 1080)
        val (left, right) = WindowPositioner.splitHalves(bounds)
        assertEquals(0, left.x)
        assertEquals(960, left.width)
        assertEquals(960, right.x)
        assertEquals(960, right.width)
        assertEquals(1080, left.height)
        assertEquals(bounds.width, left.width + right.width)
        assertEquals(left.x + left.width, right.x)

        val odd = WindowPositioner.splitHalves(WindowPositioner.ScreenBounds(0, 0, 1921, 1000))
        assertEquals(960, odd.first.width)
        assertEquals(961, odd.second.width)
        assertEquals(960, odd.second.x)
        assertEquals(1921, odd.first.width + odd.second.width)

        val work = WindowPositioner.splitHalves(WindowPositioner.ScreenBounds(10, 40, 1900, 1000))
        assertEquals(10, work.first.x)
        assertEquals(40, work.first.y)
        assertEquals(950, work.first.width)
        assertEquals(960, work.second.x)
        assertEquals(950, work.second.width)
        assertEquals(1000, work.first.height)
    }

    @Test
    fun normalPlayOpensFullscreenAndCanBeTurnedOff() {
        val url = "http://example.test/ep1.mp4"
        val vlcOn = StreamPlayer.vlcCommand("/usr/bin/vlc", listOf(url), windows = false, startPositionSeconds = 45)
        assertTrue(vlcOn.contains("--fullscreen"))
        assertTrue(vlcOn.contains("--audio-language=eng,en,english"))
        assertTrue(vlcOn.contains("--start-time=45"))
        assertEquals(url, vlcOn.last())

        val vlcOff = StreamPlayer.vlcCommand(
            "/usr/bin/vlc",
            listOf(url),
            windows = false,
            startPositionSeconds = 45,
            fullscreen = false
        )
        assertFalse(vlcOff.contains("--fullscreen"))
        assertTrue(vlcOff.contains("--audio-language=eng,en,english"))
        assertTrue(vlcOff.contains("--start-time=45"))

        val mpvOn = StreamPlayer.mpvCommand("mpv", listOf(url), windows = false, startPositionSeconds = 12)
        assertTrue(mpvOn.contains("--fullscreen"))
        assertTrue(mpvOn.contains("--alang=eng,en,english"))
        assertTrue(mpvOn.contains("--start=12"))
        val mpvOff = StreamPlayer.mpvCommand("mpv", listOf(url), windows = false, fullscreen = false, startPositionSeconds = 12)
        assertFalse(mpvOff.contains("--fullscreen"))
        assertTrue(mpvOff.contains("--alang=eng,en,english"))
        assertTrue(mpvOff.contains("--start=12"))

        val ffOn = StreamPlayer.ffplayCommand("ffplay", url, startPositionSeconds = 8)
        assertTrue(ffOn.contains("-fs"))
        assertTrue(ffOn.contains("-ss"))
        assertEquals("8", ffOn[ffOn.indexOf("-ss") + 1])
        val ffOff = StreamPlayer.ffplayCommand("ffplay", url, startPositionSeconds = 8, fullscreen = false)
        assertFalse(ffOff.contains("-fs"))
        assertTrue(ffOff.contains("-ss"))
    }

    @Test
    fun liveMpvKeepsWindowOpenAndFfplaySkipsAutoexit() {
        val liveUrl = "http://hudv.net/live/u/p/84.m3u8"
        val mpv = StreamPlayer.mpvCommand("mpv.exe", listOf(liveUrl), windows = true, live = true)
        assertTrue(mpv.contains("--keep-open=yes"))
        assertFalse(mpv.contains("--keep-open=no"))
        assertTrue(mpv.contains("--alang=eng,en,english"))
        val vod = StreamPlayer.mpvCommand("mpv", listOf("http://ex.test/a.mp4"), windows = false, live = false)
        assertTrue(vod.contains("--keep-open=no"))
        assertTrue(vod.contains("--alang=eng,en,english"))
        val ffLive = StreamPlayer.ffplayCommand("ffplay", liveUrl, live = true)
        assertFalse(ffLive.contains("-autoexit"))
        val ffVod = StreamPlayer.ffplayCommand("ffplay", "http://ex.test/a.mp4", live = false)
        assertTrue(ffVod.contains("-autoexit"))
    }

    @Test
    fun vlcAndMpvPreferEnglishAudioOnLiveAndVod() {
        val liveUrl = "http://hudv.net/live/u/p/84.m3u8"
        val vodUrl = "http://hudv.net/series/u/p/1.mkv"
        val vlcWin = """C:\Program Files\VideoLAN\VLC\vlc.exe"""

        val vlcLiveWin = StreamPlayer.vlcCommand(vlcWin, listOf(liveUrl), windows = true, live = true)
        val vlcVodWin = StreamPlayer.vlcCommand(vlcWin, listOf(vodUrl), windows = true, live = false)
        val vlcLiveLin = StreamPlayer.vlcCommand("/usr/bin/vlc", listOf(liveUrl), windows = false, live = true)
        val vlcVodLin = StreamPlayer.vlcCommand("/usr/bin/vlc", listOf(vodUrl), windows = false, live = false)
        for (cmd in listOf(vlcLiveWin, vlcVodWin, vlcLiveLin, vlcVodLin)) {
            assertTrue(cmd.contains(StreamPlayer.VLC_AUDIO_LANGUAGE), cmd.toString())
        }
        assertEquals("--ignore-config", vlcLiveWin[1])
        assertEquals("--ignore-config", vlcVodWin[1])
        assertFalse(vlcLiveLin.contains("--ignore-config"))
        assertFalse(vlcVodLin.contains("--ignore-config"))
        assertFalse(vlcLiveWin.contains("--play-and-exit"))
        assertTrue(vlcVodWin.contains("--play-and-exit"))
        assertFalse(vlcLiveLin.contains("--play-and-exit"))
        assertTrue(vlcVodLin.contains("--play-and-exit"))

        val mpvLiveWin = StreamPlayer.mpvCommand("mpv.exe", listOf(liveUrl), windows = true, live = true)
        val mpvVodWin = StreamPlayer.mpvCommand("mpv.exe", listOf(vodUrl), windows = true, live = false)
        val mpvLiveLin = StreamPlayer.mpvCommand("mpv", listOf(liveUrl), windows = false, live = true)
        val mpvVodLin = StreamPlayer.mpvCommand("mpv", listOf(vodUrl), windows = false, live = false)
        for (cmd in listOf(mpvLiveWin, mpvVodWin, mpvLiveLin, mpvVodLin)) {
            assertTrue(cmd.contains(StreamPlayer.MPV_AUDIO_LANGUAGE), cmd.toString())
        }
        assertTrue(mpvLiveWin.contains("--keep-open=yes"))
        assertTrue(mpvVodWin.contains("--keep-open=no"))
        assertTrue(mpvLiveLin.contains("--keep-open=yes"))
        assertTrue(mpvVodLin.contains("--keep-open=no"))
    }
}
