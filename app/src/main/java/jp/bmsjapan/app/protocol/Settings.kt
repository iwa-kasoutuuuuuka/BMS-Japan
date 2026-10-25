package jp.bmsjapan.app.protocol

import androidx.annotation.StringRes
import jp.bmsjapan.app.R
import kotlin.math.roundToInt

/**
 * 生の値 ⇄ 表示値の変換。
 * 表示値 = (raw − offset) × scale × sign
 */
data class Encoding(val scale: Double, val offset: Int = 0, val sign: Int = 1) {
    fun decode(raw: Int): Double = (raw - offset) * scale * sign
    fun encode(value: Double): Int = (value / (scale * sign)).roundToInt() + offset

    companion object {
        val RAW = Encoding(1.0)
        val MILLI = Encoding(0.001)
        val DECI = Encoding(0.1)
        val TEMPERATURE = Encoding(1.0, offset = 40)

        /** 充電電流。この BMS では充電を 30000 より小さい値で表す */
        val CHARGE_CURRENT = Encoding(0.1, offset = 30000, sign = -1)

        /** 放電電流。30000 より大きい値で表す */
        val DISCHARGE_CURRENT = Encoding(0.1, offset = 30000)
    }
}

enum class SettingGroup(@param:StringRes val title: Int) {
    VOLTAGE(R.string.group_voltage),
    CURRENT(R.string.group_current),
    TEMPERATURE(R.string.group_temperature),
    BATTERY(R.string.group_battery),
    BALANCE(R.string.group_balance),
    INFO(R.string.group_info),
}

/** 解除値との関係（保護値が解除値をまたがないようにする） */
enum class ReleaseRule { NONE, ABOVE_RELEASE, BELOW_RELEASE }

/**
 * 設定項目の定義。範囲（min〜max）は元アプリには無く、誤設定を防ぐためにこのアプリで決めたもの。
 */
enum class Setting(
    val register: Int,
    @param:StringRes val label: Int,
    val group: SettingGroup,
    val encoding: Encoding,
    val decimals: Int,
    val unit: String,
    val min: Double,
    val max: Double,
    /** 対になる解除値のレジスタ（無ければ null） */
    val releaseRegister: Int? = null,
    val releaseRule: ReleaseRule = ReleaseRule.NONE,
    val editable: Boolean = true,
) {
    CELL_OV(140, R.string.set_cell_ov, SettingGroup.VOLTAGE, Encoding.MILLI, 3, "V", 2.0, 4.5, 139, ReleaseRule.ABOVE_RELEASE),
    CELL_UV(142, R.string.set_cell_uv, SettingGroup.VOLTAGE, Encoding.MILLI, 3, "V", 1.5, 3.5, 141, ReleaseRule.BELOW_RELEASE),
    PACK_OV(144, R.string.set_pack_ov, SettingGroup.VOLTAGE, Encoding.DECI, 1, "V", 1.0, 300.0, 143, ReleaseRule.ABOVE_RELEASE),
    PACK_UV(146, R.string.set_pack_uv, SettingGroup.VOLTAGE, Encoding.DECI, 1, "V", 1.0, 300.0, 145, ReleaseRule.BELOW_RELEASE),
    CELL_DIFF(160, R.string.set_cell_diff, SettingGroup.VOLTAGE, Encoding.MILLI, 3, "V", 0.010, 1.000, 159, ReleaseRule.ABOVE_RELEASE),

    CHARGE_OC(148, R.string.set_charge_oc, SettingGroup.CURRENT, Encoding.CHARGE_CURRENT, 1, "A", 1.0, 500.0, 147),
    DISCHARGE_OC(150, R.string.set_discharge_oc, SettingGroup.CURRENT, Encoding.DISCHARGE_CURRENT, 1, "A", 1.0, 500.0, 149),

    CHARGE_OT(152, R.string.set_charge_ot, SettingGroup.TEMPERATURE, Encoding.TEMPERATURE, 0, "℃", 0.0, 100.0, 151, ReleaseRule.ABOVE_RELEASE),
    CHARGE_UT(154, R.string.set_charge_ut, SettingGroup.TEMPERATURE, Encoding.TEMPERATURE, 0, "℃", -40.0, 30.0, 153, ReleaseRule.BELOW_RELEASE),
    DISCHARGE_OT(156, R.string.set_discharge_ot, SettingGroup.TEMPERATURE, Encoding.TEMPERATURE, 0, "℃", 0.0, 100.0, 155, ReleaseRule.ABOVE_RELEASE),
    DISCHARGE_UT(158, R.string.set_discharge_ut, SettingGroup.TEMPERATURE, Encoding.TEMPERATURE, 0, "℃", -40.0, 30.0, 157, ReleaseRule.BELOW_RELEASE),
    TEMP_DIFF(162, R.string.set_temp_diff, SettingGroup.TEMPERATURE, Encoding.RAW, 0, "℃", 1.0, 50.0, 161, ReleaseRule.ABOVE_RELEASE),
    MOS_OT(168, R.string.set_mos_ot, SettingGroup.TEMPERATURE, Encoding.TEMPERATURE, 0, "℃", 30.0, 120.0),

    RATED_CAPACITY(128, R.string.set_rated_capacity, SettingGroup.BATTERY, Encoding.DECI, 1, "Ah", 1.0, 2000.0),
    CELL_NOMINAL(129, R.string.set_cell_nominal, SettingGroup.BATTERY, Encoding.MILLI, 3, "V", 1.5, 4.5),
    SOC(167, R.string.set_soc, SettingGroup.BATTERY, Encoding.DECI, 1, "%", 0.0, 100.0),
    SLEEP_WAIT(138, R.string.set_sleep_wait, SettingGroup.BATTERY, Encoding.RAW, 0, "秒", 0.0, 65535.0),

    BALANCE_START_V(163, R.string.set_balance_start_v, SettingGroup.BALANCE, Encoding.MILLI, 3, "V", 2.0, 4.5),
    BALANCE_START_DIFF(164, R.string.set_balance_start_diff, SettingGroup.BALANCE, Encoding.MILLI, 3, "V", 0.001, 0.500),
    BALANCE_CURRENT(208, R.string.set_balance_current, SettingGroup.BALANCE, Encoding.DECI, 1, "A", 0.1, 20.0),

    // 機器の構成に関わる値は、誤って変えると計測が狂うため表示だけにする
    BOARD1_CELLS(131, R.string.set_board1_cells, SettingGroup.INFO, Encoding.RAW, 0, "", 0.0, 0.0, editable = false),
    BOARD2_CELLS(132, R.string.set_board2_cells, SettingGroup.INFO, Encoding.RAW, 0, "", 0.0, 0.0, editable = false),
    BOARD3_CELLS(133, R.string.set_board3_cells, SettingGroup.INFO, Encoding.RAW, 0, "", 0.0, 0.0, editable = false),
    TEMP_SENSORS(134, R.string.set_temp_sensors, SettingGroup.INFO, Encoding.RAW, 0, "", 0.0, 0.0, editable = false),
    ;

    fun format(value: Double): String = "%.${decimals}f".format(value)
}

/** ON/OFF で切り替える項目 */
enum class SwitchSetting(val register: Int, @param:StringRes val label: Int, @param:StringRes val confirmOn: Int, @param:StringRes val confirmOff: Int) {
    CHARGE_MOS(165, R.string.label_charge_mos, R.string.confirm_charge_on, R.string.confirm_charge_off),
    DISCHARGE_MOS(166, R.string.label_discharge_mos, R.string.confirm_discharge_on, R.string.confirm_discharge_off),
    BALANCE(207, R.string.label_balancing, R.string.confirm_balance_on, R.string.confirm_balance_off),
}

enum class BatteryType(val code: Int, @param:StringRes val label: Int, val cellOvRelease: Double, val cellOv: Double, val cellUvRelease: Double, val cellUv: Double) {
    LFP(0, R.string.battery_lfp, 3.55, 3.60, 2.65, 2.60),
    NCM(1, R.string.battery_ncm, 4.10, 4.20, 2.85, 2.82),
    LTO(2, R.string.battery_lto, 2.65, 2.70, 1.85, 1.80),
    ;

    /** 元アプリと同じ条件: 20 セルを超えると三元系、6 セル未満だとチタン酸は選べない */
    fun availableFor(cellCount: Int): Boolean = when (this) {
        NCM -> cellCount <= 20
        LTO -> cellCount >= 6
        LFP -> true
    }

    companion object {
        fun fromCode(code: Int): BatteryType? = entries.firstOrNull { it.code == code }
    }
}

/** 読み出した設定値（レジスタ番号 → 生の値） */
data class BmsSettings(val raw: Map<Int, Int>) {
    operator fun get(s: Setting): Double? = raw[s.register]?.let(s.encoding::decode)
    fun release(s: Setting): Double? = s.releaseRegister?.let { r -> raw[r]?.let(s.encoding::decode) }
    fun switch(s: SwitchSetting): Boolean? = raw[s.register]?.let { it != 0 }
    val batteryType: BatteryType? get() = raw[137]?.let(BatteryType::fromCode)

    fun merge(start: Int, regs: IntArray): BmsSettings =
        BmsSettings(raw + regs.withIndex().associate { (i, v) -> start + i to v })

    companion object {
        const val MAIN_START = 128
        const val MAIN_COUNT = 41        // 128〜168
        const val BALANCE_START = 204
        const val BALANCE_COUNT = 5      // 204〜208
        val EMPTY = BmsSettings(emptyMap())
    }
}

sealed interface ValidationResult {
    data class Ok(val raw: Int) : ValidationResult
    data object NotANumber : ValidationResult
    data class OutOfRange(val min: String, val max: String) : ValidationResult
    data class MustBeAbove(val release: String) : ValidationResult
    data class MustBeBelow(val release: String) : ValidationResult
}

fun Setting.validate(input: String, current: BmsSettings): ValidationResult {
    val v = input.trim().replace(',', '.').toDoubleOrNull() ?: return ValidationResult.NotANumber
    // "NaN" や "Infinity" も数値として読めてしまうので、ここで弾く（範囲の比較をすり抜けるため）
    if (!v.isFinite()) return ValidationResult.NotANumber
    if (v < min - 1e-9 || v > max + 1e-9) return ValidationResult.OutOfRange(format(min), format(max))
    val release = current.release(this)
    if (release != null) {
        when (releaseRule) {
            ReleaseRule.ABOVE_RELEASE -> if (v <= release) return ValidationResult.MustBeAbove(format(release))
            ReleaseRule.BELOW_RELEASE -> if (v >= release) return ValidationResult.MustBeBelow(format(release))
            ReleaseRule.NONE -> Unit
        }
    }
    val raw = encoding.encode(v)
    if (raw !in 0..0xFFFF) return ValidationResult.OutOfRange(format(min), format(max))
    return ValidationResult.Ok(raw)
}

/**
 * 電池の種類を変えるときの一括書き込み（元アプリと同じく 137 から 27 レジスタ）。
 * セル過電圧・低電圧・総電圧の保護/解除値と均等化開始電圧をプリセットに置き換え、それ以外は今の値をそのまま送り返す。
 */
fun buildBatteryPreset(type: BatteryType, cellCount: Int, current: BmsSettings): IntArray? {
    // セル数は BMS から読んだ値。0 や異常に大きい値だと総電圧の保護値が 0 V などになるので書かない
    if (cellCount !in MIN_PRESET_CELLS..MAX_PRESET_CELLS) return null
    if (!type.availableFor(cellCount)) return null
    val start = 137
    val count = 27
    val values = IntArray(count)
    for (i in 0 until count) values[i] = current.raw[start + i] ?: return null
    fun set(reg: Int, v: Int) { values[reg - start] = v }
    fun mv(v: Double) = (v * 1000).roundToInt()
    fun packDeci(perCell: Double) = (perCell * cellCount * 10).roundToInt()
    set(137, type.code)
    set(139, mv(type.cellOvRelease)); set(140, mv(type.cellOv))
    set(141, mv(type.cellUvRelease)); set(142, mv(type.cellUv))
    set(143, packDeci(type.cellOvRelease)); set(144, packDeci(type.cellOv))
    set(145, packDeci(type.cellUvRelease)); set(146, packDeci(type.cellUv))
    set(163, 3000)
    return values
}

/**
 * 実容量（スレーブ 0x81 のレジスタ 267〜268、32bit、mAh）。
 * 画面では Ah で扱う。
 */
object ActualCapacity {
    const val REGISTER = 267
    const val COUNT = 2
    const val MIN_AH = 1.0
    const val MAX_AH = 2000.0

    fun decode(regs: IntArray): Int = (regs[0] shl 16) or regs[1]

    fun encode(mAh: Int): IntArray = intArrayOf((mAh ushr 16) and 0xFFFF, mAh and 0xFFFF)

    fun format(mAh: Int): String = "%.1f".format(mAh / 1000.0)

    /** 入力（Ah）を確認して mAh を返す。だめなら null */
    fun parse(input: String): Int? {
        val ah = input.trim().replace(',', '.').toDoubleOrNull() ?: return null
        if (!ah.isFinite()) return null
        if (ah < MIN_AH - 1e-9 || ah > MAX_AH + 1e-9) return null
        return (ah * 1000).roundToInt()
    }
}

/** 電池の種類のプリセットを書いてよいセル数（この BMS は最大 48 セル） */
const val MIN_PRESET_CELLS = 1
const val MAX_PRESET_CELLS = 48

/** パスワード（レジスタ 201〜203、ASCII 6 文字） */
object BmsPassword {
    const val START = 201
    const val COUNT = 3
    const val DEFAULT = "123456"

    fun decode(regs: IntArray): String = buildString {
        for (r in regs) {
            append(((r ushr 8) and 0xFF).toChar())
            append((r and 0xFF).toChar())
        }
    }.trimEnd('\u0000', ' ')

    /** 6 桁の数字を 3 レジスタ（ASCII 2 文字ずつ）にする */
    fun encode(pwd: String): IntArray {
        require(isValidFormat(pwd)) { "password must be 6 digits" }
        return IntArray(COUNT) { i -> (pwd[i * 2].code shl 8) or pwd[i * 2 + 1].code }
    }

    /**
     * 元アプリの入力欄は「数字のみ・最大 6 桁」。
     * 6 桁未満だと 3 レジスタ分のデータが足りないフレームになってしまうため、このアプリでは 6 桁ちょうどにする。
     */
    fun isValidFormat(pwd: String): Boolean = pwd.length == COUNT * 2 && pwd.all { it in '0'..'9' }

    fun validateChange(current: String?, old: String, new: String, repeat: String): PasswordChangeCheck = when {
        old.isEmpty() || new.isEmpty() || repeat.isEmpty() -> PasswordChangeCheck.EMPTY
        current == null || old != current -> PasswordChangeCheck.WRONG_OLD
        !isValidFormat(new) -> PasswordChangeCheck.BAD_FORMAT
        new != repeat -> PasswordChangeCheck.MISMATCH
        new == old -> PasswordChangeCheck.SAME_AS_OLD
        else -> PasswordChangeCheck.OK
    }
}

enum class PasswordChangeCheck { OK, EMPTY, WRONG_OLD, BAD_FORMAT, MISMATCH, SAME_AS_OLD }
