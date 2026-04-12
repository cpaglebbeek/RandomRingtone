package nl.icthorse.randomringtone.data

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Device-hash-based license manager.
 * Controleert licentie bij icthorse.nl/Apps/Android/RandomRing/lics/{deviceHash}.json
 * Met 72-uur grace period voor offline gebruik.
 */
class LicenseManager(private val context: Context) {

    companion object {
        private const val LICENSE_BASE_URL = "https://icthorse.nl/Apps/Android/RandomRing/lics"
        private const val GRACE_PERIOD_MS = 72 * 60 * 60 * 1000L  // 72 uur
        private const val PREFS_NAME = "randomringtone_license"
        private const val INFINITE_EXPIRY = 4000000000000L
    }

    data class LicenseStatus(
        val active: Boolean = false,
        val expiry: Long = 0,
        val name: String = "",
        val company: String = "",
        val customerId: String = "",
        val message: String = "",
        val isGracePeriod: Boolean = false,
        val graceHoursLeft: Int = 0,
        val deviceHash: String = "",
        val lastCheck: Long = 0,
        val isInfinite: Boolean = false,
        val error: String? = null
    )

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    val deviceHash: String
        get() = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (_: Exception) { "unknown" }

    /**
     * Controleer licentie bij de server. Cachet resultaat lokaal.
     * Bij netwerkfout: grace period van 72 uur.
     */
    suspend fun checkLicense(): LicenseStatus = withContext(Dispatchers.IO) {
        val hash = deviceHash
        RemoteLogger.i("LicenseManager", "checkLicense", mapOf("deviceHash" to hash))

        try {
            val request = Request.Builder()
                .url("$LICENSE_BASE_URL/$hash.json")
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val body = response.body?.string() ?: "{}"
                val json = JSONObject(body)

                val active = json.optBoolean("active", false)
                val expiry = json.optLong("expiry", 0)
                val now = System.currentTimeMillis()
                val isInfinite = expiry > INFINITE_EXPIRY
                val isExpired = !isInfinite && expiry < now

                val status = LicenseStatus(
                    active = active && !isExpired,
                    expiry = expiry,
                    name = json.optString("name", ""),
                    company = json.optString("company", ""),
                    customerId = json.optString("customerId", ""),
                    message = json.optString("message", ""),
                    deviceHash = hash,
                    lastCheck = now,
                    isInfinite = isInfinite
                )

                // Cache in prefs + update RemoteLogger owner
                cacheStatus(status)
                if (status.name.isNotBlank()) RemoteLogger.updateOwner(status.name)
                RemoteLogger.output("LicenseManager", "License check OK", mapOf(
                    "active" to status.active.toString(),
                    "name" to status.name,
                    "infinite" to isInfinite.toString()
                ))
                return@withContext status

            } else if (response.code == 404) {
                // Geen licentie gevonden voor dit device
                RemoteLogger.w("LicenseManager", "No license found (404)", mapOf("hash" to hash))
                val status = LicenseStatus(
                    active = false,
                    deviceHash = hash,
                    lastCheck = System.currentTimeMillis(),
                    message = "Geen licentie gevonden",
                    error = "Device niet gelicenseerd"
                )
                cacheStatus(status)
                return@withContext status
            } else {
                // Server error → grace period
                RemoteLogger.w("LicenseManager", "Server error ${response.code}", mapOf("hash" to hash))
                return@withContext graceOrCached(hash, "Server error: ${response.code}")
            }
        } catch (e: Exception) {
            // Netwerk error → grace period
            RemoteLogger.w("LicenseManager", "Network error", mapOf("error" to (e.message ?: "unknown")))
            return@withContext graceOrCached(hash, "Netwerk: ${e.message}")
        }
    }

    /**
     * Haal gecachte licentie status op (zonder server check).
     */
    fun getCachedStatus(): LicenseStatus {
        val hash = deviceHash
        val active = prefs.getBoolean("active", false)
        val lastCheck = prefs.getLong("lastCheck", 0)

        if (lastCheck == 0L) {
            return LicenseStatus(deviceHash = hash, message = "Nog niet gecontroleerd")
        }

        // Check grace period als niet actief
        if (!active) {
            return graceFromCache(hash)
        }

        val expiry = prefs.getLong("expiry", 0)
        val isInfinite = expiry > INFINITE_EXPIRY
        val now = System.currentTimeMillis()
        val isExpired = !isInfinite && expiry < now

        return LicenseStatus(
            active = !isExpired,
            expiry = expiry,
            name = prefs.getString("name", "") ?: "",
            company = prefs.getString("company", "") ?: "",
            customerId = prefs.getString("customerId", "") ?: "",
            message = prefs.getString("message", "") ?: "",
            deviceHash = hash,
            lastCheck = lastCheck,
            isInfinite = isInfinite
        )
    }

    private fun graceOrCached(hash: String, errorMsg: String): LicenseStatus {
        val lastCheck = prefs.getLong("lastCheck", 0)
        val wasActive = prefs.getBoolean("active", false)

        if (wasActive && lastCheck > 0) {
            // Was actief → grace period
            val elapsed = System.currentTimeMillis() - lastCheck
            val remaining = GRACE_PERIOD_MS - elapsed
            if (remaining > 0) {
                val hoursLeft = (remaining / (60 * 60 * 1000)).toInt()
                return LicenseStatus(
                    active = true,
                    isGracePeriod = true,
                    graceHoursLeft = hoursLeft,
                    expiry = prefs.getLong("expiry", 0),
                    name = prefs.getString("name", "") ?: "",
                    company = prefs.getString("company", "") ?: "",
                    customerId = prefs.getString("customerId", "") ?: "",
                    message = "Grace period ($hoursLeft uur resterend)",
                    deviceHash = hash,
                    lastCheck = lastCheck,
                    isInfinite = prefs.getLong("expiry", 0) > INFINITE_EXPIRY
                )
            }
        }

        // Grace verlopen of nooit actief geweest
        return LicenseStatus(
            active = false,
            deviceHash = hash,
            lastCheck = lastCheck,
            message = if (wasActive) "Grace period verlopen" else "Niet gelicenseerd",
            error = errorMsg
        )
    }

    private fun graceFromCache(hash: String): LicenseStatus {
        val lastCheck = prefs.getLong("lastCheck", 0)
        return LicenseStatus(
            active = false,
            deviceHash = hash,
            lastCheck = lastCheck,
            message = prefs.getString("message", "Niet gelicenseerd") ?: ""
        )
    }

    private fun cacheStatus(status: LicenseStatus) {
        prefs.edit()
            .putBoolean("active", status.active)
            .putLong("expiry", status.expiry)
            .putString("name", status.name)
            .putString("company", status.company)
            .putString("customerId", status.customerId)
            .putString("message", status.message)
            .putLong("lastCheck", status.lastCheck)
            .apply()
    }
}
