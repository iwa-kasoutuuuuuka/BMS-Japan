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

    /** 元アプリはこの文字列を含む名前の機器だけを一覧に出していた */
    const val NAME_HINT = "JHB"
}
