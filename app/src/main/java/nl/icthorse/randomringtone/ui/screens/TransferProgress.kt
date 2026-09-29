package nl.icthorse.randomringtone.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import nl.icthorse.randomringtone.data.BackupProgress

/**
 * Voortgangsbalk voor backup/restore (v2.0.1): balk, %, MB, snelheid, live ETA en verstreken tijd.
 * De ETA komt uit TransferMeter (glijdend venster) en telt tussen twee updates per seconde door; blijft een update
 * langer dan 5 s uit, dan staat er "wacht op verbinding…".
 */
@Composable
fun TransferProgress(p: BackupProgress?, modifier: Modifier = Modifier) {
    if (p == null) {
        LinearProgressIndicator(modifier = modifier.fillMaxWidth())
        return
    }
    var receivedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(p) { receivedAt = System.currentTimeMillis(); now = receivedAt }
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
    val sinceUpdate = ((now - receivedAt) / 1000).toInt().coerceAtLeast(0)

    val fraction = when {
        p.totalBytes > 0 -> p.percentage
        p.total > 0 -> p.current.toFloat() / p.total
        else -> 0f
    }.coerceIn(0f, 1f)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(p.phase, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "${(fraction * 100).toInt()}%" + if (p.totalBytes > 0) "  ·  ${fmtBytes(p.bytesCopied)} / ${fmtBytes(p.totalBytes)}" else "",
                style = MaterialTheme.typography.labelSmall
            )
            if (p.bytesPerSecond > 0 && sinceUpdate < 5) {
                Text("${fmtBytes(p.bytesPerSecond)}/s", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val etaText = when {
                fraction >= 1f -> "klaar"
                sinceUpdate >= 5 -> "wacht op verbinding…"
                p.etaSeconds < 0 -> "resterende tijd berekenen…"
                else -> "nog ± " + fmtDuration((p.etaSeconds - sinceUpdate).coerceAtLeast(0))
            }
            Text(etaText, style = MaterialTheme.typography.labelMedium)
            Text("bezig " + fmtDuration(p.elapsedSeconds + sinceUpdate), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.2f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f MB".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

internal fun fmtDuration(s: Int): String = when {
    s >= 3600 -> "${s / 3600} u ${(s % 3600) / 60} min"
    s >= 60 -> "${s / 60} min ${s % 60} s"
    else -> "$s s"
}
