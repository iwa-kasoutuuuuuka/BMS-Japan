"""スキャン時の BMS 判定（名前、または広告データの "JHB"）"""

from bms_japan.transport.base import FoundDevice, adv_contains_jhb


def test_real_leaf_advertisement_matches():
    # 実機 LEAF_005: 会社ID 260 (0x0104)、データ末尾 4A4842 = "JHB"
    md = {260: bytes.fromhex("015018050102c84a4842")}
    assert adv_contains_jhb(md)
    assert FoundDevice("50:18:05:01:02:C8", "LEAF_005", -84, adv_has_jhb=True).looks_like_bms


def test_other_devices_do_not_match():
    md = {46517: bytes.fromhex("13523630315a4a4237584739503033383050000100003a95")}
    assert not adv_contains_jhb(md)
    assert not FoundDevice("11:AA:E7:A1:47:E0", "BG02", -62).looks_like_bms


def test_name_still_works():
    assert FoundDevice("AA:BB:CC:DD:EE:01", "JHB-001", -60).looks_like_bms
