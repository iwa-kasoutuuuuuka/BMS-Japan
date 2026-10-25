package jp.bmsjapan.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkWriteTest {

    /** リン酸鉄で設定された BMS（セル数は台ごとに変えられる） */
    private fun lfp(cells: Int = 7, cellOvRelease: Int = 3550): BmsSettings {
        val r = IntArray(BmsSettings.MAIN_COUNT)
        fun set(reg: Int, v: Int) { r[reg - 128] = v }
        set(128, 1320); set(129, 3200); set(130, cells); set(137, 0); set(138, 3600)
        set(139, cellOvRelease); set(140, 3650); set(141, 2650); set(142, 2600)
        set(143, 249); set(144, 256); set(145, 186); set(146, 182)
        set(147, 29000); set(148, 28000); set(149, 31500); set(150, 32000)
        set(151, 90); set(152, 95); set(153, 45); set(154, 40)
        set(155, 95); set(156, 100); set(157, 25); set(158, 20)
        set(159, 200); set(160, 300); set(161, 10); set(162, 15)
        set(163, 3400); set(164, 10); set(165, 1); set(166, 1); set(167, 600); set(168, 130)
        return BmsSettings.EMPTY.merge(128, r)
    }

    @Test fun setValueIsCheckedAgainstEachUnitsOwnSettings() {
        val op = BulkOp.SetValue(Setting.CELL_OV, 3.62)
        // 解除値 3.55 V の台には書ける
        val ok = planBulk(op, lfp(), 7)
        assertEquals(BulkPlan.Ok(listOf(WriteStep(140, intArrayOf(3620)))), ok)
        // 別の台は解除値が 3.63 V なので、保護値 3.62 V は解除値を下回る → 書かない
        assertEquals(BulkPlan.Rejected(BulkSkip.OUT_OF_RANGE), planBulk(op, lfp(cellOvRelease = 3630), 7))
    }

    @Test fun socCanBeSetOnAllUnits() {
        // 満充電後に全台の SOC を 100% に合わせる用途
        val plan = planBulk(BulkOp.SetValue(Setting.SOC, 100.0), lfp(), 7)
        assertEquals(BulkPlan.Ok(listOf(WriteStep(167, intArrayOf(1000)))), plan)
    }

    @Test fun switchWritesOneRegister() {
        assertEquals(
            BulkPlan.Ok(listOf(WriteStep(166, intArrayOf(0)))),
            planBulk(BulkOp.Switch(SwitchSetting.DISCHARGE_MOS, false), lfp(), 7),
        )
    }

    private fun block(p: BulkPlan) = (p as BulkPlan.Ok).steps.last { it.start == 137 }.values

    @Test fun batteryPresetUsesEachUnitsCellCount() {
        val op = BulkOp.Battery(BatteryType.NCM, expectedType = BatteryType.LFP)
        assertEquals(294, block(planBulk(op, lfp(7), 7))[144 - 137])   // 4.20 × 7
        assertEquals(336, block(planBulk(op, lfp(8), 8))[144 - 137])   // 4.20 × 8
        // 三元系は 20 セルまで
        assertEquals(BulkPlan.Rejected(BulkSkip.BAD_CELLS), planBulk(op, lfp(24), 24))
        // 設定のセル数（7）と運転データのセル数（8）が違う台は書かない
        assertEquals(BulkPlan.Rejected(BulkSkip.BAD_CELLS), planBulk(op, lfp(7), 8))
    }

    @Test fun batteryPresetSkipsUnitsWithAnotherChemistry() {
        // 開いている台はリン酸鉄だったが、この台はすでに三元系
        val ncm = lfp().merge(137, intArrayOf(1))
        assertEquals(
            BulkPlan.Rejected(BulkSkip.CHEMISTRY_MISMATCH),
            planBulk(BulkOp.Battery(BatteryType.LTO, expectedType = BatteryType.LFP), ncm, 7),
        )
    }

    @Test fun batteryPresetFixesValuesOutsideTheNewChemistry() {
        // チタン酸（充電の低温保護 −20 ℃、公称 2.40 V）からリン酸鉄へ
        val lto = lfp().merge(137, intArrayOf(2)).merge(153, intArrayOf(25, 20)).merge(129, intArrayOf(2400))
        val steps = (planBulk(BulkOp.Battery(BatteryType.LFP, BatteryType.LTO), lto, 7) as BulkPlan.Ok).steps
        val b = steps.last().values
        assertEquals(40, b[154 - 137])          // 0 ℃（リン酸鉄は 0 ℃ 未満で充電しない）
        assertEquals(45, b[153 - 137])          // 解除値 5 ℃
        assertEquals(WriteStep(129, intArrayOf(3200)), steps.first())   // 公称電圧も直す（一括書き込みより先に）
        assertEquals(137, steps.last().start)
    }

    private fun cond(minV: Double?, maxV: Double?) = CellConditions(minV, maxV, 25, 25)

    @Test fun recommendedPresetStopsWhenProtectionWouldTripUnlessAccepted() {
        val op = BulkOp.Recommended(RecommendedPreset.LEAF_STANDARD, expectedType = BatteryType.LFP)
        assertEquals(BulkPlan.Rejected(BulkSkip.IMMEDIATE_EFFECT), planBulk(op, lfp(), 7, cond(2.99, 3.01), acceptChemistryChange = true))
        val ok = planBulk(op, lfp(), 7, cond(2.99, 3.01), acceptImmediateEffect = true, acceptChemistryChange = true) as BulkPlan.Ok
        assertEquals(listOf(164, 129, 137), ok.steps.map { it.start })
        assertEquals(27, ok.steps.last().values.size)
        assertTrue(planBulk(op, lfp(), 7, cond(3.70, 3.75), acceptChemistryChange = true) is BulkPlan.Ok)
    }

    @Test fun recommendedPresetNeedsChemistryAcknowledgementAndLiveData() {
        val op = BulkOp.Recommended(RecommendedPreset.LEAF_STANDARD, expectedType = BatteryType.LFP)
        // リン酸鉄 → 三元系の変更を了承していない
        assertEquals(BulkPlan.Rejected(BulkSkip.CHEMISTRY_NOT_ACCEPTED), planBulk(op, lfp(), 7, cond(3.7, 3.75)))
        // セル電圧が読めない台は、判定できないので書かない
        assertEquals(
            BulkPlan.Rejected(BulkSkip.NO_LIVE_DATA),
            planBulk(op, lfp(), 7, CellConditions.UNKNOWN, acceptChemistryChange = true),
        )
        // 種類が違う台は書かない
        val lto = lfp().merge(137, intArrayOf(2))
        assertEquals(BulkPlan.Rejected(BulkSkip.CHEMISTRY_MISMATCH), planBulk(op, lto, 7, cond(3.7, 3.75), acceptChemistryChange = true))
    }

    @Test fun worstConditionsIgnoresUnitsWithoutData() {
        val base = LiveData.parse(IntArray(62))
        val a = base.copy(minCellVoltage = 3.10, maxCellVoltage = 3.20, minTemperature = 10, maxTemperature = 20, temperatureSensorCount = 2)
        val b = base.copy(minCellVoltage = 2.95, maxCellVoltage = 3.30, minTemperature = 5, maxTemperature = 30, temperatureSensorCount = 2)
        val empty = base.copy(minCellVoltage = 0.0, maxCellVoltage = 0.0, temperatureSensorCount = 0)
        assertEquals(CellConditions(2.95, 3.30, 5, 30), CellConditions.worst(listOf(a, b, empty)))
        assertEquals(CellConditions.UNKNOWN, CellConditions.worst(listOf(empty)))
    }
}
