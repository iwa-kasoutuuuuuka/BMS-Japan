"""デモ用の疑似 BMS（シミュレーター）。

実機と同じバイト列のプロトコルで応答するので、フレームの組み立て・CRC・解析のコードを
そのまま通して試せる。受信した要求もバイト単位で組み立てる（細切れの書き込みに対応）。

- 16 セル LFP、温度センサー 4 個、パスワード 123456、実容量 280 Ah
- 故障履歴 10 件（2 ページ目の途中で終わる）
- 充電/放電 MOS を OFF にすると電流が止まる、SOC 設定が残り容量に反映される、など
"""

from __future__ import annotations

import json
import random
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path

from .protocol import history as hist
from .protocol import modbus as mb


@dataclass
class DemoProfile:
    name: str
    soc: float = 85.3
    state: int = 1  # 0 待機 / 1 充電 / 2 放電
    current_a: float = 12.5  # 大きさ
    cell_base_mv: int = 3300
    cell_offsets_mv: list[int] = field(default_factory=lambda: [0, 3, 5, 2, 8, 1, 4, 6, 15, 2, 3, 7, 1, 0, 4, 9])
    temps_c: list[int] = field(default_factory=lambda: [25, 26, 24, 27])
    cycles: int = 42
    alarms: tuple[int, int, int, int] = (0, 0, 0, 0)
    capacity_mah: int = 280_000
    seed: int = 1


DEMO_PROFILES: dict[str, DemoProfile] = {
    "DEMO-01": DemoProfile("JHB-DEMO-01", soc=85.3, state=1, current_a=12.5, seed=1),
    "DEMO-02": DemoProfile(
        "JHB-DEMO-02",
        soc=61.0,
        state=2,
        current_a=30.4,
        cell_base_mv=3260,
        temps_c=[29, 31, 30, 28],
        cycles=118,
        seed=2,
    ),
    "DEMO-03": DemoProfile(
        "JHB-DEMO-03",
        soc=47.5,
        state=0,
        current_a=0.0,
        cell_base_mv=3280,
        cycles=7,
        alarms=(0, 0, 0, 1 << 9),  # 均等化の条件を満たしていません
        capacity_mah=230_000,
        seed=3,
    ),
}


def _default_settings() -> dict[int, int]:
    """LFP 16 セルの典型的な設定値（Kotlin のテストと同じ値）"""
    r = {reg: 0 for reg in range(128, 169)}
    r.update(
        {
            128: 2800,  # 280.0 Ah
            129: 3200,
            130: 16,
            131: 16,
            134: 4,
            137: 0,  # LFP
            138: 3600,
            139: 3550, 140: 3650,
            141: 2700, 142: 2500,
            143: 568, 144: 584,
            145: 432, 146: 400,
            147: 29000, 148: 28000,  # 充電過電流 解除 100A / 保護 200A
            149: 31500, 150: 32000,  # 放電過電流 解除 150A / 保護 200A
            151: 90, 152: 95,
            153: 45, 154: 40,
            155: 95, 156: 100,
            157: 25, 158: 20,
            159: 200, 160: 300,
            161: 10, 162: 15,
            163: 3400, 164: 10,
            165: 1, 166: 1,
            167: 853,
            168: 130,
        }
    )  # fmt: skip
    return r


def _ascii_regs(text: str, count: int) -> list[int]:
    b = text.encode("ascii")[: count * 2].ljust(count * 2, b"\x00")
    return [(b[i * 2] << 8) | b[i * 2 + 1] for i in range(count)]


class FakeBms:
    """1 台分の疑似 BMS。:meth:`receive` に送信バイト列を渡すと、応答のバイト列を返す。"""

    #: 読み出しを受け付けるレジスタの上限（これを超える読み出しには応答しない＝タイムアウトになる）
    MAX_REGISTER = 255

    def __init__(self, address: str, profile: DemoProfile | None = None) -> None:
        self.address = address
        self.profile = profile or DemoProfile(f"JHB-{address}")
        self.name = self.profile.name
        self.rng = random.Random(self.profile.seed)
        self.regs: dict[int, int] = {i: 0 for i in range(0, self.MAX_REGISTER + 1)}
        self.regs.update(_default_settings())
        self.regs[167] = round(self.profile.soc * 10)
        self.regs.update(dict(zip(range(87, 99), _ascii_regs(f"SN{address}-2026", 12))))
        self.regs.update(dict(zip(range(169, 201), _ascii_regs("DEMO-SIM v1.0  BMS-Japan PC", 32))))
        self.regs.update(dict(zip(range(201, 204), _ascii_regs("123456", 3))))
        self.regs.update({204: 0x1A0A, 205: 0x0100, 206: 16, 207: 1, 208: 20})
        self.aux: dict[int, int] = {}
        self.aux[267] = (self.profile.capacity_mah >> 16) & 0xFFFF
        self.aux[268] = self.profile.capacity_mah & 0xFFFF
        self.history = self._make_history(10)
        self._rx = bytearray()
        #: テスト用: 次の N 個の応答を捨てる（再送の確認）
        self.drop_replies = 0
        #: テスト用: 応答の前にゴミを付ける（同期取り直しの確認）
        self.garbage_prefix = b""
        #: 受け取った要求（16 進）。テストで送信内容を確認するため
        self.log: list[str] = []

    # ---- 状態の保存（CLI のデモで、コマンドをまたいで書き込みを残すため） ----

    PERSIST_RANGES = [(128, 168), (201, 216)]

    def to_state(self) -> dict:
        regs = {str(r): self.regs[r] for a, b in self.PERSIST_RANGES for r in range(a, b + 1)}
        return {"regs": regs, "aux": {str(k): v for k, v in self.aux.items()}}

    def load_state(self, state: dict) -> None:
        """保存した状態を読む。形や値がおかしいものは無視する（壊れたファイルで落ちないように）"""
        if not isinstance(state, dict):
            return
        allowed = {r for a, b in self.PERSIST_RANGES for r in range(a, b + 1)}
        regs = state.get("regs", {})
        if isinstance(regs, dict):
            for k, v in regs.items():
                try:
                    r, val = int(k), int(v)
                except (TypeError, ValueError):
                    continue
                if r in allowed and 0 <= val <= 0xFFFF:
                    self.regs[r] = val
        aux = state.get("aux", {})
        if isinstance(aux, dict):
            for k, v in aux.items():
                try:
                    r, val = int(k), int(v)
                except (TypeError, ValueError):
                    continue
                if 0 <= val <= 0xFFFFFFFF:
                    self.aux[r] = val

    # ---- 疑似データ ----

    def _make_history(self, n: int) -> list[bytes]:
        events = [255, 75, 8, 8, 76, 10, 160, 162, 254, 255]
        t0 = datetime(2026, 10, 20, 8, 0, 0)
        out = []
        for i in range(n):
            ev = events[i % len(events)]
            occurred = not (ev == 8 and i % 2 == 1)
            out.append(
                hist.encode_record(
                    number=i + 1,
                    total=n,
                    time=t0 + timedelta(hours=7 * i, minutes=13 * i),
                    event_id=ev,
                    occurred=occurred,
                    total_voltage=53.0 + 0.1 * i,
                    current_raw=30000 - 100 - 5 * i,
                    soc=60.0 + 2 * i,
                    mos=0x03 if ev not in (160, 162) else 0x02,
                    max_cell_mv=3450 + (200 if ev == 8 else 0),
                    max_cell_index=9,
                    min_cell_mv=3300,
                    min_cell_index=1,
                    max_temp=27 + i % 3,
                    max_temp_index=4,
                    min_temp=24,
                    min_temp_index=3,
                    fault_code=0,
                )
            )
        return out

    def _history_page(self, page: int) -> list[int]:
        data = b"".join(self.history[page * hist.RECORDS_PER_PAGE : (page + 1) * hist.RECORDS_PER_PAGE])
        data = data.ljust(hist.PAGE_REGISTERS * 2, b"\x00")
        return [(data[i * 2] << 8) | data[i * 2 + 1] for i in range(hist.PAGE_REGISTERS)]

    def _update_live(self) -> None:
        """運転データ（0〜61）を今の設定から作り直す"""
        p = self.profile
        r = self.regs
        n = r[130] or 16
        n = max(1, min(32, n))
        cells = []
        for i in range(n):
            off = p.cell_offsets_mv[i % len(p.cell_offsets_mv)]
            cells.append(p.cell_base_mv + off + self.rng.randint(-1, 1))
        for i in range(32):
            r[i] = cells[i] if i < n else 0
        temps = p.temps_c[:8]
        for i in range(8):
            r[32 + i] = (temps[i] + 40) if i < len(temps) else 0

        state = p.state
        current = p.current_a
        charge_on = r[165] != 0
        discharge_on = r[166] != 0
        if state == 1 and not charge_on:
            state, current = 0, 0.0
        if state == 2 and not discharge_on:
            state, current = 0, 0.0
        if current:
            current = max(0.0, current + self.rng.choice((-0.1, 0.0, 0.1)))

        total_v = sum(cells) / 1000.0
        # 電流の向きは実機で未確認。docs の推定（充電がマイナス）に合わせて符号を付ける
        signed = -current if state == 1 else current
        soc = r[167] / 10.0
        cap_mah = (self.aux[267] << 16) | self.aux[268]

        r[40] = round(total_v * 10)
        r[41] = 30000 + round(signed * 10)
        r[42] = r[167]
        r[43] = max(cells)
        r[44] = min(cells)
        r[45] = max(temps) + 40
        r[46] = min(temps) + 40
        r[47] = state
        r[48] = round(cap_mah / 1000.0 * soc / 100.0 * 10)
        r[49] = n
        r[50] = len(temps)
        r[51] = p.cycles
        balancing = r[207] != 0 and max(cells) >= r[163] and (max(cells) - min(cells)) >= r[164]
        r[52] = 1 if balancing else 0
        r[53] = 1 if charge_on else 0
        r[54] = 1 if discharge_on else 0
        r[55] = round(sum(cells) / n)
        r[56] = max(cells) - min(cells)
        r[57] = round(total_v * current)  # W（= kW × 1000）
        r[58], r[59], r[60], r[61] = p.alarms
        r[206] = n

    # ---- 受信と応答 ----

    def receive(self, data: bytes) -> list[bytes]:
        """送信されたバイト列（細切れでもよい）を受け取り、完成した要求への応答を返す"""
        self._rx += data
        out: list[bytes] = []
        buf = self._rx
        while len(buf) >= 2:
            slave, fn = buf[0], buf[1]
            if slave not in (mb.SLAVE_BMS, mb.SLAVE_AUX):
                del buf[0]
                continue
            if fn in (mb.FN_READ, mb.FN_WRITE_SINGLE):
                length = 8
            elif fn == mb.FN_WRITE_MULTI:
                if len(buf) < 6:
                    break
                count = (buf[4] << 8) | buf[5]
                length = 6 + count * 2 + 2  # バイト数フィールドなし
            else:
                del buf[0]
                continue
            if len(buf) < length:
                break
            frame = bytes(buf[:length])
            if not mb.is_crc_valid(frame):
                del buf[0]
                continue
            del buf[:length]
            self.log.append(mb.to_hex(frame))
            reply = self._handle(frame)
            if reply is None:
                continue
            if self.drop_replies > 0:
                self.drop_replies -= 1
                continue
            out.append(self.garbage_prefix + reply)
        return out

    def _handle(self, f: bytes) -> bytes | None:
        slave, fn = f[0], f[1]
        a = (f[2] << 8) | f[3]
        b = (f[4] << 8) | f[5]
        if slave == mb.SLAVE_AUX:
            return self._handle_aux(fn, a, b, f)
        if fn == mb.FN_READ:
            start, count = a, b
            if start >= hist.BASE_ADDRESS and count == hist.PAGE_REGISTERS:
                values = self._history_page(start - hist.BASE_ADDRESS)
            else:
                if count == 0 or count > 127 or start + count - 1 > self.MAX_REGISTER:
                    return None  # 実機でも範囲外は応答しないと想定
                if start < 62:
                    self._update_live()
                values = [self.regs[start + i] for i in range(count)]
            body = bytes((mb.SLAVE_BMS, mb.FN_READ, count * 2))
            for v in values:
                body += mb.u16(v)
            return mb.with_crc(body)
        if fn == mb.FN_WRITE_SINGLE:
            if a > self.MAX_REGISTER:
                return None
            self.regs[a] = b
            return f  # エコー
        if fn == mb.FN_WRITE_MULTI:
            start, count = a, b
            if start + count - 1 > self.MAX_REGISTER:
                return None
            for i in range(count):
                self.regs[start + i] = (f[6 + i * 2] << 8) | f[7 + i * 2]
            return mb.with_crc(bytes((mb.SLAVE_BMS, mb.FN_WRITE_MULTI)) + mb.u16(start) + mb.u16(count))
        return None

    def _handle_aux(self, fn: int, start: int, count: int, f: bytes) -> bytes | None:
        """スレーブ 0x81（実容量）。応答は 0x51 から返る"""
        if start != 267 or count != 2:
            return None
        if fn == mb.FN_READ:
            body = bytes((mb.SLAVE_AUX_REPLY, mb.FN_READ, 4)) + mb.u16(self.aux[267]) + mb.u16(self.aux[268])
            return mb.with_crc(body)
        if fn == mb.FN_WRITE_MULTI:
            self.aux[267] = (f[6] << 8) | f[7]
            self.aux[268] = (f[8] << 8) | f[9]
            return mb.with_crc(bytes((mb.SLAVE_AUX_REPLY, mb.FN_WRITE_MULTI)) + mb.u16(267) + mb.u16(2))
        return None


class DemoRegistry:
    """デモ機器の集まり。同じプロセスの中では接続し直しても状態が残る。

    ``state_file`` を指定すると、書き込んだ設定をファイルに保存し、次回の起動でも引き継ぐ。
    """

    def __init__(self, state_file: str | Path | None = None) -> None:
        self.state_file = Path(state_file) if state_file else None
        self._devices: dict[str, FakeBms] = {}

    def addresses(self) -> list[str]:
        return list(DEMO_PROFILES)

    def get(self, address: str) -> FakeBms:
        key = address.upper()
        if key not in self._devices:
            if key not in DEMO_PROFILES:
                raise ConnectionError(f"デモ機器が見つかりません: {address}（{', '.join(DEMO_PROFILES)} のどれかを指定）")
            dev = FakeBms(key, DEMO_PROFILES[key])
            saved = self._load().get(key)
            if saved:
                dev.load_state(saved)
            self._devices[key] = dev
        return self._devices[key]

    def _load(self) -> dict:
        if not self.state_file or not self.state_file.exists():
            return {}
        try:
            return json.loads(self.state_file.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return {}

    def save(self, address: str) -> None:
        if not self.state_file:
            return
        key = address.upper()
        if key not in self._devices:
            return
        data = self._load()
        data[key] = self._devices[key].to_state()
        try:
            self.state_file.parent.mkdir(parents=True, exist_ok=True)
            self.state_file.write_text(json.dumps(data), encoding="utf-8")
        except OSError:
            pass

    def reset(self) -> None:
        self._devices.clear()
        if self.state_file and self.state_file.exists():
            self.state_file.unlink()
