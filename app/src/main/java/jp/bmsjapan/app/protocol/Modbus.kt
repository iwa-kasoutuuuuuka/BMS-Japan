package jp.bmsjapan.app.protocol

/**
 * Balance BMS が使う Modbus RTU 風フレームの生成。
 * 仕様の詳細は docs/ble_protocol.md を参照。
 */
object Modbus {
    /** BMS 本体のスレーブアドレス */
    const val SLAVE_BMS: Int = 0xD2

    /** 実容量など一部の値はこのアドレスに問い合わせる */
    const val SLAVE_AUX: Int = 0x81

    /** 0x81 への問い合わせには 0x51 で応答が返る（元アプリは応答の先頭 "5103"/"5110" で判別している） */
    const val SLAVE_AUX_REPLY: Int = 0x51

    /** 問い合わせ先から、応答に使われるアドレスを返す */
    fun replySlaves(slave: Int): Set<Int> = if (slave == SLAVE_AUX) setOf(SLAVE_AUX_REPLY, SLAVE_AUX) else setOf(slave)

    const val FN_READ: Int = 0x03
    const val FN_WRITE_SINGLE: Int = 0x06
    const val FN_WRITE_MULTI: Int = 0x10

    /** Modbus CRC16（初期値 0xFFFF、多項式 0xA001）。戻り値は下位バイトが先の 2 バイト。 */
    fun crc16(data: ByteArray, length: Int = data.size): ByteArray {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1
            }
        }
        return byteArrayOf((crc and 0xFF).toByte(), (crc ushr 8 and 0xFF).toByte())
    }

    fun withCrc(body: ByteArray): ByteArray = body + crc16(body)

    /** 末尾 2 バイトの CRC が正しいか */
    fun isCrcValid(frame: ByteArray): Boolean {
        if (frame.size < 3) return false
        val crc = crc16(frame, frame.size - 2)
        return crc[0] == frame[frame.size - 2] && crc[1] == frame[frame.size - 1]
    }

    /** 保持レジスタの読み出し: [addr 03 start(2) count(2) crc(2)] */
    fun readRegisters(start: Int, count: Int, slave: Int = SLAVE_BMS): ByteArray =
        withCrc(byteArrayOf(slave.toByte(), FN_READ.toByte()) + u16(start) + u16(count))

    /** 1 レジスタ書き込み: [addr 06 reg(2) value(2) crc(2)] */
    fun writeRegister(register: Int, value: Int, slave: Int = SLAVE_BMS): ByteArray =
        withCrc(byteArrayOf(slave.toByte(), FN_WRITE_SINGLE.toByte()) + u16(register) + u16(value))

    /**
     * 複数レジスタ書き込み。
     * 注意: この BMS は標準 Modbus にある「バイト数」フィールドを入れない独自仕様。
     * [addr 10 start(2) count(2) data(count*2) crc(2)]
     */
    fun writeRegisters(start: Int, values: IntArray, slave: Int = SLAVE_BMS): ByteArray {
        var body = byteArrayOf(slave.toByte(), FN_WRITE_MULTI.toByte()) + u16(start) + u16(values.size)
        for (v in values) body += u16(v)
        return withCrc(body)
    }

    /** 16bit に収まらない値は黙って切り詰めず、エラーにする（誤った値を BMS に書かないため） */
    private fun u16(v: Int): ByteArray {
        require(v in 0..0xFFFF) { "value out of 16-bit range: $v" }
        return byteArrayOf((v ushr 8 and 0xFF).toByte(), (v and 0xFF).toByte())
    }

    /**
     * ログ用の 16 進表記。パスワード（レジスタ 201〜203）を含むフレームは中身を伏せる。
     * - 201〜203 にかかる書き込み（0x06 / 0x10）
     * - 6 バイトの読み出し応答（パスワードの読み出しへの応答と同じ形）
     */
    fun redactForLog(frame: ByteArray): String {
        val hex = frame.toHex()
        if (frame.size < 3) return hex
        val fn = frame[1].toInt() and 0xFF
        fun u16At(i: Int) = ((frame[i].toInt() and 0xFF) shl 8) or (frame[i + 1].toInt() and 0xFF)
        val hidden = "…(パスワードを含むため非表示)"
        return when {
            fn == FN_READ && (frame[2].toInt() and 0xFF) == 6 -> hex.take(6) + hidden
            fn == FN_WRITE_SINGLE && frame.size >= 6 && u16At(2) in 201..203 -> hex.take(8) + hidden
            fn == FN_WRITE_MULTI && frame.size > 8 -> {
                val start = u16At(2)
                val end = start + u16At(4) - 1
                if (start <= 203 && end >= 201) hex.take(12) + hidden else hex
            }
            else -> hex
        }
    }
}

/** よく使うコマンド */
object BmsCommands {
    /** 運転データ（レジスタ 0〜61） */
    val READ_LIVE: ByteArray get() = Modbus.readRegisters(0, 62)

    /** 33〜48 セル目の電圧（レジスタ 71〜86） */
    val READ_EXTRA_CELLS: ByteArray get() = Modbus.readRegisters(71, 16)

    /** 時刻同期（レジスタ 212〜214 に YY MM DD hh mm ss） */
    fun syncTime(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): ByteArray =
        Modbus.writeRegisters(
            212,
            intArrayOf(
                ((year - 2000) shl 8) or month,
                (day shl 8) or hour,
                (minute shl 8) or second,
            ),
        )
}

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "hex length must be even" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
