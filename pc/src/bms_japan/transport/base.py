"""通信路の共通インターフェース（実機 BLE とデモ用シミュレーターで共通）。"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Callable
from dataclasses import dataclass

DataCallback = Callable[[bytes], None]
DisconnectCallback = Callable[[], None]


@dataclass(frozen=True)
class FoundDevice:
    address: str
    name: str | None
    rssi: int | None

    @property
    def looks_like_bms(self) -> bool:
        """元アプリは名前に "JHB" を含む機器だけを一覧に出していた"""
        return self.name is not None and "JHB" in self.name.upper()


class Transport(ABC):
    """1 台の BMS への生のバイト通信路。

    ``open`` で受信コールバックを登録し、``write`` で 1 フレームを送る。
    受信データ（Notify）は細切れで ``on_data`` に届く。
    """

    address: str
    name: str | None = None

    @abstractmethod
    async def open(self, on_data: DataCallback, on_disconnect: DisconnectCallback | None = None) -> None: ...

    @abstractmethod
    async def write(self, data: bytes) -> None: ...

    @abstractmethod
    async def close(self) -> None: ...

    @property
    @abstractmethod
    def is_open(self) -> bool: ...

    async def authenticate(self) -> bool | None:
        """B40 モジュールの "HiLink" 認証。対応していない機器は None"""
        return None

    @property
    def kind(self) -> str:
        return "unknown"
