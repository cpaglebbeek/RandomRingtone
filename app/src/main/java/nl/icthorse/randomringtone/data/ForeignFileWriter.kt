package nl.icthorse.randomringtone.data

import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Een bestand dat de restore niet mocht vervangen: de nieuwe inhoud staat klaar in [tempPath]. */
data class PendingOverwrite(val targetPath: String, val tempPath: String)

/**
 * v2.2.3: bestanden van een EERDERE installatie (andere eigenaar) mag de app op Android 11+ niet zelf wijzigen of
 * verwijderen (EACCES). De officiële route: MediaStore.createWriteRequest ⇒ één systeemdialoog "toestaan dat
 * RandomRingtone N audiobestanden wijzigt" ⇒ daarna schrijven via de MediaStore-URI.
 */
object ForeignFileWriter {

    private fun collections(): List<Uri> = listOf(
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    )

    /** Zoek de MediaStore-URI van een bestand op pad (null = niet geïndexeerd). */
    fun mediaUriFor(context: Context, path: String): Uri? {
        for (collection in collections()) {
            try {
                context.contentResolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path), null
                )?.use { c -> if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0)) }
            } catch (_: Exception) { }
        }
        return null
    }

    /** Koppel elk pending bestand aan zijn MediaStore-URI; de rest kan niet via een schrijfverzoek. */
    suspend fun resolve(context: Context, pending: List<PendingOverwrite>): Pair<Map<PendingOverwrite, Uri>, List<PendingOverwrite>> =
        withContext(Dispatchers.IO) {
            val found = mutableMapOf<PendingOverwrite, Uri>()
            val missing = mutableListOf<PendingOverwrite>()
            pending.forEach { p -> mediaUriFor(context, p.targetPath)?.let { found[p] = it } ?: missing.add(p) }
            found to missing
        }

    /** Systeemdialoog (Android 11+). null ⇒ niet beschikbaar. */
    fun writeRequest(context: Context, uris: Collection<Uri>): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.isNotEmpty())
            runCatching { MediaStore.createWriteRequest(context.contentResolver, uris) }.getOrNull()
        else null

    /** Na toestemming: nieuwe inhoud over de bestaande heen schrijven ("wt" = truncate). Geeft de geschreven paden. */
    suspend fun apply(context: Context, granted: Map<PendingOverwrite, Uri>): List<String> = withContext(Dispatchers.IO) {
        val written = mutableListOf<String>()
        for ((p, uri) in granted) {
            try {
                File(p.tempPath).inputStream().use { input ->
                    context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> input.copyTo(out) }
                }
                written.add(p.targetPath)
            } catch (e: Exception) {
                RemoteLogger.w("Restore", "Overschrijven via MediaStore mislukt", mapOf("file" to p.targetPath, "error" to (e.message ?: "?")))
            }
        }
        RemoteLogger.i("Restore", "Bestanden van eerdere installatie vervangen", mapOf("written" to "${written.size}", "asked" to "${granted.size}"))
        written
    }

    /** Tijdelijke kopieën opruimen. */
    fun cleanup(pending: List<PendingOverwrite>) = pending.forEach { runCatching { File(it.tempPath).delete() } }

    fun pendingDir(context: Context) = File(context.cacheDir, "restore_pending").apply { mkdirs() }
}
