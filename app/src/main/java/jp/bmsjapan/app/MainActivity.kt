package jp.bmsjapan.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.bmsjapan.app.ui.BmsTheme
import jp.bmsjapan.app.ui.BmsViewModel
import jp.bmsjapan.app.ui.DeviceScreen
import jp.bmsjapan.app.ui.ScanScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BmsTheme {
                val vm: BmsViewModel = viewModel()
                val connection by vm.connection.collectAsState()
                // 接続中ならライブデータ、そうでなければスキャン画面
                if (connection == null) ScanScreen(vm) else DeviceScreen(vm)
            }
        }
    }
}
