package nl.icthorse.randomringtone.ui.screens

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*

/**
 * Afronding van elke restore (v2.2.3):
 * - bestanden van een eerdere installatie die de restore niet mocht vervangen ⇒ één Android-toestemmingsvenster
 *   (MediaStore.createWriteRequest) ⇒ vervangen ⇒ album art opnieuw lezen;
 * - daarna scant de Bibliotheek bij openen opnieuw ([LibraryRescan]).
 * [onMessage] krijgt de eindmelding; roep pas daarna iets aan dat dit scherm sluit (anders gaat het
 * toestemmingsresultaat verloren).
 */
class RestoreFinisher internal constructor(
    private val context: Context,
    private val db: RingtoneDatabase,
    private val scope: CoroutineScope
) {
    internal var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var granted: Map<PendingOverwrite, Uri> = emptyMap()
    private var all: List<PendingOverwrite> = emptyList()
    private var done: (String) -> Unit = {}

    fun finish(result: BackupResult, onMessage: (String) -> Unit) {
        LibraryRescan.requested = true
        if (result.pendingOverwrites.isEmpty()) { onMessage(result.message); return }
        scope.launch {
            all = result.pendingOverwrites
            done = { extra -> onMessage(result.message + extra) }
            val (found, missing) = ForeignFileWriter.resolve(context, all)
            val request = ForeignFileWriter.writeRequest(context, found.values)
            val l = launcher
            if (request == null || l == null) {
                ForeignFileWriter.cleanup(all)
                done("\nVervangen niet mogelijk voor ${all.size} bestand(en)")
                return@launch
            }
            if (missing.isNotEmpty()) RemoteLogger.w("Restore", "Niet in MediaStore — kan niet vervangen",
                mapOf("files" to missing.joinToString(" | ") { it.targetPath.substringAfterLast('/') }))
            granted = found
            RemoteLogger.i("Restore", "Toestemming gevraagd om te vervangen", mapOf("files" to found.size.toString()))
            l.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }

    internal fun onResult(ok: Boolean) {
        scope.launch {
            val extra = if (ok) {
                val written = ForeignFileWriter.apply(context, granted)
                Mp3TagReader.enrichAll(context, db, written.toSet())
                "\n${written.size} bestand(en) van een eerdere installatie vervangen"
            } else {
                RemoteLogger.w("Restore", "Toestemming om te vervangen geweigerd", mapOf("files" to granted.size.toString()))
                "\nVervangen geweigerd — de oude bestanden blijven staan"
            }
            ForeignFileWriter.cleanup(all)
            granted = emptyMap(); all = emptyList()
            done(extra)
        }
    }
}

@Composable
fun rememberRestoreFinisher(db: RingtoneDatabase): RestoreFinisher {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val finisher = remember { RestoreFinisher(context.applicationContext, db, scope) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        finisher.onResult(it.resultCode == Activity.RESULT_OK)
    }
    finisher.launcher = launcher
    return finisher
}
