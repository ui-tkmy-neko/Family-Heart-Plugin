# FamilyHeartPlugin

仕様書 v1.2 の Phase 1〜7 を Kotlin で実装した版です。

実装範囲:
- Phase 1: MySQL、UUID/MCID、Relationship、ID、配偶者、親子、自動親登録、各種制限
- Phase 2: Request、承認/拒否、オンライン条件、ログアウト時キャンセル
- Phase 3: Chest GUI、メイン/家族/Relationship/申請/スキンシップ/設定画面
- Phase 4: Family Buff 条件、YAML設定、定期再計算
- Phase 5: hug/kiss/feed、距離・最寄り対象、家族外の承認、演出、クールダウン、feed Tab補完除外
- Phase 6: 管理者 CUI、Relationship 強制削除/初期化、Penalty、Audit Log、reload
- Phase 7: Relationship キャッシュ、非同期 DB、Prepared Statement、Transaction、SELECT ... FOR UPDATE

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
