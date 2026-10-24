package jp.bmsjapan.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    /** LFP 16 セルの典型的な設定値 */
    private fun sampleSettings(): BmsSettings {
        val r = IntArray(BmsSettings.MAIN_COUNT)
        fun set(reg: Int, v: Int) { r[reg - 128] = v }
        set(128, 2800)                 // 280.0 Ah
        set(129, 3200)                 // 3.200 V
        set(131, 16); set(134, 4)
        set(137, 0)                    // LFP
        set(138, 3600)                 // 3600 秒
        set(139, 3550); set(140, 3650) // セル過電圧 解除 3.55 / 保護 3.65
        set(141, 2700); set(142, 2500)
        set(143, 568); set(144, 584)
        set(145, 432); set(146, 400)
        set(147, 29000); set(148, 28000) // 充電過電流 解除 100A / 保護 200A
        set(149, 31500); set(150, 32000) // 放電過電流 解除 150A / 保護 200A
        set(151, 90); set(152, 95)     // 充電高温 50 / 55 ℃
        set(153, 45); set(154, 40)     // 充電低温 5 / 0 ℃
        set(155, 95); set(156, 100)
        set(157, 25); set(158, 20)
        set(159, 200); set(160, 300)
        set(161, 10); set(162, 15)
        set(163, 3400); set(164, 10)
        set(165, 1); set(166, 0)
        set(167, 1000)
        set(168, 130)                  // MOS 90 ℃
        return BmsSettings.EMPTY.merge(128, r).merge(204, intArrayOf(0, 0, 16, 1, 20))
    }

    @Test fun decodeValues() {
        val s = sampleSettings()
        assertEquals(3.65, s[Setting.CELL_OV]!!, 1e-9)
        assertEquals(3.55, s.release(Setting.CELL_OV)!!, 1e-9)
        assertEquals(58.4, s[Setting.PACK_OV]!!, 1e-9)
        assertEquals(200.0, s[Setting.CHARGE_OC]!!, 1e-9)
        assertEquals(200.0, s[Setting.DISCHARGE_OC]!!, 1e-9)
        assertEquals(55.0, s[Setting.CHARGE_OT]!!, 1e-9)
        assertEquals(0.0, s[Setting.CHARGE_UT]!!, 1e-9)
        assertEquals(90.0, s[Setting.MOS_OT]!!, 1e-9)
        assertEquals(280.0, s[Setting.RATED_CAPACITY]!!, 1e-9)
        assertEquals(100.0, s[Setting.SOC]!!, 1e-9)
        assertEquals(2.0, s[Setting.BALANCE_CURRENT]!!, 1e-9)
        assertEquals(BatteryType.LFP, s.batteryType)
        assertEquals(true, s.switch(SwitchSetting.CHARGE_MOS))
        assertEquals(false, s.switch(SwitchSetting.DISCHARGE_MOS))
        assertEquals(true, s.switch(SwitchSetting.BALANCE))
    }

    @Test fun encodeRoundTrip() {
        for (setting in Setting.entries.filter { it.editable }) {
            val mid = (setting.min + setting.max) / 2
            val v = setting.format(mid).toDouble()
            assertEquals(setting.name, v, setting.encoding.decode(setting.encoding.encode(v)), 1e-6)
        }
    }

    @Test fun chargeCurrentEncodedBelowOffset() {
        // 元アプリ: (3000 − A) × 10
        assertEquals(28000, Encoding.CHARGE_CURRENT.encode(200.0))
        // 元アプリ: (A + 3000) × 10
        assertEquals(32000, Encoding.DISCHARGE_CURRENT.encode(200.0))
        assertEquals(135, Encoding.TEMPERATURE.encode(95.0))
    }

    @Test fun validation() {
        val s = sampleSettings()
        assertEquals(ValidationResult.Ok(3700), Setting.CELL_OV.validate("3.700", s))
        assertEquals(ValidationResult.Ok(3700), Setting.CELL_OV.validate("3,7", s))
        assertEquals(ValidationResult.NotANumber, Setting.CELL_OV.validate("abc", s))
        assertTrue(Setting.CELL_OV.validate("5.0", s) is ValidationResult.OutOfRange)
        // 保護値は解除値 3.55 V より高くないといけない
        assertEquals(ValidationResult.MustBeAbove("3.550"), Setting.CELL_OV.validate("3.50", s))
        // 低電圧の保護値は解除値 2.70 V より低くないといけない
        assertEquals(ValidationResult.MustBeBelow("2.700"), Setting.CELL_UV.validate("2.80", s))
        assertEquals(ValidationResult.Ok(-10 + 40), Setting.CHARGE_UT.validate("-10", s))
    }

    @Test fun batteryPresetNcm() {
        val s = sampleSettings()
        val v = buildBatteryPreset(BatteryType.NCM, 13, s)!!
        assertEquals(27, v.size)
        assertEquals(1, v[0])                    // 137 = 三元系
        assertEquals(3600, v[1])                 // 138 は今の値のまま
        assertEquals(4100, v[139 - 137]); assertEquals(4200, v[140 - 137])
        assertEquals(2850, v[141 - 137]); assertEquals(2820, v[142 - 137])
        assertEquals(533, v[143 - 137])          // 4.10 × 13 × 10
        assertEquals(546, v[144 - 137])
        assertEquals(28000, v[148 - 137])        // 電流は今の値のまま
        assertEquals(3000, v[163 - 137])
        val frame = Modbus.writeRegisters(137, v)
        assertEquals(2 + 4 + 27 * 2 + 2, frame.size)   // バイト数フィールドなし
    }

    @Test fun presetNeedsAllRegisters() {
        assertNull(buildBatteryPreset(BatteryType.LFP, 16, BmsSettings.EMPTY))
    }

    @Test fun batteryAvailability() {
        assertTrue(!BatteryType.NCM.availableFor(21))
        assertTrue(!BatteryType.LTO.availableFor(5))
        assertTrue(BatteryType.LFP.availableFor(48))
    }

    @Test fun passwordEncodeMatchesOriginalFrame() {
        val regs = BmsPassword.encode("654321")
        assertEquals("654321", BmsPassword.decode(regs))
        // 元アプリ: "D21000C90003" + ASCII の16進 + CRC
        val frame = Modbus.writeRegisters(BmsPassword.START, regs).toHex()
        assertTrue(frame.startsWith("D21000C90003363534333231"))
        assertEquals(6 + 6 + 2, frame.length / 2)
    }

    @Test fun passwordChangeChecks() {
        val cur = "123456"
        assertEquals(PasswordChangeCheck.OK, BmsPassword.validateChange(cur, "123456", "987654", "987654"))
        assertEquals(PasswordChangeCheck.EMPTY, BmsPassword.validateChange(cur, "", "987654", "987654"))
        assertEquals(PasswordChangeCheck.WRONG_OLD, BmsPassword.validateChange(cur, "111111", "987654", "987654"))
        assertEquals(PasswordChangeCheck.WRONG_OLD, BmsPassword.validateChange(null, "123456", "987654", "987654"))
        assertEquals(PasswordChangeCheck.BAD_FORMAT, BmsPassword.validateChange(cur, "123456", "9876", "9876"))
        assertEquals(PasswordChangeCheck.BAD_FORMAT, BmsPassword.validateChange(cur, "123456", "98765a", "98765a"))
        assertEquals(PasswordChangeCheck.MISMATCH, BmsPassword.validateChange(cur, "123456", "987654", "987655"))
        assertEquals(PasswordChangeCheck.SAME_AS_OLD, BmsPassword.validateChange(cur, "123456", "123456", "123456"))
    }

    @Test fun passwordDecode() {
        // "123456" = 0x3132 0x3334 0x3536
        assertEquals("123456", BmsPassword.decode(intArrayOf(0x3132, 0x3334, 0x3536)))
        assertEquals("1234", BmsPassword.decode(intArrayOf(0x3132, 0x3334, 0x0000)))
    }
}
