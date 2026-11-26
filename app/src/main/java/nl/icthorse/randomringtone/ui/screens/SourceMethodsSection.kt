package nl.icthorse.randomringtone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*

/**
 * Instellingen → "Bronnen & downloadmethodes" (v2.3.0).
 * Per bron een keuze met uitleg en een knop "Test" die live meet of de methode op dit toestel/netwerk werkt.
 */
@Composable
fun SourceMethodsSection(storage: StorageManager, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var youtube by remember { mutableStateOf(YouTubeMethod.SERVER) }
    val results = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) {
        youtube = storage.getYouTubeMethod()
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
        "Hoe een Spotify-link of YouTube-video een ringtonebestand wordt (sinds v2.6.0 alleen nog via YouTube). Met 'Test' meet je live of een methode " +
            "op dit toestel en netwerk werkt.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    // ── Spotify (v2.6.0: één methode) ──
    Text("Spotify", style = MaterialTheme.typography.titleSmall)
    MethodRow(
        selected = true, label = "Volledig nummer (via YouTube)",
        detail = "Titel, artiest en duur komen van de openbare Spotify-pagina. De app zoekt het nummer op YouTube, " +
            "controleert titel, artiest en duur en downloadt de audio (M4A) volgens de YouTube-methode hieronder. " +
            "Een playlist of album is in één keer te importeren.",
        result = results["spotify_full"], onSelect = null,
        onTest = {
            runTest("spotify_full") {
                YouTubeOnDevice.selfTest() + "\nPlaylist/album: " + SpotifyCollection.selfTest()
            }
        }
    )

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
                        YouTubeMethod.SERVER -> nl.icthorse.randomringtone.data.YouTubeServer.selfTest(
                            nl.icthorse.randomringtone.data.LicenseManager(context))
                        YouTubeMethod.DEVICE -> YouTubeOnDevice.selfTest()
                    }
                }
            }
        )
    }

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
