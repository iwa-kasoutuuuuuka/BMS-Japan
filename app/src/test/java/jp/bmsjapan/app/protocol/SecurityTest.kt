package jp.bmsjapan.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 脆弱性診断で見つかった点の再発防止テスト */
class SecurityTest {

    private fun settings(): BmsSettings {
        val r = IntArray(BmsSettings.MAIN_COUNT) { 100 }
        return BmsSettings.EMPTY.merge(BmsSettings.MAIN_START, r)
    }

    @Test fun nanAndInfinityAreRejected() {
        val s = settings()
        assertEquals(ValidationResult.NotANumber, Setting.CHARGE_UT.validate("NaN", s))
        assertEquals(ValidationResult.NotANumber, Setting.CELL_OV.validate("Infinity", s))
        assertEquals(ValidationResult.NotANumber, Setting.CELL_OV.validate("-Infinity", s))
        assertNull(ActualCapacity.parse("NaN"))
        assertNull(ActualCapacity.parse("Infinity"))
    }

    @Test fun presetRejectsBadCellCounts() {
        val s = settings()
        assertNull(buildBatteryPreset(BatteryType.LFP, 0, s))
        assertNull(buildBatteryPreset(BatteryType.LFP, -1, s))
        assertNull(buildBatteryPreset(BatteryType.LFP, 49, s))
        assertNull(buildBatteryPreset(BatteryType.LFP, 65535, s))
        assertNull(buildBatteryPreset(BatteryType.NCM, 21, s))   // 元アプリと同じ: 20 セル超は三元系不可
        assertTrue(buildBatteryPreset(BatteryType.LFP, 16, s) != null)
    }

    @Test fun outOfRangeValuesAreNotTruncated() {
        assertThrows(IllegalArgumentException::class.java) { Modbus.writeRegister(165, 0x10000) }
        assertThrows(IllegalArgumentException::class.java) { Modbus.writeRegisters(137, intArrayOf(-1)) }
    }

    @Test fun passwordFramesAreRedactedInLogs() {
        val write = Modbus.writeRegisters(BmsPassword.START, BmsPassword.encode("654321"))
        val shown = Modbus.redactForLog(write)
        assertFalse(shown.contains("363534333231"))   // "654321" の ASCII
        assertTrue(shown.startsWith("D21000C90003"))

        val readReply = Modbus.withCrc("D20306313233343536".hexToBytes())
        assertFalse(Modbus.redactForLog(readReply).contains("313233343536"))

        val single = Modbus.writeRegister(202, 0x3334)
        assertFalse(Modbus.redactForLog(single).contains("3334"))

        // パスワードと関係ないフレームはそのまま
        assertEquals(BmsCommands.READ_LIVE.toHex(), Modbus.redactForLog(BmsCommands.READ_LIVE))
    }
}
