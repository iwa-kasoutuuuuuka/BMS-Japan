package jp.bmsjapan.app.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import jp.bmsjapan.app.R
import jp.bmsjapan.app.ble.BmsConnection
import jp.bmsjapan.app.ble.BmsScanner
import jp.bmsjapan.app.ble.ConnectionState
import jp.bmsjapan.app.ble.FoundDevice
import jp.bmsjapan.app.data.SavedDevice
import jp.bmsjapan.app.data.SavedDevicesCodec
import jp.bmsjapan.app.data.SavedSetup
import jp.bmsjapan.app.protocol.ActualCapacity
import jp.bmsjapan.app.protocol.BatteryType
import jp.bmsjapan.app.protocol.Modbus
import jp.bmsjapan.app.protocol.BmsPassword
import jp.bmsjapan.app.protocol.BmsSettings
import jp.bmsjapan.app.protocol.HistoryProtocol
import jp.bmsjapan.app.protocol.HistoryRecord
import jp.bmsjapan.app.protocol.LiveData
import jp.bmsjapan.app.protocol.PackMode
import jp.bmsjapan.app.protocol.PackSummary
import jp.bmsjapan.app.protocol.PasswordChangeCheck
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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

    // ---- つなぎ方（単体・並列・直列） ----
    private val prefs = app.getSharedPreferences("bms_japan", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(PackMode.fromCode(prefs.getInt(PREF_MODE, PackMode.SINGLE.code)))
    val mode: StateFlow<PackMode> = _mode.asStateFlow()

    /** つなぎ方を変える。今の台数が新しい上限を超えるときは全部切断する */
    fun setMode(m: PackMode) {
        if (m == _mode.value) return
        if (_units.value.size > m.maxUnits) disconnectAll()
        _mode.value = m
        prefs.edit().putInt(PREF_MODE, m.code).apply()
    }

    // ---- 接続中の機器 ----
    private val _units = MutableStateFlow<List<PackUnit>>(emptyList())
    val units: StateFlow<List<PackUnit>> = _units.asStateFlow()

    // ---- 前回の機器と自動接続 ----
    private val _saved = MutableStateFlow(SavedDevicesCodec.decode(prefs.getString(PREF_SAVED, null))?.takeIf { !it.isEmpty })
    val saved: StateFlow<SavedSetup?> = _saved.asStateFlow()

    private val _autoConnect = MutableStateFlow(prefs.getBoolean(PREF_AUTO_CONNECT, true))
    val autoConnect: StateFlow<Boolean> = _autoConnect.asStateFlow()

    private var autoConnectTried = false
    private val retryJobs = mutableMapOf<String, Job>()

    fun setAutoConnect(on: Boolean) {
        _autoConnect.value = on
        prefs.edit().putBoolean(PREF_AUTO_CONNECT, on).apply()
    }

    /** 今つないでいる機器を「前回の機器」として保存する */
    private fun persistUnits() {
        val list = _units.value
        if (list.isEmpty()) return
        val setup = SavedSetup(_mode.value, list.map { SavedDevice(it.address, it.name) })
        _saved.value = setup
        prefs.edit().putString(PREF_SAVED, SavedDevicesCodec.encode(setup)).apply()
    }

    fun forgetSaved() {
        _saved.value = null
        prefs.edit().remove(PREF_SAVED).apply()
    }

    /**
     * 起動して BLE が使えるようになったときに 1 回だけ呼ぶ。
     * 自動接続がオンで前回の機器があれば接続を始めて true を返す（このときはスキャンしない）。
     */
    fun autoConnectOnStart(): Boolean {
        if (autoConnectTried) return false
        autoConnectTried = true
        if (!_autoConnect.value || _units.value.isNotEmpty()) return false
        _saved.value ?: return false
        connectSaved()
        return true
    }

    /** 前回の機器にまとめて接続する（スキャンせず、アドレスで直接つなぐ） */
    fun connectSaved() {
        val setup = _saved.value ?: return
        stopScan()
        disconnectAll()
        _mode.value = setup.mode
        prefs.edit().putInt(PREF_MODE, setup.mode.code).apply()
        _units.value = setup.devices.map { newUnit(it.address, it.name) }
        setup.devices.forEach { supervise(it.address) }
        if (setup.mode == PackMode.SINGLE) setup.devices.firstOrNull()?.let { select(it.address) }
    }

    // ---- 自動再接続 ----

    /** 切れたら自動でつなぎ直すか（設定として保存） */
    private val _autoReconnect = MutableStateFlow(prefs.getBoolean(PREF_AUTO_RECONNECT, true))
    val autoReconnect: StateFlow<Boolean> = _autoReconnect.asStateFlow()

    fun setAutoReconnect(on: Boolean) {
        _autoReconnect.value = on
        prefs.edit().putBoolean(PREF_AUTO_RECONNECT, on).apply()
    }

    /** つなぎ直している機器と、その回数（アドレス → 何回目か） */
    private val _reconnecting = MutableStateFlow<Map<String, Int>>(emptyMap())
    val reconnecting: StateFlow<Map<String, Int>> = _reconnecting.asStateFlow()

    private fun setAttempt(address: String, attempt: Int?) {
        _reconnecting.value = if (attempt == null) _reconnecting.value - address else _reconnecting.value + (address to attempt)
    }

    private fun connectionOf(address: String): BmsConnection? = _units.value.firstOrNull { it.address == address }?.connection

    /**
     * 1 台の接続を見張る。一覧にいる間ずっと動く。
     * - 最初の接続に失敗したとき、または使用中に切れたとき、少しずつ間隔を空けてつなぎ直す（最大 [MAX_RECONNECT_ATTEMPTS] 回）
     * - つながったら回数を 0 に戻す
     * - 「この台を外す」「すべて切断」ではこの見張りを先に止めるので、つなぎ直さない
     */
    private fun supervise(address: String) {
        retryJobs.remove(address)?.cancel()
        retryJobs[address] = viewModelScope.launch {
            var attempt = 0
            while (true) {
                val conn = connectionOf(address) ?: break
                val state = conn.state.first { it != ConnectionState.CONNECTING }
                if (connectionOf(address) !== conn) continue   // 手動の再接続などで差し替わった
                if (state == ConnectionState.CONNECTED) {
                    attempt = 0
                    setAttempt(address, null)
                    conn.state.first { it != ConnectionState.CONNECTED }   // 切れるまで待つ
                    if (connectionOf(address) !== conn) continue
                    Log.i(TAG, "connection lost: $address")
                }
                if (!_autoReconnect.value || attempt >= MAX_RECONNECT_ATTEMPTS) break
                attempt++
                setAttempt(address, attempt)
                delay(RECONNECT_DELAYS_MS[minOf(attempt - 1, RECONNECT_DELAYS_MS.lastIndex)])
                if (connectionOf(address) !== conn) continue
                Log.i(TAG, "reconnecting $address ($attempt/$MAX_RECONNECT_ATTEMPTS)")
                replaceConnection(address)
            }
            setAttempt(address, null)
        }
    }

    /** 自動再接続をやめる（その台は「接続できませんでした」のまま残る） */
    fun stopReconnecting(address: String) {
        retryJobs.remove(address)?.cancel()
        setAttempt(address, null)
    }

    /** 並列・直列で機器を追加するためにスキャン画面を開いているか */
    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding.asStateFlow()

    fun startAdding() {
        _adding.value = true
    }

    fun stopAdding() {
        stopScan()
        _adding.value = false
    }

    /** 詳細画面（ライブ・履歴・設定）で開いている機器 */
    private val _connection = MutableStateFlow<BmsConnection?>(null)
    val connection: StateFlow<BmsConnection?> = _connection.asStateFlow()

    val selectedName: StateFlow<String?> = combine(_connection, _units) { c, list ->
        list.firstOrNull { it.connection === c }?.name
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 全台の状態（一覧・合計の表示用） */
    val unitStatuses: StateFlow<List<UnitStatus>> = _units
        .flatMapLatest { list ->
            if (list.isEmpty()) flowOf(emptyList())
            else combine(list.map { u ->
                combine(u.connection.state, u.connection.live) { s, l -> UnitStatus(u.address, u.name, s, l) }
            }) { it.toList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 接続済みでデータが届いている台だけをまとめた値 */
    val pack: StateFlow<PackSummary?> = combine(unitStatuses, _mode) { list, m ->
        PackSummary.of(m, list.filter { it.state == ConnectionState.CONNECTED }.mapNotNull { it.live })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val connectionState: StateFlow<ConnectionState?> = _connection
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val live: StateFlow<LiveData?> = _connection
        .flatMapLatest { it?.live ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val lastUpdate: StateFlow<Long?> = _connection
        .flatMapLatest { it?.lastUpdate ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * スキャン画面で機器をタップしたとき。
     * - 単体: 今の接続を切って、その機器の詳細画面を開く
     * - 並列・直列: 一覧に追加する（最大 6 台）
     */
    fun connect(device: FoundDevice) {
        stopScan()
        val name = device.name ?: device.address
        if (_mode.value == PackMode.SINGLE) {
            disconnectAll()
            val unit = newUnit(device.address, name)
            _units.value = listOf(unit)
            persistUnits()
            supervise(unit.address)
            select(unit.address)
            return
        }
        _adding.value = false
        if (_units.value.any { it.address == device.address }) return
        if (_units.value.size >= _mode.value.maxUnits) {
            _messages.tryEmit(UiMessage(R.string.msg_too_many_units, _mode.value.maxUnits.toString()))
            return
        }
        _units.value = _units.value + newUnit(device.address, name)
        persistUnits()
        supervise(device.address)
    }

    private fun newUnit(address: String, name: String) =
        PackUnit(address, name, BmsConnection(getApplication(), address).also { it.connect() })

    /** 一覧から 1 台を選んで詳細画面を開く */
    fun select(address: String) {
        val unit = _units.value.firstOrNull { it.address == address } ?: return
        if (_connection.value !== unit.connection) resetSettingsState()
        _connection.value = unit.connection
    }

    /** 詳細画面を閉じる。単体なら切断、並列・直列なら一覧に戻る */
    fun closeDevice() {
        if (_mode.value == PackMode.SINGLE) {
            disconnectAll()
        } else {
            _connection.value = null
            resetSettingsState()
        }
    }

    /** 開いている機器をつなぎ直す */
    fun reconnect() {
        val address = _connection.value?.address ?: return
        reconnectUnit(address)
        select(address)
    }

    /** 手動でつなぎ直す（「再接続」ボタン）。自動再接続の回数も最初から数え直す */
    fun reconnectUnit(address: String) {
        if (connectionOf(address) == null) return
        retryJobs.remove(address)?.cancel()
        setAttempt(address, null)
        replaceConnection(address)
        supervise(address)
    }

    /**
     * 接続オブジェクトを作り直す。同じ機器なので、設定画面のパスワード確認や履歴はそのまま残す。
     */
    private fun replaceConnection(address: String) {
        val old = _units.value.firstOrNull { it.address == address } ?: return
        old.connection.close()
        val fresh = newUnit(address, old.name)
        _units.value = _units.value.map { if (it.address == address) fresh else it }
        if (_connection.value === old.connection) _connection.value = fresh.connection
    }

    /** 1 台を外す。「前回の機器」からも外す */
    fun removeUnit(address: String) {
        val unit = _units.value.firstOrNull { it.address == address } ?: return
        retryJobs.remove(address)?.cancel()
        setAttempt(address, null)
        unit.connection.close()
        if (_connection.value === unit.connection) {
            _connection.value = null
            resetSettingsState()
        }
        _units.value = _units.value - unit
        if (_units.value.isEmpty()) forgetSaved() else persistUnits()
    }

    /** すべて切断する。「前回の機器」は残す（次回の起動で自動接続できるように） */
    fun disconnectAll() {
        retryJobs.values.forEach { it.cancel() }
        retryJobs.clear()
        _reconnecting.value = emptyMap()
        _units.value.forEach { it.connection.close() }
        _units.value = emptyList()
        _connection.value = null
        _adding.value = false
        resetSettingsState()
    }

    fun refreshNow() {
        val c = _connection.value ?: return
        viewModelScope.launch { runCatching { c.refreshLive() } }
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
        historyJob?.cancel()
        _history.value = HistoryState()
        devicePassword = null
        _access.value = SettingsAccess.CheckingPassword
        _settings.value = null
        _actualCapacityMah.value = null
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
                // 実容量は別アドレス（0x81）。対応していない機種もあるので失敗しても続ける
                _actualCapacityMah.value = runCatching {
                    ActualCapacity.decode(c.readRegisters(ActualCapacity.REGISTER, ActualCapacity.COUNT, Modbus.SLAVE_AUX))
                }.onFailure { Log.w(TAG, "actual capacity not available", it) }.getOrNull()
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

    /** 実容量（mAh）。読めなかったときは null */
    private val _actualCapacityMah = MutableStateFlow<Int?>(null)
    val actualCapacityMah: StateFlow<Int?> = _actualCapacityMah.asStateFlow()

    fun writeActualCapacity(mAh: Int) = runWrite { c ->
        c.writeRegisters(ActualCapacity.REGISTER, ActualCapacity.encode(mAh), Modbus.SLAVE_AUX)
    }

    fun applyBatteryType(type: BatteryType) {
        val s = _settings.value ?: return
        val cells = live.value?.cellCount ?: return
        val values = buildBatteryPreset(type, cells, s) ?: return
        runWrite { c -> c.writeRegisters(137, values) }
    }

    /**
     * パスワードを変更する。入力の確認結果をすぐ返し、OK のときだけ書き込む。
     * 書き込み後に読み直して、BMS に保存されたことを確かめる。
     */
    fun changePassword(old: String, new: String, repeat: String): PasswordChangeCheck {
        val check = BmsPassword.validateChange(devicePassword, old, new, repeat)
        if (check != PasswordChangeCheck.OK) return check
        val c = _connection.value ?: return check
        if (_access.value !is SettingsAccess.Unlocked || _busy.value) return check
        viewModelScope.launch {
            _busy.value = true
            try {
                c.writeRegisters(BmsPassword.START, BmsPassword.encode(new))
                val stored = BmsPassword.decode(c.readRegisters(BmsPassword.START, BmsPassword.COUNT))
                if (stored == new) {
                    devicePassword = new
                    _messages.tryEmit(UiMessage(R.string.msg_pwd_changed))
                } else {
                    // 応答はあったが読み直すと違う。BMS に入っている方を正とする
                    devicePassword = stored
                    _messages.tryEmit(UiMessage(R.string.msg_pwd_verify_failed))
                }
            } catch (e: Exception) {
                Log.w(TAG, "password change failed", e)
                _messages.tryEmit(UiMessage(R.string.msg_pwd_change_failed))
            } finally {
                _busy.value = false
            }
        }
        return check
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

    // ---- 故障履歴 ----
    private val _history = MutableStateFlow(HistoryState())
    val history: StateFlow<HistoryState> = _history.asStateFlow()
    private var historyJob: Job? = null

    /** 履歴タブを開いたときに呼ぶ。まだ何も読んでいなければ最初の分を読む */
    fun openHistory() {
        val h = _history.value
        if (h.records.isEmpty() && !h.loading && !h.complete) loadMoreHistory()
    }

    fun reloadHistory() {
        historyJob?.cancel()
        _history.value = HistoryState()
        loadMoreHistory()
    }

    /** 元アプリと同じく、1 回に 4 ページ（28 件）ずつ読む */
    fun loadMoreHistory(pages: Int = 4) {
        val c = _connection.value ?: return
        if (_history.value.loading || _history.value.complete) return
        historyJob = viewModelScope.launch {
            _history.value = _history.value.copy(loading = true, error = false)
            try {
                repeat(pages) {
                    val h = _history.value
                    if (h.complete) return@repeat
                    val page = HistoryProtocol.parsePage(
                        c.readRegisters(HistoryProtocol.pageAddress(h.nextPage), HistoryProtocol.PAGE_REGISTERS),
                    )
                    val seen = h.records.map { it.number }.toSet()
                    val fresh = page.records.filter { it.number !in seen }
                    val records = h.records + fresh
                    val total = h.total ?: page.records.firstOrNull()?.totalCount
                    val complete = page.reachedEnd ||
                        fresh.size < page.records.size ||          // 同じ番号が戻ってきた＝一周した
                        page.records.isEmpty() ||
                        (total != null && records.size >= total) ||
                        h.nextPage + 1 >= MAX_HISTORY_PAGES
                    _history.value = h.copy(records = records, total = total, nextPage = h.nextPage + 1, complete = complete)
                }
            } catch (e: Exception) {
                Log.w(TAG, "history read failed", e)
                _history.value = _history.value.copy(error = true)
            } finally {
                _history.value = _history.value.copy(loading = false)
            }
        }
    }

    override fun onCleared() {
        _units.value.forEach { it.connection.close() }
    }

    companion object {
        const val TAG = "BmsViewModel"
        const val PREF_MODE = "pack_mode"
        const val PREF_SAVED = "saved_devices"
        const val PREF_AUTO_CONNECT = "auto_connect"
        const val PREF_AUTO_RECONNECT = "auto_reconnect"

        /** つなぎ直す最大回数と、回ごとの待ち時間（約 4 分間試す） */
        const val MAX_RECONNECT_ATTEMPTS = 10
        val RECONNECT_DELAYS_MS = longArrayOf(3_000, 5_000, 10_000, 15_000, 30_000)

        /** 念のための上限（127 レジスタ × 7 件 × 100 ページ = 700 件） */
        const val MAX_HISTORY_PAGES = 100
    }
}

/** 接続中の 1 台 */
data class PackUnit(val address: String, val name: String, val connection: BmsConnection)

/** 一覧表示用の 1 台の状態 */
data class UnitStatus(val address: String, val name: String, val state: ConnectionState, val live: LiveData?)

data class HistoryState(
    val records: List<HistoryRecord> = emptyList(),
    val total: Int? = null,
    val nextPage: Int = 0,
    val complete: Boolean = false,
    val loading: Boolean = false,
    val error: Boolean = false,
)
