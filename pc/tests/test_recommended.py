"""おすすめ設定（Android 版 RecommendedTest と同じ値）"""

from bms_japan.protocol.recommended import CellConditions, RecommendedPreset, build_recommended_plan
from bms_japan.protocol.settings import EMPTY_SETTINGS, BatteryType, BmsSettings, build_battery_preset_steps, preset_cell_count


def lfp(cells: int = 7) -> BmsSettings:
    r = {
        128: 1320, 130: cells, 137: 0, 138: 3600,
        139: 3550, 140: 3650, 141: 2650, 142: 2600,
        143: 249, 144: 256, 145: 186, 146: 182,
        147: 29000, 148: 28000, 149: 31500, 150: 32000,
        151: 90, 152: 95, 153: 45, 154: 40,
        155: 95, 156: 100, 157: 25, 158: 20,
        159: 200, 160: 300, 161: 10, 162: 15,
        163: 3400, 164: 10, 165: 1, 166: 1, 167: 600, 168: 130, 129: 3200,
    }  # fmt: skip
    return BmsSettings(r)


def test_leaf_standard_7s():
    plan = build_recommended_plan(RecommendedPreset.LEAF_STANDARD, 7, lfp())
    assert plan is not None
    at = lambda reg: plan.block[reg - 137]  # noqa: E731
    assert at(137) == 1
    assert (at(139), at(140), at(141), at(142)) == (4100, 4150, 3200, 3000)
    assert (at(143), at(144), at(145), at(146)) == (287, 291, 224, 210)  # 28.7 / 29.05→29.1 / 22.4 / 21.0 V
    assert at(152) == 85 and at(154) == 40
    assert at(163) == 3900
    assert at(148) == 28000 and at(150) == 32000  # 電流は今のまま
    assert plan.extra == [(164, 15), (129, 3700)]


def c(min_v, max_v, min_t=25, max_t=25):
    return CellConditions(min_v, max_v, min_t, max_t)


def test_immediate_effect():
    plan = build_recommended_plan(RecommendedPreset.LEAF_STANDARD, 7, lfp())
    assert plan.immediate_effect(c(2.992, 3.006)) == "discharge_stops"
    assert plan.immediate_effect(c(3.70, 3.75)) is None
    assert plan.immediate_effect(c(3.90, 4.12)) == "charge_stops"  # 解除値 4.10 V と比べる
    assert plan.immediate_effect(CellConditions()) is None


def test_immediate_effect_temperature():
    long = build_recommended_plan(RecommendedPreset.LEAF_LONG_LIFE, 7, lfp())
    assert long.immediate_effect(c(3.7, 3.8, 2, 5)) == "charge_stops_temp"
    assert long.immediate_effect(c(3.7, 3.8, 30, 41)) == "charge_stops_temp"
    assert long.immediate_effect(c(3.7, 3.8, 30, 52)) == "discharge_stops_temp"
    assert long.immediate_effect(c(3.7, 3.8, None, None)) is None


def test_chemistry_change_and_order():
    plan = build_recommended_plan(RecommendedPreset.LEAF_STANDARD, 7, lfp())
    assert plan.changes_chemistry and plan.from_type is BatteryType.LFP
    assert [r for r, _ in plan.steps] == [164, 129, 137]


def test_battery_preset_fixes_values_outside_new_chemistry():
    # チタン酸（充電の低温保護 −20 ℃、公称 2.40 V）からリン酸鉄へ
    lto = BmsSettings({**lfp().raw, 137: 2, 153: 25, 154: 20, 129: 2400})
    steps = build_battery_preset_steps(BatteryType.LFP, 7, lto)
    assert steps[0] == (129, [3200])
    reg, block = steps[-1]
    assert reg == 137 and block[154 - 137] == 40 and block[153 - 137] == 45


def test_preset_cell_count_refuses_mismatch():
    assert preset_cell_count(lfp(7), 7) == 7
    assert preset_cell_count(lfp(7), None) == 7
    assert preset_cell_count(lfp(7), 8) is None


def test_all_presets_pass_safety_checks():
    for p in RecommendedPreset:
        for n in (4, 7, 8, 14, 16):
            assert build_recommended_plan(p, n, lfp(n)) is not None, (p, n)


def test_refuses_bad_input():
    assert build_recommended_plan(RecommendedPreset.LEAF_STANDARD, 0, lfp()) is None
    assert build_recommended_plan(RecommendedPreset.LEAF_STANDARD, 21, lfp(21)) is None
    assert build_recommended_plan(RecommendedPreset.LEAF_STANDARD, 7, EMPTY_SETTINGS) is None
