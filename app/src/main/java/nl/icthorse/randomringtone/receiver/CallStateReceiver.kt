package nl.icthorse.randomringtone.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*
import nl.icthorse.randomringtone.data.RemoteLogger
import nl.icthorse.randomringtone.service.VideoRingtoneService
import org.json.JSONArray
import org.json.JSONObject

/**
 * BroadcastReceiver voor EVERY_CALL schedule.
 * Luistert naar telefoon-state changes en wisselt de ringtone na elk gesprek.
 *
 * Beller-identificatie (gelaagd):
 * 1. EXTRA_INCOMING_NUMBER bij RINGING (vereist READ_CALL_LOG op Android 10+)
 * 2. CallLog.Calls query na gesprek (vereist READ_CALL_LOG)
 * 3. Fallback: "Onbekend"
 * Nummer wordt altijd opgezocht in contacten (vereist READ_CONTACTS).
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStateReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var lastCallerNumber: String? = null
        private var lastCallerName: String? = null
        private var wasIncoming = false
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val appContext = context.applicationContext
        RemoteLogger.init(appContext)

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val currentState = when (stateStr) {
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> return
        }

        // Laag 1: Beller-info opvangen bij RINGING via EXTRA_INCOMING_NUMBER
        if (currentState == TelephonyManager.CALL_STATE_RINGING) {
            wasIncoming = true
            @Suppress("DEPRECATION")
            val extraNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            if (!extraNumber.isNullOrBlank()) {
                lastCallerNumber = extraNumber
                lastCallerName = resolveContactName(appContext, extraNumber)
                RemoteLogger.trigger("CallState", "Phone state: $stateStr", mapOf(
                    "from" to lastState.toString(),
                    "to" to currentState.toString(),
                    "callerNumber" to extraNumber,
                    "callerName" to (lastCallerName ?: "niet in contacten"),
                    "bron" to "EXTRA_INCOMING_NUMBER"
                ))
            } else {
                RemoteLogger.trigger("CallState", "Phone state: $stateStr", mapOf(
                    "from" to lastState.toString(),
                    "to" to currentState.toString(),
                    "callerNumber" to "niet beschikbaar",
                    "hint" to "READ_CALL_LOG permissie nodig voor nummer op Android 10+"
                ))
            }
        } else if (currentState == TelephonyManager.CALL_STATE_IDLE ||
                   currentState == TelephonyManager.CALL_STATE_OFFHOOK) {
            // Gesprek opgenomen of beëindigd → stop video overlay
            VideoRingtoneService.stop(appContext)
            RemoteLogger.trigger("CallState", "Phone state: $stateStr", mapOf(
                "from" to lastState.toString(),
                "to" to currentState.toString()
            ))
        } else {
            RemoteLogger.trigger("CallState", "Phone state: $stateStr", mapOf(
                "from" to lastState.toString(),
                "to" to currentState.toString()
            ))
        }

        // VideoRing: start video overlay bij inkomend gesprek
        if (currentState == TelephonyManager.CALL_STATE_RINGING) {
            val appContext = context.applicationContext
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = RingtoneDatabase.getInstance(appContext)
                    val activeVideo = db.videoRingtoneDao().getActive()
                    if (activeVideo != null && java.io.File(activeVideo.localPath).exists()) {
                        if (android.provider.Settings.canDrawOverlays(appContext)) {
                            VideoRingtoneService.start(
                                appContext,
                                activeVideo.localPath,
                                lastCallerName ?: "Onbekend",
                                lastCallerNumber
                            )
                            RemoteLogger.i("CallState", "VideoRing gestart", mapOf(
                                "video" to activeVideo.title,
                                "caller" to (lastCallerName ?: "onbekend")
                            ))
                        }
                    }
                } catch (e: Exception) {
                    RemoteLogger.e("CallState", "VideoRing start mislukt", mapOf(
                        "error" to (e.message ?: "")
                    ))
                } finally {
                    pendingResult.finish()
                }
            }
        }

        // Gesprek beëindigd: was OFFHOOK of RINGING, nu IDLE
        if (currentState == TelephonyManager.CALL_STATE_IDLE &&
            lastState != TelephonyManager.CALL_STATE_IDLE
        ) {
            if (wasIncoming) {
                // Laag 2: Als RINGING geen nummer gaf, probeer CallLog
                if (lastCallerNumber == null) {
                    val callLogResult = queryLastIncomingCall(appContext)
                    if (callLogResult != null) {
                        lastCallerNumber = callLogResult.first
                        lastCallerName = callLogResult.second
                            ?: resolveContactName(context, callLogResult.first)
                        RemoteLogger.i("CallState", "Beller via CallLog geïdentificeerd", mapOf(
                            "callerNumber" to (lastCallerNumber ?: "?"),
                            "callerName" to (lastCallerName ?: "niet in contacten"),
                            "bron" to "CallLog.Calls"
                        ))
                    }
                }

                RemoteLogger.i("CallState", "Inkomend gesprek beëindigd → EVERY_CALL ringtone wissel starten", mapOf(
                    "caller" to formatCaller(lastCallerName, lastCallerNumber)
                ))
                handleCallEnded(appContext, lastCallerNumber, lastCallerName)
            } else {
                RemoteLogger.i("CallState", "Uitgaand gesprek beëindigd → SKIP (geen swap bij uitgaand)")
            }
            lastCallerNumber = null
            lastCallerName = null
            wasIncoming = false
        }

        lastState = currentState
    }

    /**
     * Zoek contactnaam op basis van telefoonnummer.
     * Vereist: READ_CONTACTS (al in manifest + runtime).
     */
    private fun resolveContactName(context: Context, number: String): String? {
        return try {
            ContactsRepository(context).getContactNameByNumber(number)
        } catch (_: Exception) { null }
    }

    /**
     * Haal het laatste inkomende gesprek op uit de CallLog.
     * Vereist: READ_CALL_LOG permissie.
     * @return Pair(nummer, cachedName) of null
     */
    private fun queryLastIncomingCall(context: Context): Pair<String, String?>? {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CALL_LOG
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            RemoteLogger.w("CallState", "READ_CALL_LOG niet toegekend — kan CallLog niet raadplegen")
            return null
        }

        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE),
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(CallLog.Calls.INCOMING_TYPE.toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(0)
                    val cachedName = cursor.getString(1)
                    if (!number.isNullOrBlank()) Pair(number, cachedName) else null
                } else null
            }
        } catch (e: Exception) {
            RemoteLogger.e("CallState", "CallLog query FAILED", mapOf("error" to (e.message ?: "unknown")))
            null
        }
    }

    private fun formatCaller(name: String?, number: String?): String {
        return when {
            name != null && number != null -> "$name ($number)"
            name != null -> name
            number != null -> number
            else -> "Onbekend"
        }
    }

    private fun handleCallEnded(context: Context, callerNumber: String?, callerName: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            val swapResults = mutableListOf<Map<String, String>>()
            try {
                val db = RingtoneDatabase.getInstance(context)
                val ringtoneManager = AppRingtoneManager(context)
                val resolver = TrackResolver(db, ringtoneManager, context)

                val allActive = db.playlistDao().getActive()
                RemoteLogger.i("CallState", "Alle actieve playlists", mapOf(
                    "count" to allActive.size.toString(),
                    "playlists" to allActive.joinToString { "${it.name}[${it.channel}/${it.schedule}/${if (it.contactUri != null) "contact:${it.contactName}" else "globaal"}]" }
                ))

                val playlists = allActive.filter {
                    it.channel == Channel.CALL && it.schedule == Schedule.EVERY_CALL
                }

                RemoteLogger.i("CallState", "EVERY_CALL playlists na filter", mapOf(
                    "count" to playlists.size.toString(),
                    "playlists" to playlists.joinToString { "${it.name}[${if (it.contactUri != null) "contact:${it.contactName}" else "globaal"}]" }
                ))

                for (playlist in playlists) {
                    try {
                        val tracks = db.playlistTrackDao().getTracksForPlaylist(playlist.id)
                        val trackCount = tracks.size

                        // Huidige ringtone: track die afspeelde bij DIT gesprek
                        // = lastPlayedTrackId van de vorige cyclus (wat nu actief is)
                        val currentTrack = playlist.lastPlayedTrackId?.let { lastId ->
                            tracks.find { it.deezerTrackId == lastId }
                        }
                        val currentLabel = currentTrack?.let { "${it.artist} - ${it.title}" } ?: "Eerste keer"

                        RemoteLogger.i("CallState", "Verwerk playlist: ${playlist.name}", mapOf(
                            "contact" to (playlist.contactName ?: "globaal"),
                            "mode" to playlist.mode.name,
                            "trackCount" to trackCount.toString(),
                            "huidigeRingtone" to currentLabel,
                            "lastPlayedTrackId" to (playlist.lastPlayedTrackId?.toString() ?: "null")
                        ))

                        val result = resolver.resolveForPlaylist(playlist)
                        if (result == null) {
                            RemoteLogger.w("CallState", "SKIP playlist — geen track beschikbaar", mapOf("playlist" to playlist.name))
                            swapResults.add(mapOf(
                                "playlist" to playlist.name,
                                "contact" to (playlist.contactName ?: "globaal"),
                                "currentTrack" to currentLabel,
                                "nextTrack" to "-",
                                "reason" to "Geen track beschikbaar",
                                "success" to "false"
                            ))
                            continue
                        }
                        val (file, track) = result
                        val nextLabel = "${track.artist} - ${track.title}"
                        val reason = "${playlist.mode.name} uit $trackCount tracks"

                        if (playlist.contactUri != null) {
                            val contactName = playlist.contactName ?: "contact"
                            RemoteLogger.i("CallState", "SWAP contact ringtone", mapOf("contact" to contactName, "track" to nextLabel))
                            val success = ringtoneManager.swapContactRingtone(file, contactName, playlist.contactUri)
                            RemoteLogger.output("CallState", "Contact swap result", mapOf(
                                "playlist" to playlist.name, "contact" to contactName,
                                "track" to nextLabel, "success" to success.toString()
                            ))
                            swapResults.add(mapOf(
                                "playlist" to playlist.name,
                                "contact" to contactName,
                                "currentTrack" to currentLabel,
                                "nextTrack" to nextLabel,
                                "reason" to reason,
                                "success" to success.toString()
                            ))
                        } else {
                            RemoteLogger.i("CallState", "SWAP globale ringtone", mapOf("track" to nextLabel))
                            val success = ringtoneManager.swapGlobalRingtone(file)
                            RemoteLogger.output("CallState", "Globale swap result", mapOf(
                                "playlist" to playlist.name,
                                "track" to nextLabel, "success" to success.toString()
                            ))
                            swapResults.add(mapOf(
                                "playlist" to playlist.name,
                                "contact" to "globaal",
                                "currentTrack" to currentLabel,
                                "nextTrack" to nextLabel,
                                "reason" to reason,
                                "success" to success.toString()
                            ))
                        }

                        resolver.updateLastPlayed(playlist, track.deezerTrackId)
                        Log.i(TAG, "EVERY_CALL: ringtone gewisseld naar $nextLabel")
                    } catch (e: Exception) {
                        RemoteLogger.e("CallState", "EXCEPTION bij playlist ${playlist.name}", mapOf(
                            "error" to (e.message ?: "unknown"),
                            "contact" to (playlist.contactName ?: "globaal")
                        ))
                        swapResults.add(mapOf(
                            "playlist" to playlist.name,
                            "contact" to (playlist.contactName ?: "globaal"),
                            "currentTrack" to "-",
                            "nextTrack" to "-",
                            "reason" to "Exception: ${e.message}",
                            "success" to "false"
                        ))
                        Log.e(TAG, "Error bij playlist ${playlist.name}", e)
                    }
                }
                RemoteLogger.i("CallState", "EVERY_CALL verwerking compleet", mapOf("verwerkt" to playlists.size.toString()))

                // CALL_SUMMARY met gelaagde beller-identificatie
                RemoteLogger.callSummary(
                    context,
                    callerName = callerName,
                    callerNumber = callerNumber,
                    swaps = swapResults
                )

            } catch (e: Exception) {
                RemoteLogger.e("CallState", "FATALE ERROR in handleCallEnded", mapOf("error" to (e.message ?: "unknown")))
                Log.e(TAG, "Error bij EVERY_CALL ringtone wissel", e)
            }
        }
    }
}
