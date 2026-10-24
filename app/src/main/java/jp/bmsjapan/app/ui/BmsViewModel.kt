package jp.bmsjapan.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.bmsjapan.app.ble.BmsConnection
import jp.bmsjapan.app.ble.BmsScanner
import jp.bmsjapan.app.ble.ConnectionState
import jp.bmsjapan.app.ble.FoundDevice
import jp.bmsjapan.app.protocol.LiveData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class BmsViewModel(app: Application) : AndroidViewModel(app) {
    private val scanner = BmsScanner(app)

    // ---- スキャン ----
    private val _devices = MutableStateFlow<List<FoundDevice>>(emptyList())
    val devices: StateFlow<List<FoundDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    private var scanJob: Job? = null

    val isBluetoothEnabled: Boolean get() = scanner.isBluetoothEnabled

    fun startScan() {
        scanJob?.cancel()
        _devices.value = emptyList()
        _scanError.value = null
        _scanning.value = true
        scanJob = viewModelScope.launch {
            scanner.scan()
                .catch { _scanError.value = it.message }
                .collect { found ->
                    val list = _devices.value.filterNot { it.address == found.address } + found
                    // BMS らしい名前を先頭に、その中は電波の強い順
                    _devices.value = list.sortedWith(compareByDescending<FoundDevice> { it.looksLikeBms }.thenByDescending { it.rssi })
                }
            _scanning.value = false
        }
        // 15 秒で自動停止
        autoStopJob?.cancel()
        autoStopJob = viewModelScope.launch {
            kotlinx.coroutines.delay(15_000)
            stopScan()
        }
    }

    private var autoStopJob: Job? = null

    fun stopScan() {
        autoStopJob?.cancel()
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    // ---- 接続 ----
    private val _connection = MutableStateFlow<BmsConnection?>(null)
    val connection: StateFlow<BmsConnection?> = _connection.asStateFlow()

    private val _selectedName = MutableStateFlow<String?>(null)
    val selectedName: StateFlow<String?> = _selectedName.asStateFlow()

    val connectionState: StateFlow<ConnectionState?> = _connection
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val live: StateFlow<LiveData?> = _connection
        .flatMapLatest { it?.live ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val lastUpdate: StateFlow<Long?> = _connection
        .flatMapLatest { it?.lastUpdate ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun connect(device: FoundDevice) {
        stopScan()
        _connection.value?.close()
        _selectedName.value = device.name ?: device.address
        _connection.value = BmsConnection(getApplication(), device.address).also { it.connect() }
    }

    fun reconnect() {
        val address = _connection.value?.address ?: return
        _connection.value?.close()
        _connection.value = BmsConnection(getApplication(), address).also { it.connect() }
    }

    fun refreshNow() {
        val c = _connection.value ?: return
        viewModelScope.launch { runCatching { c.requestLive() } }
    }

    fun disconnect() {
        _connection.value?.close()
        _connection.value = null
        _selectedName.value = null
    }

    override fun onCleared() {
        _connection.value?.close()
    }
}
