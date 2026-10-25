package jp.bmsjapan.app.protocol

import androidx.annotation.StringRes
import jp.bmsjapan.app.R
import kotlin.math.roundToInt

/**
 * 特定の電池向けの「おすすめ設定」。電池の種類のプリセット（元アプリと同じ 137〜163 の一括書き込み）に、
 * 温度の保護値と均等化の設定も加えたもの。
 *
 * 値はセルの一般的な仕様に基づく目安で、BMS の保護（非常時に止める値）として余裕を持たせている。
 * 普段の充電を止める電圧は、充電器・インバーター側でこれより低く設定する前提。
 */
enum class RecommendedPreset(
    @param:StringRes val label: Int,
    @param:StringRes val description: Int,
    val type: BatteryType,
    // セル電圧（V）
    val cellOvRelease: Double, val cellOv: Double,
    val cellUvRelease: Double, val cellUv: Double,
    val balanceStart: Double, val balanceDiff: Double,
    /** セルの公称電圧（V）。SOC の計算などに使われる */
    val nominal: Double,
    // 温度（℃）: 解除 / 保護
    val chargeOtRelease: Int, val chargeOt: Int,
    val chargeUtRelease: Int, val chargeUt: Int,
    val dischargeOtRelease: Int, val dischargeOt: Int,
    val dischargeUtRelease: Int, val dischargeUt: Int,
) {
    /**
     * 日産リーフのモジュール（第 1 世代 24/30 kWh のマンガン系、第 2 世代 40/62 kWh の三元系のどちらにも使える値）。
     * 上限 4.15 V・下限 3.00 V は、車両が使う範囲（おおむね 3.0〜4.15 V）に合わせた保護値。
     */
    LEAF_STANDARD(
        R.string.preset_leaf_standard, R.string.preset_leaf_standard_desc, BatteryType.NCM,
        cellOvRelease = 4.10, cellOv = 4.15,
        cellUvRelease = 3.20, cellUv = 3.00,
        balanceStart = 3.90, balanceDiff = 0.015,
        nominal = 3.70,
        chargeOtRelease = 40, chargeOt = 45,
        chargeUtRelease = 5, chargeUt = 0,
        dischargeOtRelease = 55, dischargeOt = 60,
        dischargeUtRelease = -15, dischargeUt = -20,
    ),

    /** 日産リーフ・長寿命寄り。満充電・深い放電を避けて、劣化を抑える */
    LEAF_LONG_LIFE(
        R.string.preset_leaf_long, R.string.preset_leaf_long_desc, BatteryType.NCM,
        cellOvRelease = 4.05, cellOv = 4.10,
        cellUvRelease = 3.45, cellUv = 3.30,
        balanceStart = 3.85, balanceDiff = 0.015,
        nominal = 3.70,
        chargeOtRelease = 35, chargeOt = 40,
        chargeUtRelease = 8, chargeUt = 3,
        dischargeOtRelease = 50, dischargeOt = 55,
        dischargeUtRelease = -10, dischargeUt = -15,
    ),
    ;

    /** 総電圧（V）。セル電圧 × セル数 */
    fun pack(cellV: Double, cells: Int): Double = cellV * cells
}

/** おすすめ設定で変わる 1 項目（表示用） */
data class PresetChange(val setting: Setting?, @param:StringRes val label: Int, val before: String, val after: String)

data class PresetPlan(
    /** 137 から 27 レジスタ（電池の種類・電圧・電流・温度・均等化開始電圧） */
    val block: IntArray,
    /** 137〜163 の後に 1 つずつ書くレジスタ（164 = 均等化を始める電圧差、129 = 公称電圧） */
    val extra: List<Pair<Int, Int>>,
    val changes: List<PresetChange>,
    /** 書き込み後のセル低電圧の解除値・セル過電圧の保護値（V）。今のセル電圧との比較に使う */
    val cellUvRelease: Double,
    val cellOv: Double,
) {
    val balanceDiffRaw: Int get() = extra.first { it.first == 164 }.second
    override fun equals(other: Any?) = other is PresetPlan && block.contentEquals(other.block) &&
        extra == other.extra && changes == other.changes
    override fun hashCode() = block.contentHashCode() * 31 + extra.hashCode()
}

/** 今のセル電圧で、書き込んだ直後に保護が働いてしまうかどうか */
enum class PresetImmediateEffect { NONE, DISCHARGE_STOPS, CHARGE_STOPS }

fun PresetPlan.immediateEffect(minCellV: Double?, maxCellV: Double?): PresetImmediateEffect = when {
    minCellV != null && minCellV > 0.5 && minCellV < cellUvRelease -> PresetImmediateEffect.DISCHARGE_STOPS
    maxCellV != null && maxCellV >= cellOv -> PresetImmediateEffect.CHARGE_STOPS
    else -> PresetImmediateEffect.NONE
}

/**
 * おすすめ設定の書き込み内容を作る。
 * セル数が 1〜48 でない、その種類が選べないセル数、今の値が揃っていない、のどれかなら null。
 * 作った値は、書き込み後の状態（電池の種類が変わった後）で安全範囲の確認を通ることを確かめてから返す。
 */
fun buildRecommendedPlan(preset: RecommendedPreset, cellCount: Int, current: BmsSettings): PresetPlan? {
    if (cellCount !in MIN_PRESET_CELLS..MAX_PRESET_CELLS) return null
    if (!preset.type.availableFor(cellCount)) return null
    val start = 137
    val count = 27
    val values = IntArray(count)
    for (i in 0 until count) values[i] = current.raw[start + i] ?: return null
    fun set(reg: Int, v: Int) { values[reg - start] = v }
    fun mv(v: Double) = (v * 1000).roundToInt()
    fun packDeci(perCell: Double) = (preset.pack(perCell, cellCount) * 10).roundToInt()
    fun temp(c: Int) = c + 40

    set(137, preset.type.code)
    set(139, mv(preset.cellOvRelease)); set(140, mv(preset.cellOv))
    set(141, mv(preset.cellUvRelease)); set(142, mv(preset.cellUv))
    set(143, packDeci(preset.cellOvRelease)); set(144, packDeci(preset.cellOv))
    set(145, packDeci(preset.cellUvRelease)); set(146, packDeci(preset.cellUv))
    set(151, temp(preset.chargeOtRelease)); set(152, temp(preset.chargeOt))
    set(153, temp(preset.chargeUtRelease)); set(154, temp(preset.chargeUt))
    set(155, temp(preset.dischargeOtRelease)); set(156, temp(preset.dischargeOt))
    set(157, temp(preset.dischargeUtRelease)); set(158, temp(preset.dischargeUt))
    set(163, mv(preset.balanceStart))
    val diffRaw = mv(preset.balanceDiff)
    val nominalRaw = mv(preset.nominal)

    // 書き込み後の状態を作り、主な項目が安全範囲・大小関係の確認を通るかを確かめる（通らなければ書かない）
    val after = current.merge(start, values).merge(164, intArrayOf(diffRaw)).merge(129, intArrayOf(nominalRaw))
    val checked = listOf(
        Setting.CELL_NOMINAL,
        Setting.CELL_OV, Setting.CELL_UV, Setting.PACK_OV, Setting.PACK_UV,
        Setting.CHARGE_OT, Setting.CHARGE_UT, Setting.DISCHARGE_OT, Setting.DISCHARGE_UT,
        Setting.BALANCE_START_V, Setting.BALANCE_START_DIFF,
    )
    for (s in checked) {
        val v = after[s] ?: return null
        if (s.validateValue(v, after, cellCount) !is ValidationResult.Ok) return null
    }

    // 表示用の変更点（変わる項目だけ）
    val changes = mutableListOf<PresetChange>()
    val beforeType = current.batteryType
    if (beforeType != preset.type) {
        changes += PresetChange(null, R.string.label_battery_type, beforeType?.name ?: "--", preset.type.name)
    }
    for (s in checked) {
        val b = current[s]
        val a = after[s]!!
        if (b == null || s.format(b) != s.format(a)) {
            changes += PresetChange(s, s.label, b?.let { "${s.format(it)} ${s.unit}" } ?: "--", "${s.format(a)} ${s.unit}")
        }
        // 解除値も変わるものは並べて見せる
        val rb = current.release(s)
        val ra = after.release(s)
        if (ra != null && (rb == null || s.format(rb) != s.format(ra))) {
            changes += PresetChange(s, R.string.preset_release_of, rb?.let { "${s.format(it)} ${s.unit}" } ?: "--", "${s.format(ra)} ${s.unit}")
        }
    }
    return PresetPlan(values, listOf(164 to diffRaw, 129 to nominalRaw), changes, preset.cellUvRelease, preset.cellOv)
}
