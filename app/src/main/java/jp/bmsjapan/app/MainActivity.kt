package jp.bmsjapan.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import jp.bmsjapan.app.ble.PollingPolicy
import jp.bmsjapan.app.ui.BmsTheme
import jp.bmsjapan.app.ui.BmsViewModel
import jp.bmsjapan.app.ui.DeviceScreen
import jp.bmsjapan.app.ui.PackScreen
import jp.bmsjapan.app.ui.ScanScreen
import jp.bmsjapan.app.ui.isDarkTheme

class MainActivity : ComponentActivity() {
    // 画面が見えている間だけ運転データを読む（裏に回ったら止め、戻ったらすぐ読み直す）
    override fun onStart() {
        super.onStart()
        PollingPolicy.setForeground(true)
    }

    override fun onStop() {
        PollingPolicy.setForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val vm: BmsViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsState()
            val dark = isDarkTheme(themeMode)

            // 端末の設定と違うモードを選んだときも、ステータスバーの文字色を背景に合わせる
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }

            BmsTheme(themeMode) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
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
}
