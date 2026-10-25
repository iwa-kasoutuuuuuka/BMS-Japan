package jp.bmsjapan.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 電池の種類・セル数に合わせた安全範囲（独立監査の指摘への対応） */
class SafetyLimitsTest {

    /** 16 セルのリン酸鉄（LFP） */
    private fun lfp16(type: Int = 0): BmsSettings {
        val r = IntArray(BmsSettings.MAIN_COUNT)
        fun set(reg: Int, v: Int) { r[reg - 128] = v }
        set(130, 16); set(137, type)
        set(139, 3550); set(140, 3650); set(141, 2700); set(142, 2500)
        set(143, 568); set(144, 584); set(145, 432); set(146, 400)
        set(151, 50); set(152, 55); set(153, 45); set(154, 40)
        set(163, 3400)
        return BmsSettings.EMPTY.merge(128, r)
    }

    @Test fun lfpCannotDisableOverchargeProtection() {
        // 以前は 4.5 V まで書けた（解除値 3.55 V より上なら通っていた）
        val r = Setting.CELL_OV.validate("4.5", lfp16())
        assertEquals(ValidationResult.OutOfRange("3.400", "3.750"), r)
        assertEquals(ValidationResult.Ok(3700), Setting.CELL_OV.validate("3.70", lfp16()))
    }

    @Test fun ncmAllowsHigherVoltage() {
        val s = lfp16(type = 1).merge(139, intArrayOf(4100, 4150))
            .merge(163, intArrayOf(4000))
        assertEquals(ValidationResult.Ok(4200), Setting.CELL_OV.validate("4.20", s))
        assertTrue(Setting.CELL_OV.validate("4.30", s) is ValidationResult.OutOfRange)
    }

    @Test fun unknownBatteryTypeBlocksVoltageSettings() {
        val s = lfp16(type = 7)
        assertEquals(ValidationResult.NeedsBatteryType, Setting.CELL_OV.validate("3.6", s))
        assertEquals(ValidationResult.NeedsBatteryType, Setting.PACK_OV.validate("58.0", s))
        // 温度は種類が分からなくても、いちばん厳しい（リン酸鉄・三元系の）範囲で確認できる
        assertTrue(Setting.CHARGE_OT.validate("50", s) is ValidationResult.Ok)
    }

    @Test fun packVoltageLimitedByCellCount() {
        val s = lfp16()
        // 16 セル × 3.75 V = 60.0 V が上限
        assertEquals(ValidationResult.OutOfRange("32.0", "60.0"), Setting.PACK_OV.validate("80", s))
        assertTrue(Setting.PACK_OV.validate("59.0", s) is ValidationResult.Ok)
    }

    @Test fun packVoltageNeedsCellCount() {
        val s = lfp16().merge(130, intArrayOf(0))
        assertEquals(ValidationResult.NeedsCellCount, Setting.PACK_OV.validate("58", s, liveCells = null))
        // 運転データのセル数があればそれを使う
        assertTrue(Setting.PACK_OV.validate("58", s, liveCells = 16) is ValidationResult.Ok)
    }

    @Test fun chargingBelowZeroIsBlockedExceptLto() {
        assertTrue(Setting.CHARGE_UT.validate("-5", lfp16()) is ValidationResult.OutOfRange)
        val lto = lfp16(type = 2).merge(153, intArrayOf(20))   // 解除値 -20 ℃
        assertTrue(Setting.CHARGE_UT.validate("-25", lto) is ValidationResult.Ok)
    }

    @Test fun voltageOrdering() {
        val s = lfp16()   // 均等化開始 3.400 V
        assertEquals(
            ValidationResult.MustBeAboveOther(Setting.BALANCE_START_V, "3.400"),
            Setting.CELL_OV.validate("3.40", s),
        )
        assertEquals(
            ValidationResult.MustBeBelowOther(Setting.CELL_OV, "3.650"),
            Setting.BALANCE_START_V.validate("3.65", s),
        )
        // 均等化開始電圧（2.900 V）以上の低電圧保護は書けない
        assertEquals(
            ValidationResult.MustBeBelowOther(Setting.BALANCE_START_V, "2.900"),
            Setting.CELL_UV.validate("3.0", s.merge(141, intArrayOf(3100)).merge(163, intArrayOf(2900))),
        )
    }

    @Test fun chargeHighTempCapped() {
        assertTrue(Setting.CHARGE_OT.validate("80", lfp16()) is ValidationResult.OutOfRange)
    }
}
