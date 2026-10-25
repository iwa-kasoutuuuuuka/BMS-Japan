"""受信フレームの組み立て（Kotlin 版 ``FrameAssembler.kt`` の移植）。"""

from __future__ import annotations

from dataclasses import dataclass

from . import modbus as mb


@dataclass(frozen=True)
class ReadResponse:
    """0x03 読み出し応答。registers は 16bit 符号なし値の並び"""

    registers: tuple[int, ...]
    slave: int = mb.SLAVE_BMS


@dataclass(frozen=True)
class WriteSingleAck:
    """0x06 書き込み応答（エコー）"""

    register: int
    value: int
    slave: int = mb.SLAVE_BMS


@dataclass(frozen=True)
class WriteMultiAck:
    """0x10 書き込み応答"""

    start: int
    count: int
    slave: int = mb.SLAVE_BMS


BmsFrame = ReadResponse | WriteSingleAck | WriteMultiAck


def _u16(b: bytes | bytearray, i: int) -> int:
    return (b[i] << 8) | b[i + 1]


class FrameAssembler:
    """Notify で細切れに届くバイト列をつなぎ合わせ、完全なフレームを取り出す。

    同期が取れないときは 1 バイトずつ読み捨てる（元アプリと同じ方式）。

    :param slaves: 受け付ける応答のスレーブアドレス
    :param lenient_crc_slaves: CRC が合わなくても受け付けるアドレス。
        実容量の応答（0x51）は元アプリが CRC を確認していないため緩くしているが、
        その場合も「4 バイトの読み出し応答」と「8 バイトの書き込み応答」の形だけに限る。
    """

    def __init__(
        self,
        slaves: set[int] | None = None,
        lenient_crc_slaves: set[int] | None = None,
    ) -> None:
        self.slaves = slaves if slaves is not None else {mb.SLAVE_BMS, mb.SLAVE_AUX_REPLY, mb.SLAVE_AUX}
        self.lenient = lenient_crc_slaves if lenient_crc_slaves is not None else {mb.SLAVE_AUX_REPLY}
        self._buf = bytearray()

    def reset(self) -> None:
        self._buf = bytearray()

    def feed(self, chunk: bytes | bytearray) -> list[BmsFrame]:
        self._buf += chunk
        out: list[BmsFrame] = []
        buf = self._buf
        while len(buf) >= 2:
            slave = buf[0]
            if slave not in self.slaves:
                del buf[0]
                continue
            fn = buf[1]
            if fn == mb.FN_READ:
                if len(buf) < 3:
                    break
                byte_count = buf[2]
                # CRC を確認しない応答は、知っている形（4 バイト＝32bit 値）だけ受け付ける
                if slave in self.lenient and byte_count != 4:
                    del buf[0]
                    continue
                length = 3 + byte_count + 2
            elif fn in (mb.FN_WRITE_SINGLE, mb.FN_WRITE_MULTI):
                length = 8
            else:
                del buf[0]
                continue
            if len(buf) < length:
                break
            frame = bytes(buf[:length])
            if not mb.is_crc_valid(frame) and slave not in self.lenient:
                del buf[0]
                continue
            del buf[:length]
            out.append(self._parse(slave, fn, frame))
        return out

    @staticmethod
    def _parse(slave: int, fn: int, f: bytes) -> BmsFrame:
        if fn == mb.FN_READ:
            n = f[2] // 2
            return ReadResponse(tuple(_u16(f, 3 + i * 2) for i in range(n)), slave)
        if fn == mb.FN_WRITE_SINGLE:
            return WriteSingleAck(_u16(f, 2), _u16(f, 4), slave)
        return WriteMultiAck(_u16(f, 2), _u16(f, 4), slave)
