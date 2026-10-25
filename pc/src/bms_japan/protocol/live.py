"""運転データ（レジスタ 0〜61）とアラーム。Kotlin 版 ``LiveData.kt`` の移植。"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from enum import Enum
from collections.abc import Sequence

from ..labels import t

REGISTER_COUNT = 62


class ChargeState(Enum):
    IDLE = "idle"
    CHARGING = "charging"
    DISCHARGING = "discharging"
    UNKNOWN = "unknown"

    @property
    def label(self) -> str:
        return t(f"state_{self.value}")


class Alarm(Enum):
    """アラームのビット定義（bit0 が最下位）。値 = (レジスタ, ビット, strings.xml のキー)"""

    CELL_OV1 = (58, 0, "alarm_cell_ov1")
    CELL_OV2 = (58, 1, "alarm_cell_ov2")
    CELL_UV1 = (58, 2, "alarm_cell_uv1")
    CELL_UV2 = (58, 3, "alarm_cell_uv2")
    PACK_OV1 = (58, 4, "alarm_pack_ov1")
    PACK_OV2 = (58, 5, "alarm_pack_ov2")
    PACK_UV1 = (58, 6, "alarm_pack_uv1")
    PACK_UV2 = (58, 7, "alarm_pack_uv2")
    CHG_OT1 = (58, 8, "alarm_chg_ot1")
    CHG_OT2 = (58, 9, "alarm_chg_ot2")
    CHG_UT1 = (58, 10, "alarm_chg_ut1")
    CHG_UT2 = (58, 11, "alarm_chg_ut2")
    DSG_OT1 = (58, 12, "alarm_dsg_ot1")
    DSG_OT2 = (58, 13, "alarm_dsg_ot2")
    DSG_UT1 = (58, 14, "alarm_dsg_ut1")
    DSG_UT2 = (58, 15, "alarm_dsg_ut2")

    CHG_OC1 = (59, 0, "alarm_chg_oc1")
    CHG_OC2 = (59, 1, "alarm_chg_oc2")
    DSG_OC1 = (59, 2, "alarm_dsg_oc1")
    DSG_OC2 = (59, 3, "alarm_dsg_oc2")
    SOC_HIGH1 = (59, 4, "alarm_soc_high1")
    SOC_HIGH2 = (59, 5, "alarm_soc_high2")
    SOC_LOW1 = (59, 6, "alarm_soc_low1")
    SOC_LOW2 = (59, 7, "alarm_soc_low2")
    VDIFF1 = (59, 8, "alarm_vdiff1")
    VDIFF2 = (59, 9, "alarm_vdiff2")
    TDIFF1 = (59, 10, "alarm_tdiff1")
    TDIFF2 = (59, 11, "alarm_tdiff2")

    CHG_MOS_OT = (60, 0, "alarm_chg_mos_ot")
    DSG_MOS_OT = (60, 1, "alarm_dsg_mos_ot")
    CHG_MOS_SENSOR = (60, 2, "alarm_chg_mos_sensor")
    DSG_MOS_SENSOR = (60, 3, "alarm_dsg_mos_sensor")
    CHG_MOS_WELD = (60, 4, "alarm_chg_mos_weld")
    DSG_MOS_WELD = (60, 5, "alarm_dsg_mos_weld")
    CHG_MOS_SHORT = (60, 6, "alarm_chg_mos_short")
    DSG_MOS_SHORT = (60, 7, "alarm_dsg_mos_short")
    AFE = (60, 8, "alarm_afe")
    CELL_WIRE = (60, 9, "alarm_cell_wire")
    TEMP_SENSOR = (60, 10, "alarm_temp_sensor")
    EEPROM = (60, 11, "alarm_eeprom")
    RTC = (60, 12, "alarm_rtc")
    PRECHARGE = (60, 13, "alarm_precharge")
    VEHICLE_COMM = (60, 14, "alarm_vehicle_comm")
    INTERNAL_COMM = (60, 15, "alarm_internal_comm")

    CURRENT_MODULE = (61, 0, "alarm_current_module")
    VOLTAGE_MODULE = (61, 1, "alarm_voltage_module")
    SHORT_CIRCUIT = (61, 2, "alarm_short_circuit")
    LOW_V_NO_CHARGE = (61, 3, "alarm_low_v_no_charge")
    GPS_SOFT_SWITCH = (61, 4, "alarm_gps_soft_switch")
    CHARGER_REMOVED = (61, 5, "alarm_charger_removed")
    THERMAL_RUNAWAY = (61, 6, "alarm_thermal_runaway")
    BALANCER_COMM = (61, 8, "alarm_balancer_comm")
    BALANCE_CONDITION = (61, 9, "alarm_balance_condition")

    @property
    def register(self) -> int:
        return self.value[0]

    @property
    def bit(self) -> int:
        return self.value[1]

    @property
    def label(self) -> str:
        return t(self.value[2])

    @staticmethod
    def decode(r58: int, r59: int, r60: int, r61: int) -> list["Alarm"]:
        regs = {58: r58, 59: r59, 60: r60, 61: r61}
        return [a for a in Alarm if (regs[a.register] >> a.bit) & 1 == 1]


@dataclass(frozen=True)
class LiveData:
    cell_voltages: list[float]  # V（セル数ぶん）
    temperatures: list[int]  # ℃（センサー数ぶん）
    total_voltage: float  # V
    current: float  # A（生の符号。向きは未確認なので表示は大きさ＋state）
    soc: float  # %
    max_cell_voltage: float  # V
    min_cell_voltage: float  # V
    max_temperature: int  # ℃
    min_temperature: int  # ℃
    state: ChargeState
    remaining_capacity: float  # Ah
    cell_count: int
    temperature_sensor_count: int
    cycle_count: int
    balancing: bool
    charge_mos: bool
    discharge_mos: bool
    average_cell_voltage: float  # V
    cell_voltage_delta: float  # V
    power_kw: float  # kW
    alarms: list[Alarm] = field(default_factory=list)

    @staticmethod
    def parse(r: Sequence[int]) -> "LiveData":
        if len(r) < REGISTER_COUNT:
            raise ValueError(f"live data needs {REGISTER_COUNT} registers, got {len(r)}")
        cell_count = r[49]
        temp_count = max(0, min(8, r[50]))
        state = {0: ChargeState.IDLE, 1: ChargeState.CHARGING, 2: ChargeState.DISCHARGING}.get(
            r[47], ChargeState.UNKNOWN
        )
        return LiveData(
            cell_voltages=[r[i] * 0.001 for i in range(max(0, min(32, cell_count)))],
            temperatures=[r[32 + i] - 40 for i in range(temp_count)],
            total_voltage=r[40] * 0.1,
            current=(r[41] - 30000) * 0.1,
            soc=r[42] / 10.0,
            max_cell_voltage=r[43] / 1000.0,
            min_cell_voltage=r[44] / 1000.0,
            max_temperature=r[45] - 40,
            min_temperature=r[46] - 40,
            state=state,
            remaining_capacity=r[48] * 0.1,
            cell_count=cell_count,
            temperature_sensor_count=temp_count,
            cycle_count=r[51],
            balancing=r[52] == 1,
            charge_mos=r[53] == 1,
            discharge_mos=r[54] == 1,
            average_cell_voltage=r[55] / 1000.0,
            cell_voltage_delta=r[56] / 1000.0,
            power_kw=r[57] / 1000.0,
            alarms=Alarm.decode(r[58], r[59], r[60], r[61]),
        )

    def with_extra_cells(self, extra: Sequence[int]) -> "LiveData":
        """33〜48 セル目（レジスタ 71〜86）を追加した結果を返す"""
        needed = max(0, min(len(extra), self.cell_count - 32))
        if needed == 0:
            return self
        return replace(self, cell_voltages=self.cell_voltages[:32] + [extra[i] * 0.001 for i in range(needed)])
