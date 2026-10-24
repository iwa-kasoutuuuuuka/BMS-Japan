<p align="center">
  <img src="docs/icon.svg" width="128" alt="BMS Japan アイコン">
</p>

<h1 align="center">BMS Japan</h1>

<p align="center">
  Balance BMS（アクティブバランサー付きリチウム電池 BMS）を<br>
  Bluetooth で見るための、日本語の Android アプリ
</p>

---

## これは何？

中国メーカーの公式アプリ「Balance BMS」（`com.inuker.bluetooth.junhengbao`）は日本語に対応していません。
このアプリは、その通信方式を調べて **日本語で一から作り直したもの** です。

- 日本語の画面
- Bluetooth（BLE）だけで動く。アカウント登録やクラウドは不要
- 軽い（元アプリにあった地図や React Native などの大きなライブラリを使っていない）

> **現在は雛形（v0.1.0）です。** BMS を探す・接続する・ライブデータを見る、までできます。
> 設定の変更や履歴の表示はまだありません。

## できること

| 画面 | 内容 |
|---|---|
| BMSを探す | 近くの BMS をスキャン（名前に `JHB` を含む機器を優先表示）。タップで接続 |
| ライブデータ | SOC、総電圧、電流、電力、残り容量、サイクル回数、セルごとの電圧（最高・最低を色分け）、温度、充電/放電 MOS と均等化の ON/OFF、アラーム（約50種類を日本語表示）。5秒ごとに自動更新 |

## 動作環境

- Android 8.0（API 26）以上、Bluetooth LE 対応の端末
- Balance BMS 対応の BMS（BLE モジュール付き）

## ビルド方法

必要なもの: Android Studio（または Android SDK 36.1 と JDK 17 以上）

```bash
git clone https://github.com/iwa-kasoutuuuuuka/BMS-Japan.git
cd BMS-Japan
./gradlew assembleDebug
```

APK は `app/build/outputs/apk/debug/app-debug.apk` にできます。

単体テスト（通信フレームの組み立てと解析）:

```bash
./gradlew testDebugUnitTest
```

## 仕組み

BMS とは BLE の上で **Modbus RTU** に似た形式でやり取りします。

```
アプリ ──(0000fff2 に書き込み)──▶ BMS     D2 03 0000 003E D7B9  … レジスタ 0〜61 を読む
アプリ ◀──(0000fff1 の Notify)── BMS      D2 03 7C [124バイト] CRC
```

- スレーブアドレス `0xD2`、CRC は Modbus CRC16
- 接続後に時刻を同期し、5秒ごとに運転データを読み出し
- 詳しいレジスタ表は [docs/ble_protocol.md](docs/ble_protocol.md) にあります

### ソースの構成

```
app/src/main/java/jp/bmsjapan/app/
├── protocol/   通信フレームの生成・受信データの組み立て・解析（Android に依存しない）
├── ble/        スキャンと GATT 接続（操作は 1 つずつ順番に実行）
└── ui/         Jetpack Compose の画面と ViewModel
```

## 今後の予定

- [ ] 設定値の表示と変更（保護電圧・電流・温度など。パスワード確認つき）
- [ ] 電池の種類（リン酸鉄 / 三元系 / チタン酸）のプリセット
- [ ] 充電 / 放電 MOS の ON/OFF 操作
- [ ] 故障履歴の表示
- [ ] 複数台の接続（並列・直列）

## 注意

- **非公式アプリです。** 元アプリのメーカーとは関係ありません。
- 通信方式は公開されている Android アプリを解析して調べたもので、すべての機種で動く保証はありません。
- 今のバージョンは **読み取りだけ** で、BMS の設定は変更しません。設定変更の機能を使う場合は、電池の仕様を確認したうえで自己責任でお使いください。

## ライセンス

MIT License
