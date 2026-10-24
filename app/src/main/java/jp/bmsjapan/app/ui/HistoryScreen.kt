package jp.bmsjapan.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import jp.bmsjapan.app.R
import jp.bmsjapan.app.protocol.HistoryEvent
import jp.bmsjapan.app.protocol.HistoryRecord
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")

@Composable
fun HistoryTab(vm: BmsViewModel) {
    val h by vm.history.collectAsState()
    var selected by remember { mutableStateOf<HistoryRecord?>(null) }
    LaunchedEffect(Unit) { vm.openHistory() }

    when {
        h.records.isEmpty() && h.loading -> Centered {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.history_loading))
        }
        h.records.isEmpty() && h.error -> Centered {
            Text(stringResource(R.string.history_read_failed))
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::reloadHistory) { Text(stringResource(R.string.action_retry)) }
        }
        h.records.isEmpty() && h.complete -> Centered { Text(stringResource(R.string.history_empty)) }
        else -> LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.history_count, h.records.size, h.total ?: h.records.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(h.records, key = { it.number }) { r -> HistoryRow(r) { selected = r } }
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        h.loading -> CircularProgressIndicator()
                        h.complete -> Text(stringResource(R.string.history_all_loaded), style = MaterialTheme.typography.bodySmall)
                        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (h.error) {
                                Text(stringResource(R.string.history_read_failed), color = MaterialTheme.colorScheme.error)
                                Spacer(Modifier.height(4.dp))
                            }
                            OutlinedButton(onClick = { vm.loadMoreHistory() }) { Text(stringResource(R.string.history_load_more)) }
                        }
                    }
                }
            }
        }
    }

    selected?.let { HistoryDetailDialog(it) { selected = null } }
}

@Composable
private fun eventText(e: HistoryEvent): String =
    if (e.arg == null) stringResource(e.label) else stringResource(e.label, e.arg)

@Composable
private fun HistoryRow(r: HistoryRecord, onClick: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OccurredBadge(r.occurred)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(eventText(r.event), fontWeight = FontWeight.Medium)
                Text(
                    r.time?.format(timeFormat) ?: stringResource(R.string.history_no_time),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(stringResource(R.string.history_number, r.number), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun OccurredBadge(occurred: Boolean) {
    val bg = if (occurred) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer
    val fg = if (occurred) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
    Text(
        stringResource(if (occurred) R.string.history_occurred else R.string.history_cleared),
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun HistoryDetailDialog(r: HistoryRecord, onDismiss: () -> Unit) {
    val onOff = { on: Boolean -> if (on) "ON" else "OFF" }
    val rows = listOf(
        stringResource(R.string.history_detail_time) to (r.time?.format(timeFormat) ?: stringResource(R.string.history_no_time)),
        stringResource(R.string.history_detail_event) to eventText(r.event),
        stringResource(R.string.history_detail_action) to stringResource(if (r.occurred) R.string.history_occurred else R.string.history_cleared),
        stringResource(R.string.label_total_voltage) to "%.1f V".format(r.totalVoltage),
        stringResource(R.string.label_current) to "%.1f A".format(r.current),
        "SOC" to "%.1f %%".format(r.soc),
        stringResource(R.string.label_charge_mos) to onOff(r.chargeMos),
        stringResource(R.string.label_discharge_mos) to onOff(r.dischargeMos),
        stringResource(R.string.history_detail_cell_max) to stringResource(R.string.with_position, "%.3f V".format(r.maxCellVoltage), r.maxCellIndex),
        stringResource(R.string.history_detail_cell_min) to stringResource(R.string.with_position, "%.3f V".format(r.minCellVoltage), r.minCellIndex),
        stringResource(R.string.history_detail_temp_max) to stringResource(R.string.with_position, "${r.maxTemperature} ℃", r.maxTemperatureIndex),
        stringResource(R.string.history_detail_temp_min) to stringResource(R.string.with_position, "${r.minTemperature} ℃", r.minTemperatureIndex),
        stringResource(R.string.history_detail_fault_code) to "0x%02X".format(r.faultCode),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_number, r.number)) },
        text = {
            Column {
                rows.forEachIndexed { i, (label, value) ->
                    if (i > 0) HorizontalDivider()
                    Row(Modifier.padding(vertical = 6.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.history_detail_current_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}
