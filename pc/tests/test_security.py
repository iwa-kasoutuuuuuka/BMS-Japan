"""脆弱性診断で見つかった点の再発防止テスト"""

import pytest

from bms_japan.protocol import modbus as mb
from bms_japan.protocol.settings import BatteryType, BmsSettings, build_battery_preset


def _settings() -> BmsSettings:
    return BmsSettings({128 + i: 100 for i in range(41)})


def test_preset_rejects_bad_cell_counts():
    s = _settings()
    for n in (0, -1, 49, 65535):
        assert build_battery_preset(BatteryType.LFP, n, s) is None
    assert build_battery_preset(BatteryType.LFP, 16, s) is not None


def test_out_of_range_values_raise_instead_of_truncating():
    with pytest.raises(ValueError):
        mb.u16(0x10000)
    with pytest.raises(ValueError):
        mb.u16(-1)


def test_password_frames_are_redacted():
    write = bytes.fromhex("D21000C90003363534333231")
    assert "363534333231" not in mb.redact_for_log(write)
    reply = bytes.fromhex("D20306313233343536AAAA")
    assert "313233343536" not in mb.redact_for_log(reply)
    single = bytes.fromhex("D20600CA3334AAAA")
    assert "3334" not in mb.redact_for_log(single)[8:]
    live = bytes.fromhex("D2030000003ED7B9")
    assert mb.redact_for_log(live) == "D2030000003ED7B9"
