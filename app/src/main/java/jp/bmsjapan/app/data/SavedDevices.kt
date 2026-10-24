package jp.bmsjapan.app.data

import jp.bmsjapan.app.protocol.PackMode

/** 前回つないだ 1 台 */
data class SavedDevice(val address: String, val name: String)

/** 前回の接続内容（つなぎ方と機器） */
data class SavedSetup(val mode: PackMode, val devices: List<SavedDevice>) {
    val isEmpty: Boolean get() = devices.isEmpty()
}

/**
 * SharedPreferences に入れるための文字列への変換。
 * 1 行目がつなぎ方のコード、2 行目以降が「アドレス<TAB>名前」。
 * Android の org.json は単体テストで使えないため、単純な形式にしている。
 */
object SavedDevicesCodec {
    private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

    fun encode(setup: SavedSetup): String = buildString {
        append(setup.mode.code)
        for (d in setup.devices) {
            append('\n')
            append(d.address)
            append('\t')
            append(d.name.replace('\t', ' ').replace('\n', ' '))
        }
    }

    fun decode(text: String?): SavedSetup? {
        if (text.isNullOrBlank()) return null
        val lines = text.split('\n')
        val mode = PackMode.fromCode(lines.first().trim().toIntOrNull())
        val devices = lines.drop(1).mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            val address = parts[0].trim()
            if (!MAC.matches(address)) return@mapNotNull null
            SavedDevice(address.uppercase(), parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: address)
        }.distinctBy { it.address }.take(mode.maxUnits)
        return SavedSetup(mode, devices)
    }
}
