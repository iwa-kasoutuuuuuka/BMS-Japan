"""BLE に依存しない純粋なプロトコル層（docs/ble_protocol.md の実装）。"""

from . import history, modbus
from .frames import BmsFrame, FrameAssembler, ReadResponse, WriteMultiAck, WriteSingleAck
from .history import HistoryEvent, HistoryRecord, PageResult, parse_page
from .live import Alarm, ChargeState, LiveData
from .pack import PackMode, PackState, PackSummary
from .settings import (
    SETTINGS,
    SETTINGS_BY_KEY,
    ActualCapacity,
    BatteryType,
    BmsPassword,
    BmsSettings,
    Encoding,
    PasswordChangeCheck,
    Setting,
    SettingGroup,
    SwitchSetting,
    ValidationResult,
    build_battery_preset,
    validate,
)

__all__ = [
    "history",
    "modbus",
    "BmsFrame",
    "FrameAssembler",
    "ReadResponse",
    "WriteMultiAck",
    "WriteSingleAck",
    "HistoryEvent",
    "HistoryRecord",
    "PageResult",
    "parse_page",
    "Alarm",
    "ChargeState",
    "LiveData",
    "PackMode",
    "PackState",
    "PackSummary",
    "SETTINGS",
    "SETTINGS_BY_KEY",
    "ActualCapacity",
    "BatteryType",
    "BmsPassword",
    "BmsSettings",
    "Encoding",
    "PasswordChangeCheck",
    "Setting",
    "SettingGroup",
    "SwitchSetting",
    "ValidationResult",
    "build_battery_preset",
    "validate",
]
