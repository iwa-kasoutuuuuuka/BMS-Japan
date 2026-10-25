"""シミュレーター（実際のバイト列でやり取りする疑似 BMS）を使った通し試験。

CLI と MCP が共通で使う service 層を、connect → live → 設定の書き込み → 読み直し → 履歴のページ送り
→ 実容量 → まとめ、の順に確かめる。
"""

import pytest

from bms_japan import service as svc
from bms_japan.client import BmsClient, BmsTimeoutError
from bms_japan.protocol import modbus as mb
from bms_japan.service import BmsJapanError, ConnectionManager, ManagerConfig
from bms_japan.simulator import DemoRegistry
from bms_japan.transport.demo import DemoTransport


@pytest.fixture
async def mgr():
    m = ConnectionManager(ManagerConfig(demo=True, reply_timeout=0.3))
    yield m
    await m.close_all()


def sim(m: ConnectionManager, address: str = "DEMO-01"):
    return m.registry.get(address)


async def test_connect_sends_time_sync(mgr):
    d = await svc.connect(mgr, "DEMO-01")
    assert d["connected"] and d["time_synced"] and d["name"] == "JHB-DEMO-01"
    # 接続時に時刻同期（D2 10 00D4 0003 ...）を送っている
    assert any(f.startswith("D21000D40003") for f in sim(mgr).log)
    assert mgr.list_connections()[0]["address"] == "DEMO-01"


async def test_live_data(mgr):
    await svc.connect(mgr, "DEMO-01")
    d = await svc.get_live_data(mgr)  # address 省略 → 接続中の 1 台
    assert sim(mgr).log[-1] == "D2030000003ED7B9"  # 運転データの読み出しフレーム
    assert d["cell_count"]["value"] == 16
    assert len(d["cell_voltages"]["values"]) == 16
    assert d["soc"] == {"value": 85.3, "unit": "%", "label": "SOC（残量）"}
    assert d["state"]["label"] == "充電中"
    # 電流は大きさ（正の値）＋向き。生の符号は充電でマイナス（シミュレーターは docs の推定に合わせている）
    assert d["current"]["value"] > 0
    assert d["current"]["direction"] == "charging"
    assert d["current"]["raw_signed"] < 0
    assert 3.2 < d["min_cell_voltage"]["value"] <= d["max_cell_voltage"]["value"] < 3.4
    assert d["alarms"] == []
    assert d["temperatures"]["values"] == [25, 26, 24, 27]


async def test_alarms_reported(mgr):
    d = await svc.get_live_data(mgr, "DEMO-03")
    assert [a["label"] for a in d["alarms"]] == ["均等化の条件を満たしていません"]


async def test_settings_write_and_read_back(mgr):
    s = await svc.get_settings(mgr, "DEMO-01")
    cell_ov = next(x for x in s["settings"] if x["key"] == "cell_ov")
    assert cell_ov["value"] == 3.65 and cell_ov["release_value"] == 3.55

    # confirm なし → 書き込まずにプレビューだけ
    p = await svc.set_setting(mgr, "cell_ov", "3.70", address="DEMO-01")
    assert p["dry_run"] and p["would_write"]["raw"] == 3700
    assert not any(f.startswith("D206008C") for f in sim(mgr).log)

    r = await svc.set_setting(mgr, "cell_ov", "3.70", confirm=True, address="DEMO-01")
    assert r["ok"] and r["verified"]
    assert r["read_back"] == {"raw": 3700, "value": 3.7, "unit": "V"}
    assert "D206008C0E74" in "".join(sim(mgr).log)  # 1 レジスタ書き込み 140 = 3700

    s2 = await svc.get_settings(mgr, "DEMO-01")
    assert next(x for x in s2["settings"] if x["key"] == "cell_ov")["value"] == 3.7


async def test_charge_current_written_as_offset(mgr):
    r = await svc.set_setting(mgr, "charge_oc", 150, confirm=True, address="DEMO-01")
    assert r["written"]["raw"] == 30000 - 1500
    r = await svc.set_setting(mgr, "discharge_oc", 180, confirm=True, address="DEMO-01")
    assert r["written"]["raw"] == 30000 + 1800 and r["verified"]


@pytest.mark.parametrize(
    "key,value,code",
    [
        ("cell_ov", "3.50", "invalid_must_be_above"),  # 解除値 3.55 V 以下
        ("cell_uv", "2.80", "invalid_must_be_below"),  # 解除値 2.70 V 以上
        ("cell_ov", "9", "invalid_out_of_range"),
        ("cell_ov", "abc", "invalid_not_a_number"),
        ("board1_cells", "8", "not_editable"),
        ("nope", "1", "unknown_setting"),
    ],
)
async def test_settings_validation_blocks_write(mgr, key, value, code):
    with pytest.raises(BmsJapanError) as e:
        await svc.set_setting(mgr, key, value, confirm=True, address="DEMO-01")
    assert e.value.code == code
    assert not any(f[2:4] == "06" for f in sim(mgr).log)  # 何も書き込んでいない


async def test_switch(mgr):
    r = await svc.set_switch(mgr, "discharge", False, confirm=True, address="DEMO-02")
    assert r["verified"] and r["read_back"]["value"] == "OFF"
    live = await svc.get_live_data(mgr, "DEMO-02")
    assert live["discharge_mos"]["value"] is False
    assert live["state"]["value"] == "idle"  # 放電中に放電MOSを切ると電流が止まる
    assert live["current"]["value"] == 0.0
    r = await svc.set_switch(mgr, "discharge", True, confirm=True, address="DEMO-02")
    assert r["verified"]


async def test_battery_type_preset(mgr):
    p = await svc.set_battery_type(mgr, "ncm", address="DEMO-01")
    assert p["dry_run"] and p["would_write"]["count"] == 27
    r = await svc.set_battery_type(mgr, "ncm", confirm=True, address="DEMO-01")
    assert r["verified"] and r["read_back"]["battery_type"] == "ncm"
    s = await svc.get_settings(mgr, "DEMO-01")
    vals = {x["key"]: x["value"] for x in s["settings"]}
    assert vals["cell_ov"] == 4.2 and vals["pack_ov"] == pytest.approx(67.2)  # 4.20 × 16
    assert vals["balance_start_v"] == 3.0
    # 複数書き込みはバイト数フィールドなし: D2 10 0089 001B + 54 バイト + CRC
    frame = next(f for f in sim(mgr).log if f.startswith("D2100089001B"))
    assert len(frame) // 2 == 2 + 4 + 27 * 2 + 2


async def test_password_flow(mgr):
    # 初期値 123456 のままならパスワード不要
    r = await svc.change_password(mgr, "123456", "654321", confirm=True, address="DEMO-01")
    assert r["verified"]
    # 変更後は password が必要
    with pytest.raises(BmsJapanError) as e:
        await svc.set_setting(mgr, "soc", 50, confirm=True, address="DEMO-01")
    assert e.value.code == "password_required"
    with pytest.raises(BmsJapanError) as e:
        await svc.set_setting(mgr, "soc", 50, password="111111", confirm=True, address="DEMO-01")
    assert e.value.code == "wrong_password"
    r = await svc.set_setting(mgr, "soc", 50, password="654321", confirm=True, address="DEMO-01")
    assert r["verified"]
    # 違う今のパスワード・形式違反
    with pytest.raises(BmsJapanError) as e:
        await svc.change_password(mgr, "123456", "111111", confirm=True, address="DEMO-01")
    assert e.value.code == "password_wrong_old"
    with pytest.raises(BmsJapanError) as e:
        await svc.change_password(mgr, "654321", "12ab56", confirm=True, address="DEMO-01")
    assert e.value.code == "password_bad_format"
    # 結果にパスワードそのものは含めない
    assert "654321" not in str(await svc.get_settings(mgr, "DEMO-01"))


async def test_history_paging(mgr):
    p0 = await svc.get_history(mgr, page=0, pages=1, address="DEMO-01")
    assert len(p0["records"]) == 7 and p0["total_count"] == 10
    assert p0["next_page"] == 1 and not p0["reached_end"]
    assert sim(mgr).log[-1] == "D2030100007F1675"  # 256 + 0 ページ
    assert p0["records"][0]["event"] == "電源ON"
    assert p0["records"][3]["occurred_label"] == "解除"
    p1 = await svc.get_history(mgr, page=p0["next_page"], pages=1, address="DEMO-01")
    assert [r["number"] for r in p1["records"]] == [8, 9, 10]
    assert p1["reached_end"] and p1["next_page"] is None
    assert sim(mgr).log[-1].startswith("D2030101007F")  # 256 + 1 ページ
    allp = await svc.get_history(mgr, page=0, pages=10, address="DEMO-01")
    assert len(allp["records"]) == 10 and allp["pages_read"] == 2


async def test_actual_capacity(mgr):
    d = await svc.get_actual_capacity(mgr, "DEMO-01")
    assert d["actual_capacity"]["value"] == 280.0 and d["actual_capacity"]["mah"] == 280_000
    assert sim(mgr).log[-1] == "8103010B0002ABF5"
    r = await svc.set_actual_capacity(mgr, "305.5", confirm=True, address="DEMO-01")
    assert r["verified"] and r["read_back"]["mah"] == 305_500
    with pytest.raises(BmsJapanError):
        await svc.set_actual_capacity(mgr, "0.5", confirm=True, address="DEMO-01")


async def test_pack_summary(mgr):
    par = await svc.get_pack_summary(mgr, ["DEMO-01", "DEMO-02"], "parallel")
    s = par["summary"]
    assert s["unit_count"] == 2 and s["mode"]["label"] == "並列"
    assert s["soc"]["value"] == pytest.approx((85.3 + 61.0) / 2, abs=0.05)
    assert s["state"]["value"] == "mixed"
    ser = await svc.get_pack_summary(mgr, ["DEMO-01", "DEMO-02", "DEMO-03"], "series")
    v = sum(u["total_voltage"] for u in ser["units"])
    assert ser["summary"]["voltage"]["value"] == pytest.approx(v, abs=0.2)
    assert ser["summary"]["soc"]["value"] == 47.5
    assert ser["summary"]["alarm_unit_count"]["value"] == 1
    with pytest.raises(BmsJapanError):
        await svc.get_pack_summary(mgr, ["DEMO-01"] * 7, "parallel")


async def test_ambiguous_address(mgr):
    await svc.connect(mgr, "DEMO-01")
    await svc.connect(mgr, "DEMO-02")
    with pytest.raises(BmsJapanError) as e:
        await svc.get_live_data(mgr)
    assert e.value.code == "ambiguous_address"
    assert len(await mgr.disconnect()) == 2


async def test_unknown_demo_device(mgr):
    with pytest.raises(BmsJapanError) as e:
        await svc.connect(mgr, "AA:BB:CC:DD:EE:FF")
    assert e.value.code == "connect_failed"


# ---- 通信の細かい動き（BmsClient を直接使う） ----


async def make_client(timeout=0.2):
    reg = DemoRegistry()
    c = BmsClient(DemoTransport("DEMO-01", reg), reply_timeout=timeout)
    await c.connect(sync_time=False)
    return c, reg.get("DEMO-01")


async def test_retry_after_dropped_reply():
    c, dev = await make_client()
    dev.drop_replies = 2  # 2 回まで応答を捨てても、3 回目で読める
    live = await c.read_live()
    assert live.cell_count == 16
    assert dev.log.count("D2030000003ED7B9") == 3
    await c.close()


async def test_timeout_after_three_attempts():
    c, dev = await make_client(timeout=0.05)
    dev.drop_replies = 3
    with pytest.raises(BmsTimeoutError):
        await c.read_live()
    await c.close()


async def test_resync_after_garbage():
    c, dev = await make_client()
    dev.garbage_prefix = bytes((0x00, 0x7F, 0xD2))  # 0xD2 で始まる紛らわしいゴミも読み捨てる
    assert (await c.read_settings()).raw[140] == 3650
    await c.close()


async def test_no_reply_for_out_of_range_read():
    c, dev = await make_client(timeout=0.05)
    with pytest.raises(BmsTimeoutError):
        await c.read_registers(250, 20)
    await c.close()


async def test_polling_updates_cache():
    import asyncio

    c, dev = await make_client()
    c.start_polling(0.05)
    await asyncio.sleep(0.2)
    assert c.last_live is not None and c.last_live.cell_count == 16
    await c.close()
    assert c._poll_task is None


async def test_demo_state_persists(tmp_path):
    f = tmp_path / "state.json"
    m1 = ConnectionManager(ManagerConfig(demo=True, demo_state_file=f))
    await svc.set_setting(m1, "soc", 42, confirm=True, address="DEMO-01")
    await m1.close_all()
    m2 = ConnectionManager(ManagerConfig(demo=True, demo_state_file=f))
    s = await svc.get_settings(m2, "DEMO-01")
    assert next(x for x in s["settings"] if x["key"] == "soc")["value"] == 42.0
    await m2.close_all()


def test_frames_are_real_protocol():
    # シミュレーターは CRC の合わない要求には応答しない
    from bms_japan.simulator import FakeBms

    dev = FakeBms("DEMO-01")
    bad = bytearray(mb.read_live())
    bad[-1] ^= 0xFF
    assert dev.receive(bytes(bad)) == []
    assert len(dev.receive(mb.read_live())) == 1
