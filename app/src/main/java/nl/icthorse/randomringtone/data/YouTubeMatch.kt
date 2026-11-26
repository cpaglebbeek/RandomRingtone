package nl.icthorse.randomringtone.data

import java.text.Normalizer
import kotlin.math.abs

/**
 * Zuivere treffercontrole (zonder netwerk) voor "nummer van Spotify → video op YouTube".
 * Regels: de kerntitel staat in de videotitel, minstens één artiest staat in videotitel of kanaalnaam, en de duur
 * wijkt hooguit [MAX_DIFF_SEC] af. Daarbinnen wint de kleinste duurafwijking, met voorkeur voor officiële audio.
 */
object YouTubeMatch {
    const val MAX_DIFF_SEC = 30L

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Titel zonder haakjestekst en zonder variant-achtervoegsel (" - Remastered 2011", " - Radio Edit"). */
    fun coreTitle(title: String): String = title
        .replace(Regex("\\s*[(\\[{].*?[)\\]}]"), "")
        .replace(Regex("\\s+-\\s+.*$"), "")
        .trim().ifBlank { title.trim() }

    fun artistTokens(artists: String): List<String> = artists
        .split(Regex("[,&]|\\s+(?:feat\\.?|ft\\.?)\\s+", RegexOption.IGNORE_CASE))
        .map { norm(it) }.filter { it.length >= 2 }

    fun titleMatches(videoTitle: String, title: String): Boolean {
        val v = norm(videoTitle)
        val t = norm(coreTitle(title))
        if (t.isEmpty()) return false
        return if (t.length >= 4) t in v else Regex("(^| )${Regex.escape(t)}( |$)").containsMatchIn(v)
    }

    fun artistMatches(hit: YouTubeOnDevice.Hit, artists: String): Boolean {
        val where = norm(hit.title) + " | " + norm(hit.uploader)
        return artistTokens(artists).any { it in where }
    }

    fun pick(hits: List<YouTubeOnDevice.Hit>, artists: String, title: String, durationSec: Long?): YouTubeOnDevice.Hit? {
        val wanted = title.lowercase()
        fun score(h: YouTubeOnDevice.Hit): Double? {
            if (!titleMatches(h.title, title) || !artistMatches(h, artists)) return null
            var s = 0.0
            if (durationSec != null && durationSec > 0 && h.durationSec > 0) {
                val d = abs(h.durationSec - durationSec)
                if (d > MAX_DIFF_SEC) return null
                s += d.toDouble()
            }
            val t = h.title.lowercase()
            if (h.uploader.endsWith(" - Topic")) s -= 5
            if ("official audio" in t) s -= 3
            if ("live" in t && "live" !in wanted) s += 20
            if (("cover" in t && "cover" !in wanted) || "karaoke" in t || ("remix" in t && "remix" !in wanted)) s += 30
            return s
        }
        return hits.mapNotNull { h -> score(h)?.let { h to it } }.minByOrNull { it.second }?.first
    }
}
