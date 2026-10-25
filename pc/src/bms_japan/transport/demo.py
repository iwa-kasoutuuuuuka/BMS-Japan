"""シミュレーター（疑似 BMS）につなぐ通信路。実機の Notify と同じく、応答を 20 バイトずつ非同期に届ける。"""

from __future__ import annotations

import asyncio

from ..simulator import DemoRegistry, FakeBms
from .base import DataCallback, DisconnectCallback, FoundDevice, Transport

CHUNK = 20  # BLE の既定 MTU 相当


class DemoTransport(Transport):
    def __init__(self, address: str, registry: DemoRegistry, latency: float = 0.002) -> None:
        self.address = address.upper()
        self.registry = registry
        self.latency = latency
        self.device: FakeBms | None = None
        self._on_data: DataCallback | None = None
        self._open = False

    @property
    def kind(self) -> str:
        return "demo"

    @property
    def is_open(self) -> bool:
        return self._open

    async def open(self, on_data: DataCallback, on_disconnect: DisconnectCallback | None = None) -> None:
        self.device = self.registry.get(self.address)
        self.name = self.device.name
        self._on_data = on_data
        self._open = True
        await asyncio.sleep(self.latency)

    async def authenticate(self) -> bool | None:
        return None  # デモ機器は B40 モジュールではない扱い

    async def write(self, data: bytes) -> None:
        if not self._open or self.device is None:
            raise ConnectionError("not connected")
        replies: list[bytes] = []
        # 送信側も細切れにして渡し、シミュレーターの要求組み立てを通す
        for i in range(0, len(data), CHUNK):
            replies += self.device.receive(data[i : i + CHUNK])
        loop = asyncio.get_running_loop()
        delay = self.latency
        for reply in replies:
            for i in range(0, len(reply), CHUNK):
                chunk = reply[i : i + CHUNK]
                loop.call_later(delay, self._deliver, chunk)
                delay += self.latency / 4

    def _deliver(self, chunk: bytes) -> None:
        if self._open and self._on_data:
            self._on_data(chunk)

    async def close(self) -> None:
        if self._open:
            self.registry.save(self.address)
        self._open = False
        self._on_data = None


def demo_scan(registry: DemoRegistry) -> list[FoundDevice]:
    from ..simulator import DEMO_PROFILES

    return [FoundDevice(addr, p.name, -50 - 6 * i) for i, (addr, p) in enumerate(DEMO_PROFILES.items())]
