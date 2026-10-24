package jp.bmsjapan.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import jp.bmsjapan.app.R
import jp.bmsjapan.app.ble.FoundDevice

/** Android のバージョンごとに必要な BLE 権限 */
fun requiredBlePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(vm: BmsViewModel) {
    val context = LocalContext.current
    val devices by vm.devices.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val scanError by vm.scanError.collectAsState()
    var showAll by remember { mutableStateOf(false) }

    fun hasPermissions() = requiredBlePermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    var granted by remember { mutableStateOf(hasPermissions()) }
    var btOn by remember { mutableStateOf(vm.isBluetoothEnabled) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions()
        if (granted && vm.isBluetoothEnabled) vm.startScan()
    }
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        btOn = vm.isBluetoothEnabled
        if (btOn && granted) vm.startScan()
    }

    LaunchedEffect(Unit) {
        if (granted && btOn) vm.startScan()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.title_scan)) }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            when {
                !granted -> Notice(
                    message = stringResource(R.string.permission_needed),
                    action = stringResource(R.string.action_allow),
                    onAction = { permLauncher.launch(requiredBlePermissions()) },
                )
                !btOn -> Notice(
                    message = stringResource(R.string.bluetooth_off),
                    action = stringResource(R.string.action_turn_on_bluetooth),
                    onAction = { btLauncher.launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                )
                else -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (scanning) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.scanning), Modifier.weight(1f))
                            OutlinedButton(onClick = vm::stopScan) { Text(stringResource(R.string.action_stop)) }
                        } else {
                            Text(stringResource(R.string.scan_hint), Modifier.weight(1f))
                            Button(onClick = vm::startScan) { Text(stringResource(R.string.action_scan)) }
                        }
                    }
                    AssistChip(
                        onClick = { showAll = !showAll },
                        label = { Text(stringResource(if (showAll) R.string.filter_bms_only else R.string.filter_show_all)) },
                    )
                    scanError?.let {
                        Text(stringResource(R.string.scan_failed, it), color = MaterialTheme.colorScheme.error)
                    }
                    val shown = if (showAll) devices else devices.filter { it.looksLikeBms }
                    if (shown.isEmpty() && !scanning) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_devices))
                        }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(shown, key = { it.address }) { d -> DeviceRow(d) { vm.connect(d) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: FoundDevice, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (device.looksLikeBms) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name ?: stringResource(R.string.unknown_device), fontWeight = FontWeight.Bold)
                Text(device.address, style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.rssi_format, device.rssi), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Notice(message: String, action: String, onAction: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message)
        Spacer(Modifier.size(16.dp))
        Button(onClick = onAction) { Text(action) }
    }
}
