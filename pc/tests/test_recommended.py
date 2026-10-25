"""おすすめ設定（Android 版 RecommendedTest と同じ値）"""

from bms_japan.protocol.recommended import RecommendedPreset, build_recommended_plan
from bms_japan.protocol.settings import EMPTY_SETTINGS, BmsSettings


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


def test_immediate_effect():
    plan = build_recommended_plan(RecommendedPreset.LEAF_STANDARD, 7, lfp())
    assert plan.immediate_effect(2.992, 3.006) == "discharge_stops"
    assert plan.immediate_effect(3.70, 3.75) is None
    assert plan.immediate_effect(3.90, 4.16) == "charge_stops"
    assert plan.immediate_effect(0.0, 0.0) is None


def test_all_presets_pass_safety_checks():
    for p in RecommendedPreset:
        for n in (4, 7, 8, 14, 16):
            assert build_recommended_plan(p, n, lfp(n)) is not None, (p, n)


def test_refuses_bad_input():
    assert build_recommended_plan(RecommendedPreset.LEAF_STANDARD, 0, lfp()) is None
    assert build_recommended_plan(RecommendedPreset.LEAF_STANDARD, 21, lfp(21)) is None
    assert build_recommended_plan(RecommendedPreset.LEAF_STANDARD, 7, EMPTY_SETTINGS) is None
