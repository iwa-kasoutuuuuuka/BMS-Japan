package jp.bmsjapan.app.protocol

import androidx.annotation.StringRes
import jp.bmsjapan.app.R

enum class ChargeState { IDLE, CHARGING, DISCHARGING, UNKNOWN }

/** 運転データ（レジスタ 0〜61）を解析した結果 */
data class LiveData(
    val cellVoltages: List<Double>,      // V（セル数ぶん。33 セル以上は追加読み出しで後から埋まる）
    val temperatures: List<Int>,         // ℃（センサー数ぶん）
    val totalVoltage: Double,            // V
    val current: Double,                 // A（＋充電／−放電）
    val soc: Double,                     // %
    val maxCellVoltage: Double,          // V
    val minCellVoltage: Double,          // V
    val maxTemperature: Int,             // ℃
    val minTemperature: Int,             // ℃
    val state: ChargeState,
    val remainingCapacity: Double,       // Ah
    val cellCount: Int,
    val temperatureSensorCount: Int,
    val cycleCount: Int,
    val balancing: Boolean,
    val chargeMos: Boolean,
    val dischargeMos: Boolean,
    val averageCellVoltage: Double,      // V
    val cellVoltageDelta: Double,        // V
    val powerKw: Double,                 // kW
    val alarms: List<Alarm>,
) {
    companion object {
        const val REGISTER_COUNT = 62

        fun parse(r: IntArray): LiveData {
            require(r.size >= REGISTER_COUNT) { "live data needs $REGISTER_COUNT registers, got ${r.size}" }
            val cellCount = r[49]
            val tempCount = r[50].coerceIn(0, 8)
            return LiveData(
                cellVoltages = (0 until cellCount.coerceIn(0, 32)).map { r[it] * 0.001 },
                temperatures = (0 until tempCount).map { r[32 + it] - 40 },
                totalVoltage = r[40] * 0.1,
                current = (r[41] - 30000) * 0.1,
                soc = r[42] / 10.0,
                maxCellVoltage = r[43] / 1000.0,
                minCellVoltage = r[44] / 1000.0,
                maxTemperature = r[45] - 40,
                minTemperature = r[46] - 40,
                state = when (r[47]) {
                    0 -> ChargeState.IDLE
                    1 -> ChargeState.CHARGING
                    2 -> ChargeState.DISCHARGING
                    else -> ChargeState.UNKNOWN
                },
                remainingCapacity = r[48] * 0.1,
                cellCount = cellCount,
                temperatureSensorCount = tempCount,
                cycleCount = r[51],
                balancing = r[52] == 1,
                chargeMos = r[53] == 1,
                dischargeMos = r[54] == 1,
                averageCellVoltage = r[55] / 1000.0,
                cellVoltageDelta = r[56] / 1000.0,
                powerKw = r[57] / 1000.0,
                alarms = Alarm.decode(r[58], r[59], r[60], r[61]),
            )
        }
    }

    /** 33〜48 セル目（レジスタ 71〜86）を追加した結果を返す */
    fun withExtraCells(extra: IntArray): LiveData {
        val needed = (cellCount - 32).coerceIn(0, extra.size)
        if (needed == 0) return this
        return copy(cellVoltages = cellVoltages.take(32) + (0 until needed).map { extra[it] * 0.001 })
    }
}

/** アラームのビット定義（bit0 が最下位）。表示名は strings.xml */
enum class Alarm(val register: Int, val bit: Int, @param:StringRes val label: Int) {
    CELL_OV1(58, 0, R.string.alarm_cell_ov1), CELL_OV2(58, 1, R.string.alarm_cell_ov2),
    CELL_UV1(58, 2, R.string.alarm_cell_uv1), CELL_UV2(58, 3, R.string.alarm_cell_uv2),
    PACK_OV1(58, 4, R.string.alarm_pack_ov1), PACK_OV2(58, 5, R.string.alarm_pack_ov2),
    PACK_UV1(58, 6, R.string.alarm_pack_uv1), PACK_UV2(58, 7, R.string.alarm_pack_uv2),
    CHG_OT1(58, 8, R.string.alarm_chg_ot1), CHG_OT2(58, 9, R.string.alarm_chg_ot2),
    CHG_UT1(58, 10, R.string.alarm_chg_ut1), CHG_UT2(58, 11, R.string.alarm_chg_ut2),
    DSG_OT1(58, 12, R.string.alarm_dsg_ot1), DSG_OT2(58, 13, R.string.alarm_dsg_ot2),
    DSG_UT1(58, 14, R.string.alarm_dsg_ut1), DSG_UT2(58, 15, R.string.alarm_dsg_ut2),

    CHG_OC1(59, 0, R.string.alarm_chg_oc1), CHG_OC2(59, 1, R.string.alarm_chg_oc2),
    DSG_OC1(59, 2, R.string.alarm_dsg_oc1), DSG_OC2(59, 3, R.string.alarm_dsg_oc2),
    SOC_HIGH1(59, 4, R.string.alarm_soc_high1), SOC_HIGH2(59, 5, R.string.alarm_soc_high2),
    SOC_LOW1(59, 6, R.string.alarm_soc_low1), SOC_LOW2(59, 7, R.string.alarm_soc_low2),
    VDIFF1(59, 8, R.string.alarm_vdiff1), VDIFF2(59, 9, R.string.alarm_vdiff2),
    TDIFF1(59, 10, R.string.alarm_tdiff1), TDIFF2(59, 11, R.string.alarm_tdiff2),

    CHG_MOS_OT(60, 0, R.string.alarm_chg_mos_ot), DSG_MOS_OT(60, 1, R.string.alarm_dsg_mos_ot),
    CHG_MOS_SENSOR(60, 2, R.string.alarm_chg_mos_sensor), DSG_MOS_SENSOR(60, 3, R.string.alarm_dsg_mos_sensor),
    CHG_MOS_WELD(60, 4, R.string.alarm_chg_mos_weld), DSG_MOS_WELD(60, 5, R.string.alarm_dsg_mos_weld),
    CHG_MOS_SHORT(60, 6, R.string.alarm_chg_mos_short), DSG_MOS_SHORT(60, 7, R.string.alarm_dsg_mos_short),
    AFE(60, 8, R.string.alarm_afe), CELL_WIRE(60, 9, R.string.alarm_cell_wire),
    TEMP_SENSOR(60, 10, R.string.alarm_temp_sensor), EEPROM(60, 11, R.string.alarm_eeprom),
    RTC(60, 12, R.string.alarm_rtc), PRECHARGE(60, 13, R.string.alarm_precharge),
    VEHICLE_COMM(60, 14, R.string.alarm_vehicle_comm), INTERNAL_COMM(60, 15, R.string.alarm_internal_comm),

    CURRENT_MODULE(61, 0, R.string.alarm_current_module), VOLTAGE_MODULE(61, 1, R.string.alarm_voltage_module),
    SHORT_CIRCUIT(61, 2, R.string.alarm_short_circuit), LOW_V_NO_CHARGE(61, 3, R.string.alarm_low_v_no_charge),
    GPS_SOFT_SWITCH(61, 4, R.string.alarm_gps_soft_switch), CHARGER_REMOVED(61, 5, R.string.alarm_charger_removed),
    THERMAL_RUNAWAY(61, 6, R.string.alarm_thermal_runaway),
    BALANCER_COMM(61, 8, R.string.alarm_balancer_comm), BALANCE_CONDITION(61, 9, R.string.alarm_balance_condition);

    companion object {
        fun decode(r58: Int, r59: Int, r60: Int, r61: Int): List<Alarm> {
            val regs = mapOf(58 to r58, 59 to r59, 60 to r60, 61 to r61)
            return entries.filter { a -> (regs.getValue(a.register) ushr a.bit) and 1 == 1 }
        }
    }
}
