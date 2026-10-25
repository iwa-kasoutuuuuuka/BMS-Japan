"""bleak を使った実機 BLE の通信路。

接続 → Notify（fff1）有効化 → （あれば）fe00/ff05 に "HiLink" を書いて読む、までを行う。
時刻同期と定期読み出しは :class:`bms_japan.client.BmsClient` 側で行う。

注意: 実機での動作確認はまだしていません（ハードウェアが無い環境で開発）。
"""

from __future__ import annotations

import asyncio
import logging

from .base import DataCallback, DisconnectCallback, FoundDevice, Transport, adv_contains_jhb

log = logging.getLogger(__name__)

SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
NOTIFY_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
WRITE_UUID = "0000fff2-0000-1000-8000-00805f9b34fb"
#: B40 モジュールの認証用サービス（無い機種もある）
MODULE_SERVICE_UUID = "02f00000-0000-0000-0000-00000000fe00"
MODULE_AUTH_UUID = "02f00000-0000-0000-0000-00000000ff05"

CONNECT_TIMEOUT_S = 35.0


async def scan(timeout: float = 8.0, include_all: bool = False) -> list[FoundDevice]:
    """周囲の BLE 機器を探す。既定では BMS らしい機器（名前か広告データに "JHB"。元アプリと同じ条件）だけを返す。"""
    from bleak import BleakScanner

    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    out: list[FoundDevice] = []
    for address, (device, adv) in found.items():
        name = adv.local_name or device.name
        jhb = adv_contains_jhb(adv.manufacturer_data, adv.service_data)
        out.append(FoundDevice(address=address, name=name, rssi=adv.rssi, adv_has_jhb=jhb))
    if not include_all:
        out = [d for d in out if d.looks_like_bms]
    out.sort(key=lambda d: (not d.looks_like_bms, -(d.rssi if d.rssi is not None else -999)))
    return out


class BleTransport(Transport):
    def __init__(self, address: str, connect_timeout: float = CONNECT_TIMEOUT_S) -> None:
        self.address = address
        self.name = None
        self._timeout = connect_timeout
        self._client = None  # bleak.BleakClient
        self._on_disconnect: DisconnectCallback | None = None

    @property
    def kind(self) -> str:
        return "ble"

    @property
    def is_open(self) -> bool:
        return self._client is not None and self._client.is_connected

    async def open(self, on_data: DataCallback, on_disconnect: DisconnectCallback | None = None) -> None:
        from bleak import BleakClient

        self._on_disconnect = on_disconnect

        def _disconnected(_client: object) -> None:
            log.info("BLE disconnected: %s", self.address)
            if self._on_disconnect:
                self._on_disconnect()

        client = BleakClient(self.address, disconnected_callback=_disconnected, timeout=self._timeout)
        # 元アプリは接続を 2 回までやり直す
        last: Exception | None = None
        for attempt in range(3):
            try:
                await client.connect()
                break
            except Exception as e:  # noqa: BLE001 - bleak は環境ごとに例外の型が違う
                last = e
                log.warning("connect attempt %d failed: %s", attempt + 1, e)
                await asyncio.sleep(1.0)
        else:
            raise ConnectionError(f"BLE connect failed: {self.address}: {last}") from last
        self._client = client
        self.name = getattr(client, "name", None)

        if client.services.get_service(SERVICE_UUID) is None:
            await self.close()
            raise ConnectionError("BMS service (fff0) not found on this device")

        def _notify(_char: object, data: bytearray) -> None:
            on_data(bytes(data))

        await client.start_notify(NOTIFY_UUID, _notify)

    async def authenticate(self) -> bool | None:
        """B40 モジュールは "HiLink" での認証がある。無い機種もあるので失敗しても続ける"""
        client = self._client
        if client is None or client.services.get_service(MODULE_SERVICE_UUID) is None:
            return None
        try:
            await client.write_gatt_char(MODULE_AUTH_UUID, b"HiLink", response=True)
            value = await client.read_gatt_char(MODULE_AUTH_UUID)
            ok = bytes(value[:2]) == b"\x01\x00"
            log.info("module auth reply: %s", bytes(value).hex())
            return ok
        except Exception as e:  # noqa: BLE001
            log.warning("module auth skipped: %s", e)
            return False

    async def write(self, data: bytes) -> None:
        client = self._client
        if client is None or not client.is_connected:
            raise ConnectionError("not connected")
        char = client.services.get_characteristic(WRITE_UUID)
        if char is None:
            raise ConnectionError("write characteristic (fff2) not found")
        if "write" in char.properties:
            # Android 版と同じ「応答あり」の書き込み（WRITE_TYPE_DEFAULT）。512 バイトまで 1 回で送れる
            await client.write_gatt_char(char, data, response=True)
            return
        # 応答なしにしか対応していない場合は、1 回に送れる量ずつ分けて送る
        # （BMS 側の受信は文字列バッファで連結される想定。実機未確認）
        chunk = max(20, char.max_write_without_response_size)
        for i in range(0, len(data), chunk):
            await client.write_gatt_char(char, data[i : i + chunk], response=False)

    async def close(self) -> None:
        client = self._client
        self._client = None
        if client is not None:
            try:
                if client.is_connected:
                    try:
                        await client.stop_notify(NOTIFY_UUID)
                    except Exception:  # noqa: BLE001
                        pass
                    await client.disconnect()
            except Exception as e:  # noqa: BLE001
                log.debug("disconnect error: %s", e)
