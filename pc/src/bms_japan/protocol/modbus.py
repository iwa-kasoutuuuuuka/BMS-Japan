"""Balance BMS の Modbus RTU 風フレーム（docs/ble_protocol.md 2章）。

Kotlin 版 ``protocol/Modbus.kt`` の移植。
"""

from __future__ import annotations

from collections.abc import Iterable

#: BMS 本体のスレーブアドレス
SLAVE_BMS = 0xD2
#: 実容量など一部の値はこのアドレスに問い合わせる
SLAVE_AUX = 0x81
#: 0x81 への問い合わせには 0x51 で応答が返る
SLAVE_AUX_REPLY = 0x51

FN_READ = 0x03
FN_WRITE_SINGLE = 0x06
FN_WRITE_MULTI = 0x10


def reply_slaves(slave: int) -> set[int]:
    """問い合わせ先から、応答に使われるアドレスを返す"""
    return {SLAVE_AUX_REPLY, SLAVE_AUX} if slave == SLAVE_AUX else {slave}


def crc16(data: bytes | bytearray, length: int | None = None) -> bytes:
    """Modbus CRC16（初期値 0xFFFF、多項式 0xA001）。戻り値は下位バイトが先の 2 バイト。"""
    n = len(data) if length is None else length
    crc = 0xFFFF
    for i in range(n):
        crc ^= data[i]
        for _ in range(8):
            crc = (crc >> 1) ^ 0xA001 if crc & 1 else crc >> 1
    return bytes((crc & 0xFF, (crc >> 8) & 0xFF))


def with_crc(body: bytes | bytearray) -> bytes:
    return bytes(body) + crc16(body)


def is_crc_valid(frame: bytes | bytearray) -> bool:
    """末尾 2 バイトの CRC が正しいか"""
    if len(frame) < 3:
        return False
    return crc16(frame, len(frame) - 2) == bytes(frame[-2:])


def u16(v: int) -> bytes:
    return bytes(((v >> 8) & 0xFF, v & 0xFF))


def read_registers(start: int, count: int, slave: int = SLAVE_BMS) -> bytes:
    """保持レジスタの読み出し: [addr 03 start(2) count(2) crc(2)]"""
    return with_crc(bytes((slave, FN_READ)) + u16(start) + u16(count))


def write_register(register: int, value: int, slave: int = SLAVE_BMS) -> bytes:
    """1 レジスタ書き込み: [addr 06 reg(2) value(2) crc(2)]"""
    return with_crc(bytes((slave, FN_WRITE_SINGLE)) + u16(register) + u16(value))


def write_registers(start: int, values: Iterable[int], slave: int = SLAVE_BMS) -> bytes:
    """複数レジスタ書き込み。

    注意: この BMS は標準 Modbus にある「バイト数」フィールドを入れない独自仕様。
    [addr 10 start(2) count(2) data(count*2) crc(2)]
    """
    vals = list(values)
    body = bytes((slave, FN_WRITE_MULTI)) + u16(start) + u16(len(vals))
    for v in vals:
        body += u16(v)
    return with_crc(body)


# ---- よく使うコマンド（Kotlin の BmsCommands） ----

LIVE_START = 0
LIVE_COUNT = 62
EXTRA_CELLS_START = 71
EXTRA_CELLS_COUNT = 16
TIME_REGISTER = 212


def read_live() -> bytes:
    """運転データ（レジスタ 0〜61）"""
    return read_registers(LIVE_START, LIVE_COUNT)


def read_extra_cells() -> bytes:
    """33〜48 セル目の電圧（レジスタ 71〜86）"""
    return read_registers(EXTRA_CELLS_START, EXTRA_CELLS_COUNT)


def time_registers(year: int, month: int, day: int, hour: int, minute: int, second: int) -> list[int]:
    return [((year - 2000) << 8) | month, (day << 8) | hour, (minute << 8) | second]


def sync_time(year: int, month: int, day: int, hour: int, minute: int, second: int) -> bytes:
    """時刻同期（レジスタ 212〜214 に YY MM DD hh mm ss）"""
    return write_registers(TIME_REGISTER, time_registers(year, month, day, hour, minute, second))


def to_hex(data: bytes | bytearray) -> str:
    return bytes(data).hex().upper()


def hex_to_bytes(s: str) -> bytes:
    clean = "".join(s.split())
    if len(clean) % 2:
        raise ValueError("hex length must be even")
    return bytes.fromhex(clean)
