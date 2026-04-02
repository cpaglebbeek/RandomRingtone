package nl.icthorse.randomringtone.data

import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Remote debug logger — stuurt alle app-events naar horsecloud55:3800
 * voor live monitoring via web-based tail -f.
 *
 * Gebruik:
 *   RemoteLogger.init(context)
 *   RemoteLogger.i("TAG", "message", mapOf("key" to "value"))
 */
object RemoteLogger {

    private const val TAG = "RemoteLogger"
    private const val SERVER_URL = "http://157.180.29.184:3800/log"
    private const val HEARTBEAT_INTERVAL_MS = 30_000L
    private const val FLUSH_INTERVAL_MS = 2_000L
    private const val MAX_QUEUE_SIZE = 500

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val queue = ConcurrentLinkedQueue<LogEntry>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private var deviceId: String = "unknown"
    private var initialized = false

    @Serializable
    data class LogEntry(
        val timestamp: String,
        val tag: String,
        val level: String,
        val message: String,
        val data: Map<String, String> = emptyMap(),
        val deviceId: String = ""
    )

    /**
     * Initialiseer de logger met Android context.
     * Start heartbeat en flush loops.
     */
    fun init(context: android.content.Context) {
        if (initialized) return
        initialized = true

        deviceId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        } catch (_: Exception) { "unknown" }

        // Start flush loop
        scope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flush()
            }
        }

        // Start heartbeat loop
        scope.launch {
            while (isActive) {
                heartbeat(context)
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }

        i("INIT", "RemoteLogger gestart", mapOf(
            "deviceId" to deviceId,
            "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})",
            "app" to try { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
        ))
    }

    // --- Log methods ---

    fun d(tag: String, message: String, data: Map<String, String> = emptyMap()) =
        enqueue("DEBUG", tag, message, data)

    fun i(tag: String, message: String, data: Map<String, String> = emptyMap()) =
        enqueue("INFO", tag, message, data)

    fun w(tag: String, message: String, data: Map<String, String> = emptyMap()) =
        enqueue("WARN", tag, message, data)

    fun e(tag: String, message: String, data: Map<String, String> = emptyMap()) =
        enqueue("ERROR", tag, message, data)

    // --- Convenience methods for common patterns ---

    fun input(tag: String, description: String, data: Map<String, String> = emptyMap()) =
        enqueue("INFO", tag, "INPUT: $description", data)

    fun output(tag: String, description: String, data: Map<String, String> = emptyMap()) =
        enqueue("INFO", tag, "OUTPUT: $description", data)

    fun trigger(tag: String, description: String, data: Map<String, String> = emptyMap()) =
        enqueue("INFO", tag, "TRIGGER: $description", data)

    fun result(tag: String, description: String, success: Boolean, data: Map<String, String> = emptyMap()) {
        val level = if (success) "INFO" else "ERROR"
        enqueue(level, tag, "RESULT: $description", data + mapOf("success" to success.toString()))
    }

    // --- Call Summary ---

    fun callSummary(
        context: android.content.Context,
        callerName: String?,
        callerNumber: String?,
        swaps: List<Map<String, String>>
    ) {
        val appVersion = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) { "?" }

        // Bulletproof JSON via org.json (geen kotlinx.serialization voor generics)
        val swapsArray = org.json.JSONArray()
        for (swap in swaps) {
            val obj = org.json.JSONObject()
            for ((key, value) in swap) {
                obj.put(key, value)
            }
            swapsArray.put(obj)
        }

        val caller = when {
            callerName != null && callerNumber != null -> "$callerName ($callerNumber)"
            callerName != null -> callerName
            callerNumber != null -> callerNumber
            else -> "Onbekend"
        }

        enqueue("CALL_SUMMARY", "CALL_SUMMARY", "Oproep verwerkt", mapOf(
            "caller" to caller,
            "callerName" to (callerName ?: "Onbekend"),
            "callerNumber" to (callerNumber ?: "Onbekend"),
            "appVersion" to appVersion,
            "deviceId" to deviceId,
            "owner" to getDeviceOwnerName(context),
            "swapCount" to swaps.size.toString(),
            "swaps" to swapsArray.toString()
        ))
    }

    private fun getDeviceOwnerName(context: android.content.Context): String {
        // Primair: Google account naam op het device
        try {
            val am = android.accounts.AccountManager.get(context)
            val accounts = am.getAccountsByType("com.google")
            if (accounts.isNotEmpty()) {
                val name = accounts[0].name
                // Email → voornaam: "christian@gmail.com" → "christian"
                val local = name.substringBefore("@")
                return local.replaceFirstChar { it.uppercase() }
            }
        } catch (_: Exception) {}

        // Fallback: device owner profiel naam
        try {
            val cursor = context.contentResolver.query(
                android.provider.ContactsContract.Profile.CONTENT_URI,
                arrayOf(android.provider.ContactsContract.Profile.DISPLAY_NAME),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val name = it.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        } catch (_: Exception) {}

        return "Onbekend"
    }

    // --- Internals ---

    private fun enqueue(level: String, tag: String, message: String, data: Map<String, String>) {
        // Also log locally
        when (level) {
            "DEBUG" -> Log.d(tag, message)
            "INFO" -> Log.i(tag, message)
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
        }

        if (queue.size >= MAX_QUEUE_SIZE) {
            queue.poll() // drop oldest
        }

        queue.add(LogEntry(
            timestamp = dateFormat.format(Date()),
            tag = tag,
            level = level,
            message = message,
            data = data,
            deviceId = deviceId
        ))
    }

    private fun heartbeat(context: android.content.Context) {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        val totalMem = runtime.totalMemory() / 1024 / 1024

        enqueue("HEARTBEAT", "HEARTBEAT", "alive", mapOf(
            "memUsed" to "${usedMem}MB",
            "memTotal" to "${totalMem}MB",
            "threads" to Thread.activeCount().toString(),
            "queueSize" to queue.size.toString()
        ))
    }

    private fun flush() {
        if (queue.isEmpty()) return

        val batch = mutableListOf<LogEntry>()
        while (batch.size < 50) {
            val entry = queue.poll() ?: break
            batch.add(entry)
        }
        if (batch.isEmpty()) return

        try {
            val body = json.encodeToString(batch)
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(SERVER_URL)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Flush failed: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Flush failed: ${e.message}")
            // Re-queue failed entries (max effort)
            batch.forEach { queue.add(it) }
        }
    }
}
