package jp.bmsjapan.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedTest {

    /** いまリン酸鉄として設定されている 7 セルの BMS（実機の状況に近い想定） */
    private fun lfp7(cells: Int = 7): BmsSettings {
        val r = IntArray(BmsSettings.MAIN_COUNT)
        fun set(reg: Int, v: Int) { r[reg - 128] = v }
        set(128, 1320); set(130, cells); set(137, 0); set(138, 3600)
        set(139, 3550); set(140, 3650); set(141, 2650); set(142, 2600)
        set(143, 249); set(144, 256); set(145, 186); set(146, 182)
        set(147, 29000); set(148, 28000); set(149, 31500); set(150, 32000)
        set(151, 90); set(152, 95); set(153, 45); set(154, 40)
        set(155, 95); set(156, 100); set(157, 25); set(158, 20)
        set(159, 200); set(160, 300); set(161, 10); set(162, 15)
        set(163, 3400); set(164, 10); set(165, 1); set(166, 1); set(167, 600); set(168, 130)
        return BmsSettings.EMPTY.merge(128, r)
    }

    @Test fun leafStandardFor7S() {
        val plan = buildRecommendedPlan(RecommendedPreset.LEAF_STANDARD, 7, lfp7())
        assertNotNull(plan)
        val v = plan!!.block
        fun at(reg: Int) = v[reg - 137]
        assertEquals(1, at(137))                 // 三元系
        assertEquals(4150, at(140)); assertEquals(4100, at(139))
        assertEquals(3000, at(142)); assertEquals(3200, at(141))
        assertEquals(291, at(144))               // 4.15 × 7 = 29.05 V
        assertEquals(287, at(143))               // 4.10 × 7 = 28.7 V
        assertEquals(210, at(146))               // 3.00 × 7 = 21.0 V
        assertEquals(224, at(145))               // 3.20 × 7 = 22.4 V
        assertEquals(85, at(152)); assertEquals(40, at(154))   // 充電 45 ℃ / 0 ℃
        assertEquals(3900, at(163))
        assertEquals(15, plan.balanceDiffRaw)
        // 電流は今の値のまま
        assertEquals(28000, at(148)); assertEquals(32000, at(150))
        assertTrue(plan.changes.any { it.setting == Setting.CELL_OV })
        assertEquals(listOf(164 to 15, 129 to 3700), plan.extra)
    }

    @Test fun warnsWhenProtectionWouldTripRightAway() {
        val plan = buildRecommendedPlan(RecommendedPreset.LEAF_STANDARD, 7, lfp7())!!
        // 実機のスクショ: セル 2.99〜3.01 V → 新しい低電圧の解除値 3.20 V より低いので放電が止まる
        assertEquals(PresetImmediateEffect.DISCHARGE_STOPS, plan.immediateEffect(2.992, 3.006))
        assertEquals(PresetImmediateEffect.NONE, plan.immediateEffect(3.70, 3.75))
        assertEquals(PresetImmediateEffect.CHARGE_STOPS, plan.immediateEffect(3.90, 4.16))
        // 読めていない（0 V）ときは判定しない
        assertEquals(PresetImmediateEffect.NONE, plan.immediateEffect(0.0, 0.0))
    }

    @Test fun everyPresetPassesSafetyChecksForCommonCellCounts() {
        for (p in RecommendedPreset.entries) {
            for (n in listOf(4, 7, 8, 14, 16)) {
                assertNotNull("${p.name} ${n}S", buildRecommendedPlan(p, n, lfp7(n)))
            }
        }
    }

    @Test fun refusesBadCellCountsAndIncompleteSettings() {
        assertNull(buildRecommendedPlan(RecommendedPreset.LEAF_STANDARD, 0, lfp7()))
        assertNull(buildRecommendedPlan(RecommendedPreset.LEAF_STANDARD, 21, lfp7()))   // 三元系は 20 セルまで
        assertNull(buildRecommendedPlan(RecommendedPreset.LEAF_STANDARD, 7, BmsSettings.EMPTY))
    }
}
