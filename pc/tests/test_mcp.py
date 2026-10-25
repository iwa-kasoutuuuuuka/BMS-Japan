"""MCP サーバーの試験（デモモード）。

1. 同じプロセスの中でツールを呼ぶ
2. 実際に ``python -m bms_japan.mcp_server`` を stdio で起動し、MCP クライアントからツール一覧と呼び出しを確かめる
"""

import json
import os
import sys

import pytest

os.environ["BMS_JAPAN_DEMO"] = "1"

from bms_japan import mcp_server  # noqa: E402

EXPECTED_TOOLS = {
    "scan_devices",
    "connect",
    "disconnect",
    "list_connections",
    "get_live_data",
    "get_settings",
    "set_setting",
    "set_switch",
    "set_battery_type",
    "change_password",
    "get_history",
    "get_actual_capacity",
    "set_actual_capacity",
    "get_pack_summary",
}
WRITE_TOOLS = {"set_setting", "set_switch", "set_battery_type", "change_password", "set_actual_capacity"}


def _payload(result):
    """CallToolResult から dict を取り出す（mcp 1.x / 2.x の両方に対応）"""
    sc = getattr(result, "structured_content", None)
    if sc is None:
        sc = getattr(result, "structuredContent", None)
    if isinstance(sc, dict):
        return sc.get("result", sc) if set(sc) == {"result"} else sc
    return json.loads(result.content[0].text)


def _is_error(result):
    v = getattr(result, "is_error", None)
    return bool(v if v is not None else getattr(result, "isError", False))


def _schema(tool):
    return getattr(tool, "input_schema", None) or tool.inputSchema


@pytest.fixture(autouse=True)
async def fresh_manager():
    mcp_server._manager = None
    yield
    if mcp_server._manager:
        await mcp_server._manager.close_all()
    mcp_server._manager = None


async def test_tools_listed_with_confirm_on_writes():
    tools = {t.name: t for t in await mcp_server.mcp.list_tools()}
    assert set(tools) == EXPECTED_TOOLS
    for name in WRITE_TOOLS:
        props = _schema(tools[name])["properties"]
        assert "confirm" in props, name
        assert props["confirm"].get("default") is False
        assert "confirm=true" in tools[name].description
    assert "password" in _schema(tools["set_setting"])["properties"]


async def test_in_process_flow():
    mgr = mcp_server.manager()
    from bms_japan import service as svc

    assert mgr.demo
    await svc.connect(mgr, "DEMO-01")
    preview = await svc.set_setting(mgr, "soc", 55.0)
    assert preview["dry_run"]
    done = await svc.set_setting(mgr, "soc", 55.0, confirm=True)
    assert done["verified"]


async def test_tool_error_message_reaches_client():
    from mcp.server.mcpserver.exceptions import ToolError

    with pytest.raises(ToolError) as e:
        await mcp_server.mcp.call_tool("set_setting", {"key": "cell_ov", "value": 9.9, "address": "DEMO-01"})
    assert "invalid_out_of_range" in str(e.value)


async def test_stdio_server_smoke(tmp_path):
    """実際にサーバーを子プロセスで起動して、MCP クライアントからつなぐ"""
    from mcp import ClientSession
    from mcp.client.stdio import StdioServerParameters, stdio_client

    params = StdioServerParameters(
        command=sys.executable,
        args=["-m", "bms_japan.mcp_server"],
        env={"BMS_JAPAN_DEMO": "1"},  # 文字コードの指定なしでも日本語が化けないこと
        cwd=str(tmp_path),
    )
    with open(tmp_path / "stderr.log", "w", encoding="utf-8") as errlog:
        async with stdio_client(params, errlog=errlog) as (read, write):
            async with ClientSession(read, write) as session:
                init = await session.initialize()
                info = getattr(init, "server_info", None) or init.serverInfo
                assert info.name == "bms-japan"
                listed = await session.list_tools()
                assert {t.name for t in listed.tools} == EXPECTED_TOOLS

                r = await session.call_tool("scan_devices", {})
                assert [d["address"] for d in _payload(r)["devices"]] == ["DEMO-01", "DEMO-02", "DEMO-03"]

                r = await session.call_tool("connect", {"address": "DEMO-01"})
                assert _payload(r)["connected"]

                r = await session.call_tool("get_live_data", {})
                live = _payload(r)
                assert live["state"]["label"] == "充電中"  # 日本語が化けずに届く
                assert len(live["cell_voltages"]["values"]) == 16

                # confirm なし → プレビューだけ
                r = await session.call_tool("set_switch", {"switch": "charge", "on": False})
                assert _payload(r)["dry_run"] is True
                r = await session.call_tool("set_switch", {"switch": "charge", "on": False, "confirm": True})
                assert _payload(r)["verified"] is True

                r = await session.call_tool("get_history", {"page": 1, "pages": 1})
                assert _payload(r)["reached_end"] is True

                r = await session.call_tool("get_pack_summary", {"addresses": ["DEMO-01", "DEMO-02"], "mode": "series"})
                assert _payload(r)["summary"]["mode"]["label"] == "直列"

                r = await session.call_tool("set_actual_capacity", {"capacity_ah": 5000, "confirm": True})
                assert _is_error(r)  # 範囲外は書き込まずにエラー

                r = await session.call_tool("set_setting", {"key": "cell_ov", "value": 3.5, "confirm": True, "address": "DEMO-01"})
                assert _is_error(r)
                assert "解除値" in r.content[0].text
