"""特定の電池向けの「おすすめ設定」（Android 版 Recommended.kt と同じ値・同じ確認）。

電池の種類のプリセット（137〜163 の一括書き込み）に、温度の保護値・均等化・公称電圧も加えたもの。
値はセルの一般的な仕様に基づく目安で、BMS の保護（非常時に止める値）として余裕を持たせている。
普段の充電を止める電圧は、充電器・インバーター側でこれより低く設定する前提。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from typing import Any

from .settings import (
    MAX_PRESET_CELLS,
    MIN_PRESET_CELLS,
    PRESET_COUNT,
    PRESET_START,
    SETTINGS_BY_KEY,
    BatteryType,
    BmsSettings,
    round_half_up,
    validate,
)


@dataclass(frozen=True)
class PresetValues:
    label: str
    description: str
    type: BatteryType
    # セル電圧（V）
    cell_ov_release: float
    cell_ov: float
    cell_uv_release: float
    cell_uv: float
    balance_start: float
    balance_diff: float
    nominal: float
    # 温度（℃）: 解除 / 保護
    charge_ot_release: int
    charge_ot: int
    charge_ut_release: int
    charge_ut: int
    discharge_ot_release: int
    discharge_ot: int
    discharge_ut_release: int
    discharge_ut: int


class RecommendedPreset(Enum):
    # 日産リーフのモジュール（第 1 世代 24/30 kWh のマンガン系、第 2 世代 40/62 kWh の三元系のどちらにも使える値）
    LEAF_STANDARD = PresetValues(
        "日産リーフ（標準）",
        "セル 3.00〜4.15 V・充電 0〜45 ℃。リーフのモジュール（24/30/40/62 kWh）向けの一般的な保護値",
        BatteryType.NCM,
        cell_ov_release=4.10, cell_ov=4.15, cell_uv_release=3.20, cell_uv=3.00,
        balance_start=3.90, balance_diff=0.015, nominal=3.70,
        charge_ot_release=40, charge_ot=45, charge_ut_release=5, charge_ut=0,
        discharge_ot_release=55, discharge_ot=60, discharge_ut_release=-15, discharge_ut=-20,
    )  # fmt: skip
    # 日産リーフ・長寿命寄り。満充電・深い放電を避けて、劣化を抑える
    LEAF_LONG_LIFE = PresetValues(
        "日産リーフ（長寿命）",
        "セル 3.30〜4.10 V・充電 3〜40 ℃。満充電と深い放電を避けて、劣化を抑える",
        BatteryType.NCM,
        cell_ov_release=4.05, cell_ov=4.10, cell_uv_release=3.45, cell_uv=3.30,
        balance_start=3.85, balance_diff=0.015, nominal=3.70,
        charge_ot_release=35, charge_ot=40, charge_ut_release=8, charge_ut=3,
        discharge_ot_release=50, discharge_ot=55, discharge_ut_release=-10, discharge_ut=-15,
    )  # fmt: skip

    @property
    def v(self) -> PresetValues:
        return self.value

    @staticmethod
    def from_name(name: str) -> "RecommendedPreset":
        key = name.strip().upper().replace("-", "_")
        try:
            return RecommendedPreset[key]
        except KeyError:
            raise ValueError(f"unknown preset: {name} ({', '.join(p.name.lower() for p in RecommendedPreset)})") from None


class LeafGeneration(Enum):
    """日産リーフの電池の世代（Android 版 LeafGeneration と同じ値）。
    値 = (表示名, 公称電圧 V, 新品時のセル容量 Ah（目安）, 車両での並列数, 長寿命をすすめるか)"""

    ZE0_24 = ("24 kWh 初期型（2010〜2013年）・マンガン系", 3.75, 33.1, 2, True)
    AZE0_24 = ("24 kWh 改良型（2013〜2017年）・マンガン系", 3.75, 33.1, 2, False)
    AZE0_30 = ("30 kWh（2015〜2017年）・マンガン系＋三元系", 3.70, 40.0, 2, False)
    ZE1_40 = ("40 kWh（2017年〜）・三元系", 3.65, 56.3, 2, False)
    ZE1_62 = ("62 kWh e+（2019年〜）・三元系", 3.65, 59.0, 3, False)

    @property
    def label(self) -> str:
        return self.value[0]

    @property
    def nominal(self) -> float:
        return self.value[1]

    @property
    def cell_ah(self) -> float:
        return self.value[2]

    @property
    def default_parallel(self) -> int:
        return self.value[3]

    @property
    def suggest_long_life(self) -> bool:
        return self.value[4]

    @staticmethod
    def from_name(name: str) -> "LeafGeneration":
        key = name.strip().upper().replace("-", "_")
        try:
            return LeafGeneration[key]
        except KeyError:
            raise ValueError(f"unknown generation: {name} ({', '.join(g.name.lower() for g in LeafGeneration)})") from None


MIN_PARALLEL, MAX_PARALLEL = 1, 20
MIN_SOH, MAX_SOH = 30, 100


def rated_capacity_ah(gen: LeafGeneration, parallel: int, soh_percent: int) -> float | None:
    """定格容量の目安（Ah、0.1 単位）＝ セル容量 × 並列数 × SOH。範囲外なら None"""
    if isinstance(parallel, bool) or isinstance(soh_percent, bool):
        return None
    if not isinstance(parallel, int) or not isinstance(soh_percent, int):
        return None
    if not (MIN_PARALLEL <= parallel <= MAX_PARALLEL and MIN_SOH <= soh_percent <= MAX_SOH):
        return None
    return round_half_up(gen.cell_ah * parallel * soh_percent / 100 * 10) / 10


@dataclass(frozen=True)
class PresetOptions:
    """おすすめ設定の追加の指定。generation で公称電圧を世代に合わせ、rated_capacity_ah を指定したときだけ定格容量も書く"""

    generation: LeafGeneration | None = None
    rated_capacity_ah: float | None = None


#: 書き込み後に確かめる項目
CHECKED_KEYS = [
    "cell_nominal", "cell_ov", "cell_uv", "pack_ov", "pack_uv",
    "charge_ot", "charge_ut", "discharge_ot", "discharge_ut",
    "balance_start_v", "balance_start_diff",
]  # fmt: skip


@dataclass(frozen=True)
class CellConditions:
    """書き込み直後に保護が働くかの判定に使う、今の状態。読めていない値は None"""

    min_cell_v: float | None = None
    max_cell_v: float | None = None
    min_temp: int | None = None
    max_temp: int | None = None

    @staticmethod
    def of(live: Any) -> "CellConditions":
        if live is None:
            return CellConditions()
        temps = live.temperature_sensor_count > 0
        return CellConditions(
            live.min_cell_voltage if live.min_cell_voltage > 0.5 else None,
            live.max_cell_voltage if live.max_cell_voltage > 0.5 else None,
            live.min_temperature if temps else None,
            live.max_temperature if temps else None,
        )


@dataclass(frozen=True)
class PresetPlan:
    block: list[int]  # 137 から 27 レジスタ
    extra: list[tuple[int, int]]  # 1 つずつ書くレジスタ（164 = 均等化を始める電圧差、129 = 公称電圧）
    changes: list[dict[str, Any]]
    preset: "RecommendedPreset"
    from_type: BatteryType | None
    options: PresetOptions = PresetOptions()

    @property
    def changes_chemistry(self) -> bool:
        return self.from_type is not self.preset.v.type

    @property
    def steps(self) -> list[tuple[int, list[int]]]:
        """書き込む順番。1 つずつ書くレジスタを先に、電池の種類を含む一括書き込みを最後にする"""
        return [(r, [v]) for r, v in self.extra] + [(PRESET_START, self.block)]

    def immediate_effect(self, c: CellConditions) -> str | None:
        """書き込んだ直後に保護が働くか。新しい解除値と比べる（Kotlin の immediateEffect と同じ）
        discharge_stops / discharge_stops_temp / charge_stops / charge_stops_temp / None"""
        p = self.preset.v
        if c.min_cell_v is not None and c.min_cell_v < p.cell_uv_release:
            return "discharge_stops"
        if (c.min_temp is not None and c.min_temp < p.discharge_ut_release) or (
            c.max_temp is not None and c.max_temp > p.discharge_ot_release
        ):
            return "discharge_stops_temp"
        if c.max_cell_v is not None and c.max_cell_v > p.cell_ov_release:
            return "charge_stops"
        if (c.min_temp is not None and c.min_temp < p.charge_ut_release) or (
            c.max_temp is not None and c.max_temp > p.charge_ot_release
        ):
            return "charge_stops_temp"
        return None


def build_recommended_plan(
    preset: RecommendedPreset, cell_count: int, current: BmsSettings, options: PresetOptions = PresetOptions()
) -> PresetPlan | None:
    """書き込み内容を作る。セル数・種類・今の値が揃わない、または安全範囲の確認を通らなければ None"""
    p = preset.v
    if not MIN_PRESET_CELLS <= cell_count <= MAX_PRESET_CELLS:
        return None
    if not p.type.available_for(cell_count):
        return None
    values: list[int] = []
    for i in range(PRESET_COUNT):
        x = current.raw.get(PRESET_START + i)
        if x is None:
            return None
        values.append(x)

    def put(reg: int, x: int) -> None:
        values[reg - PRESET_START] = x

    def mv(x: float) -> int:
        return round_half_up(x * 1000)

    def pack_deci(per_cell: float) -> int:
        return round_half_up(per_cell * cell_count * 10)

    def temp(c: int) -> int:
        return c + 40

    put(137, p.type.code)
    put(139, mv(p.cell_ov_release)); put(140, mv(p.cell_ov))  # fmt: skip
    put(141, mv(p.cell_uv_release)); put(142, mv(p.cell_uv))  # fmt: skip
    put(143, pack_deci(p.cell_ov_release)); put(144, pack_deci(p.cell_ov))  # fmt: skip
    put(145, pack_deci(p.cell_uv_release)); put(146, pack_deci(p.cell_uv))  # fmt: skip
    put(151, temp(p.charge_ot_release)); put(152, temp(p.charge_ot))  # fmt: skip
    put(153, temp(p.charge_ut_release)); put(154, temp(p.charge_ut))  # fmt: skip
    put(155, temp(p.discharge_ot_release)); put(156, temp(p.discharge_ot))  # fmt: skip
    put(157, temp(p.discharge_ut_release)); put(158, temp(p.discharge_ut))  # fmt: skip
    put(163, mv(p.balance_start))
    diff_raw = mv(p.balance_diff)
    nominal_raw = mv(options.generation.nominal if options.generation else p.nominal)
    extra: list[tuple[int, int]] = [(164, diff_raw), (129, nominal_raw)]
    keys = list(CHECKED_KEYS)
    if options.rated_capacity_ah is not None:
        ah = options.rated_capacity_ah
        if not isinstance(ah, (int, float)) or isinstance(ah, bool) or ah != ah or ah in (float("inf"), float("-inf")):
            return None
        rc = SETTINGS_BY_KEY["rated_capacity"]
        extra.append((rc.register, rc.encoding.encode(float(ah))))
        keys.insert(0, "rated_capacity")

    after = current.merge(PRESET_START, values)
    for reg, v in extra:
        after = after.merge(reg, [v])
    for key in keys:
        s = SETTINGS_BY_KEY[key]
        val = after.get(s)
        if val is None or not validate(s, val, after, cell_count).ok:
            return None

    changes: list[dict[str, Any]] = []
    bt_before = current.battery_type
    if bt_before is not p.type:
        changes.append({
            "key": "battery_type", "label": "電池の種類",
            "before": bt_before.name.lower() if bt_before else None, "after": p.type.name.lower(),
        })  # fmt: skip

    def fmt(s: Any, x: float | None) -> str | None:
        return None if x is None else f"{s.format(x)} {s.unit}"

    for key in keys:
        s = SETTINGS_BY_KEY[key]
        if s.bms_derived:
            continue  # 総電圧の保護値は BMS が自動で決める（書いても無視される）ので、変更の一覧には出さない
        b, a = current.get(s), after.get(s)
        if fmt(s, b) != fmt(s, a):
            changes.append({"key": key, "label": s.label, "before": fmt(s, b), "after": fmt(s, a)})
        rb, ra = current.release(s), after.release(s)
        if ra is not None and fmt(s, rb) != fmt(s, ra):
            changes.append({"key": key + "_release", "label": s.label + "（解除値）", "before": fmt(s, rb), "after": fmt(s, ra)})
    return PresetPlan(values, extra, changes, preset, bt_before, options)
