"""故障履歴。Kotlin 版 ``History.kt`` の移植。

1 件 34 バイト:
0-1 番号 / 2-3 総件数 / 4-5 長さ / 6-11 年月日時分秒 / 12 イベントID / 13 発生(≠0)・解除(0)
/ 14-15 総電圧×0.1 / 16-17 電流 (値−30000)×0.1 / 18-19 SOC×0.1 / 20 MOS状態 (bit0 充電, bit1 放電)
/ 21-22 最高セル電圧 mV / 23 その位置 / 24-25 最低セル電圧 mV / 26 その位置
/ 27 最高温度 (−40) / 28 その位置 / 29 最低温度 (−40) / 30 その位置 / 31 故障コード
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from collections.abc import Sequence

from ..labels import t

#: 履歴の読み出しアドレスは「256 + ページ番号」
BASE_ADDRESS = 256
#: 1 ページ 127 レジスタ = 254 バイトに 7 件
PAGE_REGISTERS = 127
RECORDS_PER_PAGE = 7
RECORD_BYTES = 34


def page_address(page: int) -> int:
    return BASE_ADDRESS + page


_NAMES: dict[int, str] = {
    0: "ev_pack_ov1", 1: "ev_pack_uv1", 2: "ev_soc_high1", 3: "ev_soc_low1",
    4: "ev_chg_oc1", 5: "ev_dsg_oc1", 6: "ev_dsg_ot1", 7: "ev_dsg_ut1",
    8: "ev_cell_ov1", 9: "ev_cell_uv1", 10: "ev_vdiff1", 11: "ev_tdiff1",
    12: "ev_pack_ov2", 13: "ev_pack_uv2", 14: "ev_soc_high2", 15: "ev_soc_low2",
    16: "ev_chg_oc2", 17: "ev_dsg_oc2", 18: "ev_cell_ov2", 19: "ev_cell_uv2",
    20: "ev_vdiff2", 21: "ev_dsg_ot2", 22: "ev_dsg_ut2", 23: "ev_tdiff2",
    24: "ev_chg_ut1", 25: "ev_chg_ut2", 26: "ev_chg_ot1", 27: "ev_chg_ot2",
    28: "ev_dsg_contactor_record", 29: "ev_chg_contactor_record", 30: "ev_pre_contactor_record",
    31: "ev_relay_switch_count", 32: "ev_dsg_mos_ot", 33: "ev_chg_mos_ot",
    34: "ev_dsg_mos_sensor", 35: "ev_chg_mos_sensor", 36: "ev_dsg_contactor_weld",
    37: "ev_chg_contactor_weld", 38: "ev_dsg_contactor_open", 39: "ev_chg_contactor_open",
    40: "ev_precharge_failed", 41: "ev_upgrade_record", 42: "ev_daisy_chain_init",
    43: "ev_afe_fault", 44: "ev_cell_wire_off", 45: "ev_temp_sensor", 46: "ev_eeprom",
    47: "ev_rtc", 48: "ev_vehicle_comm",
    59: "ev_pack_ov3", 60: "ev_pack_uv3", 61: "ev_soc_high3", 62: "ev_soc_low3",
    63: "ev_chg_oc3", 64: "ev_dsg_oc3", 65: "ev_temp_high3", 66: "ev_temp_low3",
    67: "ev_cell_ov3", 68: "ev_cell_uv3", 69: "ev_vdiff3", 70: "ev_tdiff3",
    71: "ev_chg_ut3", 72: "ev_chg_ot3", 73: "ev_thermal_runaway", 74: "ev_heating_fault",
    75: "ev_charging_state", 76: "ev_discharging_state", 77: "ev_mos_ot1", 78: "ev_mos_ot2",
    79: "ev_ambient_ot1", 80: "ev_ambient_ot2", 81: "ev_balancer_comm_lost",
    82: "ev_soh_low1", 83: "ev_soh_low2", 84: "ev_soh_low3",
    122: "ev_smart_charger_ok", 123: "ev_smart_charger_ng", 124: "ev_smart_cabinet_ok",
    125: "ev_smart_cabinet_ng", 126: "ev_chg_mos_ot", 127: "ev_chg_mos_sensor",
    128: "ev_dsg_mos_ot", 129: "ev_dsg_mos_sensor", 130: "ev_short_circuit",
    131: "ev_upgrade_flag", 132: "ev_low_v_no_charge", 133: "ev_high_v_no_discharge",
    134: "ev_parallel_comm_ok", 135: "ev_short_circuit",
    144: "ev_afe_chip_fault", 145: "ev_afe_comm_fault", 146: "ev_afe_sampling_fault",
    147: "ev_voltage_acq_fault", 148: "ev_voltage_wire_off", 149: "ev_mos_temp_sensor",
    150: "ev_current_sensor", 151: "ev_temp_acq_fault", 152: "ev_temp_wire_off",
    153: "ev_eeprom", 154: "ev_flash", 155: "ev_rtc",
    156: "ev_chg_mos_fault", 157: "ev_dsg_mos_fault", 158: "ev_pre_mos_fault",
    159: "ev_precharge_failed", 160: "ev_cmd_chg_mos_off", 161: "ev_cmd_dsg_mos_off",
    162: "ev_switch_chg_mos_off", 163: "ev_switch_dsg_mos_off", 167: "ev_heating_abnormal",
    253: "ev_realtime_snapshot", 254: "ev_power_off", 255: "ev_power_on",
}  # fmt: skip

_RESERVED = {136, 137, 138, 139, 140, 141, 142, 143, 164, 165, 166, 168, 169}


@dataclass(frozen=True)
class HistoryEvent:
    """イベント名。予備の番号などは引数つきの共通の文言にする"""

    label_key: str
    arg: int | None = None

    @property
    def label(self) -> str:
        return t(self.label_key, self.arg) if self.arg is not None else t(self.label_key)

    @staticmethod
    def of(event_id: int) -> "HistoryEvent":
        if event_id in _NAMES:
            return HistoryEvent(_NAMES[event_id])
        if 49 <= event_id <= 58:
            return HistoryEvent("ev_reserved_signal", event_id - 48)
        if event_id in _RESERVED:
            return HistoryEvent("ev_reserved", event_id)
        return HistoryEvent("ev_unknown", event_id)


@dataclass(frozen=True)
class HistoryRecord:
    number: int
    total_count: int
    time: datetime | None
    event_id: int
    occurred: bool
    total_voltage: float
    current: float
    soc: float
    charge_mos: bool
    discharge_mos: bool
    max_cell_voltage: float
    max_cell_index: int
    min_cell_voltage: float
    min_cell_index: int
    max_temperature: int
    max_temperature_index: int
    min_temperature: int
    min_temperature_index: int
    fault_code: int

    @property
    def event(self) -> HistoryEvent:
        return HistoryEvent.of(self.event_id)


@dataclass(frozen=True)
class PageResult:
    records: list[HistoryRecord]
    reached_end: bool


def registers_to_bytes(registers: Sequence[int]) -> bytes:
    out = bytearray()
    for r in registers:
        out += bytes(((r >> 8) & 0xFF, r & 0xFF))
    return bytes(out)


def parse_page(registers: Sequence[int]) -> PageResult:
    """1 ページ分を解析する。番号 0 の記録が出たら、そこで履歴は終わり"""
    b = registers_to_bytes(registers)
    out: list[HistoryRecord] = []
    offset = 0
    while offset + RECORD_BYTES <= len(b):
        rec = parse_record(b, offset)
        if rec is None:
            return PageResult(out, reached_end=True)
        out.append(rec)
        offset += RECORD_BYTES
    return PageResult(out, reached_end=False)


def parse_record(b: bytes, o: int = 0) -> HistoryRecord | None:
    def u8(i: int) -> int:
        return b[o + i]

    def u16(i: int) -> int:
        return (u8(i) << 8) | u8(i + 1)

    number = u16(0)
    if number == 0:
        return None
    try:
        time: datetime | None = datetime(2000 + u8(6), u8(7), u8(8), u8(9), u8(10), u8(11))
    except ValueError:
        time = None
    mos = u8(20)
    return HistoryRecord(
        number=number,
        total_count=u16(2),
        time=time,
        event_id=u8(12),
        occurred=u8(13) != 0,
        total_voltage=u16(14) * 0.1,
        current=(u16(16) - 30000) * 0.1,
        soc=u16(18) * 0.1,
        charge_mos=bool(mos & 0x01),
        discharge_mos=bool(mos & 0x02),
        max_cell_voltage=u16(21) / 1000.0,
        max_cell_index=u8(23),
        min_cell_voltage=u16(24) / 1000.0,
        min_cell_index=u8(26),
        max_temperature=u8(27) - 40,
        max_temperature_index=u8(28),
        min_temperature=u8(29) - 40,
        min_temperature_index=u8(30),
        fault_code=u8(31),
    )


def encode_record(
    number: int,
    total: int,
    time: datetime,
    event_id: int,
    occurred: bool,
    total_voltage: float,
    current_raw: int,
    soc: float,
    mos: int,
    max_cell_mv: int,
    max_cell_index: int,
    min_cell_mv: int,
    min_cell_index: int,
    max_temp: int,
    max_temp_index: int,
    min_temp: int,
    min_temp_index: int,
    fault_code: int = 0,
) -> bytes:
    """34 バイトの記録を組み立てる（シミュレーターとテスト用）"""
    b = bytearray(RECORD_BYTES)

    def p16(i: int, v: int) -> None:
        b[i] = (v >> 8) & 0xFF
        b[i + 1] = v & 0xFF

    p16(0, number)
    p16(2, total)
    p16(4, 26)
    b[6:12] = bytes((time.year - 2000, time.month, time.day, time.hour, time.minute, time.second))
    b[12] = event_id & 0xFF
    b[13] = 1 if occurred else 0
    p16(14, round(total_voltage * 10))
    p16(16, current_raw)
    p16(18, round(soc * 10))
    b[20] = mos & 0xFF
    p16(21, max_cell_mv)
    b[23] = max_cell_index
    p16(24, min_cell_mv)
    b[26] = min_cell_index
    b[27] = max_temp + 40
    b[28] = max_temp_index
    b[29] = min_temp + 40
    b[30] = min_temp_index
    b[31] = fault_code & 0xFF
    return bytes(b)
