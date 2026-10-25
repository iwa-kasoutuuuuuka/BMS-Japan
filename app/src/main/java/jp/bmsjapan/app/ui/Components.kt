package jp.bmsjapan.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import jp.bmsjapan.app.R

// ---- カード ----

/** アプリ共通のカード。ダークでは細い枠線で立体感を出す */
@Composable
fun BmsCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, LocalBmsColors.current.cardBorder),
    ) { content() }
}

/** 見出し付きのカード */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    BmsCard {
        Column(Modifier.padding(16.dp)) {
            SectionTitle(title)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 4.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}

// ---- SOC のリング ----

/**
 * 270° の円弧で SOC を表すゲージ。値が変わるとなめらかに動く。
 * 中央に大きく SOC、下に [subtitle]。
 */
@Composable
fun SocGauge(soc: Double, subtitle: String, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val colors = LocalBmsColors.current
    val fraction by animateFloatAsState(
        targetValue = (soc / 100.0).toFloat().coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 900),
        label = "soc",
    )
    // 残量が少ないときはリングの色を警告色に寄せる
    val endColor = when {
        soc < 15 -> MaterialTheme.colorScheme.error
        soc < 30 -> colors.discharge
        else -> colors.gaugeEnd
    }
    // 270° の円弧は下が開いているので、高さを少し詰めて下の余白を減らす
    Box(modifier.width(size).height(size * 0.84f), contentAlignment = Alignment.TopCenter) {
    Box(Modifier.requiredSize(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = this.size.minDimension * 0.085f
            val inset = stroke / 2 + 4f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = colors.gaugeTrack,
                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (fraction > 0f) {
                // 開始位置（135°）からグラデーションが始まるように回してから描く
                rotate(135f) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to colors.gaugeStart,
                            0.75f to endColor,
                            1f to colors.gaugeStart,
                        ),
                        startAngle = 0f, sweepAngle = 270f * fraction, useCenter = false,
                        topLeft = topLeft, size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(soc), style = MaterialTheme.typography.displayLarge)
                Text(
                    "%",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 10.dp),
                )
            }
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    }
}

// ---- 状態チップ ----

@Composable
fun StateChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
}

// ---- 数値タイル ----

data class Metric(val label: String, val value: String, val unit: String = "", val accent: Color? = null)

/** ラベル・大きな数値・単位のタイルを 2 列で並べる */
@Composable
fun MetricTiles(items: List<Metric>, columns: Int = 2) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { m -> MetricTile(m, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun MetricTile(m: Metric, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            m.accent?.let {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(it),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(m.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            // 「3.291〜3.318」のような長い値は少し小さくして、はみ出さないようにする
            Text(
                m.value,
                style = if (m.value.length > 8) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )
            if (m.unit.isNotEmpty()) {
                Text(
                    " " + m.unit,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
    }
}

// ---- スイッチ表示 ----

@Composable
fun SwitchPill(label: String, on: Boolean, modifier: Modifier = Modifier) {
    val good = LocalBmsColors.current.good
    val bg = if (on) good.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val fg = if (on) good else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(if (on) R.string.on else R.string.off), color = fg, style = MaterialTheme.typography.titleMedium)
    }
}

// ---- セル ----

/**
 * セル 1 本のタイル。下からのバーで、全セルの最低〜最高の中での位置を表す。
 * 最高は橙、最低は水色。
 */
@Composable
fun CellTile(index: Int, volts: Double, minV: Double, maxV: Double, isMax: Boolean, isMin: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalBmsColors.current
    val accent = when {
        isMax -> colors.cellMax
        isMin -> colors.cellMin
        else -> MaterialTheme.colorScheme.primary
    }
    // 差がほとんど無いときもバーが見えるように、下限を少し持たせる
    val span = (maxV - minV).coerceAtLeast(0.001)
    val level = (0.25 + 0.75 * ((volts - minV) / span)).toFloat().coerceIn(0.1f, 1f)
    Box(
        modifier
            .height(58.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .fillMaxHeight(level)
                .background(accent.copy(alpha = if (isMax || isMin) 0.30f else 0.16f)),
        )
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text("#$index", style = MaterialTheme.typography.labelSmall, color = if (isMax || isMin) accent else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("%.3f".format(volts), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---- 表示モードのメニュー ----

/** 上部バーの「︙」から 自動／ライト／ダーク を選ぶ */
@Composable
fun ThemeMenuButton(vm: BmsViewModel) {
    val mode by vm.themeMode.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.theme_menu))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.theme_menu),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            listOf(
                ThemeMode.SYSTEM to R.string.theme_system,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            ).forEach { (m, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { RadioButton(selected = mode == m, onClick = null) },
                    onClick = {
                        vm.setThemeMode(m)
                        open = false
                    },
                )
            }
        }
    }
}
