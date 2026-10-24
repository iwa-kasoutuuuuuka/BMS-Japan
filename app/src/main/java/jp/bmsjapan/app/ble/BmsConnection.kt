package jp.bmsjapan.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import jp.bmsjapan.app.protocol.BmsCommands
import jp.bmsjapan.app.protocol.BmsFrame
import jp.bmsjapan.app.protocol.FrameAssembler
import jp.bmsjapan.app.protocol.LiveData
import jp.bmsjapan.app.protocol.Modbus
import jp.bmsjapan.app.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, FAILED }

/** BMS から期待した応答が返ってこなかった */
class BmsTimeoutException(message: String) : Exception(message)

/**
 * 1 台の BMS との BLE 接続。
 * 接続 → MTU 512 → サービス探索 → Notify 有効化 → 認証（あれば）→ 時刻同期 → 5 秒ごとに運転データを読む。
 *
 * 2 段階の順番待ちがある:
 * - [gattMutex]: Android の GATT は同時に 1 操作しか受け付けない
 * - [transactionMutex]: 応答にアドレスが入っていないため、「送信 → 応答を待つ」を 1 組ずつ行う
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT は接続前に画面側で確認している
class BmsConnection(
    private val context: Context,
    val address: String,
    private val pollIntervalMs: Long = 5_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gattMutex = Mutex()
    private val transactionMutex = Mutex()
    private var pending: CompletableDeferred<Int>? = null
    private var gatt: BluetoothGatt? = null
    private var pollJob: Job? = null
    private val assembler = FrameAssembler()
    private val frames = MutableSharedFlow<BmsFrame>(extraBufferCapacity = 32)

    private val _state = MutableStateFlow(ConnectionState.CONNECTING)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _live = MutableStateFlow<LiveData?>(null)
    val live: StateFlow<LiveData?> = _live.asStateFlow()

    private val _lastUpdate = MutableStateFlow<Long?>(null)
    val lastUpdate: StateFlow<Long?> = _lastUpdate.asStateFlow()

    fun connect() {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        val device = adapter?.getRemoteDevice(address)
        if (device == null) {
            _state.value = ConnectionState.FAILED
            return
        }
        _state.value = ConnectionState.CONNECTING
        assembler.reset()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    fun close() {
        pollJob?.cancel()
        scope.cancel()
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        _state.value = ConnectionState.DISCONNECTED
    }

    // ---- GATT コールバック ----

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "connection status=$status state=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                scope.launch { setUp(g) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                pollJob?.cancel()
                pending?.complete(-1)
                _state.value = if (_state.value == ConnectionState.CONNECTING) ConnectionState.FAILED else ConnectionState.DISCONNECTED
                runCatching { g.close() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "mtu=$mtu status=$status")
            pending?.complete(status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(status)
        }

        @Deprecated("Android 12 以下用")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(c, value)
        }

        @Deprecated("Android 12 以下用")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onNotify(c, c.value ?: return)
        }
    }

    // ---- 初期化シーケンス ----

    private suspend fun setUp(g: BluetoothGatt) {
        try {
            gattOp { g.requestMtu(512) }
            val ok = gattOp { g.discoverServices() }
            check(ok == BluetoothGatt.GATT_SUCCESS) { "service discovery failed: $ok" }
            val service = g.getService(BmsUuids.SERVICE) ?: error("BMS service not found")
            val notify = service.getCharacteristic(BmsUuids.NOTIFY) ?: error("notify characteristic not found")
            enableNotify(g, notify)

            // B40 モジュールは "HiLink" での認証がある。無い機種もあるので失敗しても続ける
            g.getService(BmsUuids.MODULE_SERVICE)?.getCharacteristic(BmsUuids.MODULE_AUTH)?.let { auth ->
                runCatching {
                    write(g, auth, "HiLink".toByteArray())
                    gattOp { g.readCharacteristic(auth) }
                }.onFailure { Log.w(TAG, "module auth skipped", it) }
            }

            val now = LocalDateTime.now()
            runCatching {
                transaction(BmsCommands.syncTime(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second)) {
                    it is BmsFrame.WriteMultiAck && it.slave == Modbus.SLAVE_BMS && it.start == 212
                }
            }.onFailure { Log.w(TAG, "time sync not acknowledged", it) }

            _state.value = ConnectionState.CONNECTED
            startPolling()
        } catch (e: Exception) {
            Log.e(TAG, "setup failed", e)
            _state.value = ConnectionState.FAILED
            runCatching { g.disconnect() }
        }
    }

    private suspend fun enableNotify(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(BmsUuids.CCCD) ?: return
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        gattOp {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                runCatching { refreshLive() }.onFailure { Log.w(TAG, "poll failed", it) }
                delay(pollIntervalMs)
            }
        }
    }

    // ---- 公開 API（すべて応答を待ってから戻る） ----

    /** 運転データを読む。33 セル以上なら追加分も読む */
    suspend fun refreshLive() {
        var data = LiveData.parse(readRegisters(0, LiveData.REGISTER_COUNT, Modbus.SLAVE_BMS))
        if (data.cellCount > 32) {
            runCatching { data = data.withExtraCells(readRegisters(71, 16)) }
        }
        _live.value = data
        _lastUpdate.value = System.currentTimeMillis()
    }

    /** 保持レジスタを読む */
    suspend fun readRegisters(start: Int, count: Int, slave: Int = Modbus.SLAVE_BMS): IntArray {
        val replyFrom = Modbus.replySlaves(slave)
        val frame = transaction(Modbus.readRegisters(start, count, slave)) {
            it is BmsFrame.ReadResponse && it.slave in replyFrom && it.registers.size == count
        }
        return (frame as BmsFrame.ReadResponse).registers
    }

    /** 1 レジスタ書き込み。BMS が同じ値をエコーで返したら成功 */
    suspend fun writeRegister(register: Int, value: Int, slave: Int = Modbus.SLAVE_BMS) {
        val replyFrom = Modbus.replySlaves(slave)
        transaction(Modbus.writeRegister(register, value, slave)) {
            it is BmsFrame.WriteSingleAck && it.slave in replyFrom && it.register == register && it.value == value
        }
    }

    /** 複数レジスタ書き込み */
    suspend fun writeRegisters(start: Int, values: IntArray, slave: Int = Modbus.SLAVE_BMS) {
        val replyFrom = Modbus.replySlaves(slave)
        transaction(Modbus.writeRegisters(start, values, slave)) {
            it is BmsFrame.WriteMultiAck && it.slave in replyFrom && it.start == start && it.count == values.size
        }
    }

    // ---- 送受信 ----

    /**
     * フレームを送り、[matches] に合う応答を待つ。応答が無ければ 2 回まで送り直す。
     * 送信より先に受信の待ち受けを始めるので、応答を取りこぼさない。
     */
    private suspend fun transaction(frame: ByteArray, matches: (BmsFrame) -> Boolean): BmsFrame =
        transactionMutex.withLock {
            var lastError: Exception? = null
            repeat(3) { attempt ->
                try {
                    return@withLock coroutineScope {
                        val reply = async(start = CoroutineStart.UNDISPATCHED) {
                            withTimeout(REPLY_TIMEOUT_MS) { frames.first(matches) }
                        }
                        sendRaw(frame)
                        reply.await()
                    }
                } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                    Log.w(TAG, "no reply for ${frame.toHex()} (attempt ${attempt + 1})")
                    lastError = BmsTimeoutException("no reply for ${frame.toHex()}")
                }
            }
            throw lastError ?: BmsTimeoutException("no reply")
        }

    private suspend fun sendRaw(frame: ByteArray) {
        val g = gatt ?: error("not connected")
        val c = g.getService(BmsUuids.SERVICE)?.getCharacteristic(BmsUuids.WRITE) ?: error("write characteristic not found")
        Log.d(TAG, "TX ${frame.toHex()}")
        write(g, c, frame)
    }

    private suspend fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray) {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val status = gattOp {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, data, type) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
        check(status == BluetoothGatt.GATT_SUCCESS) { "write failed: $status" }
    }

    private fun onNotify(c: BluetoothGattCharacteristic, value: ByteArray) {
        if (c.uuid != BmsUuids.NOTIFY) return
        Log.d(TAG, "RX ${value.toHex()}")
        for (frame in assembler.feed(value)) frames.tryEmit(frame)
    }

    /**
     * GATT 操作を 1 つ実行し、対応するコールバックの status を待つ。
     * [start] が false を返したら（キューに入らなかったら）その場で失敗。
     */
    private suspend fun gattOp(start: () -> Boolean): Int = gattMutex.withLock {
        val d = CompletableDeferred<Int>()
        pending = d
        if (!start()) {
            pending = null
            error("GATT operation rejected")
        }
        try {
            withTimeout(OP_TIMEOUT_MS) { d.await() }
        } finally {
            pending = null
        }
    }

    private companion object {
        const val TAG = "BmsConnection"
        const val OP_TIMEOUT_MS = 10_000L
        const val REPLY_TIMEOUT_MS = 3_000L
    }
}
