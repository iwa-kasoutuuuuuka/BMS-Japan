package jp.bmsjapan.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import jp.bmsjapan.app.R
import jp.bmsjapan.app.protocol.BatteryType
import jp.bmsjapan.app.protocol.BmsSettings
import jp.bmsjapan.app.protocol.LiveData
import jp.bmsjapan.app.protocol.Setting
import jp.bmsjapan.app.protocol.SettingGroup
import jp.bmsjapan.app.protocol.SwitchSetting
import jp.bmsjapan.app.protocol.ValidationResult
import jp.bmsjapan.app.protocol.validate

@Composable
fun SettingsTab(vm: BmsViewModel, live: LiveData) {
    val access by vm.access.collectAsState()
    LaunchedEffect(Unit) { vm.openSettings() }

    when (val a = access) {
        SettingsAccess.CheckingPassword -> Centered {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.checking_password))
        }
        SettingsAccess.PasswordReadFailed -> Centered {
            Text(stringResource(R.string.password_read_failed))
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::openSettings) { Text(stringResource(R.string.action_retry)) }
        }
        is SettingsAccess.Locked -> PasswordGate(a.wrongAttempt, vm::submitPassword)
        SettingsAccess.Unlocked -> SettingsList(vm, live)
    }
}

@Composable
private fun PasswordGate(wrong: Boolean, onSubmit: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Lock, contentDescription = null)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.password_prompt), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.password_hint), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { v -> input = v.filter { it.isLetterOrDigit() }.take(6) },
            label = { Text(stringResource(R.string.password_label)) },
            singleLine = true,
            isError = wrong,
            supportingText = { if (wrong) Text(stringResource(R.string.password_wrong)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = { onSubmit(input) }, enabled = input.isNotEmpty()) {
            Text(stringResource(R.string.action_confirm))
        }
    }
}

@Composable
private fun SettingsList(vm: BmsViewModel, live: LiveData) {
    val settings by vm.settings.collectAsState()
    val busy by vm.busy.collectAsState()
    var editing by remember { mutableStateOf<Setting?>(null) }
    var confirmSwitch by remember { mutableStateOf<Pair<SwitchSetting, Boolean>?>(null) }
    var choosingBattery by remember { mutableStateOf(false) }

    val s = settings
    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (busy) Text(stringResource(R.string.loading_settings))
                else Button(onClick = vm::loadSettings) { Text(stringResource(R.string.action_retry)) }
            }
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.settings_caution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section(stringResource(R.string.group_switches)) {
                SwitchSetting.entries.forEach { sw ->
                    val on = s.switch(sw)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(sw.label), Modifier.weight(1f))
                        if (on == null) {
                            Text("--")
                        } else {
                            Switch(checked = on, enabled = !busy, onCheckedChange = { confirmSwitch = sw to it })
                        }
                    }
                }
            }

            Section(stringResource(R.string.group_battery_type)) {
                SettingRow(
                    label = stringResource(R.string.label_battery_type),
                    value = s.batteryType?.let { stringResource(it.label) } ?: "--",
                    caption = null,
                    enabled = !busy,
                    onClick = { choosingBattery = true },
                )
            }

            SettingGroup.entries.forEach { group ->
                val items = Setting.entries.filter { it.group == group }
                Section(stringResource(group.title)) {
                    items.forEachIndexed { i, setting ->
                        if (i > 0) HorizontalDivider()
                        val value = s[setting]
                        val release = s.release(setting)
                        SettingRow(
                            label = stringResource(setting.label),
                            value = value?.let { "${setting.format(it)} ${setting.unit}".trim() } ?: "--",
                            caption = release?.let { stringResource(R.string.release_value, "${setting.format(it)} ${setting.unit}".trim()) },
                            enabled = setting.editable && value != null && !busy,
                            onClick = { editing = setting },
                        )
                    }
                }
            }
        }
    }

    editing?.let { setting ->
        EditSettingDialog(
            setting = setting,
            settings = s ?: return@let,
            onDismiss = { editing = null },
            onConfirm = { raw ->
                editing = null
                vm.writeSetting(setting, raw)
            },
        )
    }

    confirmSwitch?.let { (sw, on) ->
        AlertDialog(
            onDismissRequest = { confirmSwitch = null },
            title = { Text(stringResource(sw.label)) },
            text = { Text(stringResource(if (on) sw.confirmOn else sw.confirmOff)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSwitch = null
                    vm.setSwitch(sw, on)
                }) { Text(stringResource(if (on) R.string.action_turn_on else R.string.action_turn_off)) }
            },
            dismissButton = { TextButton(onClick = { confirmSwitch = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (choosingBattery && s != null) {
        BatteryTypeDialog(
            current = s.batteryType,
            cellCount = live.cellCount,
            onDismiss = { choosingBattery = false },
            onConfirm = { type ->
                choosingBattery = false
                vm.applyBatteryType(type)
            },
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String, caption: String?, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label)
            caption?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        if (enabled) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        } else {
            Spacer(Modifier.width(24.dp))
        }
    }
}

@Composable
private fun EditSettingDialog(setting: Setting, settings: BmsSettings, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val current = settings[setting]
    var input by remember { mutableStateOf(current?.let(setting::format) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    val notANumber = stringResource(R.string.error_not_number)
    val rangeFmt = stringResource(R.string.error_range)
    val aboveFmt = stringResource(R.string.error_above_release)
    val belowFmt = stringResource(R.string.error_below_release)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(setting.label)) },
        text = {
            Column {
                current?.let {
                    Text(stringResource(R.string.current_value, "${setting.format(it)} ${setting.unit}".trim()))
                }
                settings.release(setting)?.let {
                    Text(
                        stringResource(R.string.release_value, "${setting.format(it)} ${setting.unit}".trim()),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = null },
                    label = { Text(stringResource(R.string.new_value)) },
                    suffix = { Text(setting.unit) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = {
                        Text(error ?: stringResource(R.string.allowed_range, setting.format(setting.min), setting.format(setting.max), setting.unit))
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (setting.min < 0) KeyboardType.Text else KeyboardType.Decimal,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                when (val r = setting.validate(input, settings)) {
                    is ValidationResult.Ok -> onConfirm(r.raw)
                    ValidationResult.NotANumber -> error = notANumber
                    is ValidationResult.OutOfRange -> error = rangeFmt.format(r.min, r.max)
                    is ValidationResult.MustBeAbove -> error = aboveFmt.format(r.release)
                    is ValidationResult.MustBeBelow -> error = belowFmt.format(r.release)
                }
            }) { Text(stringResource(R.string.action_write)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun BatteryTypeDialog(current: BatteryType?, cellCount: Int, onDismiss: () -> Unit, onConfirm: (BatteryType) -> Unit) {
    var selected by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.label_battery_type)) },
        text = {
            Column {
                BatteryType.entries.filter { it.availableFor(cellCount) }.forEach { type ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == type, onClick = { selected = type })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == type, onClick = { selected = type })
                        Column {
                            Text(stringResource(type.label))
                            Text(
                                stringResource(
                                    R.string.battery_preset_detail,
                                    "%.2f".format(type.cellOv),
                                    "%.2f".format(type.cellUv),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.battery_preset_warning, cellCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && selected != current,
                onClick = { selected?.let(onConfirm) },
            ) { Text(stringResource(R.string.action_write)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
