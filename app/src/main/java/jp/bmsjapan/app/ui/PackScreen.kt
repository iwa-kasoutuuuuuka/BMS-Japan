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
                Text(stringResource(R.string.pack_units), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            items(statuses, key = { it.address }) { s ->
                UnitCard(
                    status = s,
                    onOpen = { vm.select(s.address) },
                    onReconnect = { vm.reconnectUnit(s.address) },
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
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            if (p == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  " + stringResource(R.string.waiting_data))
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(p.soc), fontSize = 44.sp, fontWeight = FontWeight.Bold)
                Text(" %", fontSize = 22.sp, modifier = Modifier.padding(bottom = 8.dp))
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(
                        when (p.state) {
                            PackState.CHARGING -> R.string.state_charging
                            PackState.DISCHARGING -> R.string.state_discharging
                            PackState.IDLE -> R.string.state_idle
                            PackState.MIXED -> R.string.state_mixed
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
            LinearProgressIndicator(
                progress = { (p.soc / 100.0).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
            )
            Text(
                stringResource(if (mode == PackMode.SERIES) R.string.pack_soc_note_series else R.string.pack_soc_note_parallel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (p.unitCount < unitCount) {
                Text(
                    stringResource(R.string.pack_partial, p.unitCount, unitCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(Modifier.height(12.dp))
            Grid(
                listOf(
                    stringResource(R.string.label_total_voltage) to "%.1f V".format(p.voltage),
                    stringResource(R.string.label_current) to "%.1f A".format(p.current),
                    stringResource(R.string.label_power) to "%.3f kW".format(p.powerKw),
                    stringResource(R.string.label_remaining) to "%.1f Ah".format(p.remainingAh),
                    stringResource(R.string.pack_cell_range) to "%.3f〜%.3f V".format(p.minCellVoltage, p.maxCellVoltage),
                    stringResource(R.string.label_cell_delta) to "%.0f mV".format(p.cellVoltageDelta * 1000),
                    stringResource(R.string.pack_temp_range) to "${p.minTemperature}〜${p.maxTemperature} ℃",
                    stringResource(R.string.section_alarms) to
                        if (p.alarmCount == 0) stringResource(R.string.no_alarms) else stringResource(R.string.pack_alarm_units, p.alarmCount),
                ),
            )
        }
    }
}

@Composable
private fun Grid(items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun UnitCard(status: UnitStatus, onOpen: () -> Unit, onReconnect: () -> Unit, onRemove: () -> Unit) {
    val live = status.live
    val alarm = live?.alarms?.isNotEmpty() == true
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = status.state == ConnectionState.CONNECTED && live != null, onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = if (alarm) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(status.name, fontWeight = FontWeight.Bold, maxLines = 1)
                when {
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
                        Text(
                            stringResource(
                                R.string.unit_line,
                                "%.1f".format(live.soc),
                                "%.1f".format(live.totalVoltage),
                                "%.1f".format(abs(live.current)),
                                stringResource(
                                    when (live.state) {
                                        ChargeState.CHARGING -> R.string.state_charging
                                        ChargeState.DISCHARGING -> R.string.state_discharging
                                        ChargeState.IDLE -> R.string.state_idle
                                        ChargeState.UNKNOWN -> R.string.state_unknown
                                    },
                                ),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
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
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_remove_unit))
            }
        }
    }
}
