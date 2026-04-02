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
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallStateReceiver"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
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

        RemoteLogger.trigger("CallState", "Phone state: $stateStr", mapOf("from" to lastState.toString(), "to" to currentState.toString()))

        // Gesprek beëindigd: was OFFHOOK of RINGING, nu IDLE
        if (currentState == TelephonyManager.CALL_STATE_IDLE &&
            lastState != TelephonyManager.CALL_STATE_IDLE
        ) {
            RemoteLogger.i("CallState", "Gesprek beëindigd → EVERY_CALL ringtone wissel starten")
            handleCallEnded(context)
        }

        lastState = currentState
    }

    private fun handleCallEnded(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
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
                        RemoteLogger.i("CallState", "Verwerk playlist: ${playlist.name}", mapOf(
                            "contact" to (playlist.contactName ?: "globaal"),
                            "mode" to playlist.mode.name,
                            "trackCount" to "resolving..."
                        ))

                        val result = resolver.resolveForPlaylist(playlist)
                        if (result == null) {
                            RemoteLogger.w("CallState", "SKIP playlist — geen track beschikbaar", mapOf("playlist" to playlist.name))
                            continue
                        }
                        val (file, track) = result

                        if (playlist.contactUri != null) {
                            val contactName = playlist.contactName ?: "contact"
                            RemoteLogger.i("CallState", "SWAP contact ringtone", mapOf("contact" to contactName, "track" to "${track.artist} - ${track.title}"))
                            val success = ringtoneManager.swapContactRingtone(file, contactName, playlist.contactUri)
                            RemoteLogger.output("CallState", "Contact swap result", mapOf(
                                "playlist" to playlist.name, "contact" to contactName,
                                "track" to "${track.artist} - ${track.title}", "success" to success.toString()
                            ))
                        } else {
                            RemoteLogger.i("CallState", "SWAP globale ringtone", mapOf("track" to "${track.artist} - ${track.title}"))
                            val success = ringtoneManager.swapGlobalRingtone(file)
                            RemoteLogger.output("CallState", "Globale swap result", mapOf(
                                "playlist" to playlist.name,
                                "track" to "${track.artist} - ${track.title}", "success" to success.toString()
                            ))
                        }

                        resolver.updateLastPlayed(playlist, track.deezerTrackId)
                        Log.i(TAG, "EVERY_CALL: ringtone gewisseld naar ${track.artist} - ${track.title}")
                    } catch (e: Exception) {
                        RemoteLogger.e("CallState", "EXCEPTION bij playlist ${playlist.name}", mapOf(
                            "error" to (e.message ?: "unknown"),
                            "contact" to (playlist.contactName ?: "globaal")
                        ))
                        Log.e(TAG, "Error bij playlist ${playlist.name}", e)
                        // Door naar volgende playlist
                    }
                }
                RemoteLogger.i("CallState", "EVERY_CALL verwerking compleet", mapOf("verwerkt" to playlists.size.toString()))
            } catch (e: Exception) {
                RemoteLogger.e("CallState", "FATALE ERROR in handleCallEnded", mapOf("error" to (e.message ?: "unknown")))
                Log.e(TAG, "Error bij EVERY_CALL ringtone wissel", e)
            }
        }
    }
}
