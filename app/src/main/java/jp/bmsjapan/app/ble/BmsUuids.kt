package jp.bmsjapan.app.ble

import java.util.UUID

object BmsUuids {
    val SERVICE: UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    val NOTIFY: UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb")
    val WRITE: UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb")

    /** B40 モジュールの認証用サービス（無い機種もある） */
    val MODULE_SERVICE: UUID = UUID.fromString("02f00000-0000-0000-0000-00000000fe00")
    val MODULE_AUTH: UUID = UUID.fromString("02f00000-0000-0000-0000-00000000ff05")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** 元アプリはこの文字列を、名前か広告データに含む機器を一覧に出していた */
    const val NAME_HINT = "JHB"

    private val JHB_BYTES = byteArrayOf(0x4A, 0x48, 0x42)

    /**
     * 広告データ（スキャンレコードの生バイト）に "JHB" が入っているか。
     * 実機では名前が "LEAF_005" などで、メーカー固有データの末尾が 4A 48 42 の機種がある。
     */
    fun advContainsJhb(record: ByteArray?): Boolean {
        if (record == null || record.size < 3) return false
        for (i in 0..record.size - 3) {
            if (record[i] == JHB_BYTES[0] && record[i + 1] == JHB_BYTES[1] && record[i + 2] == JHB_BYTES[2]) return true
        }
        return false
    }
}
