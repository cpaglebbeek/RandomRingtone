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

                // Zoek alle actieve CALL playlists met EVERY_CALL schema
                val playlists = db.playlistDao().getActive().filter {
                    it.channel == Channel.CALL && it.schedule == Schedule.EVERY_CALL
                }

                RemoteLogger.i("CallState", "EVERY_CALL playlists gevonden", mapOf("count" to playlists.size.toString()))

                for (playlist in playlists) {
                    RemoteLogger.i("CallState", "Verwerk playlist: ${playlist.name}", mapOf("contact" to (playlist.contactName ?: "globaal")))
                    val result = resolver.resolveForPlaylist(playlist) ?: continue
                    val (file, track) = result

                    if (playlist.contactUri != null) {
                        // Per-contact: swap content van <contact>-RandomRing.mp3
                        val contactName = playlist.contactName ?: "contact"
                        RemoteLogger.i("CallState", "SWAP contact ringtone", mapOf("contact" to contactName, "track" to "${track.artist} - ${track.title}"))
                        ringtoneManager.swapContactRingtone(file, contactName, playlist.contactUri)
                    } else {
                        // Globaal: swap content van RandomRingtone_Global.mp3
                        RemoteLogger.i("CallState", "SWAP globale ringtone", mapOf("track" to "${track.artist} - ${track.title}"))
                        ringtoneManager.swapGlobalRingtone(file)
                    }

                    resolver.updateLastPlayed(playlist, track.deezerTrackId)
                    RemoteLogger.output("CallState", "EVERY_CALL ringtone geswapped", mapOf(
                        "playlist" to playlist.name, "track" to "${track.artist} - ${track.title}",
                        "scope" to if (playlist.contactUri != null) "contact:${playlist.contactName}" else "globaal"
                    ))
                    Log.i(TAG, "EVERY_CALL: ringtone gewisseld naar ${track.artist} - ${track.title}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error bij EVERY_CALL ringtone wissel", e)
            }
        }
    }
}
