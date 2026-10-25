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
    #: 広告データ（メーカー固有データ）に "JHB" が入っているか
    adv_has_jhb: bool = False

    @property
    def looks_like_bms(self) -> bool:
        """元アプリと同じ判定: 名前に "JHB" を含む、または広告データに "JHB"（4A 48 42）が入っている。
        実機では名前が "LEAF_005" などで、メーカー固有データの末尾が 4A4842 の機種がある。"""
        return self.adv_has_jhb or (self.name is not None and "JHB" in self.name.upper())


def adv_contains_jhb(manufacturer_data: dict[int, bytes], service_data: dict[str, bytes] | None = None) -> bool:
    """広告データのどこかに "JHB" のバイト列があるか"""
    blobs = list(manufacturer_data.values()) + list((service_data or {}).values())
    return any(b"JHB" in bytes(b) for b in blobs)


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
