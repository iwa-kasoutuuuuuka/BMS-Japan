"""CLI と MCP サーバーで共通の操作。結果はすべて JSON にできる dict で返す。

書き込み操作の安全策:
- 入力を項目ごとの安全な範囲・保護値と解除値の関係で確認してから書く
- BMS のパスワードが初期値（123456）でなければ、正しいパスワードが必要
- ``confirm=True`` が無ければ書き込まず、書き込む予定の内容（プレビュー）だけを返す
- 書き込んだあと読み直して、結果（read_back）と一致したか（verified）を返す
"""

from __future__ import annotations

import logging
import os
import time
import secrets
import json
import hashlib
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

from . import __version__
from .client import BmsClient, BmsTimeoutError
from .labels import LIVE_LABELS, t
from .protocol.history import HistoryRecord
from .protocol.live import LiveData
from .protocol.pack import PackMode, PackSummary
from .protocol.recommended import (
    MAX_PARALLEL,
    MAX_SOH,
    MIN_PARALLEL,
    MIN_SOH,
    CellConditions,
    LeafGeneration,
    PresetOptions,
    RecommendedPreset,
    build_recommended_plan,
    rated_capacity_ah,
)
from .protocol.settings import (
    SETTINGS,
    SETTINGS_BY_KEY,
    ActualCapacity,
    BatteryType,
    BmsPassword,
    BmsSettings,
    PasswordChangeCheck,
    Setting,
    SwitchSetting,
    build_battery_preset_steps,
    preset_cell_count,
    validate,
)
from .simulator import DemoRegistry
from .transport.base import FoundDevice, Transport

log = logging.getLogger(__name__)

CURRENT_SIGN_NOTE = "電流の符号（向き）は実機で未確認のため、大きさと状態（レジスタ47）で表示しています。"

HISTORY_MAX_PAGES = 64  # 1 回に読むページ数の上限（448 件）
#: 履歴として読めるページ番号の上限（Android 版と同じ 100 ページ = 700 件）。
#: 256 + page が別のレジスタ（パスワードなど）に届かないようにする
HISTORY_PAGE_LIMIT = 100


class BmsJapanError(Exception):
    """利用者に見せるエラー。message は日本語（英語の補足つき）"""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(f"[{code}] {message}")
        self.code = code
        self.message = message


def env_flag(name: str) -> bool:
    return os.environ.get(name, "").strip().lower() in ("1", "true", "yes", "on")


def default_demo_state_file() -> Path:
    # 共有の一時フォルダだと、他のユーザーが同名のファイルやリンクを先に置けてしまうため
    d = Path.home() / ".bms_japan"
    d.mkdir(mode=0o700, parents=True, exist_ok=True)
    return d / "demo_state.json"


def q(value: Any, unit: str | None, label: str, **extra: Any) -> dict[str, Any]:
    d: dict[str, Any] = {"value": value, "unit": unit, "label": label}
    d.update(extra)
    return d


def r3(x: float) -> float:
    return round(x, 3)


# ---- JSON への変換 ----


def live_to_dict(live: LiveData) -> dict[str, Any]:
    L = LIVE_LABELS
    cells = [r3(v) for v in live.cell_voltages]
    max_i = cells.index(max(cells)) + 1 if cells else None
    min_i = cells.index(min(cells)) + 1 if cells else None
    return {
        "state": {"value": live.state.value, "label": live.state.label, "register": 47},
        "soc": q(round(live.soc, 1), "%", L["soc"]),
        "total_voltage": q(round(live.total_voltage, 1), "V", L["total_voltage"]),
        "current": q(
            round(abs(live.current), 1),
            "A",
            L["current"],
            direction=live.state.value,
            direction_label=live.state.label,
            raw_signed=round(live.current, 1),
            note=CURRENT_SIGN_NOTE,
        ),
        "power": q(round(live.power_kw, 3), "kW", L["power"]),
        "remaining_capacity": q(round(live.remaining_capacity, 1), "Ah", L["remaining_capacity"]),
        "cycle_count": q(live.cycle_count, "回", L["cycle_count"]),
        "cell_count": q(live.cell_count, "個", L["cell_count"]),
        "temperature_sensor_count": q(live.temperature_sensor_count, "個", L["temperature_sensor_count"]),
        "max_cell_voltage": q(r3(live.max_cell_voltage), "V", L["max_cell_voltage"]),
        "min_cell_voltage": q(r3(live.min_cell_voltage), "V", L["min_cell_voltage"]),
        "average_cell_voltage": q(r3(live.average_cell_voltage), "V", L["average_cell_voltage"]),
        "cell_voltage_delta": q(r3(live.cell_voltage_delta), "V", L["cell_voltage_delta"]),
        "max_temperature": q(live.max_temperature, "℃", L["max_temperature"]),
        "min_temperature": q(live.min_temperature, "℃", L["min_temperature"]),
        "charge_mos": q(live.charge_mos, None, L["charge_mos"], text="ON" if live.charge_mos else "OFF"),
        "discharge_mos": q(live.discharge_mos, None, L["discharge_mos"], text="ON" if live.discharge_mos else "OFF"),
        "balancing": q(live.balancing, None, L["balancing"], text="ON" if live.balancing else "OFF"),
        "cell_voltages": {
            "label": L["cell_voltages"],
            "unit": "V",
            "values": cells,
            "max_cell": max_i,
            "min_cell": min_i,
        },
        "temperatures": {"label": L["temperatures"], "unit": "℃", "values": list(live.temperatures)},
        "alarms": [
            {"key": a.name, "label": a.label, "register": a.register, "bit": a.bit} for a in live.alarms
        ],
    }


def setting_to_dict(s: Setting, current: BmsSettings) -> dict[str, Any]:
    v = current.get(s)
    rel = current.release(s)
    d: dict[str, Any] = {
        "key": s.key,
        "label": s.label,
        "group": s.group.name.lower(),
        "group_label": s.group.label,
        "register": s.register,
        "value": None if v is None else float(s.format(v)),
        "unit": s.unit,
        "decimals": s.decimals,
        "editable": s.editable,
    }
    if s.editable:
        d["min"] = s.min
        d["max"] = s.max
    if s.release_register is not None:
        d["release_register"] = s.release_register
        d["release_value"] = None if rel is None else float(s.format(rel))
        d["release_rule"] = s.release_rule.value
    return d


def settings_to_dict(current: BmsSettings) -> dict[str, Any]:
    bt = current.battery_type
    return {
        "battery_type": {
            "value": bt.name.lower() if bt else None,
            "code": current.raw.get(137),
            "label": bt.label if bt else "不明",
            "register": 137,
        },
        "switches": {
            sw.name.lower(): {
                "value": current.switch(sw),
                "text": "ON" if current.switch(sw) else "OFF",
                "label": sw.label,
                "register": sw.register,
            }
            for sw in SwitchSetting
        },
        "settings": [setting_to_dict(s, current) for s in SETTINGS],
    }


def record_to_dict(r: HistoryRecord) -> dict[str, Any]:
    return {
        "number": r.number,
        "time": r.time.isoformat(sep=" ") if r.time else None,
        "event_id": r.event_id,
        "event": r.event.label,
        "occurred": r.occurred,
        "occurred_label": "発生" if r.occurred else "解除",
        "total_voltage": round(r.total_voltage, 1),
        "current_abs": round(abs(r.current), 1),
        "current_raw_signed": round(r.current, 1),
        "soc": round(r.soc, 1),
        "charge_mos": r.charge_mos,
        "discharge_mos": r.discharge_mos,
        "max_cell_voltage": r3(r.max_cell_voltage),
        "max_cell_index": r.max_cell_index,
        "min_cell_voltage": r3(r.min_cell_voltage),
        "min_cell_index": r.min_cell_index,
        "max_temperature": r.max_temperature,
        "max_temperature_index": r.max_temperature_index,
        "min_temperature": r.min_temperature,
        "min_temperature_index": r.min_temperature_index,
        "fault_code": r.fault_code,
        "units": {"voltage": "V", "current": "A", "soc": "%", "temperature": "℃"},
    }


def pack_to_dict(p: PackSummary) -> dict[str, Any]:
    return {
        "mode": {"value": p.mode.name.lower(), "label": p.mode.label},
        "unit_count": p.unit_count,
        "state": {"value": p.state.value, "label": p.state.label},
        "voltage": q(round(p.voltage, 1), "V", t("label_total_voltage")),
        "current": q(round(p.current, 1), "A", t("label_current"), note=CURRENT_SIGN_NOTE),
        "power": q(round(p.power_kw, 3), "kW", t("label_power")),
        "remaining_capacity": q(round(p.remaining_ah, 1), "Ah", t("label_remaining")),
        "soc": q(round(p.soc, 1), "%", "SOC（残量）"),
        "max_cell_voltage": q(r3(p.max_cell_voltage), "V", "最高セル電圧"),
        "min_cell_voltage": q(r3(p.min_cell_voltage), "V", "最低セル電圧"),
        "cell_voltage_delta": q(r3(p.cell_voltage_delta), "V", "セル電圧差"),
        "max_temperature": q(p.max_temperature, "℃", t("label_temp_max")),
        "min_temperature": q(p.min_temperature, "℃", t("label_temp_min")),
        "alarm_unit_count": q(p.alarm_count, "台", "アラームが出ている台数"),
    }


# ---- 接続の管理 ----


@dataclass
class ManagerConfig:
    demo: bool = False
    demo_state_file: str | Path | None = None
    reply_timeout: float = 3.0
    poll_interval: float | None = None  # None なら定期読み出しをしない
    #: True なら address を指定した読み書きで、まだつないでいない機器に自動でつなぐ（CLI 用）。
    #: MCP では False（接続すると BMS に時刻を書き込むため、読み取りのツールが勝手に書き込まないように）
    auto_connect: bool = True
    #: True なら、書き込みにはプレビューで渡した confirm_token が必要（MCP 用）
    require_confirm_token: bool = False


#: 確認トークンの有効時間（秒）
CONFIRM_TOKEN_TTL_S = 120


class ConnectionManager:
    """複数台の BMS への接続をまとめて持つ。MCP では一度つないだら何度でも呼べる。"""

    def __init__(self, config: ManagerConfig | None = None) -> None:
        self.config = config or ManagerConfig()
        self.registry = DemoRegistry(self.config.demo_state_file) if self.config.demo else None
        self.clients: dict[str, BmsClient] = {}
        self._confirm_tokens: dict[str, tuple[str, float]] = {}

    # ---- 書き込みの確認トークン ----

    def issue_confirm_token(self, digest: str) -> str:
        """プレビューのときに発行する。書き込む内容（digest）に結び付き、1 回だけ・短時間だけ使える"""
        now = time.monotonic()
        self._confirm_tokens = {k: v for k, v in self._confirm_tokens.items() if v[1] > now}
        token = secrets.token_urlsafe(9)
        self._confirm_tokens[token] = (digest, now + CONFIRM_TOKEN_TTL_S)
        return token

    def consume_confirm_token(self, token: str | None, digest: str) -> bool:
        if not token:
            return False
        entry = self._confirm_tokens.pop(token, None)
        return entry is not None and entry[0] == digest and entry[1] > time.monotonic()

    @property
    def demo(self) -> bool:
        return self.config.demo

    def _transport(self, address: str) -> Transport:
        if self.demo:
            from .transport.demo import DemoTransport

            assert self.registry is not None
            return DemoTransport(address, self.registry)
        from .transport.ble import BleTransport

        return BleTransport(address)

    @staticmethod
    def _key(address: str) -> str:
        return address.strip().upper()

    async def scan(self, timeout: float = 8.0, include_all: bool = False) -> list[FoundDevice]:
        if self.demo:
            from .transport.demo import demo_scan

            assert self.registry is not None
            return demo_scan(self.registry)
        from .transport.ble import scan

        try:
            return await scan(timeout, include_all)
        except Exception as e:  # noqa: BLE001
            raise BmsJapanError("scan_failed", f"スキャンに失敗しました（Bluetooth がオンか確認してください）/ scan failed: {e}") from e

    async def connect(self, address: str) -> BmsClient:
        key = self._key(address)
        existing = self.clients.get(key)
        if existing and existing.connected:
            return existing
        if existing:
            await self._safe_close(existing)
        client = BmsClient(self._transport(key), reply_timeout=self.config.reply_timeout)
        try:
            await client.connect()
        except BmsJapanError:
            raise
        except Exception as e:  # noqa: BLE001
            await self._safe_close(client)
            raise BmsJapanError("connect_failed", f"{address} に接続できませんでした / could not connect: {e}") from e
        self.clients[key] = client
        if self.config.poll_interval:
            client.start_polling(self.config.poll_interval)
        return client

    @staticmethod
    async def _safe_close(client: BmsClient) -> None:
        try:
            await client.close()
        except Exception as e:  # noqa: BLE001
            log.debug("close error: %s", e)

    async def disconnect(self, address: str | None = None) -> list[str]:
        keys = [self._key(address)] if address else list(self.clients)
        closed = []
        for k in keys:
            c = self.clients.pop(k, None)
            if c:
                await self._safe_close(c)
                closed.append(k)
        return closed

    async def close_all(self) -> None:
        await self.disconnect(None)

    async def client(self, address: str | None) -> BmsClient:
        """address の接続を返す（無ければつなぐ）。address 省略時は接続が 1 台だけならそれを使う。"""
        if not address:
            live = [c for c in self.clients.values()]
            if len(live) == 1:
                address = live[0].address
            elif not live:
                raise BmsJapanError("no_address", "address を指定してください（scan_devices で探せます）/ address is required")
            else:
                raise BmsJapanError(
                    "ambiguous_address",
                    "複数台に接続中です。address を指定してください / several devices connected; specify address",
                )
        if not self.config.auto_connect and self._key(address) not in self.clients:
            raise BmsJapanError(
                "not_connected",
                "まだ接続していません。先に connect を呼んでください / not connected; call connect first",
            )
        return await self.connect(address)

    def list_connections(self) -> list[dict[str, Any]]:
        out = []
        for k, c in self.clients.items():
            out.append(
                {
                    "address": k,
                    "name": c.name,
                    "connected": c.connected,
                    "transport": c.transport.kind,
                    "module_auth": c.auth_result,
                    "time_synced": c.time_synced,
                    "last_update": datetime.fromtimestamp(c.last_update).isoformat(timespec="seconds")
                    if c.last_update
                    else None,
                }
            )
        return out


# ---- 操作 ----


def _mismatches(after: BmsSettings, steps: list[tuple[int, list[int]]]) -> list[dict[str, Any]]:
    """書いた値と、書いた後に読み直した値が違うレジスタ（BMS が受け付けなかった・自分で直した）。
    実機では、総電圧の保護値（143〜146）が書いた値ではなく前の値のまま残ることがあった"""
    out = []
    for reg, vals in steps:
        for i, v in enumerate(vals):
            got = after.raw.get(reg + i)
            if got != v:
                out.append({"register": reg + i, "wrote": v, "read_back": got})
    return out


def _header(client: BmsClient) -> dict[str, Any]:
    return {"address": client.address, "name": client.name}


def _now() -> str:
    return datetime.now().isoformat(timespec="seconds")


async def _io(coro: Any) -> Any:
    try:
        return await coro
    except BmsTimeoutError as e:
        raise BmsJapanError("timeout", f"BMS から応答がありません / no reply from BMS: {e}") from e
    except ConnectionError as e:
        raise BmsJapanError("not_connected", f"接続が切れています / connection lost: {e}") from e


async def scan_devices(mgr: ConnectionManager, timeout: float = 8.0, include_all: bool = False) -> dict[str, Any]:
    devices = await mgr.scan(timeout, include_all)
    return {
        "demo": mgr.demo,
        "devices": [
            {"address": d.address, "name": d.name, "rssi": d.rssi, "looks_like_bms": d.looks_like_bms} for d in devices
        ],
    }


async def connect(mgr: ConnectionManager, address: str) -> dict[str, Any]:
    c = await mgr.connect(address)
    return {
        **_header(c),
        "connected": True,
        "demo": mgr.demo,
        "module_auth": c.auth_result,
        "time_synced": c.time_synced,
    }


async def get_live_data(mgr: ConnectionManager, address: str | None = None) -> dict[str, Any]:
    c = await mgr.client(address)
    live = await _io(c.read_live())
    return {**_header(c), "read_at": _now(), **live_to_dict(live)}


async def get_settings(mgr: ConnectionManager, address: str | None = None) -> dict[str, Any]:
    c = await mgr.client(address)
    s = await _io(c.read_settings())
    return {**_header(c), "read_at": _now(), **settings_to_dict(s)}


async def _check_password(c: BmsClient, password: str | None) -> dict[str, Any]:
    """パスワードの確認。BMS から読み出したパスワードが初期値なら確認を省略（元アプリと同じ）"""
    current = await _io(c.read_password())
    if current == BmsPassword.DEFAULT:
        return {"password_required": False}
    if not password:
        raise BmsJapanError(
            "password_required",
            "この BMS はパスワードが変更されています。password を指定してください / BMS password required",
        )
    if password != current:
        raise BmsJapanError("wrong_password", "パスワードが違います / wrong BMS password")
    return {"password_required": True}


def _digest(action: str, address: str, params: dict[str, Any]) -> str:
    return hashlib.sha256(json.dumps([action, address, params], sort_keys=True, default=str).encode()).hexdigest()


def _gate(
    mgr: ConnectionManager,
    c: BmsClient,
    action: str,
    params: dict[str, Any],
    details: dict[str, Any],
    message: str,
    confirm: bool,
    confirm_token: str | None,
) -> dict[str, Any] | None:
    """confirm が無ければプレビューを返す。確認トークンが必要な設定なら、書き込み前にトークンを確かめる。
    書き込んでよければ None を返す。"""
    digest = _digest(action, c.address, params)
    if not confirm:
        out = {**_header(c), **_preview(action, details, message)}
        if mgr.config.require_confirm_token:
            out["confirm_token"] = mgr.issue_confirm_token(digest)
            out["confirm_token_expires_in_s"] = CONFIRM_TOKEN_TTL_S
            out["message"] = (
                message + " 利用者に内容を見せて同意を得てから、confirm=true と confirm_token を付けて呼び直してください。"
                " / Show this to the user, get explicit approval, then call again with confirm=true and confirm_token."
            )
        return out
    if mgr.config.require_confirm_token and not mgr.consume_confirm_token(confirm_token, digest):
        raise BmsJapanError(
            "confirm_token_required",
            "書き込みには、先に confirm なしで呼んで受け取った confirm_token が必要です（内容が同じで、"
            f"{CONFIRM_TOKEN_TTL_S} 秒以内・1 回限り）。 / A matching confirm_token from a preview call is required.",
        )
    return None


def _preview(action: str, details: dict[str, Any], message: str) -> dict[str, Any]:
    return {
        "ok": False,
        "dry_run": True,
        "action": action,
        "would_write": details,
        "message": message + " 実行するには confirm=true（CLI では --yes）を付けてください。"
        " / Not written. Re-run with confirm=true to apply.",
    }


async def set_setting(
    mgr: ConnectionManager,
    key: str,
    value: str | float,
    password: str | None = None,
    confirm: bool = False,
    address: str | None = None,
    confirm_token: str | None = None,
) -> dict[str, Any]:
    setting = SETTINGS_BY_KEY.get(key.strip().lower())
    if setting is None:
        keys = ", ".join(s.key for s in SETTINGS if s.editable)
        raise BmsJapanError("unknown_setting", f"不明な設定項目です: {key}（使える項目: {keys}）")
    if not setting.editable:
        raise BmsJapanError("not_editable", f"{setting.label} は表示のみで、変更できません / read-only setting")
    c = await mgr.client(address)
    current = await _io(c.read_settings())
    live_cells = (await _io(c.read_live())).cell_count if setting.key in ("pack_ov", "pack_uv") else None
    res = validate(setting, value, current, live_cells)
    if not res.ok:
        raise BmsJapanError(f"invalid_{res.kind}", f"{setting.label}: {res.message(setting)}")
    assert res.raw is not None
    await _check_password(c, password)
    before = current.get(setting)
    new_value = setting.encoding.decode(res.raw)
    details = {
        "key": setting.key,
        "label": setting.label,
        "register": setting.register,
        "raw": res.raw,
        "value": float(setting.format(new_value)),
        "unit": setting.unit,
        "before": None if before is None else float(setting.format(before)),
    }
    pre = _gate(
        mgr, c, "set_setting", {"key": setting.key, "raw": res.raw}, details,
        f"{setting.label} を {setting.format(new_value)} {setting.unit} に変更します。", confirm, confirm_token,
    )
    if pre is not None:
        return pre
    await _io(c.write_register(setting.register, res.raw))
    after = await _io(c.read_settings())
    after_raw = after.raw.get(setting.register)
    after_v = after.get(setting)
    return {
        **_header(c),
        "ok": True,
        "written": details,
        "read_back": {
            "raw": after_raw,
            "value": None if after_v is None else float(setting.format(after_v)),
            "unit": setting.unit,
        },
        "verified": after_raw == res.raw,
    }


async def set_switch(
    mgr: ConnectionManager,
    switch: str,
    on: bool,
    password: str | None = None,
    confirm: bool = False,
    address: str | None = None,
    confirm_token: str | None = None,
) -> dict[str, Any]:
    # "false" などの文字列は真偽値として扱わない（"false" は真と評価されてしまうため）
    if not isinstance(on, bool):
        raise BmsJapanError("invalid_value", "on は true / false で指定してください")
    try:
        sw = SwitchSetting.from_name(switch)
    except ValueError as e:
        raise BmsJapanError("unknown_switch", f"不明なスイッチです: {switch}（charge / discharge / balance）") from e
    c = await mgr.client(address)
    current = await _io(c.read_settings())
    await _check_password(c, password)
    value = 1 if on else 0
    details = {
        "switch": sw.name.lower(),
        "label": sw.label,
        "register": sw.register,
        "raw": value,
        "value": "ON" if on else "OFF",
        "before": None if current.switch(sw) is None else ("ON" if current.switch(sw) else "OFF"),
    }
    pre = _gate(
        mgr, c, "set_switch", {"switch": sw.name, "value": value}, details,
        sw.confirm_text(on).replace("\n", " "), confirm, confirm_token,
    )
    if pre is not None:
        return pre
    await _io(c.write_register(sw.register, value))
    after = await _io(c.read_settings())
    got = after.switch(sw)
    return {
        **_header(c),
        "ok": True,
        "written": details,
        "read_back": {"value": None if got is None else ("ON" if got else "OFF"), "raw": after.raw.get(sw.register)},
        "verified": got == on,
    }


async def set_battery_type(
    mgr: ConnectionManager,
    battery_type: str,
    password: str | None = None,
    confirm: bool = False,
    address: str | None = None,
    confirm_token: str | None = None,
) -> dict[str, Any]:
    try:
        bt = BatteryType.from_name(battery_type)
    except ValueError as e:
        raise BmsJapanError("unknown_battery_type", f"不明な電池の種類です: {battery_type}（lfp / ncm / lto）") from e
    c = await mgr.client(address)
    live = await _io(c.read_live())
    current = await _io(c.read_settings())
    n = preset_cell_count(current, live.cell_count)
    if n is None:
        raise BmsJapanError(
            "cell_count_mismatch",
            f"設定のセル数（{current.raw.get(130)}）と運転データのセル数（{live.cell_count}）が違う（または読めない）ため、書き込みを中止しました",
        )
    if not bt.available_for(n):
        raise BmsJapanError(
            "battery_type_unavailable",
            f"{n} セルでは {bt.label} は選べません（三元系は 20 セル以下、チタン酸は 6 セル以上）",
        )
    steps = build_battery_preset_steps(bt, n, current)
    if steps is None:
        raise BmsJapanError(
            "settings_incomplete", "今の設定値を読み切れなかった（または書き込み後の値が新しい種類の安全範囲を外れる）ため、書き込みを中止しました"
        )
    values = steps[-1][1]
    await _check_password(c, password)
    changed = {
        str(reg + i): {"before": current.raw.get(reg + i), "after": v}
        for reg, vals in steps
        for i, v in enumerate(vals)
        if current.raw.get(reg + i) != v
    }
    details = {
        "battery_type": bt.name.lower(),
        "label": bt.label,
        "cell_count": n,
        "start_register": 137,
        "count": len(values),
        "values": values,
        "changed_registers": changed,
        "cell_ov": bt.cell_ov,
        "cell_ov_release": bt.cell_ov_release,
        "cell_uv": bt.cell_uv,
        "cell_uv_release": bt.cell_uv_release,
    }
    pre = _gate(
        mgr, c, "set_battery_type", {"type": bt.name, "steps": steps}, details,
        f"電池の種類を {bt.label} にします。{t('battery_preset_warning', n)}", confirm, confirm_token,
    )
    if pre is not None:
        return pre
    for reg, vals in steps:
        await _io(c.write_registers(reg, vals) if len(vals) > 1 else c.write_register(reg, vals[0]))
    after = await _io(c.read_settings())
    got = [after.raw.get(137 + i) for i in range(len(values))]
    bt_after = after.battery_type
    return {
        **_header(c),
        "ok": True,
        "written": details,
        "read_back": {
            "battery_type": bt_after.name.lower() if bt_after else None,
            "label": bt_after.label if bt_after else None,
            "values": got,
        },
        "verified": not _mismatches(after, steps),
        "mismatches": _mismatches(after, steps),
    }


async def set_recommended_preset(
    mgr: ConnectionManager,
    preset: str,
    password: str | None = None,
    confirm: bool = False,
    address: str | None = None,
    confirm_token: str | None = None,
    accept_immediate_effect: bool = False,
    accept_chemistry_change: bool = False,
    generation: str | None = None,
    parallel: int | None = None,
    soh: int | None = None,
) -> dict[str, Any]:
    """おすすめ設定（日産リーフなど）をまとめて書き込む。電流の保護値は変えない。
    generation を指定すると公称電圧を世代に合わせる。さらに soh（%）を指定すると、
    定格容量（セル容量 × 並列数 × SOH）も書き込む（parallel を省略すると車両の並列数）"""
    for name, flag in (("accept_immediate_effect", accept_immediate_effect), ("accept_chemistry_change", accept_chemistry_change)):
        if not isinstance(flag, bool):
            raise BmsJapanError("invalid_value", f"{name} は true / false で指定してください")
    try:
        rp = RecommendedPreset.from_name(preset)
    except ValueError as e:
        names = " / ".join(p.name.lower() for p in RecommendedPreset)
        raise BmsJapanError("unknown_preset", f"不明なおすすめ設定です: {preset}（{names}）") from e
    gen = None
    if generation is not None:
        try:
            gen = LeafGeneration.from_name(generation)
        except ValueError as e:
            names = " / ".join(g.name.lower() for g in LeafGeneration)
            raise BmsJapanError("unknown_generation", f"不明な世代です: {generation}（{names}）") from e
    capacity = None
    if soh is not None or parallel is not None:
        if gen is None:
            raise BmsJapanError("generation_required", "定格容量を計算するには generation（世代）も指定してください")
        par = gen.default_parallel if parallel is None else parallel
        capacity = rated_capacity_ah(gen, par, soh if soh is not None else -1)
        if capacity is None:
            raise BmsJapanError(
                "invalid_value",
                f"並列数は {MIN_PARALLEL}〜{MAX_PARALLEL}、SOH は {MIN_SOH}〜{MAX_SOH}% の整数で指定してください",
            )
    options = PresetOptions(gen, capacity)
    c = await mgr.client(address)
    live = await _io(c.read_live())
    current = await _io(c.read_settings())
    n = preset_cell_count(current, live.cell_count)
    if n is None or not rp.v.type.available_for(n):
        raise BmsJapanError(
            "preset_unavailable",
            f"セル数（{n if n is not None else live.cell_count}）では {rp.v.label} は使えません（三元系は 20 セル以下）",
        )
    plan = build_recommended_plan(rp, n, current, options)
    if plan is None:
        raise BmsJapanError("settings_incomplete", "今の設定値を読み切れなかった（または安全範囲の確認を通らなかった）ため、書き込みを中止しました")
    await _check_password(c, password)
    cond = CellConditions.of(live)
    effect = plan.immediate_effect(cond)
    p = rp.v
    temps = f"{cond.min_temp}〜{cond.max_temp}"
    warning = {
        None: None,
        "discharge_stops": f"今のセル電圧（最低 {live.min_cell_voltage:.3f} V）は新しいセル低電圧の解除値 {p.cell_uv_release:.2f} V より低いため、"
        "書き込むとすぐに放電が止まります。先に充電することをおすすめします",
        "discharge_stops_temp": f"今の温度（{temps} ℃）は新しい放電できる温度（解除値 {p.discharge_ut_release}〜{p.discharge_ot_release} ℃）の外のため、"
        "書き込むとすぐに放電が止まることがあります",
        "charge_stops": f"今のセル電圧（最高 {live.max_cell_voltage:.3f} V）は新しいセル過電圧の解除値 {p.cell_ov_release:.2f} V より高いため、"
        "書き込むとすぐに充電が止まることがあります",
        "charge_stops_temp": f"今の温度（{temps} ℃）は新しい充電できる温度（解除値 {p.charge_ut_release}〜{p.charge_ot_release} ℃）の外のため、"
        "書き込むとすぐに充電が止まることがあります",
    }[effect]
    chemistry_warning = None
    if plan.changes_chemistry:
        before = plan.from_type.label if plan.from_type else "不明"
        chemistry_warning = (
            f"電池の種類が「{before}」から「{p.type.label}」に変わり、セル過電圧の保護が {p.cell_ov:.2f} V になります。"
            "つながっているセルがリーフ（マンガン系・三元系）でない場合、過充電の保護が効かなくなり危険です。"
            "利用者にセルの種類を確かめてもらってください"
        )
    details = {
        "preset": rp.name.lower(),
        "label": rp.v.label,
        "description": rp.v.description,
        "cell_count": n,
        "changes": plan.changes,
        "block": {"start_register": 137, "values": plan.block},
        "extra_registers": {str(r): v for r, v in plan.extra},
        "immediate_effect": effect,
        "warning": warning,
        "chemistry_change": {"from": plan.from_type.name.lower() if plan.from_type else None, "to": p.type.name.lower()}
        if plan.changes_chemistry
        else None,
        "chemistry_warning": chemistry_warning,
        "generation": {"key": gen.name.lower(), "label": gen.label, "nominal_v": gen.nominal} if gen else None,
        "rated_capacity_ah": capacity,
        "generation_hint": "この世代は熱で劣化しやすいため leaf_long_life をすすめます"
        if gen is not None and gen.suggest_long_life and rp is RecommendedPreset.LEAF_STANDARD
        else None,
        "note": "電流の保護値はそのままです。普段の充電を止める電圧は、充電器・インバーター側でこれより低く（目安: セル 4.05〜4.10 V）設定してください",
    }
    if effect is not None and confirm and not accept_immediate_effect:
        raise BmsJapanError("immediate_effect", (warning or "") + "。それでも書き込む場合は accept_immediate_effect=true を付けてください")
    if plan.changes_chemistry and confirm and not accept_chemistry_change:
        raise BmsJapanError(
            "chemistry_change", (chemistry_warning or "") + "。確認できた場合だけ accept_chemistry_change=true を付けてください"
        )
    # 確認トークンは、警告の有無と了承の指定にも結びつける（プレビューで見せていない了承を後から付けられないように）
    pre = _gate(
        mgr, c, "set_recommended_preset",
        {
            "preset": rp.name, "steps": plan.steps, "effect": effect, "chemistry_change": plan.changes_chemistry,
            "accept_immediate_effect": accept_immediate_effect, "accept_chemistry_change": accept_chemistry_change,
        },
        details,
        f"おすすめ設定「{p.label}」（{n} セル）を書き込みます。"
        + (f"注意: {chemistry_warning}" if chemistry_warning else "")
        + (f"注意: {warning}" if warning else ""),
        confirm, confirm_token,
    )  # fmt: skip
    if pre is not None:
        return pre
    for reg, vals in plan.steps:
        await _io(c.write_registers(reg, vals) if len(vals) > 1 else c.write_register(reg, vals[0]))
    after = await _io(c.read_settings())
    got = [after.raw.get(137 + i) for i in range(len(plan.block))]
    got_extra = {str(r): after.raw.get(r) for r, _ in plan.extra}
    return {
        **_header(c),
        "ok": True,
        "written": details,
        "read_back": {"values": got, "extra_registers": got_extra},
        "verified": not _mismatches(after, plan.steps),
        "mismatches": _mismatches(after, plan.steps),
    }


async def change_password(
    mgr: ConnectionManager,
    old_password: str,
    new_password: str,
    repeat_password: str | None = None,
    confirm: bool = False,
    address: str | None = None,
    confirm_token: str | None = None,
) -> dict[str, Any]:
    c = await mgr.client(address)
    current = await _io(c.read_password())
    repeat = new_password if repeat_password is None else repeat_password
    check = BmsPassword.validate_change(current, old_password, new_password, repeat)
    if check is not PasswordChangeCheck.OK:
        raise BmsJapanError(f"password_{check.value}", check.message)
    details = {"start_register": BmsPassword.START, "count": BmsPassword.COUNT, "new_password_length": len(new_password)}
    # トークンには新しいパスワードそのものではなく、ハッシュを結び付ける
    pre = _gate(
        mgr, c, "change_password", {"new_sha256": hashlib.sha256(new_password.encode()).hexdigest()}, details,
        "BMS のパスワードを変更します（新しいパスワードを忘れないようにしてください）。", confirm, confirm_token,
    )
    if pre is not None:
        return pre
    await _io(c.write_password(new_password))
    after = await _io(c.read_password())
    return {**_header(c), "ok": after == new_password, "written": details, "verified": after == new_password}


async def get_history(
    mgr: ConnectionManager,
    page: int = 0,
    pages: int = 1,
    address: str | None = None,
) -> dict[str, Any]:
    if page < 0 or page >= HISTORY_PAGE_LIMIT:
        raise BmsJapanError("invalid_page", f"page は 0〜{HISTORY_PAGE_LIMIT - 1} にしてください")
    pages = max(1, min(HISTORY_MAX_PAGES, pages, HISTORY_PAGE_LIMIT - page))
    c = await mgr.client(address)
    records: list[HistoryRecord] = []
    reached_end = False
    total: int | None = None
    p = page
    for p in range(page, page + pages):
        res = await _io(c.read_history_page(p))
        records += res.records
        if res.records:
            total = res.records[0].total_count
        if res.reached_end or not res.records:
            reached_end = True
            break
        if total is not None and records and records[-1].number >= total:
            reached_end = True
            break
    pages_read = p - page + 1
    return {
        **_header(c),
        "start_page": page,
        "pages_read": pages_read,
        "next_page": None if reached_end else page + pages_read,
        "reached_end": reached_end,
        "total_count": total,
        "records": [record_to_dict(r) for r in records],
    }


async def get_actual_capacity(mgr: ConnectionManager, address: str | None = None) -> dict[str, Any]:
    c = await mgr.client(address)
    mah = await _io(c.read_actual_capacity())
    return {
        **_header(c),
        "actual_capacity": q(round(mah / 1000.0, 1), "Ah", t("set_actual_capacity"), mah=mah),
    }


async def set_actual_capacity(
    mgr: ConnectionManager,
    capacity_ah: str | float,
    password: str | None = None,
    confirm: bool = False,
    address: str | None = None,
    confirm_token: str | None = None,
) -> dict[str, Any]:
    mah = ActualCapacity.parse(capacity_ah)
    if mah is None:
        raise BmsJapanError(
            "invalid_capacity",
            f"実容量は {ActualCapacity.MIN_AH:.0f}〜{ActualCapacity.MAX_AH:.0f} Ah の範囲で入力してください",
        )
    c = await mgr.client(address)
    before = await _io(c.read_actual_capacity())
    await _check_password(c, password)
    details = {
        "slave": "0x81",
        "register": ActualCapacity.REGISTER,
        "mah": mah,
        "value": round(mah / 1000.0, 1),
        "unit": "Ah",
        "before": round(before / 1000.0, 1),
    }
    pre = _gate(
        mgr, c, "set_actual_capacity", {"mah": mah}, details,
        f"実容量を {ActualCapacity.format(mah)} Ah にします。", confirm, confirm_token,
    )
    if pre is not None:
        return pre
    await _io(c.write_actual_capacity(mah))
    after = await _io(c.read_actual_capacity())
    return {
        **_header(c),
        "ok": True,
        "written": details,
        "read_back": {"mah": after, "value": round(after / 1000.0, 1), "unit": "Ah"},
        "verified": after == mah,
    }


async def get_pack_summary(
    mgr: ConnectionManager,
    addresses: list[str],
    mode: str = "parallel",
) -> dict[str, Any]:
    try:
        pm = PackMode.from_name(mode)
    except ValueError as e:
        raise BmsJapanError("invalid_mode", f"不明なつなぎ方です: {mode}（single / parallel / series）") from e
    # 同じ台が二重に入ると、電流や容量を二重に数えてしまうので除く
    addresses = list(dict.fromkeys(a.strip().upper() for a in addresses if a and a.strip()))
    if not addresses:
        addresses = list(mgr.clients)
    if not addresses:
        raise BmsJapanError("no_address", "addresses を指定してください / addresses required")
    if len(addresses) > pm.max_units:
        raise BmsJapanError("too_many_units", f"{pm.label}は最大 {pm.max_units} 台までです")
    units: list[dict[str, Any]] = []
    lives: list[LiveData] = []
    for a in addresses:
        c = await mgr.client(a)
        live = await _io(c.read_live())
        lives.append(live)
        units.append(
            {
                **_header(c),
                "soc": round(live.soc, 1),
                "total_voltage": round(live.total_voltage, 1),
                "current_abs": round(abs(live.current), 1),
                "state": live.state.label,
                "alarm_count": len(live.alarms),
            }
        )
    summary = PackSummary.of(pm, lives)
    assert summary is not None
    return {"read_at": _now(), "summary": pack_to_dict(summary), "units": units}


def server_info(mgr: ConnectionManager) -> dict[str, Any]:
    return {"version": __version__, "demo": mgr.demo}
