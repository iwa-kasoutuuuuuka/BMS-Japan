package jp.bmsjapan.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    /** 広告データに "JHB" が入っているか */
    val advHasJhb: Boolean = false,
) {
    /** 元アプリと同じ判定: 名前に "JHB" を含む、または広告データに "JHB" が入っている */
    val looksLikeBms: Boolean get() = advHasJhb || name?.contains(BmsUuids.NAME_HINT, ignoreCase = true) == true
}

class BmsScanner(context: Context) {
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    /** 見つかった機器を随時流す。collect をやめるとスキャンも止まる。 */
    @SuppressLint("MissingPermission") // 呼び出し前に画面側で権限を確認している
    fun scan(): Flow<FoundDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is off"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                trySend(
                    FoundDevice(
                        address = result.device.address,
                        name = result.scanRecord?.deviceName ?: result.device.name,
                        rssi = result.rssi,
                        advHasJhb = BmsUuids.advContainsJhb(result.scanRecord?.bytes),
                    ),
                )
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("scan failed: $errorCode"))
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        awaitClose { runCatching { scanner.stopScan(callback) } }
    }
}
