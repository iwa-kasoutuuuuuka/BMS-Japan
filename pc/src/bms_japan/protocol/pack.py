"""複数台のまとめ。Kotlin 版 ``Pack.kt`` の移植。"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from collections.abc import Sequence

from ..labels import t
from .live import ChargeState, LiveData


class PackMode(Enum):
    """値 = (コード, ラベル, 最大台数)。元アプリの CONNECT_TYPE: 0 単体 / 1 並列 / 2 直列"""

    SINGLE = (0, "mode_single", 1)
    PARALLEL = (1, "mode_parallel", 6)
    SERIES = (2, "mode_series", 6)

    @property
    def code(self) -> int:
        return self.value[0]

    @property
    def label(self) -> str:
        return t(self.value[1])

    @property
    def max_units(self) -> int:
        return self.value[2]

    @staticmethod
    def from_code(code: int | None) -> "PackMode":
        for m in PackMode:
            if m.code == code:
                return m
        return PackMode.SINGLE

    @staticmethod
    def from_name(name: str) -> "PackMode":
        try:
            return PackMode[name.strip().upper()]
        except KeyError:
            raise ValueError(f"unknown mode: {name} (single / parallel / series)") from None


class PackState(Enum):
    """全体の状態。台ごとに充電・放電が分かれているときは MIXED"""

    IDLE = "idle"
    CHARGING = "charging"
    DISCHARGING = "discharging"
    MIXED = "mixed"

    @property
    def label(self) -> str:
        return t(f"state_{self.value}")


@dataclass(frozen=True)
class PackSummary:
    mode: PackMode
    unit_count: int
    voltage: float  # V
    current: float  # A（大きさ）
    power_kw: float  # kW
    remaining_ah: float  # Ah
    soc: float  # %
    state: PackState
    max_cell_voltage: float  # V（全台の中で最高）
    min_cell_voltage: float  # V（全台の中で最低）
    max_temperature: int
    min_temperature: int
    alarm_count: int  # アラームが出ている台数

    @property
    def cell_voltage_delta(self) -> float:
        return self.max_cell_voltage - self.min_cell_voltage

    @staticmethod
    def of(mode: PackMode, units: Sequence[LiveData]) -> "PackSummary | None":
        """まとめ方:
        - 並列: 電圧は平均、電流と残り容量は合計、SOC は平均（元アプリと同じ）
        - 直列: 電圧は合計、電流は平均、SOC は最も低い台（元アプリと同じ）
                残り容量は最も少ない台（元アプリは合計していた）
        - 単体: 1 台の値そのまま
        電力はどの場合も合計。
        """
        if not units:
            return None
        n = len(units)
        currents = [abs(u.current) for u in units]
        if mode is PackMode.SERIES:
            voltage = sum(u.total_voltage for u in units)
            remaining = min(u.remaining_capacity for u in units)
            soc = min(u.soc for u in units)
        else:
            voltage = sum(u.total_voltage for u in units) / n
            remaining = sum(u.remaining_capacity for u in units)
            soc = sum(u.soc for u in units) / n
        current = sum(currents) if mode is PackMode.PARALLEL else sum(currents) / n
        return PackSummary(
            mode=mode,
            unit_count=n,
            voltage=voltage,
            current=current,
            power_kw=sum(u.power_kw for u in units),
            remaining_ah=remaining,
            soc=soc,
            state=PackSummary.combined_state([u.state for u in units]),
            max_cell_voltage=max(u.max_cell_voltage for u in units),
            min_cell_voltage=min(u.min_cell_voltage for u in units),
            max_temperature=max(u.max_temperature for u in units),
            min_temperature=min(u.min_temperature for u in units),
            alarm_count=sum(1 for u in units if u.alarms),
        )

    @staticmethod
    def combined_state(states: Sequence[ChargeState]) -> PackState:
        charging = any(s is ChargeState.CHARGING for s in states)
        discharging = any(s is ChargeState.DISCHARGING for s in states)
        if charging and discharging:
            return PackState.MIXED
        if charging:
            return PackState.CHARGING
        if discharging:
            return PackState.DISCHARGING
        return PackState.IDLE
