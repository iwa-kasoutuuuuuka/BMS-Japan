"""Kotlin 版 SettingsTest.kt の移植（同じ期待値）。"""

import pytest

from bms_japan.protocol import modbus as mb
from bms_japan.protocol.settings import (
    CHARGE_CURRENT,
    DISCHARGE_CURRENT,
    EMPTY_SETTINGS,
    MAIN_COUNT,
    SETTINGS,
    SETTINGS_BY_KEY,
    TEMPERATURE,
    BatteryType,
    BmsPassword,
    PasswordChangeCheck,
    SwitchSetting,
    ValidationResult,
    build_battery_preset,
    validate,
)

S = SETTINGS_BY_KEY


def sample_settings():
    """LFP 16 セルの典型的な設定値"""
    r = [0] * MAIN_COUNT

    def put(reg, v):
        r[reg - 128] = v

    put(128, 2800)
    put(129, 3200)
    put(131, 16)
    put(134, 4)
    put(137, 0)
    put(138, 3600)
    put(139, 3550); put(140, 3650)  # fmt: skip
    put(141, 2700); put(142, 2500)  # fmt: skip
    put(143, 568); put(144, 584)  # fmt: skip
    put(145, 432); put(146, 400)  # fmt: skip
    put(147, 29000); put(148, 28000)  # fmt: skip
    put(149, 31500); put(150, 32000)  # fmt: skip
    put(151, 90); put(152, 95)  # fmt: skip
    put(153, 45); put(154, 40)  # fmt: skip
    put(155, 95); put(156, 100)  # fmt: skip
    put(157, 25); put(158, 20)  # fmt: skip
    put(159, 200); put(160, 300)  # fmt: skip
    put(161, 10); put(162, 15)  # fmt: skip
    put(163, 3400); put(164, 10)  # fmt: skip
    put(165, 1); put(166, 0)  # fmt: skip
    put(167, 1000)
    put(168, 130)
    return EMPTY_SETTINGS.merge(128, r).merge(204, [0, 0, 16, 1, 20])


def test_decode_values():
    s = sample_settings()
    assert s.get(S["cell_ov"]) == pytest.approx(3.65)
    assert s.release(S["cell_ov"]) == pytest.approx(3.55)
    assert s.get(S["pack_ov"]) == pytest.approx(58.4)
    assert s.get(S["charge_oc"]) == pytest.approx(200.0)
    assert s.get(S["discharge_oc"]) == pytest.approx(200.0)
    assert s.get(S["charge_ot"]) == pytest.approx(55.0)
    assert s.get(S["charge_ut"]) == pytest.approx(0.0)
    assert s.get(S["mos_ot"]) == pytest.approx(90.0)
    assert s.get(S["rated_capacity"]) == pytest.approx(280.0)
    assert s.get(S["soc"]) == pytest.approx(100.0)
    assert s.get(S["balance_current"]) == pytest.approx(2.0)
    assert s.battery_type is BatteryType.LFP
    assert s.switch(SwitchSetting.CHARGE_MOS) is True
    assert s.switch(SwitchSetting.DISCHARGE_MOS) is False
    assert s.switch(SwitchSetting.BALANCE) is True


def test_encode_round_trip():
    for setting in [x for x in SETTINGS if x.editable]:
        mid = (setting.min + setting.max) / 2
        v = float(setting.format(mid))
        assert setting.encoding.decode(setting.encoding.encode(v)) == pytest.approx(v, abs=1e-6), setting.key


def test_charge_current_encoded_below_offset():
    assert CHARGE_CURRENT.encode(200.0) == 28000  # 元アプリ: (3000 − A) × 10
    assert DISCHARGE_CURRENT.encode(200.0) == 32000  # 元アプリ: (A + 3000) × 10
    assert TEMPERATURE.encode(95.0) == 135


def test_validation():
    s = sample_settings()
    assert validate(S["cell_ov"], "3.700", s) == ValidationResult("ok", raw=3700)
    assert validate(S["cell_ov"], "3,7", s) == ValidationResult("ok", raw=3700)
    assert validate(S["cell_ov"], 3.7, s) == ValidationResult("ok", raw=3700)
    assert validate(S["cell_ov"], "abc", s).kind == "not_a_number"
    assert validate(S["cell_ov"], "5.0", s).kind == "out_of_range"
    # 保護値は解除値 3.55 V より高くないといけない
    assert validate(S["cell_ov"], "3.50", s) == ValidationResult("must_be_above", release="3.550")
    # 低電圧の保護値は解除値 2.70 V より低くないといけない
    assert validate(S["cell_uv"], "2.80", s) == ValidationResult("must_be_below", release="2.700")
    # リン酸鉄では充電の低温保護は 0 ℃ 以上（解除値 5 ℃ より低く）
    assert validate(S["charge_ut"], "2", s) == ValidationResult("ok", raw=2 + 40)
    assert validate(S["charge_ut"], "-10", s).kind == "out_of_range"
    assert validate(S["board1_cells"], "8", s).kind == "not_editable"
    assert validate(S["cell_ov"], "nan", s).kind == "not_a_number"


def test_battery_preset_ncm():
    s = sample_settings()
    v = build_battery_preset(BatteryType.NCM, 13, s)
    assert v is not None
    assert len(v) == 27
    assert v[0] == 1  # 137 = 三元系
    assert v[1] == 3600  # 138 は今の値のまま
    assert v[139 - 137] == 4100 and v[140 - 137] == 4200
    assert v[141 - 137] == 2850 and v[142 - 137] == 2820
    assert v[143 - 137] == 533  # 4.10 × 13 × 10
    assert v[144 - 137] == 546
    assert v[148 - 137] == 28000  # 電流は今の値のまま
    assert v[163 - 137] == 3000
    frame = mb.write_registers(137, v)
    assert len(frame) == 2 + 4 + 27 * 2 + 2  # バイト数フィールドなし


def test_preset_needs_all_registers():
    assert build_battery_preset(BatteryType.LFP, 16, EMPTY_SETTINGS) is None


def test_battery_availability():
    assert not BatteryType.NCM.available_for(21)
    assert not BatteryType.LTO.available_for(5)
    assert BatteryType.LFP.available_for(48)


def test_password_encode_matches_original_frame():
    regs = BmsPassword.encode("654321")
    assert BmsPassword.decode(regs) == "654321"
    frame = mb.to_hex(mb.write_registers(BmsPassword.START, regs))
    assert frame.startswith("D21000C90003363534333231")  # 元アプリ: "D21000C90003" + ASCII + CRC
    assert len(frame) // 2 == 6 + 6 + 2


def test_password_encode_rejects_bad_format():
    with pytest.raises(ValueError):
        BmsPassword.encode("1234")


def test_password_change_checks():
    cur = "123456"
    v = BmsPassword.validate_change
    assert v(cur, "123456", "987654", "987654") is PasswordChangeCheck.OK
    assert v(cur, "", "987654", "987654") is PasswordChangeCheck.EMPTY
    assert v(cur, "111111", "987654", "987654") is PasswordChangeCheck.WRONG_OLD
    assert v(None, "123456", "987654", "987654") is PasswordChangeCheck.WRONG_OLD
    assert v(cur, "123456", "9876", "9876") is PasswordChangeCheck.BAD_FORMAT
    assert v(cur, "123456", "98765a", "98765a") is PasswordChangeCheck.BAD_FORMAT
    assert v(cur, "123456", "987654", "987655") is PasswordChangeCheck.MISMATCH
    assert v(cur, "123456", "123456", "123456") is PasswordChangeCheck.SAME_AS_OLD


def test_password_decode():
    assert BmsPassword.decode([0x3132, 0x3334, 0x3536]) == "123456"
    assert BmsPassword.decode([0x3132, 0x3334, 0x0000]) == "1234"


def test_labels_are_japanese():
    assert S["cell_ov"].label == "セル過電圧の保護"
    assert BatteryType.LFP.label == "リン酸鉄リチウム（LiFePO4）"
    assert SwitchSetting.DISCHARGE_MOS.confirm_text(False).startswith("放電MOSをOFFにして")
