package nl.icthorse.randomringtone.data

import java.io.InputStream
import java.io.OutputStream

/**
 * Voortgang + live ETA voor backup/restore (v2.0.1).
 *
 * Snelheid = bytes over een glijdend venster (standaard 8 s) tot *nu*, niet het gemiddelde sinds de start. Valt de
 * overdracht stil, dan daalt de snelheid vanzelf en loopt de ETA op (i.p.v. te bevriezen); na een heel venster zonder
 * bytes is de snelheid 0 en de ETA onbekend (-1). [add] meldt hooguit elke [minEmitMs] dat er een update uit mag.
 */
class TransferMeter(
    totalBytes: Long,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val windowMs: Long = 8_000L,
    private val minEmitMs: Long = 250L
) {
    var totalBytes: Long = totalBytes.coerceAtLeast(0)
        private set
    var doneBytes: Long = 0
        private set
    val startMs: Long = clock()

    private val samples = ArrayDeque<LongArray>().apply { addLast(longArrayOf(startMs, 0)) }
    private var lastEmitMs = Long.MIN_VALUE / 2

    /** Totaal alsnog verhogen (bv. een bestand dat groter bleek). */
    fun growTotal(extra: Long) { if (extra > 0) totalBytes += extra }

    /** Registreer [n] verwerkte bytes. → true als er (gethrottled) een voortgangsupdate uit mag. */
    fun add(n: Long): Boolean {
        if (n <= 0) return false
        doneBytes += n
        if (doneBytes > totalBytes) totalBytes = doneBytes
        val now = clock()
        if (now - samples.last()[0] >= 100) samples.addLast(longArrayOf(now, doneBytes)) else samples.last()[1] = doneBytes
        // Houd precies één sample van vóór het venster als basis; oudere mogen weg.
        while (samples.size > 2 && samples[1][0] <= now - windowMs) samples.removeFirst()
        if (now - lastEmitMs >= minEmitMs) { lastEmitMs = now; return true }
        return false
    }

    fun bytesPerSecond(): Long {
        val now = clock()
        val from = maxOf(startMs, now - windowMs)
        val span = now - from
        if (span < 500) return 0  // eerste halve seconde: te weinig meting
        return (doneBytes - bytesAt(from)).coerceAtLeast(0) * 1000 / span
    }

    /** Lineair geïnterpoleerde voortgang op tijdstip [t] (voor een eerlijke venstergrens). */
    private fun bytesAt(t: Long): Long {
        var prev = samples.first()
        for (s in samples) {
            if (s[0] >= t) {
                if (s[0] == prev[0]) return s[1]
                val f = (t - prev[0]).toDouble() / (s[0] - prev[0])
                return (prev[1] + f * (s[1] - prev[1])).toLong()
            }
            prev = s
        }
        return prev[1]  // geen sample na t: sindsdien stil
    }

    fun etaSeconds(): Int {
        val remaining = totalBytes - doneBytes
        if (remaining <= 0) return 0
        val bps = bytesPerSecond()
        return if (bps > 0) ((remaining + bps - 1) / bps).toInt() else -1
    }

    fun fraction(): Float = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    fun elapsedSeconds(): Int = ((clock() - startMs) / 1000).toInt()

    fun progress(phase: String, current: Int, total: Int): BackupProgress = BackupProgress(
        phase = phase, current = current, total = total, percentage = fraction(), bytesCopied = doneBytes,
        totalBytes = totalBytes, bytesPerSecond = bytesPerSecond(), etaSeconds = etaSeconds(),
        elapsedSeconds = elapsedSeconds()
    )
}

/** copyTo met teller per blok (64 KB), zodat de voortgang ook binnen een groot bestand beweegt. */
fun InputStream.copyCounting(out: OutputStream, onBytes: (Long) -> Unit): Long {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        out.write(buf, 0, n)
        total += n
        onBytes(n.toLong())
    }
    return total
}
