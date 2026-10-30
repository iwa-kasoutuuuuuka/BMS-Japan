"""bms-japan コマンド（日本語表示。--json で JSON 出力）。"""

from __future__ import annotations

import argparse
import asyncio
import getpass
import json
import logging
import os
import sys
from collections.abc import Awaitable, Callable
from typing import Any

from . import __version__
from . import service as svc
from .protocol.settings import SETTINGS
from .service import BmsJapanError, ConnectionManager, ManagerConfig

EXIT_ERROR = 1
EXIT_ABORTED = 3


# ---- 表示 ----


def _out(text: str = "") -> None:
    print(text, flush=True)


def _fmt_q(d: dict[str, Any]) -> str:
    v = d["value"]
    if isinstance(v, bool):
        return "ON" if v else "OFF"
    unit = d.get("unit") or ""
    return f"{v} {unit}".strip()


def print_live(d: dict[str, Any]) -> None:
    _out(f"■ {d.get('name') or ''} ({d['address']})  {d['read_at']}")
    _out(f"  状態: {d['state']['label']}")
    for k in ("soc", "total_voltage", "current", "power", "remaining_capacity", "cycle_count"):
        _out(f"  {d[k]['label']}: {_fmt_q(d[k])}")
    _out(
        f"  セル: 最高 {d['max_cell_voltage']['value']:.3f} V / 最低 {d['min_cell_voltage']['value']:.3f} V"
        f" / 平均 {d['average_cell_voltage']['value']:.3f} V / 差 {d['cell_voltage_delta']['value'] * 1000:.0f} mV"
    )
    _out(f"  温度: 最高 {d['max_temperature']['value']} ℃ / 最低 {d['min_temperature']['value']} ℃")
    _out(
        f"  {d['charge_mos']['label']}: {d['charge_mos']['text']}   {d['discharge_mos']['label']}: "
        f"{d['discharge_mos']['text']}   {d['balancing']['label']}: {d['balancing']['text']}"
    )
    cells = d["cell_voltages"]
    _out(f"  {cells['label']}（{len(cells['values'])} セル）:")
    row = []
    for i, v in enumerate(cells["values"], 1):
        mark = "↑" if i == cells["max_cell"] else ("↓" if i == cells["min_cell"] else " ")
        row.append(f"{i:>2}:{v:.3f}{mark}")
        if len(row) == 4:
            _out("    " + "  ".join(row))
            row = []
    if row:
        _out("    " + "  ".join(row))
    temps = d["temperatures"]["values"]
    if temps:
        _out("  温度センサー: " + "  ".join(f"{i}:{v}℃" for i, v in enumerate(temps, 1)))
    if d["alarms"]:
        _out("  アラーム:")
        for a in d["alarms"]:
            _out(f"    ⚠ {a['label']}")
    else:
        _out("  アラーム: なし")
    _out(f"  ※ {svc.CURRENT_SIGN_NOTE}")


def print_settings(d: dict[str, Any]) -> None:
    _out(f"■ 設定値  {d.get('name') or ''} ({d['address']})")
    _out(f"  電池の種類: {d['battery_type']['label']}")
    _out("  スイッチ: " + "   ".join(f"{s['label']} {s['text']}" for s in d["switches"].values()))
    group = None
    for s in d["settings"]:
        if s["group_label"] != group:
            group = s["group_label"]
            _out(f"  [{group}]")
        dec = s["decimals"]
        val = "-" if s["value"] is None else f"{s['value']:.{dec}f} {s['unit']}".strip()
        extra = ""
        if s.get("release_value") is not None:
            extra = f"（解除 {s['release_value']:.{dec}f} {s['unit']}）"
        rng = f"  範囲 {s['min']:.{dec}f}〜{s['max']:.{dec}f}" if s["editable"] else ("  (表示のみ: BMS がセルの値から自動計算)" if s.get("bms_derived") else "  (表示のみ)")
        _out(f"    {s['key']:<20} {s['label']}: {val}{extra}{rng}")


def print_history(d: dict[str, Any]) -> None:
    total = d["total_count"]
    _out(f"■ 故障履歴  {d.get('name') or ''} ({d['address']})  全 {total if total is not None else '?'} 件")
    if not d["records"]:
        _out("  記録はありません")
    for r in d["records"]:
        _out(
            f"  #{r['number']:>3} {r['time'] or '日時不明':<19} [{r['occurred_label']}] {r['event']}"
            f"  {r['total_voltage']}V {r['current_abs']}A SOC {r['soc']}%"
            f"  セル {r['max_cell_voltage']:.3f}V(#{r['max_cell_index']})/{r['min_cell_voltage']:.3f}V(#{r['min_cell_index']})"
            f"  {r['max_temperature']}/{r['min_temperature']}℃"
        )
    if d["next_page"] is not None:
        _out(f"  続きがあります: --page {d['next_page']}")
    else:
        _out("  （履歴の終わり）")


def print_pack(d: dict[str, Any]) -> None:
    s = d["summary"]
    _out(f"■ まとめ（{s['mode']['label']}・{s['unit_count']} 台）  {d['read_at']}")
    _out(f"  状態: {s['state']['label']}")
    for k in ("soc", "voltage", "current", "power", "remaining_capacity"):
        _out(f"  {s[k]['label']}: {_fmt_q(s[k])}")
    _out(
        f"  セル: 最高 {s['max_cell_voltage']['value']:.3f} V / 最低 {s['min_cell_voltage']['value']:.3f} V"
        f" / 差 {s['cell_voltage_delta']['value'] * 1000:.0f} mV"
    )
    _out(f"  温度: 最高 {s['max_temperature']['value']} ℃ / 最低 {s['min_temperature']['value']} ℃")
    _out(f"  アラームが出ている台数: {s['alarm_unit_count']['value']}")
    _out("  台ごと:")
    for u in d["units"]:
        _out(
            f"    {u.get('name') or ''} ({u['address']}): SOC {u['soc']}%  {u['total_voltage']} V  {u['current_abs']} A"
            f"  {u['state']}  アラーム {u['alarm_count']} 件"
        )


def print_write_result(d: dict[str, Any]) -> None:
    w = d.get("written", {})
    if d.get("verified"):
        _out("✔ 書き込みました。読み直して一致を確認しました。")
    else:
        _out("✖ 書き込み後の読み直しで値が一致しませんでした。BMS の状態を確認してください。")
    _out("  書き込み内容: " + json.dumps(w, ensure_ascii=False))
    if "read_back" in d:
        _out("  読み直した値: " + json.dumps(d["read_back"], ensure_ascii=False))


# ---- 実行 ----


def make_manager(args: argparse.Namespace) -> ConnectionManager:
    demo = args.demo or svc.env_flag("BMS_JAPAN_DEMO")
    state = os.environ.get("BMS_JAPAN_DEMO_STATE") or (str(svc.default_demo_state_file()) if demo else None)
    return ConnectionManager(ManagerConfig(demo=demo, demo_state_file=state, reply_timeout=args.timeout))


async def resolve_address(mgr: ConnectionManager, args: argparse.Namespace) -> str:
    addr = getattr(args, "address", None) or os.environ.get("BMS_JAPAN_ADDRESS")
    if addr:
        return addr
    if mgr.demo:
        return "DEMO-01"
    found = [d for d in await mgr.scan(timeout=6.0) if d.looks_like_bms]
    if len(found) == 1:
        if not args.json:
            _out(f"（見つかった BMS: {found[0].name} {found[0].address}）")
        return found[0].address
    if not found:
        raise BmsJapanError("no_device", "BMS が見つかりません。--address で指定するか、BMS の電源と距離を確認してください")
    names = ", ".join(f"{d.name}({d.address})" for d in found)
    raise BmsJapanError("ambiguous_address", f"BMS が複数見つかりました。--address で指定してください: {names}")


def password_arg(args: argparse.Namespace) -> str | None:
    return getattr(args, "password", None) or os.environ.get("BMS_JAPAN_PASSWORD")


async def run_write(
    args: argparse.Namespace,
    call: Callable[[bool], Awaitable[dict[str, Any]]],
) -> int:
    """書き込み: まずプレビュー（confirm=False）→ --yes か対話で確認 → 書き込み"""
    preview = await call(False)
    if args.yes:
        result = await call(True)
    else:
        if args.json:
            _out(json.dumps(preview, ensure_ascii=False, indent=2))
            return EXIT_ABORTED
        _out("■ 書き込みの確認")
        _out("  " + preview["message"].split(" / ")[0])
        _out("  内容: " + json.dumps(preview["would_write"], ensure_ascii=False))
        if not sys.stdin.isatty():
            _out("  --yes が無いため、書き込みませんでした。")
            return EXIT_ABORTED
        ans = input("  書き込みますか？ [y/N]: ").strip().lower()
        if ans not in ("y", "yes"):
            _out("  中止しました。")
            return EXIT_ABORTED
        result = await call(True)
    if args.json:
        _out(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print_write_result(result)
    return 0 if result.get("verified") else EXIT_ERROR


async def dispatch(args: argparse.Namespace) -> int:
    mgr = make_manager(args)
    try:
        return await _dispatch(mgr, args)
    finally:
        await mgr.close_all()


def _emit(args: argparse.Namespace, data: dict[str, Any], printer: Callable[[dict[str, Any]], None]) -> None:
    if args.json:
        _out(json.dumps(data, ensure_ascii=False, indent=2))
    else:
        printer(data)


async def _dispatch(mgr: ConnectionManager, args: argparse.Namespace) -> int:
    cmd = args.command
    if cmd == "scan":
        d = await svc.scan_devices(mgr, args.scan_timeout, args.all)
        if args.json:
            _out(json.dumps(d, ensure_ascii=False, indent=2))
        else:
            if mgr.demo:
                _out("（デモモード: 疑似 BMS を表示しています）")
            if not d["devices"]:
                _out("BMS が見つかりませんでした。")
            for dev in d["devices"]:
                mark = "★" if dev["looks_like_bms"] else " "
                _out(f" {mark} {dev['address']}  {dev['name'] or '(名前なし)'}  RSSI {dev['rssi']}")
        return 0

    if cmd == "demo-reset":
        if not mgr.demo:
            raise BmsJapanError("not_demo", "demo-reset は --demo のときだけ使えます")
        assert mgr.registry is not None
        mgr.registry.reset()
        _out("デモ機器の状態を初期値に戻しました。")
        return 0

    if cmd == "pack":
        addrs = args.addresses or (["DEMO-01", "DEMO-02"] if mgr.demo else [])
        d = await svc.get_pack_summary(mgr, addrs, args.mode)
        _emit(args, d, print_pack)
        return 0

    address = await resolve_address(mgr, args)
    pw = password_arg(args)

    if cmd == "live":
        if not args.watch:
            _emit(args, await svc.get_live_data(mgr, address), print_live)
            return 0
        try:
            while True:
                d = await svc.get_live_data(mgr, address)
                if args.json:
                    _out(json.dumps(d, ensure_ascii=False))
                else:
                    _out("\x1b[2J\x1b[H" if sys.stdout.isatty() else "")
                    print_live(d)
                    _out("  （Ctrl+C で終了）")
                await asyncio.sleep(args.interval)
        except (KeyboardInterrupt, asyncio.CancelledError):
            return 0

    if cmd == "settings":
        if args.settings_cmd == "set":
            return await run_write(
                args, lambda ok: svc.set_setting(mgr, args.key, args.value, pw, ok, address)
            )
        _emit(args, await svc.get_settings(mgr, address), print_settings)
        return 0

    if cmd == "switch":
        on = args.state == "on"
        return await run_write(args, lambda ok: svc.set_switch(mgr, args.name, on, pw, ok, address))

    if cmd == "battery-type":
        return await run_write(args, lambda ok: svc.set_battery_type(mgr, args.type, pw, ok, address))

    if cmd == "recommended":
        return await run_write(
            args,
            lambda ok: svc.set_recommended_preset(
                mgr, args.preset, pw, ok, address,
                accept_immediate_effect=args.accept_immediate_effect,
                accept_chemistry_change=args.accept_chemistry_change,
                generation=args.generation, parallel=args.parallel, soh=args.soh,
            ),
        )

    if cmd == "password":
        old = args.old or getpass.getpass("今のパスワード: ")
        new = args.new or getpass.getpass("新しいパスワード（6 桁の数字）: ")
        repeat = args.repeat or (new if args.new else getpass.getpass("新しいパスワード（確認）: "))
        return await run_write(args, lambda ok: svc.change_password(mgr, old, new, repeat, ok, address))

    if cmd == "history":
        pages = svc.HISTORY_MAX_PAGES if args.all else args.pages
        _emit(args, await svc.get_history(mgr, args.page, pages, address), print_history)
        return 0

    if cmd == "capacity":
        if args.capacity_cmd == "set":
            return await run_write(args, lambda ok: svc.set_actual_capacity(mgr, args.ah, pw, ok, address))
        d = await svc.get_actual_capacity(mgr, address)
        _emit(args, d, lambda x: _out(f"{x['actual_capacity']['label']}: {x['actual_capacity']['value']} Ah"))
        return 0

    raise BmsJapanError("unknown_command", f"不明なコマンド: {cmd}")


def build_parser() -> argparse.ArgumentParser:
    keys = ", ".join(s.key for s in SETTINGS if s.editable)
    p = argparse.ArgumentParser(
        prog="bms-japan",
        description="Balance BMS（アクティブバランサー BMS）を PC から操作する日本語ツール（非公式）",
    )
    p.add_argument("--version", action="version", version=f"bms-japan {__version__}")
    p.add_argument("--demo", action="store_true", help="実機の代わりに疑似 BMS を使う（環境変数 BMS_JAPAN_DEMO=1 でも可）")
    p.add_argument("--json", action="store_true", help="結果を JSON で出力する")
    p.add_argument("--timeout", type=float, default=3.0, help="応答待ちの秒数（既定 3 秒、3 回まで送り直す）")
    p.add_argument("-v", "--verbose", action="store_true", help="通信の詳細を表示する")

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("-a", "--address", help="BMS のアドレス（環境変数 BMS_JAPAN_ADDRESS でも可。デモでは DEMO-01〜03）")
    write = argparse.ArgumentParser(add_help=False)
    write.add_argument("--password", help="BMS のパスワード（初期値 123456 のままなら不要。BMS_JAPAN_PASSWORD でも可）")
    write.add_argument("-y", "--yes", action="store_true", help="確認なしで書き込む")

    sub = p.add_subparsers(dest="command", required=True, metavar="COMMAND")

    s = sub.add_parser("scan", help="BMS を探す")
    s.add_argument("--timeout", dest="scan_timeout", type=float, default=8.0, help="探す秒数")
    s.add_argument("--all", action="store_true", help="名前に JHB を含まない機器も表示する")

    s = sub.add_parser("live", parents=[common], help="運転データを表示する")
    s.add_argument("--watch", action="store_true", help="一定間隔で表示し続ける")
    s.add_argument("--interval", type=float, default=5.0, help="--watch の間隔（秒、既定 5）")

    s = sub.add_parser("settings", help="設定値の表示・変更")
    ss = s.add_subparsers(dest="settings_cmd", required=True, metavar="show|set")
    ss.add_parser("show", parents=[common], help="設定値を表示する")
    st = ss.add_parser("set", parents=[common, write], help=f"設定値を変更する（KEY: {keys}）")
    st.add_argument("key", help="設定項目のキー（settings show で確認）")
    st.add_argument("value", help="新しい値（表示と同じ単位）")

    s = sub.add_parser("switch", parents=[common, write], help="充電MOS・放電MOS・均等化の ON/OFF")
    s.add_argument("name", choices=["charge", "discharge", "balance"])
    s.add_argument("state", choices=["on", "off"])

    s = sub.add_parser("battery-type", parents=[common, write], help="電池の種類を変える（保護値がまとめて変わります）")
    s.add_argument("type", choices=["lfp", "ncm", "lto"])

    s = sub.add_parser(
        "recommended", parents=[common, write], help="おすすめ設定（日産リーフなど）をまとめて書き込む（電流は変えない）"
    )
    s.add_argument(
        "preset",
        choices=["leaf-standard", "leaf-long-life"],
        help="leaf-standard: セル 3.00〜4.15 V / leaf-long-life: セル 3.30〜4.10 V",
    )
    s.add_argument(
        "--accept-immediate-effect",
        action="store_true",
        help="今のセル電圧・温度で書き込み直後に放電・充電が止まる場合でも書き込む",
    )
    s.add_argument(
        "--accept-chemistry-change",
        action="store_true",
        help="電池の種類が変わる（例: リン酸鉄→三元系）場合に、セルがリーフであることを確認したうえで書き込む",
    )
    s.add_argument(
        "--generation",
        choices=["ze0-24", "aze0-24", "aze0-30", "ze1-40", "ze1-62"],
        help="リーフの世代（公称電圧を合わせる）: ze0-24=24kWh 2010-2013 / aze0-24=24kWh 2013-2017 / aze0-30=30kWh / ze1-40=40kWh / ze1-62=62kWh",
    )
    s.add_argument("--parallel", type=int, help="1 直列あたりの並列セル数（省略時は車両と同じ）")
    s.add_argument("--soh", type=int, help="残り容量の割合（%%、30〜100）。指定すると定格容量も書き込む（--generation が必要）")

    s = sub.add_parser("password", help="BMS のパスワードを変更する")
    sp = s.add_subparsers(dest="password_cmd", required=True, metavar="change")
    pc = sp.add_parser("change", parents=[common], help="パスワードを変更する（6 桁の数字）")
    pc.add_argument("--old", help="今のパスワード（省略すると入力を求めます。コマンド履歴に残るため省略を推奨）")
    pc.add_argument("--new", help="新しいパスワード（6 桁の数字。省略すると入力を求めます。省略を推奨）")
    pc.add_argument("--repeat", help="新しいパスワード（確認用。省略時は --new と同じ）")
    pc.add_argument("-y", "--yes", action="store_true", help="確認なしで書き込む")

    s = sub.add_parser("history", parents=[common], help="故障履歴を表示する（1 ページ 7 件）")
    s.add_argument("--page", type=int, default=0, help="最初のページ（0 から）")
    s.add_argument("--pages", type=int, default=4, help="読むページ数（既定 4 = 28 件、元アプリと同じ）")
    s.add_argument("--all", action="store_true", help="終わりまで全部読む")

    s = sub.add_parser("capacity", help="実容量の表示・変更")
    sc = s.add_subparsers(dest="capacity_cmd", required=True, metavar="show|set")
    sc.add_parser("show", parents=[common], help="実容量を表示する")
    cs = sc.add_parser("set", parents=[common, write], help="実容量を変更する（Ah、1〜2000）")
    cs.add_argument("ah", help="実容量（Ah）")

    s = sub.add_parser("pack", help="複数台をまとめて表示する（並列・直列）")
    s.add_argument("addresses", nargs="*", help="BMS のアドレス（最大 6 台）")
    s.add_argument("--mode", choices=["parallel", "series", "single"], default="parallel", help="つなぎ方")

    sub.add_parser("demo-reset", help="デモ機器の状態を初期値に戻す（--demo と一緒に使う）")
    return p


def _setup_console() -> None:
    """文字コードの調整。

    画面（コンソール）に直接出すときは Python に任せる。パイプやファイルに出すとき
    （Claude Code などのエージェントから実行したとき）は UTF-8 にする。
    BMS_JAPAN_ENCODING（例: cp932）で変えられる。
    """
    forced = os.environ.get("BMS_JAPAN_ENCODING")
    for stream in (sys.stdout, sys.stderr):
        try:
            if forced:
                stream.reconfigure(encoding=forced, errors="replace")  # type: ignore[attr-defined]
            elif not stream.isatty():
                stream.reconfigure(encoding="utf-8", errors="replace")  # type: ignore[attr-defined]
            else:
                stream.reconfigure(errors="replace")  # type: ignore[attr-defined]
        except Exception:  # noqa: BLE001
            pass


def main(argv: list[str] | None = None) -> int:
    _setup_console()
    args = build_parser().parse_args(argv)
    # -v でも bleak など他のライブラリのログは WARNING のまま（受信データにパスワードが含まれることがあるため）
    logging.basicConfig(level=logging.WARNING, format="%(levelname)s %(name)s: %(message)s", stream=sys.stderr)
    logging.getLogger("bms_japan").setLevel(logging.DEBUG if args.verbose else logging.WARNING)
    try:
        return asyncio.run(dispatch(args))
    except KeyboardInterrupt:
        return 130
    except BmsJapanError as e:
        if args.json:
            _out(json.dumps({"ok": False, "error": {"code": e.code, "message": e.message}}, ensure_ascii=False))
        else:
            print(f"エラー: {e.message}", file=sys.stderr)
        return EXIT_ERROR


if __name__ == "__main__":
    sys.exit(main())
