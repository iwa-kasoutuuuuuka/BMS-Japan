"""Kotlin 版 ProtocolTest.kt の移植（同じ期待値）。"""

import pytest

from bms_japan.protocol import modbus as mb
from bms_japan.protocol.frames import FrameAssembler, ReadResponse, WriteMultiAck, WriteSingleAck
from bms_japan.protocol.live import Alarm, ChargeState, LiveData
from bms_japan.protocol.settings import ActualCapacity

H = mb.to_hex


# 期待値は docs/ble_protocol.md の送信例と同じ
def test_read_live_frame():
    assert H(mb.read_live()) == "D2030000003ED7B9"


def test_read_settings_frame():
    assert H(mb.read_registers(128, 41)) == "D20300800029965F"


def test_read_extra_cells_frame():
    assert H(mb.read_extra_cells()) == "D20300470010E7B0"


def test_write_single_frame():
    assert H(mb.write_register(0xA5, 0)) == "D20600A500008A4A"


def test_sync_time_frame():
    assert H(mb.sync_time(2026, 10, 24, 22, 30, 0)) == "D21000D400031A0A18161E00E341"


@pytest.mark.parametrize(
    "start,count,expected",
    [
        (62, 9, "D203003E0009F7A3"),
        (204, 5, "D20300CC00055655"),
        (201, 3, "D20300C90003C656"),
        (169, 32, "D20300A900208791"),
        (87, 12, "D2030057000CE7BC"),
        (256, 127, "D2030100007F1675"),
    ],
)
def test_doc_examples(start, count, expected):
    # docs/ble_protocol.md「送信例」の残りの行
    assert H(mb.read_registers(start, count)) == expected


def test_crc_validation():
    assert mb.is_crc_valid(mb.hex_to_bytes("D2030000003ED7B9"))
    assert not mb.is_crc_valid(mb.hex_to_bytes("D2030000003ED7B8"))


def sample_live_registers() -> list[int]:
    """16 セル LFP のサンプル応答"""
    r = [0] * 62
    for i in range(16):
        r[i] = 3300 + i
    r[32], r[33] = 65, 66
    r[40] = 528
    r[41] = 30000 + 125
    r[42] = 853
    r[43], r[44] = 3315, 3300
    r[45], r[46] = 66, 65
    r[47] = 1
    r[48] = 2560
    r[49], r[50], r[51] = 16, 2, 42
    r[52], r[53], r[54] = 1, 1, 0
    r[55], r[56], r[57] = 3307, 15, 660
    r[58] = 0b1
    r[60] = 1 << 9
    return r


def encode_read_response(regs: list[int], slave: int = 0xD2) -> bytes:
    body = bytes((slave, 0x03, len(regs) * 2))
    for v in regs:
        body += mb.u16(v)
    return mb.with_crc(body)


def test_parse_live_data():
    d = LiveData.parse(sample_live_registers())
    assert len(d.cell_voltages) == 16
    assert d.cell_voltages[0] == pytest.approx(3.300)
    assert d.temperatures == [25, 26]
    assert d.total_voltage == pytest.approx(52.8)
    assert d.current == pytest.approx(12.5)
    assert d.soc == pytest.approx(85.3)
    assert d.state is ChargeState.CHARGING
    assert d.remaining_capacity == pytest.approx(256.0)
    assert d.cycle_count == 42
    assert d.charge_mos
    assert not d.discharge_mos
    assert d.cell_voltage_delta == pytest.approx(0.015)
    assert d.power_kw == pytest.approx(0.66)
    assert d.alarms == [Alarm.CELL_OV1, Alarm.CELL_WIRE]
    assert d.alarms[1].label == "セル電圧線の外れ"


def test_negative_current_when_discharging():
    r = sample_live_registers()
    r[41] = 30000 - 304
    assert LiveData.parse(r).current == pytest.approx(-30.4)


def test_live_needs_62_registers():
    with pytest.raises(ValueError):
        LiveData.parse([0] * 10)


def test_assembler_handles_fragments_and_garbage():
    frame = encode_read_response(sample_live_registers())
    stream = bytes((0x00, 0x7F)) + frame + mb.write_register(0xA5, 0)
    asm = FrameAssembler()
    got = []
    for i in range(0, len(stream), 20):  # 20 バイトずつ（BLE の既定 MTU 相当）
        got += asm.feed(stream[i : i + 20])
    assert len(got) == 2
    assert isinstance(got[0], ReadResponse)
    assert len(got[0].registers) == 62
    assert got[0].registers[40] == 528
    assert got[1] == WriteSingleAck(0xA5, 0)


def test_assembler_skips_bad_crc():
    bad = bytearray(encode_read_response([1, 1]))
    bad[-1] = (bad[-1] + 1) & 0xFF
    good = encode_read_response([7, 7])
    assert FrameAssembler().feed(bytes(bad) + good) == [ReadResponse((7, 7))]


# ---- 実容量（スレーブ 0x81、応答は 0x51） ----


def test_actual_capacity_read_frame():
    assert H(mb.read_registers(ActualCapacity.REGISTER, 2, mb.SLAVE_AUX)) == "8103010B0002ABF5"


def test_actual_capacity_write_frame():
    hx = H(mb.write_registers(ActualCapacity.REGISTER, ActualCapacity.encode(280_000), mb.SLAVE_AUX))
    assert hx.startswith("8110010B0002000445C0")
    assert len(hx) // 2 == 12  # 2 + 4 + 4 + 2（バイト数フィールドなし）


def test_actual_capacity_reply_parsed():
    reply = mb.with_crc(mb.hex_to_bytes("510304000445C0"))
    got = FrameAssembler().feed(reply)
    assert got == [ReadResponse((0x0004, 0x45C0), mb.SLAVE_AUX_REPLY)]
    assert ActualCapacity.decode(got[0].registers) == 280_000


def test_actual_capacity_reply_accepted_even_with_bad_crc():
    # 元アプリは 0x51 の応答の CRC を確認していないので、こちらも受け付ける
    got = FrameAssembler().feed(mb.hex_to_bytes("510304000445C00000"))
    assert len(got) == 1


def test_lenient_slave_only_known_shapes():
    # CRC を見ない 0x51 でも、4 バイト以外の読み出し応答は受け付けない
    got = FrameAssembler().feed(mb.hex_to_bytes("5103060001000200030000"))
    assert got == []


def test_actual_capacity_write_ack():
    ack = mb.with_crc(mb.hex_to_bytes("5110010B0002"))
    assert FrameAssembler().feed(ack) == [WriteMultiAck(267, 2, mb.SLAVE_AUX_REPLY)]


def test_actual_capacity_input():
    assert ActualCapacity.parse("280") == 280_000
    assert ActualCapacity.parse("105,5") == 105_500
    assert ActualCapacity.parse("0.5") is None
    assert ActualCapacity.parse("abc") is None
    assert ActualCapacity.format(280_000) == "280.0"


def test_extra_cells_appended():
    r = sample_live_registers()
    r[49] = 34
    for i in range(16, 32):
        r[i] = 3310
    d = LiveData.parse(r).with_extra_cells([3290] * 16)
    assert len(d.cell_voltages) == 34
    assert d.cell_voltages[33] == pytest.approx(3.290)


def test_reply_slaves():
    assert mb.reply_slaves(0x81) == {0x51, 0x81}
    assert mb.reply_slaves(0xD2) == {0xD2}
