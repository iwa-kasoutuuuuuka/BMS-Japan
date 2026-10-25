package jp.bmsjapan.app.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/** 運転データを読む間隔と、読むかどうか */
data class PollSettings(val intervalMs: Long, val active: Boolean)

/**
 * 全台で共通の読み出しの決まり。
 * - アプリが裏に回ったら読み出しを止める（電池と BLE の通信量を減らす）。戻ったらすぐ読み直す
 * - 間隔は利用者が選べる（2 秒・5 秒・10 秒）
 */
object PollingPolicy {
    const val DEFAULT_INTERVAL_MS = 5_000L
    val INTERVALS_MS = longArrayOf(2_000L, 5_000L, 10_000L)

    private val _settings = MutableStateFlow(PollSettings(DEFAULT_INTERVAL_MS, active = true))
    val settings: StateFlow<PollSettings> = _settings.asStateFlow()

    fun setInterval(ms: Long) {
        _settings.value = _settings.value.copy(intervalMs = ms.coerceIn(1_000L, 60_000L))
    }

    fun setForeground(foreground: Boolean) {
        _settings.value = _settings.value.copy(active = foreground)
    }

    /**
     * 同時に接続を始めるのは 1 台まで。
     * Android は複数台へ同時に connectGatt すると失敗（status 133）しやすいため、順番につなぐ。
     */
    internal val connectGate = Mutex()
}
