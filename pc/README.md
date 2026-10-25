# BMS Japan（PC 版）

Balance BMS（アクティブバランサー付きのリチウム電池用 BMS。元アプリは `com.inuker.bluetooth.junhengbao`）を、
Windows などの PC から Bluetooth LE で見たり設定したりするための日本語ツールです。Android 版 BMS Japan と同じプロトコル実装を Python に移植しています。

- **CLI**（`bms-japan`）: ターミナルから運転データ・設定・履歴などを表示／変更
- **MCP サーバー**（`bms-japan-mcp`、stdio）: Claude Code や Google Antigravity のエージェントから操作
- **デモモード**: 実機が無くても、本物と同じバイト列で応答する疑似 BMS（3 台）で全機能を試せます

> ⚠ **非公式ツールです。** 実機での動作確認はまだしていません（プロトコルは元アプリの解析に基づきます。詳しくは `../docs/ble_protocol.md`）。
> 設定の書き込みは BMS の保護動作を変えます。内容を理解したうえで、自己責任で使ってください。

## 動作環境

- Python 3.10 以上（3.12 で確認）
- Windows 10/11（Bluetooth LE 対応のアダプター）。macOS / Linux（BlueZ）でも bleak が動けば使えるはずです（未確認）

## インストール

```powershell
cd D:\100BMS\BMS-Japan\pc
py -3.12 -m venv .venv
.venv\Scripts\python.exe -m pip install -e ".[dev]"
```

これで `.venv\Scripts\bms-japan.exe` と `.venv\Scripts\bms-japan-mcp.exe` ができます。
（以下の例では `bms-japan` と書きます。venv を有効にするか、フルパスで実行してください。）

テスト:

```powershell
.venv\Scripts\python.exe -m pytest
```

## CLI の使い方

| コマンド | 内容 |
|---|---|
| `bms-japan scan [--all]` | BMS を探す（名前に「JHB」を含む機器。`--all` で全部） |
| `bms-japan live [-a ADDR] [--watch] [--interval 5]` | 運転データ（SOC・電圧・電流・セル電圧・温度・MOS・アラーム） |
| `bms-japan settings show` | 設定値（保護値と解除値、電池の種類、スイッチ） |
| `bms-japan settings set KEY VALUE [--password P] [--yes]` | 設定値を 1 つ変更 |
| `bms-japan switch charge\|discharge\|balance on\|off [--yes]` | 充電MOS・放電MOS・均等化の ON/OFF |
| `bms-japan battery-type lfp\|ncm\|lto [--yes]` | 電池の種類（保護値がまとめて変わります） |
| `bms-japan password change [--old X --new Y] [--yes]` | パスワード変更（6 桁の数字） |
| `bms-japan history [--page 0] [--pages 4] [--all]` | 故障履歴（1 ページ 7 件） |
| `bms-japan capacity show` / `capacity set AH [--yes]` | 実容量（Ah） |
| `bms-japan pack ADDR1 ADDR2 ... --mode parallel\|series` | 複数台のまとめ（最大 6 台） |
| `bms-japan --demo demo-reset` | デモ機器の状態を初期値に戻す |

共通オプション: `--demo`（疑似 BMS を使う）、`--json`（JSON で出力）、`--timeout 3`（応答待ち秒数）、`-v`（通信の詳細）。
アドレスは `-a` のほか環境変数 `BMS_JAPAN_ADDRESS`、パスワードは `BMS_JAPAN_PASSWORD` でも指定できます。
アドレスを省略すると、近くに BMS が 1 台だけならそれを使います。

例:

```powershell
bms-japan scan
bms-japan live -a 12:34:56:78:9A:BC
bms-japan settings set cell_ov 3.65 --yes
bms-japan switch discharge off --password 654321 --yes
bms-japan history --all --json
bms-japan pack 12:34:56:78:9A:BC 12:34:56:78:9A:BD --mode parallel
```

設定項目のキー（`settings show` の左端）: `cell_ov` `cell_uv` `pack_ov` `pack_uv` `cell_diff` `charge_oc` `discharge_oc`
`charge_ot` `charge_ut` `discharge_ot` `discharge_ut` `temp_diff` `mos_ot` `rated_capacity` `cell_nominal` `soc`
`sleep_wait` `balance_start_v` `balance_start_diff` `balance_current`（値は表示と同じ単位: V, A, ℃, Ah, %, 秒）。

### 書き込みの安全策（CLI・MCP 共通）

1. 項目ごとの安全な範囲を確認（例: セル過電圧 2.0〜4.5 V）。保護値が解除値をまたぐ値（例: 過電圧の保護値 ≤ 解除値）は書き込みません
2. BMS のパスワードが初期値 `123456` から変わっていれば、正しいパスワードが必要です（元アプリと同じく、BMS から読んだパスワードと照合します）
3. `--yes`（MCP では `confirm: true`）が無いと書き込まず、書き込む予定の内容だけを表示します。CLI は画面から実行したときだけ `y/N` で確認します
4. 書き込み後に読み直して、一致したか（`verified`）を表示します

### 文字化けするとき

画面に直接出すときは Windows の設定に従い、パイプやファイルに出すとき（エージェントから実行したときなど）は UTF-8 で出力します。
PowerShell のパイプで化ける場合は `$env:BMS_JAPAN_ENCODING="cp932"` などで変えられます。

## デモモード（実機なしで試す）

`--demo` または環境変数 `BMS_JAPAN_DEMO=1` で、疑似 BMS（`DEMO-01` 充電中 / `DEMO-02` 放電中 / `DEMO-03` 待機・アラーム 1 件）を使います。
16 セル LFP、パスワード 123456、実容量 280 Ah、故障履歴 10 件。実機と同じフレーム（CRC・20 バイトごとの分割）でやり取りするので、
プロトコルのコードもそのまま動きます。

```powershell
bms-japan --demo live
bms-japan --demo settings set cell_ov 3.70 --yes
bms-japan --demo pack DEMO-01 DEMO-02 DEMO-03 --mode series
bms-japan --demo demo-reset
```

CLI のデモでは書き込んだ内容が `%TEMP%\bms_japan_demo_state.json` に残ります（`BMS_JAPAN_DEMO_STATE` で場所を変更可）。
MCP サーバーのデモはプロセスが動いている間だけ残ります。

## MCP サーバー

起動コマンド: `D:/100BMS/BMS-Japan/pc/.venv/Scripts/python.exe -m bms_japan.mcp_server`（stdio）

### ツール

| ツール | 内容 |
|---|---|
| `scan_devices` | BMS を探す |
| `connect` / `disconnect` / `list_connections` | 接続の管理（一度つなげば何度でも呼べます。接続中は 5 秒ごとに運転データを読みます） |
| `get_live_data` | 運転データ（値・単位・日本語ラベル付きの JSON） |
| `get_settings` | 設定値 |
| `get_history` | 故障履歴（`page`, `pages`。`next_page` が null なら終わり） |
| `get_actual_capacity` | 実容量 |
| `get_pack_summary` | 複数台のまとめ（parallel / series） |
| `set_setting` / `set_switch` / `set_battery_type` / `change_password` / `set_actual_capacity` | 書き込み。**`confirm: true` が必要**。初期値以外のパスワードなら `password` も必要 |

`address` を省略すると、接続中の 1 台を使います。エラーは `[コード] 日本語メッセージ / English` の形で返ります。

### Claude Code に登録する

このリポジトリには、プロジェクト用の設定 `.mcp.json`（パスはリポジトリからの相対パス）が入っています（`bms-japan` = 実機、`bms-japan-demo` = デモ）。
リポジトリのフォルダで Claude Code を起動すると、初回に使ってよいか確認されます。

```json
{
  "mcpServers": {
    "bms-japan": {
      "type": "stdio",
      "command": "D:/100BMS/BMS-Japan/pc/.venv/Scripts/python.exe",
      "args": ["-m", "bms_japan.mcp_server"],
      "env": {}
    },
    "bms-japan-demo": {
      "type": "stdio",
      "command": "D:/100BMS/BMS-Japan/pc/.venv/Scripts/python.exe",
      "args": ["-m", "bms_japan.mcp_server"],
      "env": { "BMS_JAPAN_DEMO": "1" }
    }
  }
}
```

コマンドで登録する場合（どのフォルダからでも使いたいときは `--scope user` を付けます）:

```powershell
claude mcp add bms-japan -- D:/100BMS/BMS-Japan/pc/.venv/Scripts/python.exe -m bms_japan.mcp_server
# 実機なしで試す
claude mcp add bms-japan-demo -e BMS_JAPAN_DEMO=1 -- D:/100BMS/BMS-Japan/pc/.venv/Scripts/python.exe -m bms_japan.mcp_server
```

### Google Antigravity に登録する

Antigravity は Claude Desktop と同じ `mcpServers` 形式の `mcp_config.json` を使います。
エージェント画面の「…」→ **MCP Servers** → **Manage MCP Servers** → **View raw config** で開くのが確実です。
ファイルの場所はバージョンによって違い、`%USERPROFILE%\.gemini\antigravity\mcp_config.json`（従来）と
`%USERPROFILE%\.gemini\config\mcp_config.json`（Antigravity 2.0 の共通設定）の 2 つがあります（この PC には両方ありました。どちらが読まれるかは未確認なので、UI から開いたほうを編集してください）。
既にある `mcpServers` の中に、次の項目を追加します:

```json
{
  "mcpServers": {
    "bms-japan": {
      "command": "D:/100BMS/BMS-Japan/pc/.venv/Scripts/python.exe",
      "args": ["-m", "bms_japan.mcp_server"],
      "env": {}
    }
  }
}
```

実機なしで試すときは `"env": { "BMS_JAPAN_DEMO": "1" }` にします。保存後、MCP Servers の画面で **Refresh** してください。

### 環境変数

| 変数 | 内容 |
|---|---|
| `BMS_JAPAN_DEMO=1` | デモモード |
| `BMS_JAPAN_DEMO_STATE` | デモの状態を保存するファイル |
| `BMS_JAPAN_TIMEOUT` | MCP の応答待ち秒数（既定 3。3 回まで送り直し） |
| `BMS_JAPAN_LOG` | MCP のログレベル（stderr に出力。例: `DEBUG`） |
| `BMS_JAPAN_ADDRESS` / `BMS_JAPAN_PASSWORD` / `BMS_JAPAN_ENCODING` | CLI 用 |

## 注意点

- **書き込みは BMS の保護を変えます。** 誤った値は電池の過充電・過放電・発熱につながるおそれがあります。エージェントに書き込みをさせるときは、必ず内容を確認してから許可してください
- **電流の向き（符号）は実機で未確認です。** 電流は大きさだけを表示し、充電・放電はレジスタ 47（状態）で示します（`raw_signed` に生の符号も入れています）
- 計測ボードのセル数・温度センサー数は、誤ると計測が狂うため表示のみです
- 実容量の応答（スレーブ 0x51）は元アプリと同じく CRC を確認しませんが、既知の 2 つの形（4 バイトの読み出し応答・書き込み応答）だけを受け付けます
- 複数台の「直列」の残り容量は、元アプリ（合計）と違い、最も少ない台の値にしています
- ファームウェア更新には対応していません

### Windows の Bluetooth について

- 設定 → Bluetooth とデバイス で Bluetooth をオンにしてください。BMS を Windows で「ペアリング」する必要はありません（BLE で直接つなぎます）
- 元アプリやスマホが BMS につながっていると、PC からはつなげません。スマホ側のアプリを閉じてください
- 見つからないときは `bms-japan scan --all` で周りの機器を全部表示し、BMS に近づけて試してください
- Android 版は MTU 512 を要求しますが、Windows では MTU を選べません。応答あり書き込み（最大 512 バイト）で送るので、通常は問題ない想定です（実機未確認）

## 構成

```
pc/
  src/bms_japan/
    protocol/      BLE に依存しない部分（Modbus フレーム・CRC・受信の組み立て・運転データ・設定・履歴・まとめ）
    transport/     ble.py（bleak）、demo.py（疑似 BMS につなぐ）
    simulator.py   疑似 BMS
    client.py      1 台とのやり取り（1 回に 1 組の送受信・再送・時刻同期・定期読み出し）
    service.py     CLI と MCP 共通の操作（入力チェック・パスワード・確認・読み直し）
    cli.py         bms-japan コマンド
    mcp_server.py  MCP サーバー
    strings_ja.py  日本語の文言（Android 版の strings.xml から tools/gen_strings.py で生成）
  tests/           Kotlin 版のテストの移植と、疑似 BMS を使った通し試験・MCP の起動試験
```

日本語の文言を Android 版と合わせ直すとき（リポジトリのルートで実行）:

```powershell
python pc/tools/gen_strings.py app/src/main/res/values/strings.xml pc/src/bms_japan/strings_ja.py
```
