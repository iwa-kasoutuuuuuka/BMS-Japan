"""1 台の BMS とのやり取り（Kotlin 版 ``BmsConnection.kt`` の移植）。

接続 → Notify → （あれば）"HiLink" 認証 → 時刻同期（レジスタ 212）→ 必要なら 5 秒ごとに運転データを読む。
応答にアドレスが入っていないため、「送信 → 応答を待つ」を 1 組ずつ（ロックで順番に）行う。
"""

from __future__ import annotations

import asyncio
import contextlib
import logging
import time
from collections.abc import Callable
from datetime import datetime

from .protocol import modbus as mb
from .protocol.frames import BmsFrame, FrameAssembler, ReadResponse, WriteMultiAck, WriteSingleAck
from .protocol.history import PAGE_REGISTERS, PageResult, page_address, parse_page
from .protocol.live import REGISTER_COUNT, LiveData
from .protocol.settings import (
    BALANCE_COUNT,
    BALANCE_START,
    MAIN_COUNT,
    MAIN_START,
    ActualCapacity,
    BmsPassword,
    BmsSettings,
)
from .transport.base import Transport

log = logging.getLogger(__name__)

REPLY_TIMEOUT_S = 3.0
RETRIES = 3  # 1 回 + 送り直し 2 回


class BmsTimeoutError(Exception):
    """BMS から期待した応答が返ってこなかった"""


class BmsClient:
    def __init__(
        self,
        transport: Transport,
        reply_timeout: float = REPLY_TIMEOUT_S,
        retries: int = RETRIES,
    ) -> None:
        self.transport = transport
        self.reply_timeout = reply_timeout
        self.retries = retries
        self._assembler = FrameAssembler()
        self._lock = asyncio.Lock()
        self._waiter: tuple[Callable[[BmsFrame], bool], asyncio.Future[BmsFrame]] | None = None
        self._poll_task: asyncio.Task[None] | None = None
        self.connected = False
        self.auth_result: bool | None = None
        self.time_synced = False
        self.last_live: LiveData | None = None
        self.last_update: float | None = None
        self.connected_at: float | None = None
        self.unmatched_frames = 0

    @property
    def address(self) -> str:
        return self.transport.address

    @property
    def name(self) -> str | None:
        return self.transport.name

    # ---- 接続 ----

    async def connect(self, sync_time: bool = True, now: datetime | None = None) -> None:
        self._assembler.reset()
        await self.transport.open(self._on_data, self._on_disconnect)
        self.connected = True
        self.connected_at = time.time()
        self.auth_result = await self.transport.authenticate()
        if sync_time:
            try:
                await self.sync_time(now or datetime.now())
                self.time_synced = True
            except BmsTimeoutError as e:
                log.warning("time sync not acknowledged: %s", e)

    async def close(self) -> None:
        await self.stop_polling()
        self.connected = False
        await self.transport.close()

    def _on_disconnect(self) -> None:
        self.connected = False
        if self._waiter and not self._waiter[1].done():
            self._waiter[1].set_exception(ConnectionError("disconnected"))

    # ---- 定期読み出し ----

    def start_polling(self, interval: float = 5.0) -> None:
        if self._poll_task and not self._poll_task.done():
            return

        async def loop() -> None:
            while self.connected:
                try:
                    await self.read_live()
                except Exception as e:  # noqa: BLE001
                    log.warning("poll failed: %s", e)
                await asyncio.sleep(interval)

        self._poll_task = asyncio.create_task(loop())

    async def stop_polling(self) -> None:
        task, self._poll_task = self._poll_task, None
        if task:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await task

    # ---- 送受信 ----

    def _on_data(self, data: bytes) -> None:
        for frame in self._assembler.feed(data):
            w = self._waiter
            if w and not w[1].done() and w[0](frame):
                w[1].set_result(frame)
            else:
                self.unmatched_frames += 1
                log.debug("unmatched frame: %s", frame)

    async def transaction(self, frame: bytes, matches: Callable[[BmsFrame], bool]) -> BmsFrame:
        """フレームを送り、``matches`` に合う応答を待つ。応答が無ければ送り直す。"""
        async with self._lock:
            if not self.connected:
                raise ConnectionError("not connected")
            last: Exception | None = None
            loop = asyncio.get_running_loop()
            for attempt in range(self.retries):
                fut: asyncio.Future[BmsFrame] = loop.create_future()
                # 送信より先に待ち受けを始めるので、応答を取りこぼさない
                self._waiter = (matches, fut)
                try:
                    log.debug("TX %s", mb.to_hex(frame))
                    await self.transport.write(frame)
                    return await asyncio.wait_for(fut, self.reply_timeout)
                except asyncio.TimeoutError:
                    log.warning("no reply for %s (attempt %d)", mb.to_hex(frame), attempt + 1)
                    last = BmsTimeoutError(f"no reply for {mb.to_hex(frame)}")
                finally:
                    self._waiter = None
            raise last or BmsTimeoutError("no reply")

    async def read_registers(self, start: int, count: int, slave: int = mb.SLAVE_BMS) -> list[int]:
        reply_from = mb.reply_slaves(slave)

        def ok(f: BmsFrame) -> bool:
            return isinstance(f, ReadResponse) and f.slave in reply_from and len(f.registers) == count

        frame = await self.transaction(mb.read_registers(start, count, slave), ok)
        assert isinstance(frame, ReadResponse)
        return list(frame.registers)

    async def write_register(self, register: int, value: int, slave: int = mb.SLAVE_BMS) -> None:
        """1 レジスタ書き込み。BMS が同じ値をエコーで返したら成功"""
        reply_from = mb.reply_slaves(slave)

        def ok(f: BmsFrame) -> bool:
            return isinstance(f, WriteSingleAck) and f.slave in reply_from and f.register == register and f.value == value

        await self.transaction(mb.write_register(register, value, slave), ok)

    async def write_registers(self, start: int, values: list[int], slave: int = mb.SLAVE_BMS) -> None:
        """複数レジスタ書き込み（バイト数フィールドなし）"""
        reply_from = mb.reply_slaves(slave)

        def ok(f: BmsFrame) -> bool:
            return isinstance(f, WriteMultiAck) and f.slave in reply_from and f.start == start and f.count == len(values)

        await self.transaction(mb.write_registers(start, values, slave), ok)

    # ---- まとまった読み書き ----

    async def sync_time(self, now: datetime) -> None:
        regs = mb.time_registers(now.year, now.month, now.day, now.hour, now.minute, now.second)
        await self.write_registers(mb.TIME_REGISTER, regs)

    async def read_live(self) -> LiveData:
        """運転データを読む。33 セル以上なら追加分も読む"""
        data = LiveData.parse(await self.read_registers(mb.LIVE_START, REGISTER_COUNT))
        if data.cell_count > 32:
            try:
                data = data.with_extra_cells(await self.read_registers(mb.EXTRA_CELLS_START, mb.EXTRA_CELLS_COUNT))
            except BmsTimeoutError:
                pass
        self.last_live = data
        self.last_update = time.time()
        return data

    async def read_settings(self) -> BmsSettings:
        s = BmsSettings({}).merge(MAIN_START, await self.read_registers(MAIN_START, MAIN_COUNT))
        return s.merge(BALANCE_START, await self.read_registers(BALANCE_START, BALANCE_COUNT))

    async def read_password(self) -> str:
        return BmsPassword.decode(await self.read_registers(BmsPassword.START, BmsPassword.COUNT))

    async def write_password(self, new: str) -> None:
        await self.write_registers(BmsPassword.START, BmsPassword.encode(new))

    async def read_actual_capacity(self) -> int:
        """実容量（mAh）。スレーブ 0x81 に問い合わせ、0x51 から応答が返る"""
        regs = await self.read_registers(ActualCapacity.REGISTER, ActualCapacity.COUNT, mb.SLAVE_AUX)
        return ActualCapacity.decode(regs)

    async def write_actual_capacity(self, mah: int) -> None:
        await self.write_registers(ActualCapacity.REGISTER, ActualCapacity.encode(mah), mb.SLAVE_AUX)

    async def read_history_page(self, page: int) -> PageResult:
        return parse_page(await self.read_registers(page_address(page), PAGE_REGISTERS))
