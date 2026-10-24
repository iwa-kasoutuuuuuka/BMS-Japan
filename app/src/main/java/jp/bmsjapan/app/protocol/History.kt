package jp.bmsjapan.app.protocol

import androidx.annotation.StringRes
import jp.bmsjapan.app.R
import java.time.LocalDateTime

/**
 * 故障履歴 1 件（34 バイト）。
 *
 * バイト位置: 0-1 番号 / 2-3 総件数 / 4-5 長さ / 6-11 年月日時分秒 / 12 イベントID / 13 発生(≠0)・解除(0)
 * / 14-15 総電圧×0.1 / 16-17 電流 (値−30000)×0.1 / 18-19 SOC×0.1 / 20 MOS状態 (bit0 充電, bit1 放電)
 * / 21-22 最高セル電圧 mV / 23 その位置 / 24-25 最低セル電圧 mV / 26 その位置
 * / 27 最高温度 (−40) / 28 その位置 / 29 最低温度 (−40) / 30 その位置 / 31 故障コード
 */
data class HistoryRecord(
    val number: Int,
    val totalCount: Int,
    val time: LocalDateTime?,
    val eventId: Int,
    val occurred: Boolean,
    val totalVoltage: Double,
    val current: Double,
    val soc: Double,
    val chargeMos: Boolean,
    val dischargeMos: Boolean,
    val maxCellVoltage: Double,
    val maxCellIndex: Int,
    val minCellVoltage: Double,
    val minCellIndex: Int,
    val maxTemperature: Int,
    val maxTemperatureIndex: Int,
    val minTemperature: Int,
    val minTemperatureIndex: Int,
    val faultCode: Int,
) {
    val event: HistoryEvent get() = HistoryEvent.of(eventId)
}

object HistoryProtocol {
    /** 履歴の読み出しアドレスは「256 + ページ番号」。1 ページ 127 レジスタ = 254 バイトに 7 件 */
    const val BASE_ADDRESS = 256
    const val PAGE_REGISTERS = 127
    const val RECORDS_PER_PAGE = 7
    const val RECORD_BYTES = 34

    fun pageAddress(page: Int): Int = BASE_ADDRESS + page

    /** 1 ページ分を解析する。番号 0 の記録が出たら、そこで履歴は終わり */
    fun parsePage(registers: IntArray): PageResult {
        val bytes = ByteArray(registers.size * 2)
        registers.forEachIndexed { i, r ->
            bytes[i * 2] = (r ushr 8).toByte()
            bytes[i * 2 + 1] = r.toByte()
        }
        val out = mutableListOf<HistoryRecord>()
        var offset = 0
        while (offset + RECORD_BYTES <= bytes.size) {
            val rec = parseRecord(bytes, offset) ?: return PageResult(out, reachedEnd = true)
            out += rec
            offset += RECORD_BYTES
        }
        return PageResult(out, reachedEnd = false)
    }

    data class PageResult(val records: List<HistoryRecord>, val reachedEnd: Boolean)

    private fun parseRecord(b: ByteArray, o: Int): HistoryRecord? {
        fun u8(i: Int) = b[o + i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        val number = u16(0)
        if (number == 0) return null
        val mos = u8(20)
        return HistoryRecord(
            number = number,
            totalCount = u16(2),
            time = runCatching { LocalDateTime.of(2000 + u8(6), u8(7), u8(8), u8(9), u8(10), u8(11)) }.getOrNull(),
            eventId = u8(12),
            occurred = u8(13) != 0,
            totalVoltage = u16(14) * 0.1,
            current = (u16(16) - 30000) * 0.1,
            soc = u16(18) * 0.1,
            chargeMos = mos and 0x01 != 0,
            dischargeMos = mos and 0x02 != 0,
            maxCellVoltage = u16(21) / 1000.0,
            maxCellIndex = u8(23),
            minCellVoltage = u16(24) / 1000.0,
            minCellIndex = u8(26),
            maxTemperature = u8(27) - 40,
            maxTemperatureIndex = u8(28),
            minTemperature = u8(29) - 40,
            minTemperatureIndex = u8(30),
            faultCode = u8(31),
        )
    }
}

/** イベント名。予備の番号などは引数つきの共通の文言にする */
data class HistoryEvent(@param:StringRes val label: Int, val arg: Int? = null) {
    companion object {
        private val names: Map<Int, Int> = mapOf(
            0 to R.string.ev_pack_ov1, 1 to R.string.ev_pack_uv1, 2 to R.string.ev_soc_high1, 3 to R.string.ev_soc_low1,
            4 to R.string.ev_chg_oc1, 5 to R.string.ev_dsg_oc1, 6 to R.string.ev_dsg_ot1, 7 to R.string.ev_dsg_ut1,
            8 to R.string.ev_cell_ov1, 9 to R.string.ev_cell_uv1, 10 to R.string.ev_vdiff1, 11 to R.string.ev_tdiff1,
            12 to R.string.ev_pack_ov2, 13 to R.string.ev_pack_uv2, 14 to R.string.ev_soc_high2, 15 to R.string.ev_soc_low2,
            16 to R.string.ev_chg_oc2, 17 to R.string.ev_dsg_oc2, 18 to R.string.ev_cell_ov2, 19 to R.string.ev_cell_uv2,
            20 to R.string.ev_vdiff2, 21 to R.string.ev_dsg_ot2, 22 to R.string.ev_dsg_ut2, 23 to R.string.ev_tdiff2,
            24 to R.string.ev_chg_ut1, 25 to R.string.ev_chg_ut2, 26 to R.string.ev_chg_ot1, 27 to R.string.ev_chg_ot2,
            28 to R.string.ev_dsg_contactor_record, 29 to R.string.ev_chg_contactor_record, 30 to R.string.ev_pre_contactor_record,
            31 to R.string.ev_relay_switch_count, 32 to R.string.ev_dsg_mos_ot, 33 to R.string.ev_chg_mos_ot,
            34 to R.string.ev_dsg_mos_sensor, 35 to R.string.ev_chg_mos_sensor, 36 to R.string.ev_dsg_contactor_weld,
            37 to R.string.ev_chg_contactor_weld, 38 to R.string.ev_dsg_contactor_open, 39 to R.string.ev_chg_contactor_open,
            40 to R.string.ev_precharge_failed, 41 to R.string.ev_upgrade_record, 42 to R.string.ev_daisy_chain_init,
            43 to R.string.ev_afe_fault, 44 to R.string.ev_cell_wire_off, 45 to R.string.ev_temp_sensor, 46 to R.string.ev_eeprom,
            47 to R.string.ev_rtc, 48 to R.string.ev_vehicle_comm,
            59 to R.string.ev_pack_ov3, 60 to R.string.ev_pack_uv3, 61 to R.string.ev_soc_high3, 62 to R.string.ev_soc_low3,
            63 to R.string.ev_chg_oc3, 64 to R.string.ev_dsg_oc3, 65 to R.string.ev_temp_high3, 66 to R.string.ev_temp_low3,
            67 to R.string.ev_cell_ov3, 68 to R.string.ev_cell_uv3, 69 to R.string.ev_vdiff3, 70 to R.string.ev_tdiff3,
            71 to R.string.ev_chg_ut3, 72 to R.string.ev_chg_ot3, 73 to R.string.ev_thermal_runaway, 74 to R.string.ev_heating_fault,
            75 to R.string.ev_charging_state, 76 to R.string.ev_discharging_state, 77 to R.string.ev_mos_ot1, 78 to R.string.ev_mos_ot2,
            79 to R.string.ev_ambient_ot1, 80 to R.string.ev_ambient_ot2, 81 to R.string.ev_balancer_comm_lost,
            82 to R.string.ev_soh_low1, 83 to R.string.ev_soh_low2, 84 to R.string.ev_soh_low3,
            122 to R.string.ev_smart_charger_ok, 123 to R.string.ev_smart_charger_ng, 124 to R.string.ev_smart_cabinet_ok,
            125 to R.string.ev_smart_cabinet_ng, 126 to R.string.ev_chg_mos_ot, 127 to R.string.ev_chg_mos_sensor,
            128 to R.string.ev_dsg_mos_ot, 129 to R.string.ev_dsg_mos_sensor, 130 to R.string.ev_short_circuit,
            131 to R.string.ev_upgrade_flag, 132 to R.string.ev_low_v_no_charge, 133 to R.string.ev_high_v_no_discharge,
            134 to R.string.ev_parallel_comm_ok, 135 to R.string.ev_short_circuit,
            144 to R.string.ev_afe_chip_fault, 145 to R.string.ev_afe_comm_fault, 146 to R.string.ev_afe_sampling_fault,
            147 to R.string.ev_voltage_acq_fault, 148 to R.string.ev_voltage_wire_off, 149 to R.string.ev_mos_temp_sensor,
            150 to R.string.ev_current_sensor, 151 to R.string.ev_temp_acq_fault, 152 to R.string.ev_temp_wire_off,
            153 to R.string.ev_eeprom, 154 to R.string.ev_flash, 155 to R.string.ev_rtc,
            156 to R.string.ev_chg_mos_fault, 157 to R.string.ev_dsg_mos_fault, 158 to R.string.ev_pre_mos_fault,
            159 to R.string.ev_precharge_failed, 160 to R.string.ev_cmd_chg_mos_off, 161 to R.string.ev_cmd_dsg_mos_off,
            162 to R.string.ev_switch_chg_mos_off, 163 to R.string.ev_switch_dsg_mos_off, 167 to R.string.ev_heating_abnormal,
            253 to R.string.ev_realtime_snapshot, 254 to R.string.ev_power_off, 255 to R.string.ev_power_on,
        )

        private val reserved = setOf(136, 137, 138, 139, 140, 141, 142, 143, 164, 165, 166, 168, 169)

        fun of(id: Int): HistoryEvent = when (id) {
            in names -> HistoryEvent(names.getValue(id))
            in 49..58 -> HistoryEvent(R.string.ev_reserved_signal, id - 48)
            in reserved -> HistoryEvent(R.string.ev_reserved, id)
            else -> HistoryEvent(R.string.ev_unknown, id)
        }
    }
}
