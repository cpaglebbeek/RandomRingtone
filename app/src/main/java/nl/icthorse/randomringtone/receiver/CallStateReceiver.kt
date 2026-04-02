package nl.icthorse.randomringtone.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import nl.icthorse.randomringtone.data.*
import nl.icthorse.randomringtone.data.RemoteLogger

/**
 * BroadcastReceiver voor EVERY_CALL schedule.
 * Luistert naar telefoon-state changes en wisselt de ringtone na elk gesprek.
 * Vangt beller-info op bij RINGING en stuurt CALL_SUMMARY na verwerking.
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStateReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var lastCallerNumber: String? = null
        private var lastCallerName: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        RemoteLogger.init(context)

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val currentState = when (stateStr) {
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> return
        }

        // Beller-info opvangen bij RINGING
        if (currentState == TelephonyManager.CALL_STATE_RINGING) {
            @Suppress("DEPRECATION")
            lastCallerNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            lastCallerName = lastCallerNumber?.let { number ->
                try {
                    ContactsRepository(context).getContactNameByNumber(number)
                } catch (_: Exception) { null }
            }
            RemoteLogger.trigger("CallState", "Phone state: $stateStr", mapOf(
                "from" to lastState.toString(),
                "to" to currentState.toString(),
                "callerNumber" to (lastCallerNumber ?: "onbekend"),
                "callerName" to (lastCallerName ?: "onbekend")
            ))
        } else {
            RemoteLogger.trigger("CallState", "Phone state: $stateStr", mapOf(
                "from" to lastState.toString(),
                "to" to currentState.toString()
            ))
        }

        // Gesprek beëindigd: was OFFHOOK of RINGING, nu IDLE
        if (currentState == TelephonyManager.CALL_STATE_IDLE &&
            lastState != TelephonyManager.CALL_STATE_IDLE
        ) {
            RemoteLogger.i("CallState", "Gesprek beëindigd → EVERY_CALL ringtone wissel starten")
            handleCallEnded(context, lastCallerNumber, lastCallerName)
            lastCallerNumber = null
            lastCallerName = null
        }

        lastState = currentState
    }

    private fun handleCallEnded(context: Context, callerNumber: String?, callerName: String?) {
        CoroutineScope(Dispatchers.IO).launch {
            val swapResults = mutableListOf<Map<String, String>>()
            try {
                val db = RingtoneDatabase.getInstance(context)
                val ringtoneManager = AppRingtoneManager(context)
                val resolver = TrackResolver(db, ringtoneManager, context)

                // Log ALLE actieve playlists vóór filter
                val allActive = db.playlistDao().getActive()
                RemoteLogger.i("CallState", "Alle actieve playlists", mapOf(
                    "count" to allActive.size.toString(),
                    "playlists" to allActive.joinToString { "${it.name}[${it.channel}/${it.schedule}/${if (it.contactUri != null) "contact:${it.contactName}" else "globaal"}]" }
                ))

                // Filter op CALL + EVERY_CALL
                val playlists = allActive.filter {
                    it.channel == Channel.CALL && it.schedule == Schedule.EVERY_CALL
                }

                RemoteLogger.i("CallState", "EVERY_CALL playlists na filter", mapOf(
                    "count" to playlists.size.toString(),
                    "playlists" to playlists.joinToString { "${it.name}[${if (it.contactUri != null) "contact:${it.contactName}" else "globaal"}]" }
                ))

                for (playlist in playlists) {
                    try {
                        val trackCount = db.playlistTrackDao().getTracksForPlaylist(playlist.id).size
                        RemoteLogger.i("CallState", "Verwerk playlist: ${playlist.name}", mapOf(
                            "contact" to (playlist.contactName ?: "globaal"),
                            "mode" to playlist.mode.name,
                            "trackCount" to trackCount.toString()
                        ))

                        val result = resolver.resolveForPlaylist(playlist)
                        if (result == null) {
                            RemoteLogger.w("CallState", "SKIP playlist — geen track beschikbaar", mapOf("playlist" to playlist.name))
                            swapResults.add(mapOf(
                                "playlist" to playlist.name,
                                "contact" to (playlist.contactName ?: "globaal"),
                                "track" to "-",
                                "reason" to "Geen track beschikbaar",
                                "success" to "false"
                            ))
                            continue
                        }
                        val (file, track) = result
                        val trackLabel = "${track.artist} - ${track.title}"
                        val reason = "${playlist.mode.name} uit $trackCount tracks"

                        if (playlist.contactUri != null) {
                            val contactName = playlist.contactName ?: "contact"
                            RemoteLogger.i("CallState", "SWAP contact ringtone", mapOf("contact" to contactName, "track" to trackLabel))
                            val success = ringtoneManager.swapContactRingtone(file, contactName, playlist.contactUri)
                            RemoteLogger.output("CallState", "Contact swap result", mapOf(
                                "playlist" to playlist.name, "contact" to contactName,
                                "track" to trackLabel, "success" to success.toString()
                            ))
                            swapResults.add(mapOf(
                                "playlist" to playlist.name,
                                "contact" to contactName,
                                "track" to trackLabel,
                                "reason" to reason,
                                "success" to success.toString()
                            ))
                        } else {
                            RemoteLogger.i("CallState", "SWAP globale ringtone", mapOf("track" to trackLabel))
                            val success = ringtoneManager.swapGlobalRingtone(file)
                            RemoteLogger.output("CallState", "Globale swap result", mapOf(
                                "playlist" to playlist.name,
                                "track" to trackLabel, "success" to success.toString()
                            ))
                            swapResults.add(mapOf(
                                "playlist" to playlist.name,
                                "contact" to "globaal",
                                "track" to trackLabel,
                                "reason" to reason,
                                "success" to success.toString()
                            ))
                        }

                        resolver.updateLastPlayed(playlist, track.deezerTrackId)
                        Log.i(TAG, "EVERY_CALL: ringtone gewisseld naar $trackLabel")
                    } catch (e: Exception) {
                        RemoteLogger.e("CallState", "EXCEPTION bij playlist ${playlist.name}", mapOf(
                            "error" to (e.message ?: "unknown"),
                            "contact" to (playlist.contactName ?: "globaal")
                        ))
                        swapResults.add(mapOf(
                            "playlist" to playlist.name,
                            "contact" to (playlist.contactName ?: "globaal"),
                            "track" to "-",
                            "reason" to "Exception: ${e.message}",
                            "success" to "false"
                        ))
                        Log.e(TAG, "Error bij playlist ${playlist.name}", e)
                    }
                }
                RemoteLogger.i("CallState", "EVERY_CALL verwerking compleet", mapOf("verwerkt" to playlists.size.toString()))

                // CALL_SUMMARY: gestructureerd overzicht voor Overview tab
                val caller = callerName ?: callerNumber ?: "Onbekend"
                RemoteLogger.callSummary(context, caller, swapResults)

            } catch (e: Exception) {
                RemoteLogger.e("CallState", "FATALE ERROR in handleCallEnded", mapOf("error" to (e.message ?: "unknown")))
                Log.e(TAG, "Error bij EVERY_CALL ringtone wissel", e)
            }
        }
    }
}
