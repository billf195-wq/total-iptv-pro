package com.totaliptv.pro.ui.help

/**
 * Settings → How to Use. Plain text, bundled in the app (no files, no network).
 * TV wording uses remote buttons; phone wording uses taps. Game Day and the TV Guide
 * screen exist only on the TV app, so the phone guide leaves them out.
 */
object HowToGuide {
    data class Section(val title: String, val body: String)

    fun sections(tv: Boolean): List<Section> {
        val press = if (tv) "press OK on" else "tap"
        val hold = if (tv) "hold OK on" else "press and hold"
        val out = mutableListOf<Section>()
        out += Section(
            "Getting started (adding your login)",
            "The first time you open the app, enter the login your TV service gave you: the server " +
                "address, username and password, then choose Save Xtream & continue. If you have a " +
                "playlist link instead, paste it in the playlist field and choose Save M3U & continue. " +
                "Your channels, movies and series then load. " +
                (if (tv) {
                    "To use a different login later, open Settings and choose Clear all data (Classic " +
                        "layout) or Change source (Desktop layout); the sign-in screen opens again."
                } else {
                    "To use a different login later, open Settings and tap Add / replace source."
                })
        )
        out += Section(
            if (tv) "Live TV & the Guide" else "Live TV",
            if (tv) {
                "Open Live, pick a category, then press OK on " +
                    "a channel to watch it. Open Guide to see what is on now and next for each channel. " +
                    "In the Guide you can watch, record now, or schedule a recording of the next show."
            } else {
                "Tap Live at the bottom of the screen, pick a category, then tap a channel to watch it."
            }
        )
        out += Section(
            "Movies & Series",
            "Open Movies or Series, pick a category, then $press a poster to see its details. " +
                "Choose Play to start, or Resume to pick up where you left off. For a series, choose a " +
                "season and an episode first. Use Mark watched to put a check mark on a movie or episode; " +
                "choose it again to mark it unwatched. Titles you finish are checked automatically."
        )
        out += Section(
            "Continue Watching (remove & move)",
            "Anything you stop part way through shows in Continue watching on Home, newest first. " +
                "To change the row, $hold a title. A menu lets you Remove it, Move to front, Move left or " +
                "Move right, or Mark as watched. Your order and removals are saved. A removed title " +
                "comes back by itself the next time you play it, and playing a title moves it to the front."
        )
        out += Section(
            "Favorites",
            if (tv) {
                "Choose Favorite in a title's details to add it; choose it again to remove it. In the " +
                    "Classic layout you can also hold OK on a poster, and your favorites show in a " +
                    "Favorites row on Home. In the Desktop layout they are under Favorites in the side " +
                    "menu. On a Continue watching title, Favorite is in the hold-OK menu."
            } else {
                "Open a title and tap Favorite. Tap it again to remove it."
            }
        )
        out += Section(
            "Search",
            if (tv) {
                "Choose Search, type with the on-screen keyboard, and results from Live, Movies and " +
                    "Series appear as you type."
            } else {
                "Tap Search at the bottom and type. Results from Live, Movies and Series appear as you type."
            }
        )
        out += Section(
            "Recording (DVR)",
            "Choose Record on a channel, movie or episode to save it on this device. Choose it again " +
                "to stop. Your saved recordings are in Recordings, where you can play or delete them. " +
                "Recordings stay on this device only."
        )
        if (tv) {
            out += Section(
                "Game Day split screen (TV only)",
                "On the Live screen, choose Game Day, pick a channel for the left side and one for the " +
                    "right, then Start. Two channels play side by side. Use Sound: Left or Sound: Right to " +
                    "choose which side you hear."
            )
        }
        out += Section(
            "Sleep timer",
            "While something plays, ${if (tv) "press any button on the remote to show the player buttons, then choose" else "tap the screen and tap"} " +
                "Sleep. Pick 15, 30, 60 or 90 minutes, or the end of this episode or movie. About a minute " +
                "before it stops, a Still watching? message appears. Choose Keep watching to turn the " +
                "timer off, or do nothing and playback stops."
        )
        out += Section(
            "Auto-play next",
            "Near the end of an episode, an Up next note shows a countdown. When the episode ends the next " +
                "one starts after 10 seconds. Choose Play next to start it now, or Cancel to stay. You can " +
                "turn this off in Settings with Auto-play next episode; then the app asks before playing " +
                "the next episode."
        )
        out += Section(
            "PIN lock",
            "In Settings, PIN lock, choose Set PIN and enter a 4-digit PIN twice" +
                (if (tv) " with the number pad (or the number keys on the remote). " else ". ") +
                "Adult categories are then hidden everywhere: lists, Search, " +
                (if (tv) "the TV Guide, " else "") +
                "Favorites, Continue watching and Recordings. Choose locked categories to hide more " +
                "Live, Movies or Series categories. Unlock with PIN shows everything until the app closes " +
                "or sits in the background for 5 minutes; Lock now hides it again. Change PIN and Turn off " +
                "PIN lock ask for the current PIN. Forgot PIN sets a new PIN and clears your chosen categories."
        )
        out += Section(
            "Tips & troubleshooting",
            buildString {
                append("If a channel or movie will not play, choose Retry in the player. ")
                if (tv) {
                    append("If lists look out of date, use Refresh data in Settings (Classic layout) or Update in the side menu (Desktop layout). ")
                } else {
                    append("If lists look out of date, use Refresh data in Settings. ")
                }
                append("If there is no sound, try the Audio button in the player. ")
                append("Subtitles has the caption choices when the title has them. ")
                if (tv) {
                    append("Press Back to leave the player or close a menu.")
                } else {
                    append("Use your phone's Back gesture to leave the player or close a screen.")
                }
            }
        )
        return out
    }
}
