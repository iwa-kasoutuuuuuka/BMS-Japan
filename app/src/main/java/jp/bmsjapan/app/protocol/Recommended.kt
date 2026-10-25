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

/**
 * 日産リーフの電池の世代。世代で変わるのは、セルの公称電圧と容量（電圧の保護値はどの世代も同じでよい）。
 * 容量は新品時のセル 1 本の目安。実際の定格容量は「セル容量 × 並列数 × SOH」で計算する。
 */
enum class LeafGeneration(
    @param:StringRes val label: Int,
    @param:StringRes val detail: Int,
    /** セルの公称電圧（V） */
    val nominal: Double,
    /** 新品時のセル 1 本の容量（Ah、目安） */
    val cellAh: Double,
    /** 車両での並列数（モジュールをそのまま使うときの目安） */
    val defaultParallel: Int,
    /** 熱で劣化しやすく、「長寿命」をすすめる世代 */
    val suggestLongLife: Boolean,
) {
    ZE0_24(R.string.gen_ze0_24, R.string.gen_ze0_24_detail, 3.75, 33.1, 2, true),
    AZE0_24(R.string.gen_aze0_24, R.string.gen_aze0_24_detail, 3.75, 33.1, 2, false),
    AZE0_30(R.string.gen_aze0_30, R.string.gen_aze0_30_detail, 3.70, 40.0, 2, false),
    ZE1_40(R.string.gen_ze1_40, R.string.gen_ze1_40_detail, 3.65, 56.3, 2, false),
    ZE1_62(R.string.gen_ze1_62, R.string.gen_ze1_62_detail, 3.65, 59.0, 3, false),
    ;

    companion object {
        const val MIN_PARALLEL = 1
        const val MAX_PARALLEL = 20
        const val MIN_SOH = 30
        const val MAX_SOH = 100

        /**
         * 定格容量の目安（Ah、0.1 単位）。並列数・SOH が範囲外なら null。
         * @param sohPercent 残っている容量の割合（%）。中古のモジュールは劣化しているため
         */
        fun ratedCapacityAh(gen: LeafGeneration, parallel: Int, sohPercent: Int): Double? {
            if (parallel !in MIN_PARALLEL..MAX_PARALLEL || sohPercent !in MIN_SOH..MAX_SOH) return null
            return (gen.cellAh * parallel * sohPercent / 100.0 * 10).roundToInt() / 10.0
        }
    }
}

/** おすすめ設定の追加の指定（世代と定格容量）。どちらも省略できる */
data class PresetOptions(
    /** 世代。指定すると公称電圧を世代に合わせる（省略時はプリセットの 3.70 V） */
    val generation: LeafGeneration? = null,
    /** 定格容量（Ah）。指定したときだけ書き込む */
    val ratedCapacityAh: Double? = null,
)

/** おすすめ設定で変わる 1 項目（表示用） */
data class PresetChange(val setting: Setting?, @param:StringRes val label: Int, val before: String, val after: String)

data class PresetPlan(
    /** 137 から 27 レジスタ（電池の種類・電圧・電流・温度・均等化開始電圧） */
    val block: IntArray,
    /** 1 つずつ書くレジスタ（164 = 均等化を始める電圧差、129 = 公称電圧） */
    val extra: List<Pair<Int, Int>>,
    val changes: List<PresetChange>,
    val preset: RecommendedPreset,
    /** 書き込み前の電池の種類（読めなければ null） */
    val fromType: BatteryType?,
    val options: PresetOptions = PresetOptions(),
) {
    val balanceDiffRaw: Int get() = extra.first { it.first == 164 }.second

    /** 電池の種類が変わる（例: リン酸鉄 → 三元系）。セルの種類を利用者に確かめてもらう */
    val changesChemistry: Boolean get() = fromType != preset.type

    /** 書き込む順番。1 つずつ書くレジスタを先に、電池の種類を含む一括書き込みを最後にする（途中で失敗しても種類だけ変わった状態にしない） */
    val steps: List<WriteStep> get() = extra.map { (reg, v) -> WriteStep(reg, intArrayOf(v)) } + WriteStep(137, block)

    override fun equals(other: Any?) = other is PresetPlan && block.contentEquals(other.block) &&
        extra == other.extra && changes == other.changes && preset == other.preset && fromType == other.fromType &&
        options == other.options
    override fun hashCode() = block.contentHashCode() * 31 + extra.hashCode()
}

/** 書き込み直後に保護が働くかの判定に使う、今の状態。読めていない値は null */
data class CellConditions(val minCellV: Double?, val maxCellV: Double?, val minTemp: Int?, val maxTemp: Int?) {
    companion object {
        val UNKNOWN = CellConditions(null, null, null, null)

        fun of(live: LiveData?): CellConditions {
            if (live == null) return UNKNOWN
            val temps = live.temperatureSensorCount > 0
            return CellConditions(
                live.minCellVoltage.takeIf { it > 0.5 },
                live.maxCellVoltage.takeIf { it > 0.5 },
                live.minTemperature.takeIf { temps },
                live.maxTemperature.takeIf { temps },
            )
        }

        /** 全台のうち、いちばん厳しい値（最低の最低値・最高の最高値）。読めていない台は除く */
        fun worst(lives: List<LiveData>): CellConditions {
            val all = lives.map(::of)
            return CellConditions(
                all.mapNotNull { it.minCellV }.minOrNull(),
                all.mapNotNull { it.maxCellV }.maxOrNull(),
                all.mapNotNull { it.minTemp }.minOrNull(),
                all.mapNotNull { it.maxTemp }.maxOrNull(),
            )
        }
    }
}

/** 今のセル電圧・温度で、書き込んだ直後に保護が働いてしまうかどうか */
enum class PresetImmediateEffect { NONE, DISCHARGE_STOPS, CHARGE_STOPS, DISCHARGE_STOPS_TEMP, CHARGE_STOPS_TEMP }

/**
 * 新しい「解除値」と比べる（保護値と解除値の間にいると、すでに保護が働いている台では解除されないため、控えめに判定する）。
 * 放電が止まる方を先に判定する（影響が大きいため）。
 */
fun PresetPlan.immediateEffect(c: CellConditions): PresetImmediateEffect {
    val p = preset
    return when {
        c.minCellV != null && c.minCellV < p.cellUvRelease -> PresetImmediateEffect.DISCHARGE_STOPS
        (c.minTemp != null && c.minTemp < p.dischargeUtRelease) || (c.maxTemp != null && c.maxTemp > p.dischargeOtRelease) ->
            PresetImmediateEffect.DISCHARGE_STOPS_TEMP
        c.maxCellV != null && c.maxCellV > p.cellOvRelease -> PresetImmediateEffect.CHARGE_STOPS
        (c.minTemp != null && c.minTemp < p.chargeUtRelease) || (c.maxTemp != null && c.maxTemp > p.chargeOtRelease) ->
            PresetImmediateEffect.CHARGE_STOPS_TEMP
        else -> PresetImmediateEffect.NONE
    }
}

/**
 * おすすめ設定の書き込み内容を作る。
 * セル数が 1〜48 でない、その種類が選べないセル数、今の値が揃っていない、のどれかなら null。
 * 作った値は、書き込み後の状態（電池の種類が変わった後）で安全範囲の確認を通ることを確かめてから返す。
 */
fun buildRecommendedPlan(
    preset: RecommendedPreset,
    cellCount: Int,
    current: BmsSettings,
    options: PresetOptions = PresetOptions(),
): PresetPlan? {
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
    val nominalRaw = mv(options.generation?.nominal ?: preset.nominal)
    val extra = mutableListOf(164 to diffRaw, 129 to nominalRaw)
    options.ratedCapacityAh?.let { ah ->
        if (!ah.isFinite()) return null
        extra += Setting.RATED_CAPACITY.register to Setting.RATED_CAPACITY.encoding.encode(ah)
    }

    // 書き込み後の状態を作り、主な項目が安全範囲・大小関係の確認を通るかを確かめる（通らなければ書かない）
    var after = current.merge(start, values)
    for ((reg, v) in extra) after = after.merge(reg, intArrayOf(v))
    val checked = listOfNotNull(
        Setting.RATED_CAPACITY.takeIf { options.ratedCapacityAh != null },
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
    return PresetPlan(values, extra, changes, preset, beforeType, options)
}
