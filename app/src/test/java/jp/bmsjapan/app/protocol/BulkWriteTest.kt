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

    @Test fun batteryPresetUsesEachUnitsCellCount() {
        val a = planBulk(BulkOp.Battery(BatteryType.NCM), lfp(7), 7) as BulkPlan.Ok
        val b = planBulk(BulkOp.Battery(BatteryType.NCM), lfp(8), 8) as BulkPlan.Ok
        assertEquals(294, a.steps[0].values[144 - 137])   // 4.20 × 7
        assertEquals(336, b.steps[0].values[144 - 137])   // 4.20 × 8
        // 三元系は 20 セルまで
        assertEquals(BulkPlan.Rejected(BulkSkip.BAD_CELLS), planBulk(BulkOp.Battery(BatteryType.NCM), lfp(24), 24))
    }

    @Test fun recommendedPresetStopsWhenProtectionWouldTripUnlessAccepted() {
        val op = BulkOp.Recommended(RecommendedPreset.LEAF_STANDARD)
        assertEquals(BulkPlan.Rejected(BulkSkip.IMMEDIATE_EFFECT), planBulk(op, lfp(), 7, 2.99, 3.01))
        val ok = planBulk(op, lfp(), 7, 2.99, 3.01, acceptImmediateEffect = true) as BulkPlan.Ok
        assertEquals(listOf(137, 164, 129), ok.steps.map { it.start })
        assertEquals(27, ok.steps[0].values.size)
        assertTrue(planBulk(op, lfp(), 7, 3.70, 3.75) is BulkPlan.Ok)
    }

    @Test fun worstCellVoltagesIgnoresUnitsWithoutData() {
        val base = LiveData.parse(IntArray(62))
        val a = base.copy(minCellVoltage = 3.10, maxCellVoltage = 3.20)
        val b = base.copy(minCellVoltage = 2.95, maxCellVoltage = 3.30)
        val empty = base.copy(minCellVoltage = 0.0, maxCellVoltage = 0.0)
        assertEquals(2.95 to 3.30, worstCellVoltages(listOf(a, b, empty)))
        assertEquals(null to null, worstCellVoltages(listOf(empty)))
    }
}
