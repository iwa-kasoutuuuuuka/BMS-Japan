"""日本語ラベル（Android 版の strings.xml と同じ文言）。"""

from __future__ import annotations

from .strings_ja import STRINGS


def t(key: str, *args: object) -> str:
    """strings.xml のキーから日本語を返す。``{0}`` などの引数を埋める。"""
    text = STRINGS.get(key, key)
    return text.format(*args) if args else text


# 運転データの項目名（strings.xml に無いものはここで補う）
LIVE_LABELS: dict[str, str] = {
    "total_voltage": t("label_total_voltage"),
    "current": t("label_current"),
    "power": t("label_power"),
    "remaining_capacity": t("label_remaining"),
    "soc": "SOC（残量）",
    "cycle_count": t("label_cycles"),
    "cell_count": t("label_cell_count"),
    "temperature_sensor_count": "温度センサーの数",
    "charge_mos": t("label_charge_mos"),
    "discharge_mos": t("label_discharge_mos"),
    "balancing": t("label_balancing"),
    "max_cell_voltage": "最高セル電圧",
    "min_cell_voltage": "最低セル電圧",
    "average_cell_voltage": "平均セル電圧",
    "cell_voltage_delta": "セル電圧差",
    "max_temperature": t("label_temp_max"),
    "min_temperature": t("label_temp_min"),
    "cell_voltages": t("section_cells"),
    "temperatures": t("section_temperature"),
    "state": "状態",
    "alarms": t("section_alarms"),
}
