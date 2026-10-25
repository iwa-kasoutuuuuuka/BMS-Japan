package jp.bmsjapan.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.bmsjapan.app.R
import jp.bmsjapan.app.ble.ConnectionState
import jp.bmsjapan.app.protocol.ChargeState
import jp.bmsjapan.app.protocol.LiveData
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(vm: BmsViewModel) {
    val state by vm.connectionState.collectAsState()
    val live by vm.live.collectAsState()
    val lastUpdate by vm.lastUpdate.collectAsState()
    val name by vm.selectedName.collectAsState()
    val selectedConnection by vm.connection.collectAsState()
    val address = selectedConnection?.address
    val reconnecting by vm.reconnecting.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.messages.collect { m -> snackbar.showSnackbar(context.getString(m.res, m.arg)) }
    }

    BackHandler { if (tab != 0) tab = 0 else vm.closeDevice() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state == ConnectionState.CONNECTED && live != null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(Icons.Filled.Info, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_live)) },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_history)) },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_settings)) },
                    )
                }
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(name ?: "", maxLines = 1)
                        Text(statusText(state), style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = vm::closeDevice) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_disconnect))
                    }
                },
                actions = {
                    if (state == ConnectionState.CONNECTED) {
                        IconButton(onClick = {
                            when (tab) {
                                0 -> vm.refreshNow()
                                1 -> vm.reloadHistory()
                                else -> vm.loadSettings()
                            }
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            val data = live
            val attempt = reconnecting[address]
            when {
                // 自動でつなぎ直している最中（切断・失敗・接続中のどれでも）
                attempt != null && (state != ConnectionState.CONNECTED || data == null) -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.reconnecting_title))
                    Text(
                        stringResource(R.string.reconnecting_detail, attempt, BmsViewModel.MAX_RECONNECT_ATTEMPTS),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedButton(onClick = { address?.let(vm::stopReconnecting) }) { Text(stringResource(R.string.action_stop_reconnect)) }
                }
                state == ConnectionState.FAILED || state == ConnectionState.DISCONNECTED -> Centered {
                    Text(stringResource(if (state == ConnectionState.FAILED) R.string.connect_failed else R.string.disconnected))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = vm::reconnect) { Text(stringResource(R.string.action_reconnect)) }
                }
                data == null -> Centered {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(if (state == ConnectionState.CONNECTED) R.string.waiting_data else R.string.connecting))
                }
                tab == 1 -> HistoryTab(vm)
                tab == 2 -> SettingsTab(vm, data)
                else -> LiveContent(data, lastUpdate)
            }
        }
    }
}

@Composable
private fun statusText(state: ConnectionState?): String = stringResource(
    when (state) {
        ConnectionState.CONNECTED -> R.string.status_connected
        ConnectionState.CONNECTING, null -> R.string.status_connecting
        ConnectionState.DISCONNECTED -> R.string.status_disconnected
        ConnectionState.FAILED -> R.string.status_failed
    },
)

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LiveContent(d: LiveData, lastUpdate: Long?) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SocCard(d)

        Section(stringResource(R.string.section_pack)) {
            MetricGrid(
                listOf(
                    stringResource(R.string.label_total_voltage) to "%.1f V".format(d.totalVoltage),
                    // 符号の向きは機種で違う可能性があるため、向きは状態（充電中/放電中）で示し、ここは大きさだけ出す
                    stringResource(R.string.label_current) to "%.1f A".format(abs(d.current)),
                    stringResource(R.string.label_power) to "%.3f kW".format(d.powerKw),
                    stringResource(R.string.label_remaining) to "%.1f Ah".format(d.remainingCapacity),
                    stringResource(R.string.label_cycles) to stringResource(R.string.cycles_format, d.cycleCount),
                    stringResource(R.string.label_cell_count) to stringResource(R.string.cells_format, d.cellCount),
                ),
            )
        }

        Section(stringResource(R.string.section_switches)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchBadge(stringResource(R.string.label_charge_mos), d.chargeMos, Modifier.weight(1f))
                SwitchBadge(stringResource(R.string.label_discharge_mos), d.dischargeMos, Modifier.weight(1f))
                SwitchBadge(stringResource(R.string.label_balancing), d.balancing, Modifier.weight(1f))
            }
        }

        Section(stringResource(R.string.section_cells)) {
            MetricGrid(
                listOf(
                    stringResource(R.string.label_cell_max) to "%.3f V".format(d.maxCellVoltage),
                    stringResource(R.string.label_cell_min) to "%.3f V".format(d.minCellVoltage),
                    stringResource(R.string.label_cell_avg) to "%.3f V".format(d.averageCellVoltage),
                    stringResource(R.string.label_cell_delta) to "%.0f mV".format(d.cellVoltageDelta * 1000),
                ),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                d.cellVoltages.forEachIndexed { i, v ->
                    CellChip(
                        index = i + 1,
                        volts = v,
                        isMax = d.cellVoltages.size > 1 && v == d.cellVoltages.max(),
                        isMin = d.cellVoltages.size > 1 && v == d.cellVoltages.min(),
                    )
                }
            }
        }

        Section(stringResource(R.string.section_temperature)) {
            MetricGrid(
                listOf(
                    stringResource(R.string.label_temp_max) to "${d.maxTemperature} ℃",
                    stringResource(R.string.label_temp_min) to "${d.minTemperature} ℃",
                ) + d.temperatures.mapIndexed { i, t ->
                    stringResource(R.string.label_temp_sensor, i + 1) to "$t ℃"
                },
            )
        }

        Section(stringResource(R.string.section_alarms)) {
            if (d.alarms.isEmpty()) {
                Text(stringResource(R.string.no_alarms), color = MaterialTheme.colorScheme.primary)
            } else {
                d.alarms.forEach { a ->
                    Text("⚠ " + stringResource(a.label), color = MaterialTheme.colorScheme.error)
                }
            }
        }

        lastUpdate?.let {
            Text(
                stringResource(R.string.last_update, SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(it))),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun SocCard(d: LiveData) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(d.soc), fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Text(" %", fontSize = 24.sp, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(
                        when (d.state) {
                            ChargeState.CHARGING -> R.string.state_charging
                            ChargeState.DISCHARGING -> R.string.state_discharging
                            ChargeState.IDLE -> R.string.state_idle
                            ChargeState.UNKNOWN -> R.string.state_unknown
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            LinearProgressIndicator(
                progress = { (d.soc / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun MetricGrid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SwitchBadge(label: String, on: Boolean, modifier: Modifier = Modifier) {
    val bg = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .background(bg, RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (on) R.string.on else R.string.off), color = fg, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CellChip(index: Int, volts: Double, isMax: Boolean, isMin: Boolean) {
    val bg = when {
        isMax -> Color(0xFFFFE0B2)
        isMin -> Color(0xFFBBDEFB)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (isMax || isMin) Color(0xFF1B1B1B) else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .width(72.dp)
            .background(bg, RoundedCornerShape(8.dp))
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("#$index", style = MaterialTheme.typography.labelSmall, color = fg)
        Text("%.3f".format(volts), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = fg)
    }
}
