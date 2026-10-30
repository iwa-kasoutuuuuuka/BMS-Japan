package jp.bmsjapan.app.protocol

import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 実機の代わりに応答する疑似 BMS（デモモード用）。
 * 本物と同じバイト列のフレームを受け取り、本物と同じ形の応答フレームを返すので、
 * 受信の組み立て・解析の処理もそのまま通る。
 *
 * @param seed 台ごとに値を少し変えるための番号
 */
class DemoBms(private val seed: Int = 0) {
    private val regs = IntArray(400)
    private var actualCapacityMah = 280_000 - seed * 7_000
    private var tick = 0

    init {
        // 設定値（LFP 16 セル相当）
        fun set(r: Int, v: Int) { regs[r] = v }
        set(128, 2800); set(129, 3200); set(130, 16); set(131, 16); set(134, 4)
        set(137, 0); set(138, 3600)
        set(139, 3550); set(140, 3650); set(141, 2700); set(142, 2500)
        set(143, 568); set(144, 584); set(145, 432); set(146, 400)
        set(147, 29000); set(148, 28000); set(149, 31500); set(150, 32000)
        set(151, 90); set(152, 95); set(153, 45); set(154, 40)
        set(155, 95); set(156, 100); set(157, 25); set(158, 20)
        set(159, 200); set(160, 300); set(161, 10); set(162, 15)
        set(163, 3400); set(164, 10); set(165, 1); set(166, 1)
        set(167, 1000); set(168, 130)
        // 均等化の設定
        set(206, 16); set(207, 1); set(208, 20)
        // パスワード "123456"
        set(201, 0x3132); set(202, 0x3334); set(203, 0x3536)
        updateLive()
    }

    /**
     * 実機の BMS は、総電圧の保護値（143〜146）を書き込みでは変えず、セルの保護値（139〜142）から自分で決める。
     * デモでは「セル値 × セル数」で近似する（実機はここから少し補正した値になる）。
     */
    private fun deriveBmsValues() {
        val n = regs[130]
        for (i in 0..3) regs[143 + i] = (regs[139 + i] * n / 100.0).roundToInt()
    }

    /** 届いたフレームに対する応答を返す。応答しないフレームは null */
    fun handle(frame: ByteArray): ByteArray? {
        if (frame.size < 8 || !Modbus.isCrcValid(frame)) return null
        val slave = frame[0].toInt() and 0xFF
        val fn = frame[1].toInt() and 0xFF
        val addr = u16(frame, 2)
        val count = u16(frame, 4)
        if (slave == Modbus.SLAVE_AUX) return handleAux(fn, addr, frame)
        if (slave != Modbus.SLAVE_BMS) return null
        return when (fn) {
            Modbus.FN_READ -> {
                if (addr == 0) updateLive()
                if (addr in 256..399) historyPage(addr - 256) else readReply(Modbus.SLAVE_BMS, IntArray(count) { regs.getOrElse(addr + it) { 0 } })
            }
            Modbus.FN_WRITE_SINGLE -> {
                if (addr < regs.size && addr !in 143..146) regs[addr] = count
                deriveBmsValues()
                frame.copyOf()
            }
            Modbus.FN_WRITE_MULTI -> {
                for (i in 0 until count) {
                    val r = addr + i
                    if (r < regs.size && r !in 143..146 && 6 + i * 2 + 1 < frame.size - 2) regs[r] = u16(frame, 6 + i * 2)
                }
                deriveBmsValues()
                Modbus.withCrc(frame.copyOfRange(0, 6))
            }
            else -> null
        }
    }

    private fun handleAux(fn: Int, addr: Int, frame: ByteArray): ByteArray? {
        if (addr != ActualCapacity.REGISTER) return null
        return when (fn) {
            Modbus.FN_READ -> readReply(Modbus.SLAVE_AUX_REPLY, ActualCapacity.encode(actualCapacityMah))
            Modbus.FN_WRITE_MULTI -> {
                actualCapacityMah = ActualCapacity.decode(intArrayOf(u16(frame, 6), u16(frame, 8)))
                Modbus.withCrc(byteArrayOf(Modbus.SLAVE_AUX_REPLY.toByte(), 0x10) + frame.copyOfRange(2, 6))
            }
            else -> null
        }
    }

    /** 運転データを少しずつ動かす */
    private fun updateLive() {
        tick++
        val cells = 16
        val base = 3300 + seed * 3
        for (i in 0 until cells) {
            regs[i] = base + ((sin((i + seed) * 1.7) * 9).roundToInt()) + (if (i == 5) 11 else 0)
        }
        for (i in cells until 32) regs[i] = 0
        regs[32] = 65 + seed; regs[33] = 66; regs[34] = 64 + seed; regs[35] = 67
        val cellV = (0 until cells).map { regs[it] }
        regs[40] = (cellV.sum() / 100.0).roundToInt()
        val currentDeci = 52 + seed * 4 + (tick % 5)       // 放電 5.x A（値−30000 が正）
        regs[41] = 30000 + currentDeci
        val soc = (764 - seed * 37 - tick / 6).coerceAtLeast(50)
        regs[42] = soc
        regs[43] = cellV.max(); regs[44] = cellV.min()
        regs[45] = 67; regs[46] = 64
        regs[47] = 2
        regs[48] = (2800 * soc / 1000.0).roundToInt()
        regs[49] = cells; regs[50] = 4; regs[51] = 42 + seed
        regs[52] = regs[207]; regs[53] = regs[165]; regs[54] = regs[166]
        regs[55] = cellV.average().roundToInt(); regs[56] = cellV.max() - cellV.min()
        regs[57] = (regs[40] * 0.1 * currentDeci * 0.1).roundToInt()
        regs[58] = 0; regs[59] = 0; regs[60] = 0; regs[61] = 0
    }

    /** 履歴 1 ページ（7 件）。全 12 件 */
    private fun historyPage(page: Int): ByteArray {
        val total = 12
        val bytes = ByteArray(HistoryProtocol.PAGE_REGISTERS * 2)
        val events = intArrayOf(255, 8, 8, 32, 32, 5, 5, 254, 255, 130, 130, 253)
        for (k in 0 until HistoryProtocol.RECORDS_PER_PAGE) {
            val n = page * HistoryProtocol.RECORDS_PER_PAGE + k + 1
            if (n > total) break
            val o = k * HistoryProtocol.RECORD_BYTES
            fun p16(i: Int, v: Int) { bytes[o + i] = (v shr 8).toByte(); bytes[o + i + 1] = v.toByte() }
            p16(0, n); p16(2, total); p16(4, 26)
            bytes[o + 6] = 26; bytes[o + 7] = 10; bytes[o + 8] = (20 + n / 3).toByte()
            bytes[o + 9] = (8 + n).toByte(); bytes[o + 10] = (n * 4).toByte(); bytes[o + 11] = 0
            bytes[o + 12] = events[n - 1].toByte()
            bytes[o + 13] = (if (n % 2 == 0) 0 else 1).toByte()
            p16(14, 528); p16(16, 30000 + 40); p16(18, 700 - n * 10)
            bytes[o + 20] = 0x03
            p16(21, 3360); bytes[o + 23] = 6
            p16(24, 3290); bytes[o + 26] = 11
            bytes[o + 27] = 68; bytes[o + 28] = 2; bytes[o + 29] = 63; bytes[o + 30] = 3
            bytes[o + 31] = n.toByte()
        }
        val r = IntArray(HistoryProtocol.PAGE_REGISTERS) { ((bytes[it * 2].toInt() and 0xFF) shl 8) or (bytes[it * 2 + 1].toInt() and 0xFF) }
        return readReply(Modbus.SLAVE_BMS, r)
    }

    private fun readReply(slave: Int, values: IntArray): ByteArray {
        var body = byteArrayOf(slave.toByte(), Modbus.FN_READ.toByte(), (values.size * 2).toByte())
        for (v in values) body += byteArrayOf((v shr 8).toByte(), v.toByte())
        return Modbus.withCrc(body)
    }

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
}
