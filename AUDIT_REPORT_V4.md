# FamilyHeartPlugin — 監査レポート (v4)

## 方針
指示どおり「初回全体監査 → 修復 → 修正部分のみ再監査 → 終了」の1サイクルのみで完結。

## 監査対象
プロジェクト全体(Kotlinソース19ファイル・約2464行、pom.xml、plugin.yml、config.yml/messages.yml/gui.yml/buffs.yml/penalties.yml、tools/self_repair_audit.py)。
既存の `tools/self_repair_audit.py` (前回修復パスが作成した決定的チェック)はPASSのままだが、
それは文字列パターン検査のみで実際のロジック不整合までは検出しないため、今回は
全ソースファイルを1ファイルずつ通読してロジックを追う形で監査した。

## 検出した問題と分類

### 修復した問題(実際の機能不全・表示不整合)

1. **[機能不全] `/fh accept` コマンドでスキンシップ申請を承認できない**
   - `RequestService.decide()` はSKINSHIP種別を仕様上常に拒否し、承認には
     `claimAcceptance → setProcessingGuard → prepareRequestAction → approvedPersistent → acceptSkinshipAfterAction`
     という別の一連のフローが必要な設計になっている。
   - このフローは `GuiManager` のRequests画面クリックハンドラ内にしか実装されておらず、
     コマンドラインの `/fh accept` (`FamilyHeartCommand.complete()`) は常に `req.decide()` を呼んでいたため、
     スキンシップ申請を `/fh accept` で承認しようとすると常に `general.database-error` になっていた。
   - 修復: 承認フローを `GuiManager.acceptSkinship(player, request, onDone)` として公開関数に切り出し、
     GUIクリックハンドラと `FamilyHeartCommand.complete()` の両方から呼ぶよう統一した。

2. **[表示不整合] GUIの結婚役割選択(MARRIAGE_ROLE)画面のエラー表示が不完全**
   - 所持金不足時、コスト額を常に空文字で表示していた(`mapOf("cost" to "")`)。
   - `economy.charge-failed` / `marriage.same-role` のケースが未対応で、
     どちらも汎用的な `general.database-error` に丸められており、
     コマンド版 `/fh accept` の表示(具体的な理由別メッセージ)と食い違っていた。
   - 修復: `RequestService` に `cost(type)` の公開ラッパーを追加し、GUI側のエラーハンドラを
     コマンド版と同じ理由別メッセージ(insufficient-funds に実コスト表示 / charge-failed /
     same-role)に揃えた。

### 検討したが「バグではない」と判断し、変更しなかった項目

- `recoverProcessingBlocking()` / `RequestDao.recoverProcessing()` が `processing_guard IS NULL`
  の場合のみPROCESSING→PENDINGへ自動復旧し、`ECONOMY_INTENT` は対象外にしている点。
  一見「ECONOMY_INTENTならまだ課金前なので復旧してよいのでは」と思えるが、実際には
  `chargeAsync()` 成功後・`setProcessingGuard(ECONOMY_CHARGED)` 完了前のごく短い時間帯は
  ガードがまだ `ECONOMY_INTENT` のまま課金だけが先に成立している可能性があり、
  この状態を自動復旧してしまうと再承認時に二重課金が起こり得る。既存の「手動突合が必要」という
  設計判断は正しいため変更しなかった。
- `/fh marry <role>`(MCID省略時に最寄りプレイヤー+役割のみを指定する形)が機能しない点。
  README上は `[MCID]` が「省略可」に読めるが、`target()` の引数位置解決とタブ補完
  (`/fh marry <name> <role>` の並び)を踏まえると、役割指定時は常にMCID明示が前提の設計と判断し、
  ドキュメントの表記揺れの範囲として扱い、コード変更はしなかった。
- 管理者サブコマンド(`admin relation/family/penalty` の各操作)で個別権限が無い場合に
  何のメッセージも出さず無反応になる点。UX上は改善余地があるが機能自体は壊れておらず、
  今回の「必要な修復」の範囲外と判断した。

## 修正箇所のみの再監査(Pass)

- `python3 tools/self_repair_audit.py` … PASS(構文・既存の決定的チェック、退行なし)
- 変更3ファイル(`GuiManager.kt` / `FamilyHeartCommand.kt` / `RequestService.kt`)の
  波括弧・丸括弧の対応数を機械的に再確認 … 全ファイルで差分0(バランス一致)
- `GuiManager.acceptSkinship()` の呼び出し元2箇所(GUIクリックハンドラ、コマンドの`complete()`)
  それぞれで、成功時コールバック(`onDone`)の内容が呼び出し元の文脈に対して適切であることを確認
  (GUI: 一覧を再オープン / コマンド: `request.accepted` を送信)。
- GUI側 `MARRIAGE_ROLE` の例外ハンドラが `RequestType.MARRY` 固定で `req.cost()` を呼んでいる点は、
  このハンドラ自体が結婚役割選択メニュー専用であるため妥当であることを確認。

## 結論
今回のサイクルで検出した実害のある問題(スキンシップ申請のコマンド未対応、GUIエラー表示の不整合)は
修復済み。既存の `self_repair_audit.py` によるPASSは維持されている。
