"""BMS Japan の MCP サーバー（stdio）。Claude Code や Google Antigravity から BMS を操作する。

起動: ``python -m bms_japan.mcp_server``（または ``bms-japan-mcp``）
デモ: 環境変数 ``BMS_JAPAN_DEMO=1`` で疑似 BMS（DEMO-01〜03）を使う。

書き込み系のツールは、すべて ``confirm: true`` が必要です。confirm が無いと書き込まずに
「書き込む予定の内容」を返します。BMS のパスワードが初期値（123456）でなければ password も必要です。
"""

from __future__ import annotations

import functools
import logging
import os
import sys
from collections.abc import Awaitable, Callable
from typing import Annotated, Any, TypeVar

from pydantic import Field

from . import __version__
from . import service as svc
from .protocol.settings import SETTINGS
from .service import ConnectionManager, ManagerConfig

try:  # mcp 2.x
    from mcp.server.mcpserver import MCPServer as _Server
    from mcp.server.mcpserver.exceptions import ToolError
except ImportError:  # mcp 1.x
    from mcp.server.fastmcp import FastMCP as _Server  # type: ignore[no-redef]
    from mcp.server.fastmcp.exceptions import ToolError  # type: ignore[no-redef]

try:
    from mcp.types import ToolAnnotations
except ImportError:  # pragma: no cover
    ToolAnnotations = None  # type: ignore[assignment,misc]

log = logging.getLogger("bms_japan.mcp")

EDITABLE_KEYS = ", ".join(f"{s.key}（{s.label}, {s.min:g}〜{s.max:g} {s.unit}）" for s in SETTINGS if s.editable)

INSTRUCTIONS = f"""\
BMS Japan: Balance BMS（アクティブバランサー BMS、元アプリ com.inuker.bluetooth.junhengbao）を BLE で操作する非公式ツール。
Unofficial tool for "Balance BMS" active-balancer lithium BMS over Bluetooth LE.

使い方 / Usage:
1. scan_devices で BMS を探す（名前に JHB を含む機器）→ 2. connect(address) → 3. get_live_data などを呼ぶ。
   address を省略すると、接続中の 1 台を使います。
- 書き込み（set_setting / set_switch / set_battery_type / change_password / set_actual_capacity）は BMS の保護動作を変えます。
  必ず利用者の明示的な同意を得てから confirm=true で呼んでください。confirm が無い呼び出しは書き込まず、予定内容を返します。
  Writes change BMS protection behaviour. Always get explicit user approval, then call with confirm=true.
- パスワードが初期値 123456 以外の BMS では password が必要です。
- 電流の符号（向き）は実機で未確認です。大きさ（value）と状態（direction）を使ってください。
- 実機での動作確認はまだしていません（プロトコルは元アプリの解析に基づく）。
{"- 現在デモモードです（疑似 BMS: DEMO-01, DEMO-02, DEMO-03）。" if svc.env_flag("BMS_JAPAN_DEMO") else ""}
"""

mcp = _Server("bms-japan", instructions=INSTRUCTIONS)

_manager: ConnectionManager | None = None

F = TypeVar("F", bound=Callable[..., Awaitable[Any]])


def tool(**kwargs: Any) -> Callable[[F], F]:
    """mcp.tool に、想定内のエラー（BmsJapanError）を ToolError に変える処理を足したもの。

    ToolError にしないと、MCP クライアントには「Error executing tool」としか伝わらない。
    """

    def deco(fn: F) -> F:
        @functools.wraps(fn)
        async def wrapper(*args: Any, **kw: Any) -> Any:
            try:
                return await fn(*args, **kw)
            except svc.BmsJapanError as e:
                raise ToolError(str(e)) from e

        mcp.tool(**kwargs)(wrapper)
        return fn

    return deco


def manager() -> ConnectionManager:
    global _manager
    if _manager is None:
        demo = svc.env_flag("BMS_JAPAN_DEMO")
        _manager = ConnectionManager(
            ManagerConfig(
                demo=demo,
                demo_state_file=os.environ.get("BMS_JAPAN_DEMO_STATE") or None,
                reply_timeout=float(os.environ.get("BMS_JAPAN_TIMEOUT", "3.0")),
                poll_interval=5.0,
            )
        )
    return _manager


def _ann(read_only: bool, destructive: bool = False, title: str | None = None) -> dict[str, Any]:
    if ToolAnnotations is None:
        return {}
    return {
        "annotations": ToolAnnotations(
            title=title,
            readOnlyHint=read_only,
            destructiveHint=destructive,
            idempotentHint=read_only,
            openWorldHint=False,
        )
    }


Address = Annotated[
    str | None,
    Field(description="BMS のアドレス（scan_devices の address）。省略時は接続中の 1 台 / BMS address; defaults to the single connected device"),
]
Password = Annotated[
    str | None,
    Field(description="BMS のパスワード（6 桁）。初期値 123456 のままなら不要 / BMS password, needed only if changed from default"),
]
Confirm = Annotated[
    bool,
    Field(description="true のときだけ実際に書き込む。false なら書き込む予定の内容を返す / must be true to actually write; false = preview only"),
]


# ---- 接続 ----


@tool(
    description="周囲の Balance BMS を BLE で探す（名前に JHB を含む機器）。Scan for Balance BMS devices over BLE.",
    **_ann(True, title="BMS を探す / Scan devices"),
)
async def scan_devices(
    timeout_s: Annotated[float, Field(description="探す秒数 / scan seconds", ge=1, le=30)] = 8.0,
    include_all: Annotated[bool, Field(description="JHB 以外の機器も含める / include non-JHB devices")] = False,
) -> dict[str, Any]:
    return await svc.scan_devices(manager(), timeout_s, include_all)


@tool(
    description="BMS に接続する（Notify 有効化・認証・時刻同期まで行い、以後 5 秒ごとに運転データを読む）。"
    " Connect to a BMS (enables notify, module auth, time sync; then polls every 5 s).",
    **_ann(False, title="接続 / Connect"),
)
async def connect(address: Annotated[str, Field(description="BMS のアドレス / BMS address (e.g. from scan_devices)")]) -> dict[str, Any]:
    return await svc.connect(manager(), address)


@tool(
    description="BMS との接続を切る。address 省略で全台。Disconnect one BMS (or all when address is omitted).",
    **_ann(False, title="切断 / Disconnect"),
)
async def disconnect(address: Address = None) -> dict[str, Any]:
    closed = await manager().disconnect(address)
    return {"disconnected": closed}


@tool(description="接続中の BMS の一覧。List current connections.", **_ann(True, title="接続一覧 / Connections"))
async def list_connections() -> dict[str, Any]:
    m = manager()
    return {"demo": m.demo, "version": __version__, "connections": m.list_connections()}


# ---- 読み出し ----


@tool(
    description="運転データ（SOC・総電圧・電流・セル電圧・温度・MOS・アラーム）を読む。値・単位・日本語ラベル付き。"
    " Read live data (SOC, voltages, current magnitude + direction, cells, temperatures, MOS, alarms).",
    **_ann(True, title="運転データ / Live data"),
)
async def get_live_data(address: Address = None) -> dict[str, Any]:
    return await svc.get_live_data(manager(), address)


@tool(
    description="設定値（保護値と解除値・電池の種類・スイッチ・均等化）を読む。各項目の key は set_setting で使う。"
    " Read settings (protection/release values, battery type, switches, balancing). Keys are used by set_setting.",
    **_ann(True, title="設定値 / Settings"),
)
async def get_settings(address: Address = None) -> dict[str, Any]:
    return await svc.get_settings(manager(), address)


@tool(
    description="故障履歴を読む（1 ページ 7 件、page は 0 から）。next_page が null なら終わり。"
    " Read fault history (7 records per page, page starts at 0). next_page=null means end.",
    **_ann(True, title="故障履歴 / History"),
)
async def get_history(
    address: Address = None,
    page: Annotated[int, Field(description="最初のページ（0〜99）/ first page (0-99)", ge=0, le=svc.HISTORY_PAGE_LIMIT - 1)] = 0,
    pages: Annotated[int, Field(description="読むページ数 / number of pages", ge=1, le=svc.HISTORY_MAX_PAGES)] = 4,
) -> dict[str, Any]:
    return await svc.get_history(manager(), page, pages, address)


@tool(
    description="実容量（Ah）を読む（スレーブ 0x81）。Read actual (learned) capacity in Ah.",
    **_ann(True, title="実容量 / Actual capacity"),
)
async def get_actual_capacity(address: Address = None) -> dict[str, Any]:
    return await svc.get_actual_capacity(manager(), address)


@tool(
    description="複数台をまとめた値（並列: 電圧平均・電流と容量は合計 / 直列: 電圧合計・SOC と容量は最小）。"
    " Combined pack summary for several BMS units (parallel or series).",
    **_ann(True, title="まとめ / Pack summary"),
)
async def get_pack_summary(
    addresses: Annotated[list[str], Field(description="BMS のアドレス（最大 6 台）。空なら接続中の全台 / addresses; empty = all connected")] = [],  # noqa: B006
    mode: Annotated[str, Field(description="parallel（並列）/ series（直列）/ single（単体）")] = "parallel",
) -> dict[str, Any]:
    return await svc.get_pack_summary(manager(), list(addresses), mode)


# ---- 書き込み（confirm 必須） ----


@tool(
    description="設定値を 1 つ変更する（範囲と解除値との関係を確認してから書き込み、読み直して結果を返す）。"
    " confirm=true が必要。利用者の同意を得てから使うこと。"
    f" Change one setting (validated, written, read back). Requires confirm=true. Keys: {EDITABLE_KEYS}",
    **_ann(False, destructive=True, title="設定の変更 / Set setting"),
)
async def set_setting(
    key: Annotated[str, Field(description="設定項目の key（get_settings の key）/ setting key")],
    value: Annotated[float, Field(description="新しい値（表示と同じ単位: V, A, ℃, Ah, %, 秒）/ new value in display units")],
    confirm: Confirm = False,
    password: Password = None,
    address: Address = None,
) -> dict[str, Any]:
    return await svc.set_setting(manager(), key, value, password, confirm, address)


@tool(
    description="充電MOS・放電MOS・均等化を ON/OFF する。放電MOSを OFF にすると負荷への電気が止まる。confirm=true が必要。"
    " Turn charge MOS / discharge MOS / balancing on or off. Turning discharge off cuts power to loads. Requires confirm=true.",
    **_ann(False, destructive=True, title="スイッチ / Set switch"),
)
async def set_switch(
    switch: Annotated[str, Field(description="charge（充電MOS）/ discharge（放電MOS）/ balance（均等化）")],
    on: Annotated[bool, Field(description="true=ON, false=OFF")],
    confirm: Confirm = False,
    password: Password = None,
    address: Address = None,
) -> dict[str, Any]:
    return await svc.set_switch(manager(), switch, on, password, confirm, address)


@tool(
    description="電池の種類を変える（セル・総電圧の保護値/解除値と均等化開始電圧がまとめて書き換わる）。confirm=true が必要。"
    " Change battery chemistry preset (rewrites cell/pack voltage protections). Requires confirm=true.",
    **_ann(False, destructive=True, title="電池の種類 / Battery type"),
)
async def set_battery_type(
    battery_type: Annotated[str, Field(description="lfp（リン酸鉄）/ ncm（三元系）/ lto（チタン酸）")],
    confirm: Confirm = False,
    password: Password = None,
    address: Address = None,
) -> dict[str, Any]:
    return await svc.set_battery_type(manager(), battery_type, password, confirm, address)


@tool(
    description="BMS のパスワードを変更する（6 桁の数字。書き込み後に読み直して確認）。confirm=true が必要。"
    " Change the BMS password (exactly 6 digits, verified by reading back). Requires confirm=true.",
    **_ann(False, destructive=True, title="パスワード変更 / Change password"),
)
async def change_password(
    old_password: Annotated[str, Field(description="今のパスワード / current password")],
    new_password: Annotated[str, Field(description="新しいパスワード（6 桁の数字）/ new 6-digit password")],
    confirm: Confirm = False,
    address: Address = None,
) -> dict[str, Any]:
    return await svc.change_password(manager(), old_password, new_password, None, confirm, address)


@tool(
    description="実容量（Ah, 1〜2000）を書き込む（スレーブ 0x81）。confirm=true が必要。"
    " Set actual capacity in Ah (1-2000). Requires confirm=true.",
    **_ann(False, destructive=True, title="実容量の変更 / Set actual capacity"),
)
async def set_actual_capacity(
    capacity_ah: Annotated[float, Field(description="実容量（Ah）/ capacity in Ah", ge=1, le=2000)],
    confirm: Confirm = False,
    password: Password = None,
    address: Address = None,
) -> dict[str, Any]:
    return await svc.set_actual_capacity(manager(), capacity_ah, password, confirm, address)


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("BMS_JAPAN_LOG", "WARNING").upper(),
        format="%(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,  # stdout は MCP の通信に使うので、ログは stderr へ
    )
    mcp.run()


if __name__ == "__main__":
    main()
