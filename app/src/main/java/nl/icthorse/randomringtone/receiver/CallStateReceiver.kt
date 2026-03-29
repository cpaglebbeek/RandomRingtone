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

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val currentState = when (stateStr) {
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            else -> return
        }

        // Gesprek beëindigd: was OFFHOOK of RINGING, nu IDLE
        if (currentState == TelephonyManager.CALL_STATE_IDLE &&
            lastState != TelephonyManager.CALL_STATE_IDLE
        ) {
            handleCallEnded(context)
        }

        lastState = currentState
    }

    private fun handleCallEnded(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = RingtoneDatabase.getInstance(context)
                val ringtoneManager = AppRingtoneManager(context)
                val resolver = TrackResolver(db, ringtoneManager)

                // Zoek alle actieve CALL playlists met EVERY_CALL schema
                val playlists = db.playlistDao().getActive().filter {
                    it.channel == Channel.CALL && it.schedule == Schedule.EVERY_CALL
                }

                for (playlist in playlists) {
                    val result = resolver.resolveForPlaylist(playlist) ?: continue
                    val (file, track) = result
                    val deezerTrack = track.toDeezerTrack()

                    if (playlist.contactUri != null) {
                        // Per-contact ringtone
                        val uri = ringtoneManager.addToMediaStorePublic(deezerTrack, file)
                        if (uri != null) {
                            ContactsRepository(context)
                                .setContactRingtone(playlist.contactUri, uri)
                        }
                    } else {
                        // Globale ringtone
                        ringtoneManager.setAsRingtone(deezerTrack, file)
                    }

                    resolver.updateLastPlayed(playlist, track.deezerTrackId)
                    Log.i(TAG, "EVERY_CALL: ringtone gewisseld naar ${track.artist} - ${track.title}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error bij EVERY_CALL ringtone wissel", e)
            }
        }
    }
}
