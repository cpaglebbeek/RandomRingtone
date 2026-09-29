package nl.icthorse.randomringtone.data

/**
 * Zuivere regels voor "wat hoort in de bibliotheek" (v2.2.0).
 *
 * De bibliotheek bevat alleen bestanden die DIRECT in de ingestelde download- of tones-map staan
 * (zelfde diepte als de scan met listFiles). Alles daarbuiten — systeem-Downloads, oude mappen na een
 * mapwijziging, verdwenen bestanden — is "stale" en mag na bevestiging weg.
 */
object LibraryScope {

    private val PRIMARY_ALIASES = listOf(
        "/sdcard",
        "/mnt/sdcard",
        "/storage/self/primary",
        "/mnt/user/0/primary"
    )
    private const val PRIMARY = "/storage/emulated/0"

    /** Normaliseer een pad: dubbele/afsluitende slashes, `.`/`..`, sdcard-aliassen en /data/data ↔ /data/user/0. */
    fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        for (p in path.trim().replace('\\', '/').split('/')) {
            when (p) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(p)
            }
        }
        var n = "/" + parts.joinToString("/")
        for (alias in PRIMARY_ALIASES) {
            if (n == alias || n.startsWith("$alias/")) { n = PRIMARY + n.substring(alias.length); break }
        }
        if (n == "/data/data" || n.startsWith("/data/data/")) n = "/data/user/0" + n.substring("/data/data".length)
        return n
    }

    /** Staat [filePath] direct in één van [dirs]? */
    fun isInDirs(filePath: String?, dirs: Collection<String>): Boolean {
        if (filePath.isNullOrBlank()) return false
        val parent = normalize(filePath).substringBeforeLast('/', "")
        return dirs.any { normalize(it) == parent }
    }

    /**
     * Tracks die niet (meer) in de ingestelde mappen staan: geen pad, pad buiten [dirs], of bestand weg.
     * [exists] wordt alleen aangeroepen voor paden binnen de mappen.
     */
    fun findStale(tracks: List<SavedTrack>, dirs: Collection<String>, exists: (String) -> Boolean): List<SavedTrack> =
        tracks.filter { t ->
            val lp = t.localPath
            lp.isNullOrBlank() || !isInDirs(lp, dirs) || !exists(lp)
        }

    /** Korte lijst voor de bevestigingsdialoog: max [max] namen + "… en N meer". */
    fun summarize(tracks: List<SavedTrack>, max: Int = 10): String {
        val names = tracks.take(max).joinToString("\n") { "· ${it.artist} - ${it.title}" }
        return if (tracks.size > max) "$names\n… en ${tracks.size - max} meer" else names
    }
}

/**
 * Zuivere contact-matching voor restore (v2.2.0): een playlist uit een backup/reconstructie kan een
 * contactnaam hebben zonder (geldige) contact-URI. Zoek het contact op naam.
 * Volgorde: exact → hoofdletterongevoelig (getrimd) → uniek voorvoegsel (zonder leestekens aan het eind).
 */
object ContactMatcher {

    private fun key(s: String) = s.trim().lowercase().trimEnd('.', ',', ' ', '-')

    fun match(name: String?, contacts: List<ContactInfo>): ContactInfo? {
        if (name.isNullOrBlank()) return null
        contacts.firstOrNull { it.name == name }?.let { return it }
        val k = key(name)
        if (k.isEmpty()) return null
        contacts.filter { key(it.name) == k }.let { if (it.isNotEmpty()) return it.first() }
        // Log/weergave kapt namen soms af ("Thomas Join Recr" ↔ "Thomas Join Recr. Bureau") — alleen bij één kandidaat
        return contacts.filter { key(it.name).startsWith(k) }.distinctBy { it.uri }.singleOrNull()
    }

    data class Resolved(val playlists: List<PlaylistBackup>, val unresolved: List<String>)

    /** Placeholder uit een reconstructie/oude restore: "name:<contactnaam>" — geen echte contact-URI. */
    const val NAME_PREFIX = "name:"

    fun isPlaceholder(uri: String?): Boolean = uri != null && uri.startsWith(NAME_PREFIX)

    /** Moet deze playlist nog aan een contact gekoppeld worden? */
    fun needsResolve(pb: PlaylistBackup): Boolean =
        isPlaceholder(pb.contactUri) || (pb.contactUri.isNullOrBlank() && !pb.contactName.isNullOrBlank())

    /**
     * Vul ontbrekende of placeholder-contactUri's in. [contacts] == null betekent: geen READ_CONTACTS.
     * Een contactplaylist die niet opgelost kan worden wordt UITGEZET met een "name:"-URI — nooit globaal.
     */
    fun resolve(playlists: List<PlaylistBackup>, contacts: List<ContactInfo>?): Resolved {
        val unresolved = mutableListOf<String>()
        val out = playlists.map { pb ->
            if (!needsResolve(pb)) return@map pb
            val name = pb.contactName?.takeIf { it.isNotBlank() }
                ?: pb.contactUri!!.removePrefix(NAME_PREFIX)
            val hit = contacts?.let { match(name, it) }
            if (hit != null) pb.copy(contactUri = hit.uri, contactName = pb.contactName ?: hit.name)
            else {
                unresolved.add("${pb.name} ($name)")
                pb.copy(contactUri = NAME_PREFIX + name, contactName = pb.contactName ?: name, isActive = false)
            }
        }
        return Resolved(out, unresolved)
    }
}
