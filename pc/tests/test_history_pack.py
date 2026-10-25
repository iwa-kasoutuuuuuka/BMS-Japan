"""Kotlin 版 HistoryTest.kt / PackTest.kt の移植（同じ期待値）。"""

from datetime import datetime

import pytest

from bms_japan.protocol import history as h
from bms_japan.protocol.history import HistoryEvent
from bms_japan.protocol.live import Alarm, ChargeState, LiveData
from bms_japan.protocol.pack import PackMode, PackState, PackSummary


def record(number: int, total: int, event_id: int, occurred: bool, month: int = 10) -> bytes:
    b = bytearray(34)

    def u16(i, v):
        b[i] = (v >> 8) & 0xFF
        b[i + 1] = v & 0xFF

    u16(0, number)
    u16(2, total)
    u16(4, 26)
    b[6:12] = bytes((26, month, 24, 13, 5, 9))  # 2026-10-24 13:05:09
    b[12] = event_id
    b[13] = 1 if occurred else 0
    u16(14, 532)  # 53.2 V
    u16(16, 30000 - 125)  # −12.5 A
    u16(18, 456)  # 45.6 %
    b[20] = 0x02  # 放電MOSのみ ON
    u16(21, 3456)
    b[23] = 7
    u16(24, 3301)
    b[26] = 12
    b[27], b[28] = 70, 2
    b[29], b[30] = 62, 4
    b[31] = 0x5A
    return bytes(b)


def to_registers(data: bytes) -> list[int]:
    padded = data.ljust(h.PAGE_REGISTERS * 2, b"\x00")
    return [(padded[i * 2] << 8) | padded[i * 2 + 1] for i in range(h.PAGE_REGISTERS)]


def test_page_address():
    assert h.page_address(0) == 256
    assert h.page_address(3) == 259
    assert h.PAGE_REGISTERS * 2 == 0xFE


def test_parse_full_page():
    data = b"".join(record(n, 9, event_id=8, occurred=n % 2 == 1) for n in range(1, 8))
    page = h.parse_page(to_registers(data))
    assert len(page.records) == 7
    assert not page.reached_end
    r = page.records[0]
    assert r.number == 1
    assert r.total_count == 9
    assert r.time == datetime(2026, 10, 24, 13, 5, 9)
    assert r.occurred
    assert not page.records[1].occurred
    assert r.total_voltage == pytest.approx(53.2)
    assert r.current == pytest.approx(-12.5)
    assert r.soc == pytest.approx(45.6)
    assert not r.charge_mos
    assert r.discharge_mos
    assert r.max_cell_voltage == pytest.approx(3.456) and r.max_cell_index == 7
    assert r.min_cell_voltage == pytest.approx(3.301) and r.min_cell_index == 12
    assert r.max_temperature == 30 and r.max_temperature_index == 2
    assert r.min_temperature == 22 and r.min_temperature_index == 4
    assert r.fault_code == 0x5A
    assert r.event == HistoryEvent("ev_cell_ov1")
    assert r.event.label == "セル電圧が高い（レベル1）"


def test_stops_at_empty_record():
    page = h.parse_page(to_registers(record(1, 2, 0, True) + record(2, 2, 1, True)))
    assert len(page.records) == 2
    assert page.reached_end


def test_invalid_date_becomes_none():
    page = h.parse_page(to_registers(record(1, 1, 0, True, month=13)))
    assert page.records[0].time is None


def test_event_names():
    assert HistoryEvent.of(51) == HistoryEvent("ev_reserved_signal", 3)
    assert HistoryEvent.of(51).label == "予備のイベント信号3"
    assert HistoryEvent.of(140) == HistoryEvent("ev_reserved", 140)
    assert HistoryEvent.of(200) == HistoryEvent("ev_unknown", 200)
    assert HistoryEvent.of(200).label == "不明なイベント（ID 200）"
    assert HistoryEvent.of(255) == HistoryEvent("ev_power_on")


def test_encode_record_round_trip():
    raw = h.encode_record(3, 10, datetime(2026, 1, 2, 3, 4, 5), 9, True, 51.2, 30000 + 55, 33.3, 3, 3400, 2, 3200, 5, 31, 1, 20, 2, 7)
    r = h.parse_record(raw)
    assert r is not None and r.number == 3 and r.event_id == 9 and r.current == pytest.approx(5.5)
    assert r.charge_mos and r.discharge_mos and r.min_temperature == 20 and r.fault_code == 7


# ---- Pack ----


def unit(voltage, current, soc, remaining, state=ChargeState.CHARGING, power=0.5, max_cell=3.35, min_cell=3.30, max_t=30, min_t=25, alarms=()):
    return LiveData(
        cell_voltages=[], temperatures=[], total_voltage=voltage, current=current, soc=soc,
        max_cell_voltage=max_cell, min_cell_voltage=min_cell, max_temperature=max_t, min_temperature=min_t,
        state=state, remaining_capacity=remaining, cell_count=16, temperature_sensor_count=2, cycle_count=10,
        balancing=False, charge_mos=True, discharge_mos=True, average_cell_voltage=3.32, cell_voltage_delta=0.05,
        power_kw=power, alarms=list(alarms),
    )  # fmt: skip


A = unit(53.0, -10.0, 80.0, 200.0, max_cell=3.40, min_t=20)
B = unit(52.0, -12.0, 60.0, 150.0, min_cell=3.25, max_t=35, alarms=[Alarm.CELL_OV1])


def test_parallel():
    p = PackSummary.of(PackMode.PARALLEL, [A, B])
    assert p.voltage == pytest.approx(52.5)
    assert p.current == pytest.approx(22.0)
    assert p.remaining_ah == pytest.approx(350.0)
    assert p.soc == pytest.approx(70.0)
    assert p.power_kw == pytest.approx(1.0)
    assert p.unit_count == 2


def test_series():
    p = PackSummary.of(PackMode.SERIES, [A, B])
    assert p.voltage == pytest.approx(105.0)
    assert p.current == pytest.approx(11.0)
    assert p.remaining_ah == pytest.approx(150.0)
    assert p.soc == pytest.approx(60.0)


def test_extremes_across_units():
    p = PackSummary.of(PackMode.PARALLEL, [A, B])
    assert p.max_cell_voltage == pytest.approx(3.40)
    assert p.min_cell_voltage == pytest.approx(3.25)
    assert p.cell_voltage_delta == pytest.approx(0.15)
    assert p.max_temperature == 35
    assert p.min_temperature == 20
    assert p.alarm_count == 1


def test_single():
    p = PackSummary.of(PackMode.SINGLE, [A])
    assert p.voltage == pytest.approx(53.0)
    assert p.current == pytest.approx(10.0)
    assert p.remaining_ah == pytest.approx(200.0)


def test_empty():
    assert PackSummary.of(PackMode.PARALLEL, []) is None


def test_states():
    c = PackSummary.combined_state
    assert c([ChargeState.CHARGING, ChargeState.DISCHARGING]) is PackState.MIXED
    assert c([ChargeState.CHARGING, ChargeState.IDLE]) is PackState.CHARGING
    assert c([ChargeState.IDLE, ChargeState.UNKNOWN]) is PackState.IDLE
    assert PackState.MIXED.label == "充電・放電が混在"


def test_mode_codes():
    assert PackMode.from_code(2) is PackMode.SERIES
    assert PackMode.from_code(None) is PackMode.SINGLE
    assert PackMode.PARALLEL.max_units == 6
