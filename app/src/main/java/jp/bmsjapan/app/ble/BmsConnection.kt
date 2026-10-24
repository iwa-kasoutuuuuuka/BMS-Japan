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
import jp.bmsjapan.app.protocol.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.LocalDateTime

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, FAILED }

/**
 * 1 台の BMS との BLE 接続。
 * 接続 → MTU 512 → サービス探索 → Notify 有効化 → 認証（あれば）→ 時刻同期 → 5 秒ごとに運転データを読む。
 *
 * Android の GATT は同時に 1 操作しか受け付けないため、操作は [gattMutex] で 1 つずつ実行する。
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT は接続前に画面側で確認している
class BmsConnection(
    private val context: Context,
    val address: String,
    private val pollIntervalMs: Long = 5_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gattMutex = Mutex()
    private var pending: CompletableDeferred<Int>? = null
    private var gatt: BluetoothGatt? = null
    private var pollJob: Job? = null
    private val assembler = FrameAssembler()

    /** 最後に送った読み出しの開始アドレス。応答にはアドレスが無いので、これで区別する */
    @Volatile private var lastReadStart = -1

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
                    write(g, auth, "HiLink".toByteArray(), withResponse = true)
                    gattOp { g.readCharacteristic(auth) }
                }.onFailure { Log.w(TAG, "module auth skipped", it) }
            }

            val now = LocalDateTime.now()
            send(BmsCommands.syncTime(now.year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second))

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
                runCatching { requestLive() }.onFailure { Log.w(TAG, "poll failed", it) }
                delay(pollIntervalMs)
            }
        }
    }

    /** 運転データを読む。33 セル以上なら追加分も読む */
    suspend fun requestLive() {
        lastReadStart = 0
        send(BmsCommands.READ_LIVE)
        val cells = _live.value?.cellCount ?: 0
        if (cells > 32) {
            delay(300) // 前の応答が届くのを待つ（応答にアドレスが無いため重ならないようにする）
            lastReadStart = 71
            send(BmsCommands.READ_EXTRA_CELLS)
        }
    }

    // ---- 送受信 ----

    /** BMS へフレームを送る（fff2 に書き込み） */
    suspend fun send(frame: ByteArray) {
        val g = gatt ?: error("not connected")
        val c = g.getService(BmsUuids.SERVICE)?.getCharacteristic(BmsUuids.WRITE) ?: error("write characteristic not found")
        Log.d(TAG, "TX ${frame.toHex()}")
        write(g, c, frame, withResponse = true)
    }

    private suspend fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, data: ByteArray, withResponse: Boolean) {
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
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
        for (frame in assembler.feed(value)) handle(frame)
    }

    private fun handle(frame: BmsFrame) {
        if (frame !is BmsFrame.ReadResponse) return
        val regs = frame.registers
        when {
            // 元アプリと同じく、バイト数（レジスタ数）で応答の種類を判断する
            regs.size == LiveData.REGISTER_COUNT -> {
                _live.value = LiveData.parse(regs)
                _lastUpdate.value = System.currentTimeMillis()
            }
            regs.size == 16 && lastReadStart == 71 -> {
                _live.value = _live.value?.withExtraCells(regs)
            }
        }
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
    }
}
