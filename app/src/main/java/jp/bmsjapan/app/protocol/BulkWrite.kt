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

    /**
     * 電池の種類のプリセット。
     * @param expectedType 開いている台の今の種類。これと違う種類の台は、確認した内容と違うので書かない
     */
    data class Battery(val type: BatteryType, val expectedType: BatteryType?) : BulkOp

    /**
     * おすすめ設定（日産リーフなど）。
     * @param expectedType 開いている台の今の種類。これと違う種類の台は、確認した内容と違うので書かない
     */
    data class Recommended(
        val preset: RecommendedPreset,
        val expectedType: BatteryType?,
        val options: PresetOptions = PresetOptions(),
    ) : BulkOp
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

    /** BMS がセルの値から自動で決める項目（書き込んでも無視される） */
    BMS_DERIVED,

    /** 運転データ（セル電圧・温度）が読めず、書き込み直後に保護が働くか判定できない */
    NO_LIVE_DATA,

    /** 電池の種類が、開いている台（確認した内容）と違う */
    CHEMISTRY_MISMATCH,

    /** 電池の種類が変わることを了承していない */
    CHEMISTRY_NOT_ACCEPTED,

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
 * @param conditions その台の今のセル電圧・温度（おすすめ設定で、書き込み直後に保護が働くかの判定に使う）
 * @param acceptImmediateEffect 書き込み直後に保護が働くことを利用者が了承したか
 * @param acceptChemistryChange おすすめ設定で電池の種類が変わることを利用者が了承したか
 */
fun planBulk(
    op: BulkOp,
    settings: BmsSettings,
    liveCells: Int?,
    conditions: CellConditions = CellConditions.UNKNOWN,
    acceptImmediateEffect: Boolean = false,
    acceptChemistryChange: Boolean = false,
): BulkPlan = when (op) {
    is BulkOp.SetValue -> if (op.setting.bmsDerived) BulkPlan.Rejected(BulkSkip.BMS_DERIVED) else when (val r = op.setting.validateValue(op.value, settings, liveCells)) {
        is ValidationResult.Ok -> BulkPlan.Ok(listOf(WriteStep(op.setting.register, intArrayOf(r.raw))))
        ValidationResult.NeedsCellCount -> BulkPlan.Rejected(BulkSkip.BAD_CELLS)
        else -> BulkPlan.Rejected(BulkSkip.OUT_OF_RANGE)
    }
    is BulkOp.Switch -> BulkPlan.Ok(listOf(WriteStep(op.sw.register, intArrayOf(if (op.on) 1 else 0))))
    is BulkOp.Battery -> {
        val cells = SafetyLimits.presetCellCount(settings, liveCells)
        when {
            !presetRegistersPresent(settings) -> BulkPlan.Rejected(BulkSkip.READ_FAILED)
            settings.batteryType != op.expectedType -> BulkPlan.Rejected(BulkSkip.CHEMISTRY_MISMATCH)
            cells == null || !op.type.availableFor(cells) -> BulkPlan.Rejected(BulkSkip.BAD_CELLS)
            else -> buildBatteryPresetSteps(op.type, cells, settings)?.let { BulkPlan.Ok(it) }
                ?: BulkPlan.Rejected(BulkSkip.OUT_OF_RANGE)
        }
    }
    is BulkOp.Recommended -> {
        val cells = SafetyLimits.presetCellCount(settings, liveCells)
        val plan = cells?.let { buildRecommendedPlan(op.preset, it, settings, op.options) }
        when {
            !presetRegistersPresent(settings) -> BulkPlan.Rejected(BulkSkip.READ_FAILED)
            settings.batteryType != op.expectedType -> BulkPlan.Rejected(BulkSkip.CHEMISTRY_MISMATCH)
            cells == null || !op.preset.type.availableFor(cells) -> BulkPlan.Rejected(BulkSkip.BAD_CELLS)
            plan == null -> BulkPlan.Rejected(BulkSkip.OUT_OF_RANGE)
            plan.changesChemistry && !acceptChemistryChange -> BulkPlan.Rejected(BulkSkip.CHEMISTRY_NOT_ACCEPTED)
            // 了承済みでなければ、セル電圧が読めない台には書かない（判定をすり抜けないように）
            !acceptImmediateEffect && conditions.minCellV == null -> BulkPlan.Rejected(BulkSkip.NO_LIVE_DATA)
            !acceptImmediateEffect && plan.immediateEffect(conditions) != PresetImmediateEffect.NONE ->
                BulkPlan.Rejected(BulkSkip.IMMEDIATE_EFFECT)
            else -> BulkPlan.Ok(plan.steps)
        }
    }
}

private fun presetRegistersPresent(s: BmsSettings): Boolean = (137 until 137 + 27).all { s.raw[it] != null }


/** 書いた値と、書いた後に読み直した値が違ったレジスタ（BMS が受け付けなかった・自分で直した） */
data class RegisterMismatch(val register: Int, val wrote: Int, val read: Int?)

/** 書き込んだ内容と、読み直した値を比べる。readBack が null を返したレジスタ（読めなかった）も食い違いとして返す */
fun compareWritten(
    steps: List<WriteStep>,
    ignore: Set<Int> = emptySet(),
    readBack: (WriteStep) -> IntArray?,
): List<RegisterMismatch> =
    steps.flatMap { st ->
        val got = readBack(st)
        st.values.indices.mapNotNull { i ->
            val r = got?.getOrNull(i)
            if (st.start + i !in ignore && r != st.values[i]) RegisterMismatch(st.start + i, st.values[i], r) else null
        }
    }

/** 表示用: そのレジスタの項目名と、生の値を表示値にする方法 */
data class RegisterDescription(
    @param:androidx.annotation.StringRes val label: Int,
    val isRelease: Boolean,
    val format: (Int) -> String,
)

fun describeRegister(register: Int): RegisterDescription? {
    Setting.entries.firstOrNull { it.register == register }?.let { s ->
        return RegisterDescription(s.label, false) { "${s.format(s.encoding.decode(it))} ${s.unit}".trim() }
    }
    Setting.entries.firstOrNull { it.releaseRegister == register }?.let { s ->
        return RegisterDescription(s.label, true) { "${s.format(s.encoding.decode(it))} ${s.unit}".trim() }
    }
    SwitchSetting.entries.firstOrNull { it.register == register }?.let { sw ->
        return RegisterDescription(sw.label, false) { if (it != 0) "ON" else "OFF" }
    }
    if (register == 137) {
        return RegisterDescription(jp.bmsjapan.app.R.string.label_battery_type, false) { BatteryType.fromCode(it)?.name ?: it.toString() }
    }
    return null
}
