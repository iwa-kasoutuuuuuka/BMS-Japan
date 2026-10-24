package jp.bmsjapan.app.protocol

/** CRC 確認済みの受信フレーム。slave は応答のスレーブアドレス */
sealed interface BmsFrame {
    val slave: Int

    /** 0x03 読み出し応答。registers は 16bit 符号なし値の並び */
    data class ReadResponse(val registers: IntArray, override val slave: Int = Modbus.SLAVE_BMS) : BmsFrame {
        override fun equals(other: Any?): Boolean =
            other is ReadResponse && slave == other.slave && registers.contentEquals(other.registers)
        override fun hashCode(): Int = 31 * slave + registers.contentHashCode()
    }

    /** 0x06 書き込み応答（エコー） */
    data class WriteSingleAck(val register: Int, val value: Int, override val slave: Int = Modbus.SLAVE_BMS) : BmsFrame

    /** 0x10 書き込み応答 */
    data class WriteMultiAck(val start: Int, val count: Int, override val slave: Int = Modbus.SLAVE_BMS) : BmsFrame
}

/**
 * Notify で細切れに届くバイト列をつなぎ合わせ、完全なフレームを取り出す。
 * 同期が取れないときは 1 バイトずつ読み捨てる（元アプリと同じ方式）。
 *
 * @param slaves 受け付ける応答のスレーブアドレス
 * @param lenientCrcSlaves CRC が合わなくても受け付けるアドレス。
 *   実容量の応答（0x51）は元アプリが CRC を確認していないため、念のため緩くしている
 */
class FrameAssembler(
    private val slaves: Set<Int> = setOf(Modbus.SLAVE_BMS, Modbus.SLAVE_AUX_REPLY, Modbus.SLAVE_AUX),
    private val lenientCrcSlaves: Set<Int> = setOf(Modbus.SLAVE_AUX_REPLY),
) {
    private var buffer = ByteArray(0)

    fun reset() {
        buffer = ByteArray(0)
    }

    fun feed(chunk: ByteArray): List<BmsFrame> {
        buffer += chunk
        val out = mutableListOf<BmsFrame>()
        while (buffer.size >= 2) {
            val slave = buffer[0].toInt() and 0xFF
            if (slave !in slaves) {
                drop(1); continue
            }
            val fn = buffer[1].toInt() and 0xFF
            val length = when (fn) {
                Modbus.FN_READ -> {
                    if (buffer.size < 3) return out
                    val byteCount = buffer[2].toInt() and 0xFF
                    // CRC を確認しない応答は、知っている形（4 バイト＝32bit 値）だけ受け付ける
                    if (slave in lenientCrcSlaves && byteCount != 4) {
                        drop(1); continue
                    }
                    3 + byteCount + 2
                }
                Modbus.FN_WRITE_SINGLE, Modbus.FN_WRITE_MULTI -> 8
                else -> {
                    drop(1); continue
                }
            }
            if (buffer.size < length) return out
            val frame = buffer.copyOfRange(0, length)
            if (!Modbus.isCrcValid(frame) && slave !in lenientCrcSlaves) {
                drop(1); continue
            }
            drop(length)
            out += parse(slave, fn, frame)
        }
        return out
    }

    private fun parse(slave: Int, fn: Int, f: ByteArray): BmsFrame = when (fn) {
        Modbus.FN_READ -> {
            val n = (f[2].toInt() and 0xFF) / 2
            BmsFrame.ReadResponse(IntArray(n) { u16(f, 3 + it * 2) }, slave)
        }
        Modbus.FN_WRITE_SINGLE -> BmsFrame.WriteSingleAck(u16(f, 2), u16(f, 4), slave)
        else -> BmsFrame.WriteMultiAck(u16(f, 2), u16(f, 4), slave)
    }

    private fun drop(n: Int) {
        buffer = buffer.copyOfRange(n, buffer.size)
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
