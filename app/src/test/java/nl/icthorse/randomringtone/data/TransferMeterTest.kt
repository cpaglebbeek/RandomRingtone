package nl.icthorse.randomringtone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TransferMeterTest {
    private var now = 0L
    private fun meter(total: Long) = TransferMeter(total, clock = { now })

    /** 1 MB/s gedurende [seconds], in stapjes van 100 ms. */
    private fun TransferMeter.run(seconds: Int, bytesPerSecond: Long = 1_000_000) {
        repeat(seconds * 10) { now += 100; add(bytesPerSecond / 10) }
    }

    @Test fun `constante snelheid - juiste snelheid en eta`() {
        val m = meter(100_000_000)
        m.run(10)
        assertEquals(1_000_000L, m.bytesPerSecond())
        assertEquals(90, m.etaSeconds())
        assertEquals(0.1f, m.fraction(), 0.0001f)
        assertEquals(10, m.elapsedSeconds())
    }

    @Test fun `snelheid volgt het venster, niet het gemiddelde sinds de start`() {
        val m = meter(1_000_000_000)
        m.run(20, 4_000_000)          // snel begin
        m.run(10, 1_000_000)          // daarna 1 MB/s, langer dan het venster van 8 s
        assertEquals(1_000_000L, m.bytesPerSecond())
    }

    @Test fun `stilval - snelheid zakt, eta loopt op, daarna onbekend`() {
        val m = meter(100_000_000)
        m.run(10)
        val etaVoor = m.etaSeconds()
        now += 4_000                  // 4 s niets
        assertEquals(500_000L, m.bytesPerSecond())
        assertTrue(m.etaSeconds() > etaVoor)
        now += 5_000                  // langer dan het venster stil
        assertEquals(0L, m.bytesPerSecond())
        assertEquals(-1, m.etaSeconds())
    }

    @Test fun `throttling van updates en eerste halve seconde onbekend`() {
        val m = meter(10_000)
        assertTrue(m.add(1))          // eerste mag altijd
        now += 100
        assertEquals(false, m.add(1))
        now += 200
        assertTrue(m.add(1))
        assertEquals(0L, m.bytesPerSecond())   // < 500 ms gemeten
        assertEquals(-1, m.etaSeconds())
    }

    @Test fun `meer dan het totaal - totaal groeit mee, eta 0 aan het eind`() {
        val m = meter(1000)
        now += 1000; m.add(1500)
        assertEquals(1500L, m.totalBytes)
        assertEquals(1f, m.fraction(), 0f)
        assertEquals(0, m.etaSeconds())
    }

    @Test fun `copyCounting telt alle bytes in blokken`() {
        val data = ByteArray(200_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        var counted = 0L; var calls = 0
        val n = ByteArrayInputStream(data).copyCounting(out) { counted += it; calls++ }
        assertEquals(200_000L, n); assertEquals(200_000L, counted)
        assertTrue(calls >= 4)
        assertTrue(out.toByteArray().contentEquals(data))
    }
}
