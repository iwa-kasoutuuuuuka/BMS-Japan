package jp.bmsjapan.app.protocol

/**
 * 並列・直列で、同じ設定を全台にまとめて書き込むときの計算。
 *
 * 1 台目で見せた「生の値」をそのまま配るのではなく、台ごとに「その台の今の設定・セル数」から書き込み内容を作り直し、
 * その台で安全範囲の確認を通ったものだけを書く（台によってセル数や解除値が違うことがあるため）。
 */
sealed interface BulkOp {
    /** 1 項目の値（表示値）を変える */
    data class SetValue(val setting: Setting, val value: Double) : BulkOp

    /** 充電MOS・放電MOS・均等化の ON/OFF */
    data class Switch(val sw: SwitchSetting, val on: Boolean) : BulkOp

    /** 電池の種類のプリセット */
    data class Battery(val type: BatteryType) : BulkOp

    /** おすすめ設定（日産リーフなど） */
    data class Recommended(val preset: RecommendedPreset) : BulkOp
}

/** 1 回の書き込み（start から values を連続で書く） */
data class WriteStep(val start: Int, val values: IntArray) {
    override fun equals(other: Any?) = other is WriteStep && start == other.start && values.contentEquals(other.values)
    override fun hashCode() = start * 31 + values.contentHashCode()
}

/** 書かなかった・書けなかった理由 */
enum class BulkSkip {
    /** 接続していない */
    NOT_CONNECTED,

    /** パスワードが初期値でも、開いている台と同じでもない */
    PASSWORD,

    /** 設定・パスワードを読めなかった */
    READ_FAILED,

    /** その台の今の設定では安全範囲の確認を通らない */
    OUT_OF_RANGE,

    /** セル数が読めない、またはその種類が選べないセル数 */
    BAD_CELLS,

    /** 書き込むとすぐ放電・充電が止まる（了承されていない） */
    IMMEDIATE_EFFECT,

    /** 書き込み中に失敗した */
    WRITE_FAILED,

    /** 書いた後に読み直すと値が違う */
    VERIFY_FAILED,
}

sealed interface BulkPlan {
    data class Ok(val steps: List<WriteStep>) : BulkPlan
    data class Rejected(val reason: BulkSkip) : BulkPlan
}

/**
 * 1 台分の書き込み内容を作る。
 * @param liveCells その台の運転データのセル数
 * @param minCellV / maxCellV その台の今のセル電圧（おすすめ設定で、書き込み直後に保護が働くかの判定に使う）
 * @param acceptImmediateEffect 書き込み直後に保護が働くことを利用者が了承したか
 */
fun planBulk(
    op: BulkOp,
    settings: BmsSettings,
    liveCells: Int?,
    minCellV: Double? = null,
    maxCellV: Double? = null,
    acceptImmediateEffect: Boolean = false,
): BulkPlan = when (op) {
    is BulkOp.SetValue -> when (val r = op.setting.validateValue(op.value, settings, liveCells)) {
        is ValidationResult.Ok -> BulkPlan.Ok(listOf(WriteStep(op.setting.register, intArrayOf(r.raw))))
        ValidationResult.NeedsCellCount -> BulkPlan.Rejected(BulkSkip.BAD_CELLS)
        else -> BulkPlan.Rejected(BulkSkip.OUT_OF_RANGE)
    }
    is BulkOp.Switch -> BulkPlan.Ok(listOf(WriteStep(op.sw.register, intArrayOf(if (op.on) 1 else 0))))
    is BulkOp.Battery -> {
        val cells = SafetyLimits.cellCount(settings, liveCells)
        val values = cells?.let { buildBatteryPreset(op.type, it, settings) }
        when {
            cells == null || !op.type.availableFor(cells) -> BulkPlan.Rejected(BulkSkip.BAD_CELLS)
            values == null -> BulkPlan.Rejected(BulkSkip.READ_FAILED)
            else -> BulkPlan.Ok(listOf(WriteStep(137, values)))
        }
    }
    is BulkOp.Recommended -> {
        val cells = SafetyLimits.cellCount(settings, liveCells)
        val plan = cells?.let { buildRecommendedPlan(op.preset, it, settings) }
        when {
            cells == null || !op.preset.type.availableFor(cells) -> BulkPlan.Rejected(BulkSkip.BAD_CELLS)
            plan == null -> BulkPlan.Rejected(BulkSkip.OUT_OF_RANGE)
            !acceptImmediateEffect && plan.immediateEffect(minCellV, maxCellV) != PresetImmediateEffect.NONE ->
                BulkPlan.Rejected(BulkSkip.IMMEDIATE_EFFECT)
            else -> BulkPlan.Ok(listOf(WriteStep(137, plan.block)) + plan.extra.map { (reg, v) -> WriteStep(reg, intArrayOf(v)) })
        }
    }
}

/** 全台のセル電圧から、いちばん厳しい値（最低の最低セル・最高の最高セル）。読めていない台（0 V）は除く */
fun worstCellVoltages(lives: List<LiveData>): Pair<Double?, Double?> {
    val valid = lives.filter { it.minCellVoltage > 0.5 }
    return valid.minOfOrNull { it.minCellVoltage } to valid.maxOfOrNull { it.maxCellVoltage }
}
