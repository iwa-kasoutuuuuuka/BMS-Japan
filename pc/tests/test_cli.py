"""CLI（bms-japan）のデモモードでの試験。"""

import json

import pytest

from bms_japan import cli


@pytest.fixture(autouse=True)
def demo_env(tmp_path, monkeypatch):
    monkeypatch.setenv("BMS_JAPAN_DEMO_STATE", str(tmp_path / "demo.json"))
    monkeypatch.delenv("BMS_JAPAN_PASSWORD", raising=False)
    monkeypatch.delenv("BMS_JAPAN_ADDRESS", raising=False)


def run(capsys, *args):
    code = cli.main(["--demo", *args])
    out = capsys.readouterr()
    return code, out.out, out.err


def test_live_text(capsys):
    code, out, _ = run(capsys, "live")
    assert code == 0
    assert "JHB-DEMO-01" in out and "充電中" in out and "セル電圧" in out


def test_live_json(capsys):
    code, out, _ = run(capsys, "--json", "live", "-a", "DEMO-02")
    d = json.loads(out)
    assert d["address"] == "DEMO-02" and d["state"]["value"] == "discharging"


def test_settings_set_requires_yes(capsys):
    code, out, _ = run(capsys, "settings", "set", "cell_ov", "3.7")
    assert code == cli.EXIT_ABORTED and "書き込みませんでした" in out
    code, out, _ = run(capsys, "--json", "settings", "show")
    assert next(s for s in json.loads(out)["settings"] if s["key"] == "cell_ov")["value"] == 3.65


def test_settings_set_and_persist(capsys):
    code, out, _ = run(capsys, "settings", "set", "cell_ov", "3.7", "--yes")
    assert code == 0 and "一致を確認" in out
    # 別のプロセス相当（新しい CLI 実行）でも書き込みが残る
    code, out, _ = run(capsys, "--json", "settings", "show")
    assert next(s for s in json.loads(out)["settings"] if s["key"] == "cell_ov")["value"] == 3.7


def test_validation_error(capsys):
    code, _, err = run(capsys, "settings", "set", "cell_ov", "3.5", "--yes")
    assert code == cli.EXIT_ERROR and "解除値" in err


def test_switch_and_battery_type(capsys):
    assert run(capsys, "switch", "balance", "off", "--yes")[0] == 0
    assert run(capsys, "battery-type", "lto", "--yes")[0] == 0
    code, out, _ = run(capsys, "--json", "settings", "show")
    d = json.loads(out)
    assert d["battery_type"]["value"] == "lto" and d["switches"]["balance"]["value"] is False


def test_password_change_then_required(capsys):
    assert run(capsys, "password", "change", "--old", "123456", "--new", "112233", "--yes")[0] == 0
    code, _, err = run(capsys, "capacity", "set", "300", "--yes")
    assert code == cli.EXIT_ERROR and "パスワード" in err
    code, out, _ = run(capsys, "capacity", "set", "300", "--yes", "--password", "112233")
    assert code == 0
    code, out, _ = run(capsys, "--json", "capacity", "show")
    assert json.loads(out)["actual_capacity"]["value"] == 300.0


def test_history_paging(capsys):
    code, out, _ = run(capsys, "history", "--pages", "1")
    assert "--page 1" in out and "全 10 件" in out
    code, out, _ = run(capsys, "--json", "history", "--all")
    assert len(json.loads(out)["records"]) == 10


def test_pack_and_scan(capsys):
    code, out, _ = run(capsys, "pack", "DEMO-01", "DEMO-02", "DEMO-03", "--mode", "series")
    assert code == 0 and "直列・3 台" in out
    code, out, _ = run(capsys, "scan")
    assert "DEMO-03" in out


def test_json_error(capsys):
    code, out, _ = run(capsys, "--json", "settings", "set", "nope", "1", "--yes")
    assert code == cli.EXIT_ERROR and json.loads(out)["error"]["code"] == "unknown_setting"
