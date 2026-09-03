# FamilyHeartPlugin

仕様書 v1.2 の Phase 1〜7 を Kotlin で実装した版です。

実装範囲:
- Phase 1: SQLite、UUID/MCID、Relationship、ID、配偶者、親子、自動親登録、各種制限
- Phase 2: Request、承認/拒否、オンライン条件、ログアウト時キャンセル
- Phase 3: Chest GUI、メイン/家族/Relationship/申請/スキンシップ/設定画面
- Phase 4: Family Buff 条件、YAML設定、定期再計算
- Phase 5: hug/kiss/feed、距離・最寄り対象、家族外の承認、演出、クールダウン、feed Tab補完除外
- Phase 6: 管理者 CUI、Relationship 強制削除/初期化、Penalty、Audit Log、reload
- Phase 7: Relationship キャッシュ、非同期 DB、Prepared Statement、Transaction、SQLite single-writer transaction

設定:
- config.yml
- messages.yml
- gui.yml
- buffs.yml
- penalties.yml

ビルド:
`mvn clean package`

生成物:
`target/familyheart-plugin.jar`

Paper 26.2 / Java 25 / Maven を対象としています。
Maven がインストール済みなら、プロジェクトフォルダで `mvn clean package` を実行してください。


## 結婚申請（同性ペア対応）
`/fh marry [MCID] {husband|wife}` で申請者の役割を指定し、対象側は `/fh accept {husband|wife}` で自身の役割を指定して承認します。wife×wife / husband×husband / wife×husband をそのまま保存します。

## Maven build

このプロジェクトは Maven + Kotlin です。

```text
mvn clean package
```

生成物は `target/familyheartplugin.jar` です。

### `Cannot find main class nekouidaga.net.familyheartplugin.FamilyHeartPlugin` が出る場合

JAR が古い、または Kotlin のコンパイル結果が入っていない可能性があります。必ずこのプロジェクトのルートで `mvn clean package` を実行し、既存の同名 JAR を `plugins` から取り除いてから、新しく生成された `target/familyheartplugin.jar` を使用してください。

`plugin.yml` の main は次のクラスです。

```yaml
main: nekouidaga.net.familyheartplugin.FamilyHeartPlugin
```


Build: `mvn clean package`


## DB consistency (v3)

- Relationship mutations write to SQLite first and refresh affected relationship caches immediately after commit.
- 外部プラグイン・外部ツールからのDB直接変更は非推奨かつサポート対象外です。外部連携が必要な場合はFamilyHeart API/Serviceを経由してください。
- DB同期はFamilyHeart自身の書き込み、サーバー起動、Player Join、期限切れ処理を基準に行います。
- Relationship cache refreshes are serialized to prevent stale async reads from overwriting newer state.
- Pending request duplication is protected by both application checks and a SQLite unique pending key.
- Auto-parent relationships store their source spouse Relationship ID so divorce/reset removes only relationships created from that source.
- Economy charges for request-based relationship operations are applied on acceptance/success, not request creation.
- Buff clear/recompute removes actual PotionEffects as well as internal tracking.

## ネタ用カスタムアイテム申請

`config.yml` の `custom-item.command` に設定した専用サブコマンド（初期値 `hdb726yb`）で、`/fh <専用サブコマンド> <MCID>` を実行すると、`custom-items.yml` にそのMCID用アイテムが定義され、かつ対象プレイヤーがオンラインの場合のみ申請を作成します。対象プレイヤーが申請GUIで承認すると、設定されたアイテムを受け取ります。専用サブコマンドはTab補完には表示しません。

## Custom item data

`custom-items.yml` の `custom-model-data` は Paper 26.2 の Item Data Component として付与され、リソースパック側のカスタムテクスチャ識別に利用できます。さらにアイテムにはFamilyHeart固有のPDCとして `custom_item_id`、`custom_item_mcid`、`custom_item_request_id` を保存します。

`effects` を設定すると、申請承認時に対象プレイヤーへ指定したPotionEffectを付与します。`duration-ticks` または `duration-seconds`、`amplifier`、`ambient`、`particles`、`icon` を指定できます。


## Database

FamilyHeart uses SQLite for persistence. The database file (`familyheart.db` by default) is created automatically on first startup. HikariCP uses a single SQLite connection because SQLite serializes writes; plugin-side database work remains asynchronous. SQLite WAL mode, `foreign_keys=ON`, `synchronous=FULL`, and a configurable busy timeout are enabled.

Important state changes are committed transaction-by-transaction. A successful plugin-side operation is therefore persisted immediately after its DB transaction commits; the plugin does not depend on periodic full-cache polling for persistence.
