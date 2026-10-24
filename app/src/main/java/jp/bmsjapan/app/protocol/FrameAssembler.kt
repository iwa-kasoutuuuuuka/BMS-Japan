package jp.bmsjapan.app.protocol

/** CRC 確認済みの受信フレーム */
sealed interface BmsFrame {
    /** 0x03 読み出し応答。registers は 16bit 符号なし値の並び */
    data class ReadResponse(val registers: IntArray) : BmsFrame {
        override fun equals(other: Any?): Boolean = other is ReadResponse && registers.contentEquals(other.registers)
        override fun hashCode(): Int = registers.contentHashCode()
    }

    /** 0x06 書き込み応答（エコー） */
    data class WriteSingleAck(val register: Int, val value: Int) : BmsFrame

    /** 0x10 書き込み応答 */
    data class WriteMultiAck(val start: Int, val count: Int) : BmsFrame
}

/**
 * Notify で細切れに届くバイト列をつなぎ合わせ、完全なフレームを取り出す。
 * 同期が取れないときは 1 バイトずつ読み捨てる（元アプリと同じ方式）。
 */
class FrameAssembler(private val slave: Int = Modbus.SLAVE_BMS) {
    private var buffer = ByteArray(0)

    fun reset() {
        buffer = ByteArray(0)
    }

    fun feed(chunk: ByteArray): List<BmsFrame> {
        buffer += chunk
        val out = mutableListOf<BmsFrame>()
        while (buffer.size >= 2) {
            if ((buffer[0].toInt() and 0xFF) != slave) {
                drop(1); continue
            }
            val fn = buffer[1].toInt() and 0xFF
            val length = when (fn) {
                Modbus.FN_READ -> {
                    if (buffer.size < 3) return out
                    3 + (buffer[2].toInt() and 0xFF) + 2
                }
                Modbus.FN_WRITE_SINGLE, Modbus.FN_WRITE_MULTI -> 8
                else -> {
                    drop(1); continue
                }
            }
            if (buffer.size < length) return out
            val frame = buffer.copyOfRange(0, length)
            if (!Modbus.isCrcValid(frame)) {
                drop(1); continue
            }
            drop(length)
            out += parse(fn, frame)
        }
        return out
    }

    private fun parse(fn: Int, f: ByteArray): BmsFrame = when (fn) {
        Modbus.FN_READ -> {
            val n = (f[2].toInt() and 0xFF) / 2
            BmsFrame.ReadResponse(IntArray(n) { u16(f, 3 + it * 2) })
        }
        Modbus.FN_WRITE_SINGLE -> BmsFrame.WriteSingleAck(u16(f, 2), u16(f, 4))
        else -> BmsFrame.WriteMultiAck(u16(f, 2), u16(f, 4))
    }

    private fun drop(n: Int) {
        buffer = buffer.copyOfRange(n, buffer.size)
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
