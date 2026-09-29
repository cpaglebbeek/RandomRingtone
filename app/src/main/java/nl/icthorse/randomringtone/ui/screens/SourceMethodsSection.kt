package nl.icthorse.randomringtone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.icthorse.randomringtone.data.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Instellingen → "Bronnen & downloadmethodes" (v2.3.0).
 * Per bron een keuze met uitleg en een knop "Test" die live meet of de methode op dit toestel/netwerk werkt.
 */
@Composable
fun SourceMethodsSection(storage: StorageManager, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var spotify by remember { mutableStateOf(SpotifyMethod.PREVIEW) }
    var youtube by remember { mutableStateOf(YouTubeMethod.DEVICE) }
    var converterId by remember { mutableStateOf(StorageManager.DEFAULT_SPOTIFY_CONVERTER) }
    val results = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) {
        spotify = storage.getSpotifyMethod()
        youtube = storage.getYouTubeMethod()
        converterId = storage.getSpotifyConverter()
    }

    fun runTest(key: String, block: suspend () -> String) {
        results[key] = "… bezig met testen"
        scope.launch {
            val r = try { block() } catch (e: Exception) { "✗ ${e.message ?: e.javaClass.simpleName}" }
            results[key] = r
            RemoteLogger.i("MethodTest", key, mapOf("result" to r.take(200)))
        }
    }

    Text("Bronnen & downloadmethodes", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Hoe een Spotify-link of YouTube-video een ringtonebestand wordt. Met 'Test' meet je live of een methode " +
            "op dit toestel en netwerk werkt.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    // ── Spotify ──
    Text("Spotify", style = MaterialTheme.typography.titleSmall)
    SpotifyMethod.entries.forEach { m ->
        MethodRow(
            selected = spotify == m, label = m.label, detail = m.detail, result = results["spotify_${m.id}"],
            onSelect = {
                spotify = m
                scope.launch { storage.setSpotifyMethod(m); snackbarHostState.showSnackbar("Spotify: ${m.label}") }
            },
            onTest = {
                runTest("spotify_${m.id}") {
                    val client = SpotifyPreviewClient(context)
                    when (m) {
                        SpotifyMethod.PREVIEW -> client.selfTestEmbed()
                        SpotifyMethod.FULL -> YouTubeOnDevice.selfTest()
                        SpotifyMethod.BACKEND -> client.selfTestBackend()
                        SpotifyMethod.CONVERTER -> probeSite(SpotifyConverter.findById(converterId).url)
                    }
                }
            }
        )
    }
    if (spotify == SpotifyMethod.CONVERTER) {
        Text("Converter-website", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 12.dp, top = 4.dp))
        SpotifyConverter.ALL.forEach { c ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    converterId = c.id
                    scope.launch { storage.setSpotifyConverter(c.id) }
                }.padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = converterId == c.id, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = MaterialTheme.typography.bodyMedium)
                    Text(c.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))

    // ── YouTube ──
    Text("YouTube", style = MaterialTheme.typography.titleSmall)
    YouTubeMethod.entries.forEach { m ->
        MethodRow(
            selected = youtube == m, label = m.label, detail = m.detail, result = results["youtube_${m.id}"],
            onSelect = {
                youtube = m
                scope.launch { storage.setYouTubeMethod(m); snackbarHostState.showSnackbar("YouTube: ${m.label}") }
            },
            onTest = {
                runTest("youtube_${m.id}") {
                    when (m) {
                        YouTubeMethod.DEVICE -> YouTubeOnDevice.selfTest()
                        YouTubeMethod.Y2MATE -> probeY2Mate()
                    }
                }
            }
        )
    }

    Spacer(Modifier.height(16.dp))

    // ── Deezer (alleen uitleg + test) ──
    Text("Deezer", style = MaterialTheme.typography.titleSmall)
    MethodRow(
        selected = true, label = "Openbare Deezer-API (automatisch)",
        detail = "Geen sleutel nodig. Levert de 30-s-preview (128 kbps) bij de Spotify-preview en hoezen in de editor.",
        result = results["deezer"], onSelect = null,
        onTest = { runTest("deezer") { probeDeezer() } }
    )
}

@Composable
private fun MethodRow(
    selected: Boolean, label: String, detail: String, result: String?,
    onSelect: (() -> Unit)?, onTest: () -> Unit
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().let { if (onSelect != null) it.clickable(onClick = onSelect) else it },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onSelect != null) { RadioButton(selected = selected, onClick = null); Spacer(Modifier.width(8.dp)) }
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onTest) { Text("Test") }
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    result, style = MaterialTheme.typography.labelMedium,
                    color = when {
                        result.startsWith("✓") -> MaterialTheme.colorScheme.primary
                        result.startsWith("✗") -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

private val probe = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
    .callTimeout(20, TimeUnit.SECONDS).build()
private const val PROBE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

/** Bereikbaar én niet naar een ander domein doorgestuurd (gekaapte sites!). */
private suspend fun probeSite(url: String): String = withContext(Dispatchers.IO) {
    val host = java.net.URI(url).host.removePrefix("www.")
    probe.newCall(Request.Builder().url(url).header("User-Agent", PROBE_UA).build()).execute().use { r ->
        val finalHost = r.request.url.host.removePrefix("www.")
        when {
            finalHost != host -> "✗ Stuurt door naar $finalHost — niet gebruiken"
            r.code == 403 || r.code == 503 -> "~ Bereikbaar, met browsercontrole (HTTP ${r.code}) — in de app handmatig"
            r.isSuccessful -> "✓ Bereikbaar (HTTP ${r.code})"
            else -> "✗ HTTP ${r.code}"
        }
    }
}

private suspend fun probeY2Mate(): String = withContext(Dispatchers.IO) {
    probe.newCall(Request.Builder().url("https://eta.etacloud.org/api/v1/auth?_=${System.currentTimeMillis()}")
        .header("User-Agent", PROBE_UA).header("Referer", "https://v3.y2mate.nu/").header("Origin", "https://v3.y2mate.nu")
        .build()).execute().use { r ->
        val body = r.body?.string().orEmpty()
        if (r.isSuccessful && "\"key\"" in body) "✓ Y2Mate-API antwoordt (sleutel ontvangen)" else "✗ Y2Mate geblokkeerd (HTTP ${r.code})"
    }
}

private suspend fun probeDeezer(): String = withContext(Dispatchers.IO) {
    val hits = DeezerApi().searchTracks("toto africa", 1)
    val t = hits.firstOrNull() ?: return@withContext "✗ Geen resultaten"
    val ok = probe.newCall(Request.Builder().url(t.preview).header("Range", "bytes=0-4095").build()).execute().use { it.isSuccessful }
    if (ok) "✓ Werkt — ${t.artist.name} - ${t.title} (preview bereikbaar)" else "✗ Preview niet bereikbaar"
}
