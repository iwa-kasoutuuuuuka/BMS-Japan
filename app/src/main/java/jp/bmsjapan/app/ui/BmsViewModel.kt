package jp.bmsjapan.app.ui

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.bmsjapan.app.R
import jp.bmsjapan.app.ble.BmsConnection
import jp.bmsjapan.app.ble.BmsScanner
import jp.bmsjapan.app.ble.ConnectionState
import jp.bmsjapan.app.ble.FoundDevice
import jp.bmsjapan.app.protocol.BatteryType
import jp.bmsjapan.app.protocol.BmsPassword
import jp.bmsjapan.app.protocol.BmsSettings
import jp.bmsjapan.app.protocol.LiveData
import jp.bmsjapan.app.protocol.Setting
import jp.bmsjapan.app.protocol.SwitchSetting
import jp.bmsjapan.app.protocol.buildBatteryPreset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 画面下に出す短いメッセージ */
data class UiMessage(@param:StringRes val res: Int, val arg: String? = null)

/** 設定タブの状態 */
sealed interface SettingsAccess {
    /** パスワードを BMS から読んでいる */
    data object CheckingPassword : SettingsAccess

    /** パスワードを読めなかった */
    data object PasswordReadFailed : SettingsAccess

    /** パスワードの入力待ち */
    data class Locked(val wrongAttempt: Boolean = false) : SettingsAccess

    /** 確認済み。設定を表示・変更できる */
    data object Unlocked : SettingsAccess
}

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
    private var autoStopJob: Job? = null

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
            delay(15_000)
            stopScan()
        }
    }

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
        _selectedName.value = device.name ?: device.address
        replaceConnection(device.address)
    }

    fun reconnect() {
        val address = _connection.value?.address ?: return
        replaceConnection(address)
    }

    private fun replaceConnection(address: String) {
        _connection.value?.close()
        resetSettingsState()
        _connection.value = BmsConnection(getApplication(), address).also { it.connect() }
    }

    fun refreshNow() {
        val c = _connection.value ?: return
        viewModelScope.launch { runCatching { c.refreshLive() } }
    }

    fun disconnect() {
        _connection.value?.close()
        _connection.value = null
        _selectedName.value = null
        resetSettingsState()
    }

    // ---- 設定 ----
    private val _access = MutableStateFlow<SettingsAccess>(SettingsAccess.CheckingPassword)
    val access: StateFlow<SettingsAccess> = _access.asStateFlow()

    private val _settings = MutableStateFlow<BmsSettings?>(null)
    val settings: StateFlow<BmsSettings?> = _settings.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var devicePassword: String? = null

    private fun resetSettingsState() {
        devicePassword = null
        _access.value = SettingsAccess.CheckingPassword
        _settings.value = null
        _busy.value = false
    }

    /** 設定タブを開いたときに呼ぶ。パスワードを読み、初期値なら確認を省く（元アプリと同じ動き） */
    fun openSettings() {
        val access = _access.value
        if (access is SettingsAccess.Unlocked) {
            if (_settings.value == null) loadSettings()
            return
        }
        if (access is SettingsAccess.Locked) return
        val c = _connection.value ?: return
        _access.value = SettingsAccess.CheckingPassword
        viewModelScope.launch {
            try {
                val pwd = BmsPassword.decode(c.readRegisters(BmsPassword.START, BmsPassword.COUNT))
                devicePassword = pwd
                if (pwd == BmsPassword.DEFAULT) unlock() else _access.value = SettingsAccess.Locked()
            } catch (e: Exception) {
                Log.w(TAG, "password read failed", e)
                _access.value = SettingsAccess.PasswordReadFailed
            }
        }
    }

    fun submitPassword(input: String) {
        if (input == devicePassword) unlock() else _access.value = SettingsAccess.Locked(wrongAttempt = true)
    }

    private fun unlock() {
        _access.value = SettingsAccess.Unlocked
        loadSettings()
    }

    fun loadSettings() {
        val c = _connection.value ?: return
        viewModelScope.launch {
            _busy.value = true
            try {
                var s = BmsSettings.EMPTY.merge(BmsSettings.MAIN_START, c.readRegisters(BmsSettings.MAIN_START, BmsSettings.MAIN_COUNT))
                // 均等化の設定は機種によって無いことがあるので、失敗しても続ける
                runCatching { s = s.merge(BmsSettings.BALANCE_START, c.readRegisters(BmsSettings.BALANCE_START, BmsSettings.BALANCE_COUNT)) }
                _settings.value = s
            } catch (e: Exception) {
                Log.w(TAG, "settings read failed", e)
                _messages.tryEmit(UiMessage(R.string.msg_read_failed))
            } finally {
                _busy.value = false
            }
        }
    }

    fun writeSetting(setting: Setting, raw: Int) = runWrite { c ->
        c.writeRegister(setting.register, raw)
    }

    fun setSwitch(sw: SwitchSetting, on: Boolean) = runWrite { c ->
        c.writeRegister(sw.register, if (on) 1 else 0)
    }

    fun applyBatteryType(type: BatteryType) {
        val s = _settings.value ?: return
        val cells = live.value?.cellCount ?: return
        val values = buildBatteryPreset(type, cells, s) ?: return
        runWrite { c -> c.writeRegisters(137, values) }
    }

    /** 書き込み → 応答を確認 → 設定を読み直す */
    private fun runWrite(block: suspend (BmsConnection) -> Unit) {
        val c = _connection.value ?: return
        if (_access.value !is SettingsAccess.Unlocked || _busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                block(c)
                _messages.tryEmit(UiMessage(R.string.msg_write_ok))
            } catch (e: Exception) {
                Log.w(TAG, "write failed", e)
                _messages.tryEmit(UiMessage(R.string.msg_write_failed))
            } finally {
                _busy.value = false
            }
            loadSettings()
        }
    }

    override fun onCleared() {
        _connection.value?.close()
    }

    private companion object {
        const val TAG = "BmsViewModel"
    }
}
