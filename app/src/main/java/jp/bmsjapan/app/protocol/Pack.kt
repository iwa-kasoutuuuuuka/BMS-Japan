package jp.bmsjapan.app.protocol

import androidx.annotation.StringRes
import jp.bmsjapan.app.R
import kotlin.math.abs

/** 複数台のつなぎ方（元アプリの CONNECT_TYPE: 0 単体 / 1 並列 / 2 直列） */
enum class PackMode(val code: Int, @param:StringRes val label: Int, val maxUnits: Int) {
    SINGLE(0, R.string.mode_single, 1),
    PARALLEL(1, R.string.mode_parallel, 6),
    SERIES(2, R.string.mode_series, 6),
    ;

    companion object {
        fun fromCode(code: Int?): PackMode = entries.firstOrNull { it.code == code } ?: SINGLE
    }
}

/** 全体の状態。台ごとに充電・放電が分かれているときは MIXED */
enum class PackState { IDLE, CHARGING, DISCHARGING, MIXED }

/** 複数台をまとめた値 */
data class PackSummary(
    val mode: PackMode,
    val unitCount: Int,
    val voltage: Double,          // V
    val current: Double,          // A（大きさ）
    val powerKw: Double,          // kW
    val remainingAh: Double,      // Ah
    val soc: Double,              // %
    val state: PackState,
    val maxCellVoltage: Double,   // V（全台の中で最高）
    val minCellVoltage: Double,   // V（全台の中で最低）
    val maxTemperature: Int,
    val minTemperature: Int,
    val alarmCount: Int,          // アラームが出ている台数
) {
    val cellVoltageDelta: Double get() = maxCellVoltage - minCellVoltage

    companion object {
        /**
         * まとめ方:
         * - 並列: 電圧は平均、電流と残り容量は合計、SOC は平均（元アプリと同じ）
         * - 直列: 電圧は合計、電流は平均、SOC は最も低い台（元アプリと同じ）
         *         残り容量は最も少ない台（直列では一番少ない台で使える量が決まるため。元アプリは合計していた）
         * - 単体: 1 台の値そのまま
         * 電力はどの場合も合計。
         */
        fun of(mode: PackMode, units: List<LiveData>): PackSummary? {
            if (units.isEmpty()) return null
            val n = units.size
            val currents = units.map { abs(it.current) }
            val voltage = when (mode) {
                PackMode.SERIES -> units.sumOf { it.totalVoltage }
                else -> units.sumOf { it.totalVoltage } / n
            }
            val current = when (mode) {
                PackMode.PARALLEL -> currents.sum()
                else -> currents.sum() / n
            }
            val remaining = when (mode) {
                PackMode.SERIES -> units.minOf { it.remainingCapacity }
                else -> units.sumOf { it.remainingCapacity }
            }
            val soc = when (mode) {
                PackMode.SERIES -> units.minOf { it.soc }
                else -> units.sumOf { it.soc } / n
            }
            return PackSummary(
                mode = mode,
                unitCount = n,
                voltage = voltage,
                current = current,
                powerKw = units.sumOf { it.powerKw },
                remainingAh = remaining,
                soc = soc,
                state = combinedState(units.map { it.state }),
                maxCellVoltage = units.maxOf { it.maxCellVoltage },
                minCellVoltage = units.minOf { it.minCellVoltage },
                maxTemperature = units.maxOf { it.maxTemperature },
                minTemperature = units.minOf { it.minTemperature },
                alarmCount = units.count { it.alarms.isNotEmpty() },
            )
        }

        fun combinedState(states: List<ChargeState>): PackState {
            val charging = states.any { it == ChargeState.CHARGING }
            val discharging = states.any { it == ChargeState.DISCHARGING }
            return when {
                charging && discharging -> PackState.MIXED
                charging -> PackState.CHARGING
                discharging -> PackState.DISCHARGING
                else -> PackState.IDLE
            }
        }
    }
}
