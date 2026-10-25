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
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Warning
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
                    ThemeMenuButton(vm)
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
    val colors = LocalBmsColors.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---- SOC のリングと状態 ----
        BmsCard {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SocGauge(
                    soc = d.soc,
                    subtitle = stringResource(R.string.gauge_remaining, "%.1f".format(d.remainingCapacity)),
                )
                Spacer(Modifier.height(4.dp))
                StateChip(stateLabel(d.state), stateColor(d.state))
                Spacer(Modifier.height(16.dp))
                MetricTiles(
                    listOf(
                        Metric(stringResource(R.string.label_total_voltage), "%.1f".format(d.totalVoltage), "V", colors.gaugeStart),
                        // 符号の向きは機種で違う可能性があるため、向きは状態（充電中/放電中）で示し、ここは大きさだけ出す
                        Metric(stringResource(R.string.label_current), "%.1f".format(abs(d.current)), "A", stateColor(d.state)),
                        Metric(stringResource(R.string.label_power), "%.3f".format(d.powerKw), "kW", colors.mixed),
                        Metric(stringResource(R.string.label_cycles), d.cycleCount.toString(), stringResource(R.string.unit_times)),
                    ),
                )
            }
        }

        Section(stringResource(R.string.section_switches)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SwitchPill(stringResource(R.string.label_charge_mos), d.chargeMos, Modifier.weight(1f))
                SwitchPill(stringResource(R.string.label_discharge_mos), d.dischargeMos, Modifier.weight(1f))
                SwitchPill(stringResource(R.string.label_balancing), d.balancing, Modifier.weight(1f))
            }
        }

        Section(stringResource(R.string.section_cells_count, d.cellCount)) {
            MetricTiles(
                listOf(
                    Metric(stringResource(R.string.label_cell_max), "%.3f".format(d.maxCellVoltage), "V", colors.cellMax),
                    Metric(stringResource(R.string.label_cell_min), "%.3f".format(d.minCellVoltage), "V", colors.cellMin),
                    Metric(stringResource(R.string.label_cell_avg), "%.3f".format(d.averageCellVoltage), "V"),
                    Metric(
                        stringResource(R.string.label_cell_delta),
                        "%.0f".format(d.cellVoltageDelta * 1000),
                        "mV",
                        if (d.cellVoltageDelta >= 0.05) colors.discharge else colors.good,
                    ),
                ),
            )
            Spacer(Modifier.height(12.dp))
            if (d.cellVoltages.isNotEmpty()) {
                val maxV = d.cellVoltages.max()
                val minV = d.cellVoltages.min()
                // 1 行 4 個で幅いっぱいに並べる（端数の行は空きで埋める）
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.cellVoltages.withIndex().chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (i, v) ->
                                CellTile(
                                    index = i + 1,
                                    volts = v,
                                    minV = minV,
                                    maxV = maxV,
                                    isMax = d.cellVoltages.size > 1 && v == maxV,
                                    isMin = d.cellVoltages.size > 1 && v == minV,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        Section(stringResource(R.string.section_temperature)) {
            MetricTiles(
                listOf(
                    Metric(stringResource(R.string.label_temp_max), d.maxTemperature.toString(), "℃", colors.cellMax),
                    Metric(stringResource(R.string.label_temp_min), d.minTemperature.toString(), "℃", colors.cellMin),
                ) + d.temperatures.mapIndexed { i, t ->
                    Metric(stringResource(R.string.label_temp_sensor, i + 1), t.toString(), "℃")
                },
            )
        }

        Section(stringResource(R.string.section_alarms)) {
            if (d.alarms.isEmpty()) {
                StateChip(stringResource(R.string.no_alarms), colors.good)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    d.alarms.forEach { a ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(a.label), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }

        lastUpdate?.let {
            Text(
                stringResource(R.string.last_update, SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(it))),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
internal fun stateLabel(state: ChargeState): String = stringResource(
    when (state) {
        ChargeState.CHARGING -> R.string.state_charging
        ChargeState.DISCHARGING -> R.string.state_discharging
        ChargeState.IDLE -> R.string.state_idle
        ChargeState.UNKNOWN -> R.string.state_unknown
    },
)

@Composable
internal fun stateColor(state: ChargeState): Color {
    val c = LocalBmsColors.current
    return when (state) {
        ChargeState.CHARGING -> c.charge
        ChargeState.DISCHARGING -> c.discharge
        else -> c.idle
    }
}
