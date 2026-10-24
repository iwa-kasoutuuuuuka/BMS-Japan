package jp.bmsjapan.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {

    // 期待値は docs/ble_protocol.md の送信例（Python で別途計算したもの）と同じ
    @Test fun readLiveFrame() = assertEquals("D2030000003ED7B9", BmsCommands.READ_LIVE.toHex())
    @Test fun readSettingsFrame() = assertEquals("D20300800029965F", Modbus.readRegisters(128, 41).toHex())
    @Test fun readExtraCellsFrame() = assertEquals("D20300470010E7B0", BmsCommands.READ_EXTRA_CELLS.toHex())
    @Test fun writeSingleFrame() = assertEquals("D20600A500008A4A", Modbus.writeRegister(0xA5, 0).toHex())
    @Test fun syncTimeFrame() =
        assertEquals("D21000D400031A0A18161E00E341", BmsCommands.syncTime(2026, 10, 24, 22, 30, 0).toHex())

    @Test fun crcValidation() {
        assertTrue(Modbus.isCrcValid("D2030000003ED7B9".hexToBytes()))
        assertFalse(Modbus.isCrcValid("D2030000003ED7B8".hexToBytes()))
    }

    /** 16 セル LFP のサンプル応答を組み立てる */
    private fun sampleLiveRegisters(): IntArray {
        val r = IntArray(62)
        for (i in 0 until 16) r[i] = 3300 + i          // 3.300〜3.315 V
        r[32] = 65; r[33] = 66                          // 25℃, 26℃
        r[40] = 528                                     // 52.8 V
        r[41] = 30000 + 125                             // +12.5 A
        r[42] = 853                                     // 85.3 %
        r[43] = 3315; r[44] = 3300
        r[45] = 66; r[46] = 65
        r[47] = 1                                       // 充電中
        r[48] = 2560                                    // 256.0 Ah
        r[49] = 16; r[50] = 2; r[51] = 42
        r[52] = 1; r[53] = 1; r[54] = 0
        r[55] = 3307; r[56] = 15; r[57] = 660
        r[58] = 0b1                                     // セル過電圧 L1
        r[60] = 1 shl 9                                 // セル電圧線の外れ
        return r
    }

    private fun encodeReadResponse(regs: IntArray): ByteArray {
        var body = byteArrayOf(0xD2.toByte(), 0x03, (regs.size * 2).toByte())
        for (v in regs) body += byteArrayOf((v shr 8).toByte(), v.toByte())
        return Modbus.withCrc(body)
    }

    @Test fun parseLiveData() {
        val d = LiveData.parse(sampleLiveRegisters())
        assertEquals(16, d.cellVoltages.size)
        assertEquals(3.300, d.cellVoltages[0], 1e-9)
        assertEquals(listOf(25, 26), d.temperatures)
        assertEquals(52.8, d.totalVoltage, 1e-9)
        assertEquals(12.5, d.current, 1e-9)
        assertEquals(85.3, d.soc, 1e-9)
        assertEquals(ChargeState.CHARGING, d.state)
        assertEquals(256.0, d.remainingCapacity, 1e-9)
        assertEquals(42, d.cycleCount)
        assertTrue(d.chargeMos)
        assertFalse(d.dischargeMos)
        assertEquals(0.015, d.cellVoltageDelta, 1e-9)
        assertEquals(0.66, d.powerKw, 1e-9)
        assertEquals(listOf(Alarm.CELL_OV1, Alarm.CELL_WIRE), d.alarms)
    }

    @Test fun negativeCurrentWhenDischarging() {
        val r = sampleLiveRegisters().also { it[41] = 30000 - 304 }
        assertEquals(-30.4, LiveData.parse(r).current, 1e-9)
    }

    @Test fun assemblerHandlesFragmentsAndGarbage() {
        val frame = encodeReadResponse(sampleLiveRegisters())
        val stream = byteArrayOf(0x00, 0x7F) + frame + Modbus.writeRegister(0xA5, 0)
        val asm = FrameAssembler()
        val got = mutableListOf<BmsFrame>()
        // 20 バイトずつ（BLE の既定 MTU 相当）に分けて流し込む
        stream.toList().chunked(20).forEach { got += asm.feed(it.toByteArray()) }
        assertEquals(2, got.size)
        val read = got[0] as BmsFrame.ReadResponse
        assertEquals(62, read.registers.size)
        assertEquals(528, read.registers[40])
        assertEquals(BmsFrame.WriteSingleAck(0xA5, 0), got[1])
    }

    @Test fun assemblerSkipsBadCrc() {
        val bad = encodeReadResponse(IntArray(2) { 1 }).also { it[it.size - 1] = (it.last() + 1).toByte() }
        val good = encodeReadResponse(IntArray(2) { 7 })
        val got = FrameAssembler().feed(bad + good)
        assertEquals(listOf(BmsFrame.ReadResponse(intArrayOf(7, 7))), got)
    }

    @Test fun extraCellsAppended() {
        val r = sampleLiveRegisters().also { it[49] = 34; for (i in 16 until 32) it[i] = 3310 }
        val d = LiveData.parse(r).withExtraCells(IntArray(16) { 3290 })
        assertEquals(34, d.cellVoltages.size)
        assertEquals(3.290, d.cellVoltages[33], 1e-9)
    }
}
