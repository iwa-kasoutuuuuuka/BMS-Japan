package jp.bmsjapan.app.protocol

import jp.bmsjapan.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class HistoryTest {

    /** 34 バイトの記録を組み立てる */
    private fun record(number: Int, total: Int, eventId: Int, occurred: Boolean, month: Int = 10): ByteArray {
        val b = ByteArray(34)
        fun u16(i: Int, v: Int) { b[i] = (v shr 8).toByte(); b[i + 1] = v.toByte() }
        u16(0, number); u16(2, total); u16(4, 26)
        b[6] = 26; b[7] = month.toByte(); b[8] = 24; b[9] = 13; b[10] = 5; b[11] = 9  // 2026-10-24 13:05:09
        b[12] = eventId.toByte()
        b[13] = if (occurred) 1 else 0
        u16(14, 532)               // 53.2 V
        u16(16, 30000 - 125)       // −12.5 A
        u16(18, 456)               // 45.6 %
        b[20] = 0x02               // 放電MOSのみ ON
        u16(21, 3456); b[23] = 7   // 最高 3.456 V（7番）
        u16(24, 3301); b[26] = 12  // 最低 3.301 V（12番）
        b[27] = 70; b[28] = 2      // 最高 30 ℃（2番）
        b[29] = 62; b[30] = 4      // 最低 22 ℃（4番）
        b[31] = 0x5A
        return b
    }

    private fun toRegisters(bytes: ByteArray): IntArray {
        val padded = bytes.copyOf(HistoryProtocol.PAGE_REGISTERS * 2)
        return IntArray(HistoryProtocol.PAGE_REGISTERS) {
            ((padded[it * 2].toInt() and 0xFF) shl 8) or (padded[it * 2 + 1].toInt() and 0xFF)
        }
    }

    @Test fun pageAddress() {
        assertEquals(256, HistoryProtocol.pageAddress(0))
        assertEquals(259, HistoryProtocol.pageAddress(3))
        // 1 ページ = 127 レジスタ = 254 バイト（応答のバイト数 0xFE に収まる）
        assertEquals(0xFE, HistoryProtocol.PAGE_REGISTERS * 2)
    }

    @Test fun parseFullPage() {
        var bytes = ByteArray(0)
        for (n in 1..7) bytes += record(n, 9, eventId = 8, occurred = n % 2 == 1)
        val page = HistoryProtocol.parsePage(toRegisters(bytes))
        assertEquals(7, page.records.size)
        assertFalse(page.reachedEnd)
        val r = page.records[0]
        assertEquals(1, r.number)
        assertEquals(9, r.totalCount)
        assertEquals(LocalDateTime.of(2026, 10, 24, 13, 5, 9), r.time)
        assertTrue(r.occurred)
        assertFalse(page.records[1].occurred)
        assertEquals(53.2, r.totalVoltage, 1e-9)
        assertEquals(-12.5, r.current, 1e-9)
        assertEquals(45.6, r.soc, 1e-9)
        assertFalse(r.chargeMos)
        assertTrue(r.dischargeMos)
        assertEquals(3.456, r.maxCellVoltage, 1e-9); assertEquals(7, r.maxCellIndex)
        assertEquals(3.301, r.minCellVoltage, 1e-9); assertEquals(12, r.minCellIndex)
        assertEquals(30, r.maxTemperature); assertEquals(2, r.maxTemperatureIndex)
        assertEquals(22, r.minTemperature); assertEquals(4, r.minTemperatureIndex)
        assertEquals(0x5A, r.faultCode)
        assertEquals(HistoryEvent(R.string.ev_cell_ov1), r.event)
    }

    @Test fun stopsAtEmptyRecord() {
        val bytes = record(1, 2, 0, true) + record(2, 2, 1, true)   // 残りは 0 埋め
        val page = HistoryProtocol.parsePage(toRegisters(bytes))
        assertEquals(2, page.records.size)
        assertTrue(page.reachedEnd)
    }

    @Test fun invalidDateBecomesNull() {
        val page = HistoryProtocol.parsePage(toRegisters(record(1, 1, 0, true, month = 13)))
        assertNull(page.records[0].time)
    }

    @Test fun eventNames() {
        assertEquals(HistoryEvent(R.string.ev_reserved_signal, 3), HistoryEvent.of(51))
        assertEquals(HistoryEvent(R.string.ev_reserved, 140), HistoryEvent.of(140))
        assertEquals(HistoryEvent(R.string.ev_unknown, 200), HistoryEvent.of(200))
        assertEquals(HistoryEvent(R.string.ev_power_on), HistoryEvent.of(255))
    }
}
