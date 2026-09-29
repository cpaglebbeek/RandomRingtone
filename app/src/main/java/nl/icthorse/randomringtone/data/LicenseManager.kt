package nl.icthorse.randomringtone.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Device-hash-based license manager.
 * Controleert licentie bij icthorse.nl/Apps/Android/RandomRing/lics/{deviceHash}.json
 * Met 72-uur grace period voor offline gebruik.
 *
 * v2.0.0: activatie-aanvraag via de backend (mail met magic link naar beheer, goedkeuring via HorseAPK) en een
 * per-toestel-token (backup/restore, Spotify-bron). Token staat in een eigen prefs-bestand dat buiten Android-backups
 * blijft (res/xml/backup_rules.xml, data_extraction_rules.xml).
 */
class LicenseManager(private val context: Context) {

    companion object {
        private const val LICENSE_BASE_URL = "https://icthorse.nl/Apps/Android/RandomRing/lics"
        private const val GRACE_PERIOD_MS = 72 * 60 * 60 * 1000L  // 72 uur
        private const val PREFS_NAME = "randomringtone_license"
        private const val INFINITE_EXPIRY = 4000000000000L
        const val BACKEND_BASE = "https://horsecloud55.ddns.net/rrlog"
        private const val AUTH_PREFS = "rr_device_auth"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /** Openstaande activatie-aanvraag (bewaard tot toegekend/afgewezen/verlopen). */
    data class PendingActivation(
        val requestId: String,
        val pollToken: String,
        val sentAt: Long,
        val name: String,
        val email: String
    )

    enum class TokenState { OK, NEEDS_ACTIVATION, NO_LICENSE, ERROR }

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

    private val authPrefs: SharedPreferences =
        context.getSharedPreferences(AUTH_PREFS, Context.MODE_PRIVATE)

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

    // ── Toestel-token ───────────────────────────────────────────────────

    val deviceToken: String?
        get() = authPrefs.getString("deviceToken", null)?.takeIf { it.isNotBlank() }

    private fun storeToken(token: String) {
        authPrefs.edit().putString("deviceToken", token).putLong("tokenStoredAt", System.currentTimeMillis()).apply()
    }

    /**
     * Zorgt dat een gelicenseerd toestel een token heeft. Toestellen die al vóór v2.0.0 een licentie hadden claimen
     * het token één keer; bestaat er server-side al een token (bv. na herinstallatie) dan is een nieuwe
     * activatie-aanvraag nodig.
     */
    suspend fun ensureDeviceToken(): TokenState = withContext(Dispatchers.IO) {
        if (deviceToken != null) return@withContext TokenState.OK
        try {
            val body = JSONObject().put("deviceHash", deviceHash).toString().toRequestBody(JSON_TYPE)
            client.newCall(Request.Builder().url("$BACKEND_BASE/license/claim").post(body).build()).execute().use { r ->
                val text = r.body?.string() ?: "{}"
                when (r.code) {
                    200 -> {
                        val token = JSONObject(text).optString("deviceToken", "")
                        if (token.isBlank()) return@withContext TokenState.ERROR
                        storeToken(token)
                        RemoteLogger.output("LicenseManager", "Toestel-token geclaimd", emptyMap())
                        TokenState.OK
                    }
                    409 -> TokenState.NEEDS_ACTIVATION
                    403 -> TokenState.NO_LICENSE
                    else -> TokenState.ERROR
                }
            }
        } catch (e: Exception) {
            RemoteLogger.w("LicenseManager", "Token claimen mislukt", mapOf("error" to (e.message ?: "?")))
            TokenState.ERROR
        }
    }

    // ── Activatie-aanvraag ───────────────────────────────────────────────

    fun pendingActivation(): PendingActivation? {
        val id = authPrefs.getString("reqId", null) ?: return null
        val poll = authPrefs.getString("reqPoll", null) ?: return null
        return PendingActivation(id, poll, authPrefs.getLong("reqSentAt", 0), authPrefs.getString("reqName", "") ?: "",
            authPrefs.getString("reqEmail", "") ?: "")
    }

    fun clearPendingActivation() {
        authPrefs.edit().remove("reqId").remove("reqPoll").remove("reqSentAt").remove("reqName").remove("reqEmail").apply()
    }

    /** Alles wat met een aanvraag meegaat — getoond aan de gebruiker vóór versturen. */
    fun collectDeviceInfo(accountPicked: Boolean): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        out["manufacturer"] = Build.MANUFACTURER ?: ""
        out["brand"] = Build.BRAND ?: ""
        out["model"] = Build.MODEL ?: ""
        out["device"] = Build.DEVICE ?: ""
        out["product"] = Build.PRODUCT ?: ""
        out["androidRelease"] = Build.VERSION.RELEASE ?: ""
        out["sdkInt"] = Build.VERSION.SDK_INT.toString()
        out["appVersion"] = nl.icthorse.randomringtone.BuildConfig.VERSION_NAME
        out["appVersionCode"] = nl.icthorse.randomringtone.BuildConfig.VERSION_CODE.toString()
        out["locale"] = java.util.Locale.getDefault().toLanguageTag()
        out["timezone"] = java.util.TimeZone.getDefault().id
        val dm = context.resources.displayMetrics
        out["screen"] = "${dm.widthPixels}x${dm.heightPixels}"
        out["density"] = "${dm.densityDpi} dpi"
        out["installer"] = try {
            val pm = context.packageManager
            (if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(context.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(context.packageName)) ?: "sideload"
        } catch (_: Exception) { "onbekend" }
        out["firstInstall"] = try {
            val t = context.packageManager.getPackageInfo(context.packageName, 0).firstInstallTime
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(t))
        } catch (_: Exception) { "" }
        out["accountPicked"] = if (accountPicked) "ja (Android-accountkiezer)" else "nee (zelf ingetypt)"
        return out
    }

    /** Verstuurt de aanvraag; de backend mailt de beheerder een magic link. → null bij succes, anders foutmelding. */
    suspend fun sendActivationRequest(name: String, email: String, note: String, accountPicked: Boolean): String? =
        withContext(Dispatchers.IO) {
            try {
                val device = JSONObject()
                collectDeviceInfo(accountPicked).forEach { (k, v) -> device.put(k, v) }
                val payload = JSONObject().put("deviceHash", deviceHash).put("name", name.trim())
                    .put("email", email.trim()).put("note", note.trim()).put("device", device)
                val req = Request.Builder().url("$BACKEND_BASE/license/request")
                    .post(payload.toString().toRequestBody(JSON_TYPE)).build()
                client.newCall(req).execute().use { r ->
                    val json = JSONObject(r.body?.string() ?: "{}")
                    if (r.code != 201) return@withContext json.optString("error", "Versturen mislukt (HTTP ${r.code})")
                    authPrefs.edit().putString("reqId", json.getString("requestId"))
                        .putString("reqPoll", json.getString("pollToken")).putLong("reqSentAt", System.currentTimeMillis())
                        .putString("reqName", name.trim()).putString("reqEmail", email.trim()).apply()
                    RemoteLogger.output("LicenseManager", "Activatie-aanvraag verstuurd", emptyMap())
                    null
                }
            } catch (e: Exception) {
                "Geen verbinding met de server (${e.message ?: "?"})"
            }
        }

    /** Status van de openstaande aanvraag: pending | granted | rejected | expired | superseded | unknown. */
    suspend fun pollActivation(): String = withContext(Dispatchers.IO) {
        val p = pendingActivation() ?: return@withContext "none"
        try {
            val body = JSONObject().put("pollToken", p.pollToken).toString().toRequestBody(JSON_TYPE)
            val req = Request.Builder().url("$BACKEND_BASE/license/request/${p.requestId}/status").post(body).build()
            client.newCall(req).execute().use { r ->
                if (r.code == 404) { clearPendingActivation(); return@withContext "expired" }
                if (!r.isSuccessful) return@withContext "unknown"
                val json = JSONObject(r.body?.string() ?: "{}")
                val token = json.optString("deviceToken", "")
                if (token.isNotBlank()) storeToken(token)
                val status = json.optString("status", "unknown")
                if (status == "granted" || status == "rejected" || status == "expired" || status == "superseded") {
                    if (status != "granted" || deviceToken != null) clearPendingActivation()
                }
                status
            }
        } catch (_: Exception) {
            "unknown"
        }
    }
}
