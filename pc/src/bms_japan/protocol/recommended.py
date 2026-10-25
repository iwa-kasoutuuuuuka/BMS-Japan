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


#: 書き込み後に確かめる項目
CHECKED_KEYS = [
    "cell_nominal", "cell_ov", "cell_uv", "pack_ov", "pack_uv",
    "charge_ot", "charge_ut", "discharge_ot", "discharge_ut",
    "balance_start_v", "balance_start_diff",
]  # fmt: skip


@dataclass(frozen=True)
class PresetPlan:
    block: list[int]  # 137 から 27 レジスタ
    extra: list[tuple[int, int]]  # 1 つずつ書くレジスタ（164 = 均等化を始める電圧差、129 = 公称電圧）
    changes: list[dict[str, Any]]
    cell_uv_release: float
    cell_ov: float

    def immediate_effect(self, min_cell_v: float | None, max_cell_v: float | None) -> str | None:
        """今のセル電圧で、書き込んだ直後に保護が働くか（discharge_stops / charge_stops / None）"""
        if min_cell_v is not None and 0.5 < min_cell_v < self.cell_uv_release:
            return "discharge_stops"
        if max_cell_v is not None and max_cell_v >= self.cell_ov:
            return "charge_stops"
        return None


def build_recommended_plan(preset: RecommendedPreset, cell_count: int, current: BmsSettings) -> PresetPlan | None:
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
    nominal_raw = mv(p.nominal)

    after = current.merge(PRESET_START, values).merge(164, [diff_raw]).merge(129, [nominal_raw])
    for key in CHECKED_KEYS:
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

    for key in CHECKED_KEYS:
        s = SETTINGS_BY_KEY[key]
        b, a = current.get(s), after.get(s)
        if fmt(s, b) != fmt(s, a):
            changes.append({"key": key, "label": s.label, "before": fmt(s, b), "after": fmt(s, a)})
        rb, ra = current.release(s), after.release(s)
        if ra is not None and fmt(s, rb) != fmt(s, ra):
            changes.append({"key": key + "_release", "label": s.label + "（解除値）", "before": fmt(s, rb), "after": fmt(s, ra)})
    return PresetPlan(values, [(164, diff_raw), (129, nominal_raw)], changes, p.cell_uv_release, p.cell_ov)
