package nl.icthorse.randomringtone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.AppBusyState
import nl.icthorse.randomringtone.data.*
import java.io.File

/** Uitkomst per nummer van een import. */
data class ImportOutcome(val item: SpotifyCollection.Item, val file: File?, val error: String?, val existed: Boolean = false)

/**
 * v2.5.0 — import van een Spotify-playlist of -album: lijst met vinkjes → volledige nummers via de YouTube-keten
 * (strikte treffercontrole) → bibliotheek, en naar keuze een (gepauzeerde) playlist in de app.
 */
@Composable
fun SpotifyImportDialog(
    ref: SpotifyCollection.Ref,
    client: SpotifyPreviewClient,
    ringtoneManager: AppRingtoneManager,
    db: RingtoneDatabase,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var collection by remember { mutableStateOf<SpotifyCollection.Collection?>(null) }
    var loadPhase by remember { mutableStateOf("${ref.kind.label} ophalen...") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var asPlaylist by remember { mutableStateOf(true) }

    var job by remember { mutableStateOf<Job?>(null) }
    var running by remember { mutableStateOf(false) }
    var runIndex by remember { mutableIntStateOf(0) }
    var runTotal by remember { mutableIntStateOf(0) }
    var runTitle by remember { mutableStateOf("") }
    var runPhase by remember { mutableStateOf("") }
    var runProgress by remember { mutableFloatStateOf(0f) }
    var outcomes by remember { mutableStateOf<List<ImportOutcome>?>(null) }

    LaunchedEffect(ref) {
        try {
            val c = SpotifyCollection.fetch(ref) { loadPhase = it }
            collection = c
            selected = c.items.filter { it.playable }.map { it.id }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.message ?: "Ophalen mislukt"
        }
    }

    fun close() {
        job?.cancel()
        AppBusyState.isBusy = false
        onDismiss()
    }

    Dialog(onDismissRequest = { if (!running) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val c = collection
                Text(c?.name?.ifBlank { null } ?: "${ref.kind.label} importeren", style = MaterialTheme.typography.titleLarge,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)

                when {
                    // ---- Uitkomst ----
                    outcomes != null -> {
                        val list = outcomes!!
                        val ok = list.count { it.file != null }
                        val failed = list.filter { it.file == null }
                        Text("$ok van ${list.size} nummers staan in de bibliotheek" +
                            (if (list.any { it.existed }) " (${list.count { it.existed }} stonden er al)" else "") + ".")
                        if (failed.isNotEmpty()) {
                            Text("Niet gelukt (${failed.size}):", style = MaterialTheme.typography.labelLarge)
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                itemsIndexed(failed) { _, o ->
                                    Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                        Text("${o.item.artist} — ${o.item.title}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(o.error ?: "onbekende fout", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        } else Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = { close() }, modifier = Modifier.fillMaxWidth()) { Text("Sluiten") }
                    }

                    // ---- Bezig ----
                    running -> {
                        Text("Nummer ${runIndex + 1} van $runTotal", style = MaterialTheme.typography.labelLarge)
                        Text(runTitle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        LinearProgressIndicator(progress = { runProgress }, modifier = Modifier.fillMaxWidth())
                        Text(runPhase, style = MaterialTheme.typography.bodySmall)
                        LinearProgressIndicator(
                            progress = { if (runTotal == 0) 0f else (runIndex + runProgress) / runTotal },
                            modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Stoppen") }
                    }

                    // ---- Fout bij laden ----
                    loadError != null -> {
                        Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.weight(1f))
                        Button(onClick = { close() }, modifier = Modifier.fillMaxWidth()) { Text("Sluiten") }
                    }

                    // ---- Laden ----
                    c == null -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(loadPhase)
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { close() }, modifier = Modifier.fillMaxWidth()) { Text("Annuleren") }
                    }

                    // ---- Lijst met vinkjes ----
                    else -> {
                        Text("${c.ref.kind.label} van ${c.owner.ifBlank { "onbekend" }} · ${c.items.size} nummers",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!c.complete) {
                            Text("Alleen de eerste ${c.items.size} nummers zijn geladen; de rest gaf Spotify niet vrij.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { selected = c.items.filter { it.playable }.map { it.id }.toSet() }) { Text("Alles") }
                            TextButton(onClick = { selected = emptySet() }) { Text("Niets") }
                        }
                        LazyColumn(modifier = Modifier.weight(1f)) {
                            itemsIndexed(c.items, key = { i, it -> "$i-${it.id}" }) { _, item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable(enabled = item.playable) {
                                        selected = if (item.id in selected) selected - item.id else selected + item.id
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = item.id in selected, enabled = item.playable, onCheckedChange = { on ->
                                        selected = if (on) selected + item.id else selected - item.id
                                    })
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(item.artist + (item.durationSec?.let { " · ${it / 60}:${"%02d".format(it % 60)}" } ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { asPlaylist = !asPlaylist }) {
                            Checkbox(checked = asPlaylist, onCheckedChange = { asPlaylist = it })
                            Text("Ook als playlist in de app opslaan (gepauzeerd)", style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { close() }, modifier = Modifier.weight(1f)) { Text("Annuleren") }
                            Button(
                                enabled = selected.isNotEmpty(),
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    val todo = c.items.filter { it.id in selected }.distinctBy { it.id }
                                    running = true; runTotal = todo.size; runIndex = 0
                                    AppBusyState.isBusy = true
                                    job = scope.launch {
                                        val done = mutableListOf<ImportOutcome>()
                                        try {
                                            runSpotifyImport(context, client, db, c, todo,
                                                ringtoneManager.storage.getDownloadDir(), asPlaylist, done
                                            ) { i, title, phase, progress ->
                                                runIndex = i; runTitle = title; runPhase = phase; runProgress = progress
                                            }
                                        } catch (_: CancellationException) {
                                            // gestopt door de gebruiker: toon wat er wel is gelukt
                                        } finally {
                                            AppBusyState.isBusy = false
                                            running = false
                                            outcomes = done.toList()
                                        }
                                    }
                                }
                            ) { Text("Download ${selected.size}") }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Downloadt [todo] één voor één en zet elk gelukt nummer in de bibliotheek (en naar keuze in een nieuwe playlist).
 * Vult [done] tijdens het lopen, zodat een afgebroken import laat zien wat er wel is.
 */
internal suspend fun runSpotifyImport(
    context: android.content.Context,
    client: SpotifyPreviewClient,
    db: RingtoneDatabase,
    collection: SpotifyCollection.Collection,
    todo: List<SpotifyCollection.Item>,
    destDir: File,
    asPlaylist: Boolean,
    done: MutableList<ImportOutcome>,
    onStep: (index: Int, title: String, phase: String, progress: Float) -> Unit
) {
    val isAlbum = collection.ref.kind == SpotifyCollection.Kind.ALBUM
    val albumCover = if (isAlbum) collection.coverUrl?.let { client.fetchBytes(it) } else null
    RemoteLogger.input("SpotifyImport", "start", mapOf("name" to collection.name, "kind" to collection.ref.kind.path,
        "selected" to "${todo.size}", "of" to "${collection.items.size}", "playlist" to "$asPlaylist"))
    var playlistId: Long? = null
    for ((i, item) in todo.withIndex()) {
        val label = "${item.artist} — ${item.title}"
        onStep(i, label, "Voorbereiden...", 0f)
        // Hoes per nummer: bij een album de albumhoes, bij een playlist de hoes van het nummer zelf.
        val meta = if (isAlbum) null else client.fetchEmbedMeta(item.id)
        val info = SpotifyPreviewClient.TrackInfo(id = item.id, name = item.title, artist = item.artist,
            albumArt = meta?.albumArt, durationSec = item.durationSec ?: meta?.durationSec)
        val r = try {
            client.downloadFullInfo(info, destDir, { ph, p -> onStep(i, label, ph, p) }, forceOverwrite = false, strict = true,
                album = if (isAlbum) collection.name else null,
                trackNo = if (isAlbum) collection.items.indexOfFirst { it.id == item.id } + 1 else null,
                trackTotal = if (isAlbum) collection.items.size else null,
                coverJpeg = albumCover)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SpotifyPreviewClient.DownloadResult(false, error = e.message ?: "fout")
        }
        val file = r.file?.takeIf { (r.success || r.fileExists) && it.exists() }
        if (file != null) {
            val trackId = TrackIdResolver.canonicalTrackIdForName(file.name)
            db.savedTrackDao().insert(SavedTrack(
                deezerTrackId = trackId, title = file.nameWithoutExtension, artist = "Spotify", previewUrl = "",
                localPath = file.absolutePath, playlistName = "_spotify", markerType = "track",
                albumArtPath = extractSpotifyArt(context, file)))
            if (asPlaylist) {
                val pid = playlistId ?: db.playlistDao().insert(
                    Playlist(name = collection.name.ifBlank { "Spotify-import" }.take(60), isActive = false)).also { playlistId = it }
                db.playlistTrackDao().insert(PlaylistTrack(pid, trackId, sortOrder = i))
            }
        }
        done.add(ImportOutcome(item, file, if (file == null) (r.error ?: "mislukt") else null, existed = r.fileExists))
        onStep(i, label, if (file != null) "Klaar" else "Mislukt", 1f)
    }
    RemoteLogger.output("SpotifyImport", "klaar", mapOf("name" to collection.name,
        "ok" to "${done.count { it.file != null }}", "mislukt" to "${done.count { it.file == null }}",
        "bestond" to "${done.count { it.existed }}"))
}
