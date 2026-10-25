package jp.bmsjapan.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import jp.bmsjapan.app.R
import jp.bmsjapan.app.ble.ConnectionState
import jp.bmsjapan.app.protocol.ChargeState
import jp.bmsjapan.app.protocol.PackMode
import jp.bmsjapan.app.protocol.PackState
import jp.bmsjapan.app.protocol.PackSummary
import kotlin.math.abs

/** 並列・直列のまとめ画面 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackScreen(vm: BmsViewModel) {
    val mode by vm.mode.collectAsState()
    val statuses by vm.unitStatuses.collectAsState()
    val pack by vm.pack.collectAsState()
    val reconnecting by vm.reconnecting.collectAsState()
    var confirmDisconnect by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        vm.messages.collect { m -> snackbar.showSnackbar(context.getString(m.res, m.arg)) }
    }
    BackHandler { confirmDisconnect = true }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pack_title, stringResource(mode.label), statuses.size)) },
                navigationIcon = {
                    IconButton(onClick = { confirmDisconnect = true }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_disconnect_all))
                    }
                },
                actions = {
                    if (statuses.size < mode.maxUnits) {
                        IconButton(onClick = vm::startAdding) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add_unit))
                        }
                    }
                    ThemeMenuButton(vm)
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { PackSummaryCard(pack, mode, statuses.size) }
            item {
                Box(Modifier.padding(top = 8.dp, start = 4.dp)) { SectionTitle(stringResource(R.string.pack_units)) }
            }
            items(statuses, key = { it.address }) { s ->
                UnitCard(
                    status = s,
                    reconnectAttempt = reconnecting[s.address],
                    onOpen = { vm.select(s.address) },
                    onReconnect = { vm.reconnectUnit(s.address) },
                    onStopReconnect = { vm.stopReconnecting(s.address) },
                    onRemove = { vm.removeUnit(s.address) },
                )
            }
            if (statuses.size < mode.maxUnits) {
                item {
                    OutlinedButton(onClick = vm::startAdding, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(stringResource(R.string.action_add_unit_count, statuses.size, mode.maxUnits))
                    }
                }
            }
        }
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.action_disconnect_all)) },
            text = { Text(stringResource(R.string.confirm_disconnect_all)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    vm.disconnectAll()
                }) { Text(stringResource(R.string.action_disconnect_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PackSummaryCard(p: PackSummary?, mode: PackMode, unitCount: Int) {
    val colors = LocalBmsColors.current
    BmsCard {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (p == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  " + stringResource(R.string.waiting_data))
                }
                return@Column
            }
            SocGauge(
                soc = p.soc,
                subtitle = stringResource(R.string.gauge_remaining, "%.1f".format(p.remainingAh)),
            )
            Spacer(Modifier.height(4.dp))
            StateChip(packStateLabel(p.state), packStateColor(p.state))
            Text(
                stringResource(if (mode == PackMode.SERIES) R.string.pack_soc_note_series else R.string.pack_soc_note_parallel),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (p.unitCount < unitCount) {
                Text(
                    stringResource(R.string.pack_partial, p.unitCount, unitCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(16.dp))
            MetricTiles(
                listOf(
                    Metric(stringResource(R.string.label_total_voltage), "%.1f".format(p.voltage), "V", colors.gaugeStart),
                    Metric(stringResource(R.string.label_current), "%.1f".format(p.current), "A", packStateColor(p.state)),
                    Metric(stringResource(R.string.label_power), "%.3f".format(p.powerKw), "kW", colors.mixed),
                    Metric(stringResource(R.string.label_cell_delta), "%.0f".format(p.cellVoltageDelta * 1000), "mV",
                        if (p.cellVoltageDelta >= 0.05) colors.discharge else colors.good),
                    Metric(stringResource(R.string.pack_cell_range), "%.3f〜%.3f".format(p.minCellVoltage, p.maxCellVoltage), "V"),
                    Metric(stringResource(R.string.pack_temp_range), "${p.minTemperature}〜${p.maxTemperature}", "℃"),
                ),
            )
            Spacer(Modifier.height(12.dp))
            if (p.alarmCount == 0) {
                StateChip(stringResource(R.string.no_alarms), colors.good)
            } else {
                StateChip(stringResource(R.string.pack_alarm_units, p.alarmCount), MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun packStateLabel(s: PackState): String = stringResource(
    when (s) {
        PackState.CHARGING -> R.string.state_charging
        PackState.DISCHARGING -> R.string.state_discharging
        PackState.IDLE -> R.string.state_idle
        PackState.MIXED -> R.string.state_mixed
    },
)

@Composable
private fun packStateColor(s: PackState): Color {
    val c = LocalBmsColors.current
    return when (s) {
        PackState.CHARGING -> c.charge
        PackState.DISCHARGING -> c.discharge
        PackState.IDLE -> c.idle
        PackState.MIXED -> c.mixed
    }
}

@Composable
private fun UnitCard(
    status: UnitStatus,
    reconnectAttempt: Int?,
    onOpen: () -> Unit,
    onReconnect: () -> Unit,
    onStopReconnect: () -> Unit,
    onRemove: () -> Unit,
) {
    val live = status.live
    val alarm = live?.alarms?.isNotEmpty() == true
    val colors = LocalBmsColors.current
    BmsCard(
        Modifier.clickable(enabled = status.state == ConnectionState.CONNECTED && live != null, onClick = onOpen),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(status.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                    if (live != null) {
                        Spacer(Modifier.width(8.dp))
                        StateChip(stateLabel(live.state), stateColor(live.state))
                    }
                }
                Spacer(Modifier.height(6.dp))
                when {
                    reconnectAttempt != null && (status.state != ConnectionState.CONNECTED || live == null) -> {
                        Text(
                            stringResource(R.string.reconnecting_short, reconnectAttempt, BmsViewModel.MAX_RECONNECT_ATTEMPTS),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.discharge,
                        )
                        TextButton(onClick = onStopReconnect) { Text(stringResource(R.string.action_stop_reconnect)) }
                    }
                    status.state == ConnectionState.FAILED || status.state == ConnectionState.DISCONNECTED -> {
                        Text(
                            stringResource(if (status.state == ConnectionState.FAILED) R.string.status_failed else R.string.status_disconnected),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = onReconnect) { Text(stringResource(R.string.action_reconnect)) }
                    }
                    live == null -> Text(stringResource(R.string.status_connecting), style = MaterialTheme.typography.bodySmall)
                    else -> {
                        // SOC の細いバー
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(colors.gaugeTrack),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth((live.soc / 100.0).toFloat().coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Brush.horizontalGradient(listOf(colors.gaugeStart, colors.gaugeEnd))),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            stringResource(
                                R.string.unit_line_short,
                                "%.1f".format(live.soc),
                                "%.1f".format(live.totalVoltage),
                                "%.1f".format(abs(live.current)),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (alarm) {
                            Text(
                                stringResource(R.string.unit_alarms, live.alarms.size),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_remove_unit), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
