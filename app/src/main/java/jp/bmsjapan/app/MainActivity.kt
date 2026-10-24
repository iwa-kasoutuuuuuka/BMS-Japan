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
import jp.bmsjapan.app.ui.PackScreen
import jp.bmsjapan.app.ui.ScanScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BmsTheme {
                val vm: BmsViewModel = viewModel()
                val units by vm.units.collectAsState()
                val adding by vm.adding.collectAsState()
                val selected by vm.connection.collectAsState()
                when {
                    // 何もつないでいない、または追加中 → スキャン画面
                    units.isEmpty() || adding -> ScanScreen(vm)
                    // 1 台を開いている → ライブ・履歴・設定
                    selected != null -> DeviceScreen(vm)
                    // 並列・直列 → まとめ画面
                    else -> PackScreen(vm)
                }
            }
        }
    }
}
