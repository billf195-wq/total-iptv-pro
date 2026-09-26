package com.totaliptv.pro.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import java.util.Locale

/**
 * Same English-audio preference the single player uses: prefer a non-commentary
 * English track with channels, then any playable track. Does not change volume.
 */
@OptIn(UnstableApi::class)
object EnglishAudio {
    private val ENGLISH_CODES = setOf("eng", "en", "english", "en-us", "en-gb", "en_us", "en_gb")

    data class Candidate(
        val isEnglish: Boolean,
        val supported: Boolean,
        val hasPlayableChannels: Boolean,
        val isDefault: Boolean,
        val secondary: Boolean,
        val selected: Boolean,
        val groupIndex: Int,
        val trackIndex: Int
    )

    fun isEnglishLanguage(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val n = raw.trim().lowercase(Locale.US).replace('_', '-')
        if (n in ENGLISH_CODES) return true
        if (n.startsWith("en-") || n.startsWith("eng")) return true
        if (n.contains("english")) return true
        return false
    }

    fun isSecondary(label: String?, language: String?, roleFlags: Int): Boolean {
        if ((roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO) != 0) return true
        if ((roleFlags and C.ROLE_FLAG_COMMENTARY) != 0) return true
        val blob = listOfNotNull(label, language).joinToString(" ").lowercase(Locale.US)
        return blob.contains("commentary") ||
            blob.contains("description") ||
            blob.contains("audio desc") ||
            blob.contains("narrat") ||
            Regex("""\bad\b""").containsMatchIn(blob)
    }

    fun rank(c: Candidate): Int {
        var score = 0
        if (c.supported) score += 40
        if (c.hasPlayableChannels) score += 20
        if (c.isEnglish) score += 50
        if (c.isDefault) score += 10
        if (!c.secondary) score += 8
        return score
    }

    /** Preferred track, or null when the player has not reported audio groups yet. */
    fun pick(options: List<Candidate>): Candidate? {
        if (options.isEmpty()) return null
        val englishPool = options.filter { it.isEnglish && !it.secondary && it.hasPlayableChannels }
        val preferredEnglish = englishPool.filter { it.supported }.maxByOrNull(::rank)
            ?: englishPool.maxByOrNull(::rank)
        if (preferredEnglish != null) return preferredEnglish
        val playable = options.filter { it.hasPlayableChannels }
        val supportedPlayable = playable.filter { it.supported }
        val pool = when {
            supportedPlayable.isNotEmpty() -> supportedPlayable
            playable.isNotEmpty() -> playable
            else -> options
        }
        return pool.maxByOrNull(::rank)
    }

    fun candidatesFrom(tracks: Tracks): List<Candidate> {
        val options = mutableListOf<Candidate>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_AUDIO || group.length == 0) return@forEachIndexed
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val channelCount = format.channelCount
                options += Candidate(
                    isEnglish = isEnglishLanguage(format.language) || isEnglishLanguage(format.label),
                    supported = group.isTrackSupported(trackIndex),
                    hasPlayableChannels = channelCount == Format.NO_VALUE || channelCount > 0,
                    isDefault = (format.selectionFlags and C.SELECTION_FLAG_DEFAULT) != 0,
                    secondary = isSecondary(format.label, format.language, format.roleFlags),
                    selected = group.isTrackSelected(trackIndex),
                    groupIndex = groupIndex,
                    trackIndex = trackIndex
                )
            }
        }
        return options
    }

    /**
     * Select the preferred audio track when groups are known.
     * @return true once a choice was applied or the current selection is already preferred.
     */
    fun applyPreferred(player: ExoPlayer): Boolean {
        val tracks = player.currentTracks
        val options = candidatesFrom(tracks)
        val best = pick(options) ?: return false
        if (best.selected) return true
        val group = tracks.groups.getOrNull(best.groupIndex)?.mediaTrackGroup ?: return false
        val override = TrackSelectionOverride(group, listOf(best.trackIndex))
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, /* disabled= */ false)
            .setOverrideForType(override)
            .build()
        return true
    }
}
