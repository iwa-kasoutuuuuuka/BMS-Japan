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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableIntStateOf
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
import jp.bmsjapan.app.protocol.ActualCapacity
import jp.bmsjapan.app.protocol.BatteryType
import jp.bmsjapan.app.protocol.BulkOp
import jp.bmsjapan.app.protocol.BulkSkip
import jp.bmsjapan.app.protocol.CellConditions
import jp.bmsjapan.app.protocol.BmsSettings
import jp.bmsjapan.app.protocol.LiveData
import jp.bmsjapan.app.protocol.PasswordChangeCheck
import jp.bmsjapan.app.protocol.LeafGeneration
import jp.bmsjapan.app.protocol.PresetImmediateEffect
import jp.bmsjapan.app.protocol.PresetOptions
import jp.bmsjapan.app.protocol.PresetPlan
import jp.bmsjapan.app.protocol.RecommendedPreset
import jp.bmsjapan.app.protocol.immediateEffect
import jp.bmsjapan.app.protocol.SafetyLimits
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
    var choosingRecommended by remember { mutableStateOf(false) }
    var changingPassword by remember { mutableStateOf(false) }
    var editingActualCapacity by remember { mutableStateOf(false) }
    val actualCapacity by vm.actualCapacityMah.collectAsState()
    val bulkCount by vm.bulkTargetCount.collectAsState()
    val bulk by vm.bulk.collectAsState()
    var switchToAll by remember { mutableStateOf(false) }

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
                // 見出しが「電池の種類」なので、行には種類の名前だけを出す（長い名前でも折り返さない）
                SettingRow(
                    label = s.batteryType?.let { stringResource(it.label) } ?: "--",
                    value = "",
                    caption = stringResource(R.string.battery_type_tap),
                    enabled = !busy,
                    onClick = { choosingBattery = true },
                )
                HorizontalDivider()
                SettingRow(
                    label = stringResource(R.string.label_recommended),
                    value = "",
                    caption = stringResource(R.string.recommended_caption),
                    enabled = !busy,
                    onClick = { choosingRecommended = true },
                )
            }

            Section(stringResource(R.string.group_security)) {
                SettingRow(
                    label = stringResource(R.string.label_change_password),
                    value = "",
                    caption = stringResource(R.string.change_password_caption),
                    enabled = !busy,
                    onClick = { changingPassword = true },
                )
            }

            SettingGroup.entries.forEach { group ->
                val items = Setting.entries.filter { it.group == group }
                Section(stringResource(group.title)) {
                    if (group == SettingGroup.BATTERY) {
                        // 実容量は別アドレス（0x81）の 32bit 値なので、ここだけ個別に扱う
                        val mAh = actualCapacity
                        SettingRow(
                            label = stringResource(R.string.set_actual_capacity),
                            value = mAh?.let { "${ActualCapacity.format(it)} Ah" } ?: "--",
                            caption = if (mAh == null) stringResource(R.string.actual_capacity_unavailable) else null,
                            enabled = mAh != null && !busy,
                            onClick = { editingActualCapacity = true },
                        )
                        HorizontalDivider()
                    }
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
            liveCells = live.cellCount,
            bulkCount = bulkCount,
            onDismiss = { editing = null },
            onConfirm = { raw, toAll ->
                editing = null
                if (toAll && bulkCount > 1) vm.runBulk(BulkOp.SetValue(setting, setting.encoding.decode(raw)))
                else vm.writeSetting(setting, raw)
            },
        )
    }

    confirmSwitch?.let { (sw, on) ->
        AlertDialog(
            // 戻る・外側のタップで閉じたときも「全台」のチェックを戻す（次のダイアログに残らないように）
            onDismissRequest = { confirmSwitch = null; switchToAll = false },
            title = { Text(stringResource(sw.label)) },
            text = {
                Column {
                    Text(stringResource(if (on) sw.confirmOn else sw.confirmOff))
                    if (bulkCount > 1) ApplyToAllRow(bulkCount, switchToAll) { switchToAll = it }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSwitch = null
                    if (switchToAll && bulkCount > 1) vm.runBulk(BulkOp.Switch(sw, on)) else vm.setSwitch(sw, on)
                    switchToAll = false
                }) { Text(stringResource(if (on) R.string.action_turn_on else R.string.action_turn_off)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSwitch = null; switchToAll = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    if (editingActualCapacity) {
        EditActualCapacityDialog(
            currentMah = actualCapacity,
            ratedAh = s?.get(Setting.RATED_CAPACITY),
            onDismiss = { editingActualCapacity = false },
            onConfirm = { mAh ->
                editingActualCapacity = false
                vm.writeActualCapacity(mAh)
            },
        )
    }

    if (changingPassword) {
        ChangePasswordDialog(
            onDismiss = { changingPassword = false },
            onSubmit = { old, new, repeat ->
                val r = vm.changePassword(old, new, repeat)
                if (r == PasswordChangeCheck.OK) changingPassword = false
                r
            },
        )
    }

    if (choosingRecommended && s != null) {
        RecommendedDialog(
            cellCount = vm.presetCellCount() ?: live.cellCount,
            live = live,
            currentRatedAh = s[Setting.RATED_CAPACITY],
            bulkCount = bulkCount,
            allLives = { vm.connectedLives },
            plan = vm::planRecommended,
            onDismiss = { choosingRecommended = false },
            onConfirm = { preset, plan, toAll, ack, chemAck ->
                choosingRecommended = false
                if (toAll && bulkCount > 1) {
                    vm.runBulk(
                        BulkOp.Recommended(preset, s.batteryType, plan.options),
                        acceptImmediateEffect = ack,
                        acceptChemistryChange = chemAck,
                    )
                } else {
                    vm.applyRecommended(preset, plan, ack, chemAck)
                }
            },
        )
    }

    if (choosingBattery && s != null) {
        BatteryTypeDialog(
            current = s.batteryType,
            cellCount = vm.presetCellCount() ?: live.cellCount,
            bulkCount = bulkCount,
            onDismiss = { choosingBattery = false },
            onConfirm = { type, toAll ->
                choosingBattery = false
                if (toAll && bulkCount > 1) vm.runBulk(BulkOp.Battery(type, s.batteryType)) else vm.applyBatteryType(type)
            },
        )
    }

    bulk?.let { BulkResultDialog(it, onDismiss = vm::dismissBulk) }
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
private fun EditSettingDialog(
    setting: Setting,
    settings: BmsSettings,
    liveCells: Int,
    bulkCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (raw: Int, toAll: Boolean) -> Unit,
) {
    var toAll by remember { mutableStateOf(false) }
    val current = settings[setting]
    // 電池の種類・セル数から決まる、実際に入力できる範囲
    val (range, blocked) = SafetyLimits.rangeFor(setting, settings, liveCells)
    var input by remember { mutableStateOf(current?.let(setting::format) ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    val notANumber = stringResource(R.string.error_not_number)
    val rangeFmt = stringResource(R.string.error_range)
    val aboveFmt = stringResource(R.string.error_above_release)
    val belowFmt = stringResource(R.string.error_below_release)
    val needsType = stringResource(R.string.error_needs_battery_type)
    val needsCells = stringResource(R.string.error_needs_cell_count)
    val aboveOtherFmt = stringResource(R.string.error_above_other)
    val belowOtherFmt = stringResource(R.string.error_below_other)
    val otherLabels = Setting.entries.associateWith { stringResource(it.label) }
    val blockedText = when (blocked) {
        ValidationResult.NeedsBatteryType -> needsType
        ValidationResult.NeedsCellCount -> needsCells
        else -> null
    }

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
                    enabled = blockedText == null,
                    supportingText = {
                        Text(
                            error ?: blockedText ?: range?.let {
                                stringResource(R.string.allowed_range, setting.format(it.min), setting.format(it.max), setting.unit)
                            } ?: "",
                        )
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if ((range?.min ?: setting.min) < 0) KeyboardType.Text else KeyboardType.Decimal,
                    ),
                )
                if (bulkCount > 1 && blockedText == null) ApplyToAllRow(bulkCount, toAll) { toAll = it }
            }
        },
        confirmButton = {
            TextButton(enabled = blockedText == null, onClick = {
                when (val r = setting.validate(input, settings, liveCells)) {
                    is ValidationResult.Ok -> onConfirm(r.raw, toAll)
                    ValidationResult.NotANumber -> error = notANumber
                    is ValidationResult.OutOfRange -> error = rangeFmt.format(r.min, r.max)
                    is ValidationResult.MustBeAbove -> error = aboveFmt.format(r.release)
                    is ValidationResult.MustBeBelow -> error = belowFmt.format(r.release)
                    ValidationResult.NeedsBatteryType -> error = needsType
                    ValidationResult.NeedsCellCount -> error = needsCells
                    is ValidationResult.MustBeAboveOther -> error = aboveOtherFmt.format(otherLabels.getValue(r.other), r.value)
                    is ValidationResult.MustBeBelowOther -> error = belowOtherFmt.format(otherLabels.getValue(r.other), r.value)
                }
            }) { Text(stringResource(R.string.action_write)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun EditActualCapacityDialog(currentMah: Int?, ratedAh: Double?, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var input by remember { mutableStateOf(currentMah?.let(ActualCapacity::format) ?: "") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.set_actual_capacity)) },
        text = {
            Column {
                currentMah?.let { Text(stringResource(R.string.current_value, "${ActualCapacity.format(it)} Ah")) }
                ratedAh?.let {
                    Text(stringResource(R.string.actual_capacity_rated, "%.1f Ah".format(it)), style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; error = false },
                    label = { Text(stringResource(R.string.new_value)) },
                    suffix = { Text("Ah") },
                    singleLine = true,
                    isError = error,
                    supportingText = {
                        Text(
                            if (error) stringResource(R.string.error_range, "%.1f".format(ActualCapacity.MIN_AH), "%.1f".format(ActualCapacity.MAX_AH))
                            else stringResource(R.string.allowed_range, "%.1f".format(ActualCapacity.MIN_AH), "%.1f".format(ActualCapacity.MAX_AH), "Ah"),
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                Text(stringResource(R.string.actual_capacity_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val mAh = ActualCapacity.parse(input)
                if (mAh == null) error = true else onConfirm(mAh)
            }) { Text(stringResource(R.string.action_write)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ChangePasswordDialog(onDismiss: () -> Unit, onSubmit: (String, String, String) -> PasswordChangeCheck) {
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<PasswordChangeCheck?>(null) }
    val digits6 = { v: String -> v.filter { it in '0'..'9' }.take(6) }

    @Composable
    fun field(value: String, onChange: (String) -> Unit, label: Int, error: Boolean) = OutlinedTextField(
        value = value,
        onValueChange = { onChange(digits6(it)); result = null },
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = error,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.fillMaxWidth(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.label_change_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                field(old, { old = it }, R.string.pwd_old, result == PasswordChangeCheck.WRONG_OLD)
                field(new, { new = it }, R.string.pwd_new, result == PasswordChangeCheck.BAD_FORMAT || result == PasswordChangeCheck.SAME_AS_OLD)
                field(repeat, { repeat = it }, R.string.pwd_repeat, result == PasswordChangeCheck.MISMATCH)
                val error = when (result) {
                    PasswordChangeCheck.EMPTY -> R.string.pwd_err_empty
                    PasswordChangeCheck.WRONG_OLD -> R.string.pwd_err_wrong_old
                    PasswordChangeCheck.BAD_FORMAT -> R.string.pwd_err_format
                    PasswordChangeCheck.MISMATCH -> R.string.pwd_err_mismatch
                    PasswordChangeCheck.SAME_AS_OLD -> R.string.pwd_err_same
                    PasswordChangeCheck.OK, null -> null
                }
                if (error != null) {
                    Text(stringResource(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.pwd_warning), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { result = onSubmit(old, new, repeat) }) { Text(stringResource(R.string.action_change)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun BatteryTypeDialog(
    current: BatteryType?,
    cellCount: Int,
    bulkCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (BatteryType, toAll: Boolean) -> Unit,
) {
    var selected by remember { mutableStateOf(current) }
    var toAll by remember { mutableStateOf(false) }
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
                if (bulkCount > 1) ApplyToAllRow(bulkCount, toAll) { toAll = it }
            }
        },
        confirmButton = {
            TextButton(
                // 全台に書くときは、開いている台が同じ種類でも他の台が違うことがあるので押せるようにする
                enabled = selected != null && (selected != current || toAll),
                onClick = { selected?.let { onConfirm(it, toAll) } },
            ) { Text(stringResource(R.string.action_write)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** おすすめ設定: 電池を選ぶ → 世代と容量 → 変わる項目を確認 → 書き込む */
@Composable
private fun RecommendedDialog(
    cellCount: Int,
    live: LiveData,
    currentRatedAh: Double?,
    bulkCount: Int,
    allLives: () -> List<LiveData>,
    plan: (RecommendedPreset, PresetOptions) -> PresetPlan?,
    onDismiss: () -> Unit,
    onConfirm: (RecommendedPreset, PresetPlan, toAll: Boolean, acknowledged: Boolean, chemistryAcknowledged: Boolean) -> Unit,
) {
    // 0: 電池を選ぶ / 1: 世代と容量 / 2: 変わる項目の確認
    var step by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<RecommendedPreset?>(null) }
    var generation by remember { mutableStateOf<LeafGeneration?>(null) }
    var writeCapacity by remember { mutableStateOf(false) }
    var parallelText by remember { mutableStateOf("") }
    var sohText by remember { mutableStateOf("100") }
    var acknowledged by remember { mutableStateOf(false) }
    var toAll by remember { mutableStateOf(false) }
    var chemistryAcknowledged by remember { mutableStateOf(false) }

    val current = selected
    val gen = generation
    val ratedAh = if (writeCapacity && gen != null) {
        val par = parallelText.trim().toIntOrNull()
        val soh = sohText.trim().toIntOrNull()
        if (par != null && soh != null) LeafGeneration.ratedCapacityAh(gen, par, soh) else null
    } else null
    // 容量を書くと決めたのに、並列数・SOH が正しくない
    val capacityInvalid = writeCapacity && gen != null && ratedAh == null
    val options = PresetOptions(gen, ratedAh)
    val p = if (step == 2 && current != null) plan(current, options) else null
    // 全台に書くときは、全台のうちいちばん厳しいセル電圧・温度で判定する
    val cond = if (toAll) CellConditions.worst(allLives()) else CellConditions.of(live)
    val effect = p?.immediateEffect(cond) ?: PresetImmediateEffect.NONE

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    step == 1 -> stringResource(R.string.gen_title)
                    step == 2 && current != null -> stringResource(current.label)
                    else -> stringResource(R.string.label_recommended)
                },
            )
        },
        text = {
            // 画面（step）が変わったら先頭から表示する
            val scroll = remember(step) { ScrollState(0) }
            Column(Modifier.verticalScroll(scroll)) {
                when {
                    step == 0 || current == null -> {
                        Text(stringResource(R.string.recommended_choose, cellCount), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        RecommendedPreset.entries.forEach { preset ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = selected == preset, onClick = { selected = preset })
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = selected == preset, onClick = { selected = preset })
                                Column {
                                    Text(stringResource(preset.label))
                                    Text(stringResource(preset.description), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    step == 1 -> {
                        Text(stringResource(R.string.gen_intro), style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        val choices: List<LeafGeneration?> = listOf(null) + LeafGeneration.entries
                        choices.forEach { g ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = generation == g, onClick = {
                                        generation = g
                                        if (g != null) parallelText = g.defaultParallel.toString() else writeCapacity = false
                                    })
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = generation == g, onClick = null)
                                Column {
                                    Text(if (g == null) stringResource(R.string.gen_none) else stringResource(g.label))
                                    Text(
                                        if (g == null) stringResource(R.string.gen_none_detail) else stringResource(g.detail),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                        if (gen != null && gen.suggestLongLife && current == RecommendedPreset.LEAF_STANDARD) {
                            Text(
                                stringResource(R.string.gen_suggest_long_life),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (gen != null) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { writeCapacity = !writeCapacity },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = writeCapacity, onCheckedChange = { writeCapacity = it })
                                Text(stringResource(R.string.gen_write_capacity), style = MaterialTheme.typography.bodyMedium)
                            }
                            if (writeCapacity) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = parallelText,
                                        onValueChange = { v -> parallelText = v.filter(Char::isDigit).take(2) },
                                        label = { Text(stringResource(R.string.gen_parallel)) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    )
                                    OutlinedTextField(
                                        value = sohText,
                                        onValueChange = { v -> sohText = v.filter(Char::isDigit).take(3) },
                                        label = { Text(stringResource(R.string.gen_soh)) },
                                        suffix = { Text("%") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    )
                                }
                                Text(
                                    if (ratedAh != null) {
                                        stringResource(
                                            R.string.gen_capacity_result,
                                            "%.1f".format(gen.cellAh), parallelText, sohText, "%.1f".format(ratedAh),
                                        )
                                    } else {
                                        stringResource(
                                            R.string.gen_capacity_invalid,
                                            LeafGeneration.MIN_PARALLEL, LeafGeneration.MAX_PARALLEL,
                                            LeafGeneration.MIN_SOH, LeafGeneration.MAX_SOH,
                                        )
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (ratedAh == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                )
                                currentRatedAh?.let {
                                    Text(stringResource(R.string.gen_capacity_current, "%.1f".format(it)), style = MaterialTheme.typography.bodySmall)
                                }
                                Text(
                                    stringResource(R.string.gen_capacity_note),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    p == null -> {
                        Text(stringResource(R.string.recommended_unavailable, cellCount), color = MaterialTheme.colorScheme.error)
                    }
                    else -> {
                        Text(stringResource(R.string.recommended_changes), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        if (p.changes.isEmpty()) Text(stringResource(R.string.recommended_no_changes))
                        p.changes.forEach { ch ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                            ) {
                                Text(stringResource(ch.label), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                Text("${ch.before} → ${ch.after}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.recommended_note), style = MaterialTheme.typography.bodySmall)
                        if (bulkCount > 1) {
                            ApplyToAllRow(bulkCount, toAll) { toAll = it; acknowledged = false }
                            if (toAll && p.options.ratedCapacityAh != null) {
                                Text(stringResource(R.string.gen_bulk_capacity_note), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (p.changesChemistry) {
                            // 電池の種類が変わると、過充電の保護値が大きく変わる。セルの種類を利用者に確かめてもらう
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(
                                    R.string.recommended_chem_warn,
                                    p.fromType?.let { stringResource(it.label) } ?: "--",
                                    stringResource(p.preset.type.label),
                                    "%.2f".format(p.preset.cellOv),
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (toAll && bulkCount > 1) {
                                Text(stringResource(R.string.recommended_chem_bulk_note), style = MaterialTheme.typography.bodySmall)
                            }
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { chemistryAcknowledged = !chemistryAcknowledged },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = chemistryAcknowledged, onCheckedChange = { chemistryAcknowledged = it })
                                Text(stringResource(R.string.recommended_chem_ack), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (effect != PresetImmediateEffect.NONE) {
                            val pr = p.preset
                            val temps = "${cond.minTemp ?: "--"}〜${cond.maxTemp ?: "--"}"
                            Spacer(Modifier.height(8.dp))
                            Text(
                                when (effect) {
                                    PresetImmediateEffect.DISCHARGE_STOPS -> stringResource(
                                        R.string.recommended_warn_discharge, "%.3f".format(cond.minCellV ?: 0.0), "%.2f".format(pr.cellUvRelease),
                                    )
                                    PresetImmediateEffect.CHARGE_STOPS -> stringResource(
                                        R.string.recommended_warn_charge, "%.3f".format(cond.maxCellV ?: 0.0), "%.2f".format(pr.cellOvRelease),
                                    )
                                    PresetImmediateEffect.DISCHARGE_STOPS_TEMP -> stringResource(
                                        R.string.recommended_warn_discharge_temp, temps, pr.dischargeUtRelease, pr.dischargeOtRelease,
                                    )
                                    else -> stringResource(R.string.recommended_warn_charge_temp, temps, pr.chargeUtRelease, pr.chargeOtRelease)
                                },
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { acknowledged = !acknowledged },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                                Text(stringResource(R.string.recommended_ack), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                step == 0 || current == null -> TextButton(enabled = current != null, onClick = { step = 1 }) {
                    Text(stringResource(R.string.action_next))
                }
                step == 1 -> TextButton(
                    enabled = !capacityInvalid,
                    onClick = { step = 2; acknowledged = false; chemistryAcknowledged = false },
                ) { Text(stringResource(R.string.action_next)) }
                else -> TextButton(
                    // 全台に書くときは、開いている台で変化が無くても他の台が違うことがあるので押せるようにする
                    enabled = p != null && (p.changes.isNotEmpty() || toAll) &&
                        (effect == PresetImmediateEffect.NONE || acknowledged) &&
                        (!p.changesChemistry || chemistryAcknowledged),
                    onClick = { p?.let { onConfirm(current, it, toAll, acknowledged, chemistryAcknowledged) } },
                ) { Text(stringResource(R.string.action_write)) }
            }
        },
        dismissButton = {
            if (step > 0) {
                TextButton(onClick = { step -= 1 }) { Text(stringResource(R.string.action_back)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** 「全台に書き込む」のチェック（並列・直列で 2 台以上のときだけ出す） */
@Composable
private fun ApplyToAllRow(count: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onChange(!checked) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = onChange)
            Text(stringResource(R.string.bulk_apply_all, count), style = MaterialTheme.typography.bodyMedium)
        }
        if (checked) {
            Text(
                stringResource(R.string.bulk_apply_all_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 全台への書き込みの進み具合と結果 */
@Composable
private fun BulkResultDialog(state: BulkState, onDismiss: () -> Unit) {
    val done = state.rows.count { it.status == BulkStatus.Done }
    AlertDialog(
        onDismissRequest = { if (state.finished) onDismiss() },
        title = { Text(stringResource(R.string.bulk_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (state.finished) {
                    Text(stringResource(R.string.bulk_summary, state.rows.size, done), fontWeight = FontWeight.Medium)
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(8.dp))
                state.rows.forEach { row ->
                    val (text, error) = when (val st = row.status) {
                        BulkStatus.Waiting -> stringResource(R.string.bulk_waiting) to false
                        BulkStatus.Running -> stringResource(R.string.bulk_running) to false
                        BulkStatus.Done -> stringResource(R.string.bulk_done) to false
                        is BulkStatus.Skipped -> stringResource(
                            when (st.reason) {
                                BulkSkip.NOT_CONNECTED -> R.string.bulk_skip_not_connected
                                BulkSkip.PASSWORD -> R.string.bulk_skip_password
                                BulkSkip.READ_FAILED -> R.string.bulk_skip_read
                                BulkSkip.OUT_OF_RANGE -> R.string.bulk_skip_range
                                BulkSkip.BAD_CELLS -> R.string.bulk_skip_cells
                                BulkSkip.IMMEDIATE_EFFECT -> R.string.bulk_skip_effect
                                BulkSkip.NO_LIVE_DATA -> R.string.bulk_skip_no_live
                                BulkSkip.CHEMISTRY_MISMATCH -> R.string.bulk_skip_chem_mismatch
                                BulkSkip.CHEMISTRY_NOT_ACCEPTED -> R.string.bulk_skip_chem_not_accepted
                                BulkSkip.WRITE_FAILED -> R.string.bulk_skip_write
                                BulkSkip.VERIFY_FAILED -> R.string.bulk_skip_verify
                            },
                        ) to true
                    }
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(row.name, fontWeight = FontWeight.Medium)
                        Text(
                            text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = state.finished, onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
