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
 * Instellingen → "Bron" (v2.7.0: alleen YourTube), met een knop "Test" die live meet of zoeken op dit toestel werkt.
 */
@Composable
fun SourceMethodsSection(storage: StorageManager, snackbarHostState: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateMapOf<String, String>() }

    fun runTest(key: String, block: suspend () -> String) {
        results[key] = "… bezig met testen"
        scope.launch {
            val r = try { block() } catch (e: Exception) { "✗ ${e.message ?: e.javaClass.simpleName}" }
            results[key] = r
            RemoteLogger.i("MethodTest", key, mapOf("result" to r.take(200)))
        }
    }

    Text("Bron", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(
        "Sinds v2.7.0 is er één bron: je eigen server YourTube. Met 'Test' meet je live of zoeken op dit toestel werkt.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    MethodRow(
        selected = true, label = "YourTube (eigen server)",
        detail = "Zoeken en downloaden lopen via de RandomRingtone-server en de motor van YourTube; de app haalt de " +
            "audio (M4A) daar op. Alleen op een gelicenseerd toestel. Is de server niet bereikbaar, dan kan er niet " +
            "gezocht of gedownload worden.",
        result = results["yourtube"], onSelect = null,
        onTest = { runTest("yourtube") { YourTube.selfTest(LicenseManager(context)) } }
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
