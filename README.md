# FamilyHeartPlugin

FamilyHeartPluginは、Paperサーバー向けの家族・Relationship管理プラグインです。

結婚、離婚、親子関係、スキンシップ、申請管理など、プレイヤー間のRelationshipを管理する機能を提供します。

## 対応環境

* Minecraft: 26.2
* Paper: 26.2
* Java: 25以上
* データベース: SQLite

### Soft Dependency

以下のプラグインに対応しています。

* Geyser
* Floodgate
* LuckPerms
* Vault

これらは必須依存ではありません。

## 主な機能

### 結婚

プレイヤー間で結婚関係を作成できます。

* 結婚申請
* 夫・妻の役割選択
* 同性婚
* 結婚情報の確認
* 離婚

夫・妻はRelationship上の役割として扱われます。

### 親子関係

プレイヤー間で親子関係を設定できます。

* 親からの申請
* 子からの申請
* 親子関係の確認
* 子供一覧の表示

### スキンシップ

プレイヤー同士で以下のアクションを利用できます。

* `hug`
* `kiss`
* `feed`

対象プレイヤーへの申請が必要なアクションについては、申請システムを介して処理されます。

### 申請管理

Relationshipに関する申請を管理できます。

申請には有効期限が設定されており、期限を過ぎた申請は自動的に失効します。

プレイヤーがログアウトした場合、そのプレイヤーに関連する処理中の申請も適切に処理されます。

申請状態はサーバーの稼働中にメモリ上で管理され、申請履歴は専用ログに記録されます。

### Relationship情報

プレイヤーのRelationship情報を確認できます。

確認できる主な情報:

* 配偶者
* 配偶者の役割
* 結婚期間
* 子供
* 子供の人数

他のプレイヤーの情報も、権限に応じて確認できます。

### GUI

GUIからFamilyHeartPluginの各機能を操作できます。

主なメニュー:

* 家族情報
* 配偶者情報
* 親子関係
* スキンシップ
* 申請一覧
* Relationship情報
* Info
* 設定

GUIにはページ移動や戻る操作が用意されています。

## コマンド

基本コマンド:

```text
/fh
```

Info:

```text
/fh info
/fh info <MCID>
/fh info <MCID> child
```

申請:

```text
/fh requests
/fh requests accept <ID>
/fh requests deny <ID>
```

詳細なコマンドは、サーバー上で `/fh` のヘルプを確認してください。

## MCID

FamilyHeartPluginでは、プレイヤーのMCIDをそのまま識別情報として扱います。

特にGeyser / Floodgate環境では、BedrockプレイヤーのMCIDが以下のような形式になる場合があります。

```text
.LisaLunaU1
```

この`.`を含め、MCIDは変更・正規化されません。

そのため、以下は別のMCIDとして扱われます。

```text
.LisaLunaU1
LisaLunaU1
```

## データ保存

FamilyHeartPluginではSQLiteを使用します。

データベースは以下に保存されます。

```text
plugins/familyheartplugin/data/familyheart.db
```

Relationshipやプレイヤー情報など、サーバー再起動後も保持する必要があるデータが保存されます。

申請のライブ状態はデータベースではなく、サーバーのメモリ上で管理されます。

## ログ

申請に関する操作は専用ログへ記録されます。

ログディレクトリ:

```text
plugins/familyheartplugin/logs/
```

現在のログ:

```text
plugins/familyheartplugin/logs/latest.log
```

ログは一定サイズに達すると自動的にローテーションされます。

ローテーション後のログ:

```text
plugins/familyheartplugin/logs/log-0001.log.gz
plugins/familyheartplugin/logs/log-0002.log.gz
```

ログにはプレイヤーを以下の形式で記録します。

```text
MCID(UUID)
```

例:

```text
.LisaLunaU1(00000000-0000-0000-0000-000000000000)
```

## 設定ファイル

主な設定ファイル:

```text
plugins/familyheartplugin/config.yml
plugins/familyheartplugin/gui.yml
plugins/familyheartplugin/messages.yml
plugins/familyheartplugin/buffs.yml
plugins/familyheartplugin/penalties.yml
```

### 申請有効期限

`config.yml`:

```yaml
request-expire-seconds: 300
```

単位は秒です。

### 子供一覧の表示条件

```yaml
info:
  child-list-threshold: 5
```

指定した人数以上の子供がいる場合、子供一覧画面を使用します。

### 子供一覧のページサイズ

```yaml
info:
  child-page-size: 7
```

1ページに表示する子供の人数を指定します。

## プラグインディレクトリ

標準的な構成は以下のとおりです。

```text
plugins/
└── familyheartplugin/
    ├── config.yml
    ├── gui.yml
    ├── messages.yml
    ├── buffs.yml
    ├── penalties.yml
    ├── data/
    │   └── familyheart.db
    └── logs/
        ├── latest.log
        ├── log-0001.log.gz
        └── log-0002.log.gz
```

## バックアップ

サーバーのバックアップを作成する場合、以下をバックアップ対象にしてください。

```text
plugins/familyheartplugin/
```

特に以下は重要です。

```text
plugins/familyheartplugin/data/familyheart.db
plugins/familyheartplugin/config.yml
plugins/familyheartplugin/gui.yml
plugins/familyheartplugin/messages.yml
```

Relationship情報を失わないため、SQLiteデータベースは定期的にバックアップすることを推奨します。

## 運営時の注意

### MCIDを変更しない

Floodgateを利用している場合を含め、MCIDを加工しないでください。

`.`などの接頭辞もMCIDの一部として扱われます。

### SQLiteを直接編集しない

通常の運用ではSQLiteデータベースを直接編集しないでください。

Relationshipデータに問題が発生した場合は、まずサーバーログおよびFamilyHeartPluginのログを確認してください。

### ログを保存する

申請やRelationshipに関する問題を調査する際には、以下のログが重要になります。

```text
plugins/familyheartplugin/logs/latest.log
```

必要に応じて、ローテーション済みの`.log.gz`も確認してください。

## トラブルシューティング

### プレイヤーが見つからない

以下を確認してください。

* MCIDが正確か
* Floodgateプレイヤーの場合`.`を含めているか
* 対象プレイヤーがオンラインか
* Geyser / Floodgateの状態に問題がないか

### 申請が表示されない

以下を確認してください。

* 申請が期限切れになっていないか
* 申請者または対象者がログアウトしていないか
* `/fh requests` で申請一覧を確認する
* `plugins/familyheartplugin/logs/latest.log` を確認する

### Relationship情報がおかしい

以下を確認してください。

1. FamilyHeartPluginのログ
2. Paperのサーバーログ
3. SQLiteデータベース
4. プレイヤーのMCIDとUUID
5. 関連する申請履歴

問題の再現条件とログを保存した上で調査してください。

## 権限

FamilyHeartPluginでは権限による機能制御に対応しています。

主な権限:

```text
familyheart.info
```

その他の権限については、導入しているバージョンの`plugin.yml`を確認してください。

## ライセンス

本ソフトウェアのライセンスについては、配布物に同梱されている`LICENSE`を確認してください。

## サポート

不具合を報告する場合は、可能な限り以下の情報を添えてください。

* Minecraft / Paperバージョン
* Javaバージョン
* FamilyHeartPluginバージョン
* 使用している関連プラグイン
* 発生した操作
* 発生した日時
* Paperのエラーログ
* FamilyHeartPluginの`latest.log`
* 再現手順

プレイヤーのUUIDやMCIDなどを含むログを外部へ公開する場合は、公開範囲に注意してください。
