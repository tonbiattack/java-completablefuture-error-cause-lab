# 題材企画: CompletableFutureの例外ラップでリトライ判定が失われる問題

## 対象

| 項目 | 内容 |
| --- | --- |
| 対象言語 | Java 21 |
| 対象読者 | Javaで`CompletableFuture`を使う実務開発者。Springの非同期サービス層でも同じ標準ライブラリの挙動に遭遇する読者。 |
| 難易度プロファイル | 実践・上級 |
| 選定理由 | 戻り値だけでなく、後続のリトライ判定と原因型を観測し、`CompletionException`による例外表現の境界を照合する必要があるため。 |
| 実行基盤 | JDK 21、Maven、JUnit Jupiter 5.11.4。 |
| フレームワーク非依存性 | Spring Boot、DI、HTTP、DBは使用しない。`CompletableFuture`、`CompletionException`、`Executor`はすべてJava標準ライブラリである。 |

## 学習する契約

> 入力「`InventoryUnavailableException`で失敗する非同期カタログ取得」に対して、期待する結果は「元の例外型を保持した`RETRY`判定」だが、バグ状態では「`CompletionException`を直接分類して`UNKNOWN`判定」になる。

### 対象の直接原因

`CompletableFuture.supplyAsync`で発生した例外は完了時に`CompletionException`として表現され得る。`handle`で受け取った`Throwable`を直接`instanceof InventoryUnavailableException`で判定するため、原因例外をunwrapせずリトライ可能性を失う。

### 対象外

Springの`@Async`、TaskExecutor、HTTPリトライ、指数バックオフ、永続化、外部API、タイムアウト、キャンセル、監視基盤は扱わない。

## 再現設計

| 要素 | 決定 |
| --- | --- |
| 公開境界 | `CatalogGateway.lookup(String)` |
| 入力・初期状態 | `InventoryUnavailableException`を投げる固定`RemoteCatalog`。成功ケースには固定SKU名を返す。 |
| Redの観測 | `RETRY`を期待する契約テストが`UNKNOWN`で失敗する。 |
| 最終観測 | `LookupResult.cause()`がドメイン例外型を保持することを別アサーションで確認する。 |
| 決定性 | `Runnable::run`の直接Executorを使い、sleep・外部接続・乱数を使わない。 |
| 固定状態の検証コマンド | `mvn clean test` |
| バグ状態の確認コマンド | `mvn test` |

## 仮説

| 仮説 | どう検証または除外するか |
| --- | --- |
| `handle`が元の`InventoryUnavailableException`を直接受け取る | 失敗結果の`cause`型とリトライ判定を確認する。 |
| `supplyAsync`の失敗が`CompletionException`に包まれ、直接の型判定が失敗する | `cause`と`cause.getCause()`の型を観測し、unwrap後の判定を比較する。 |
| Executorやスケジューリングが失敗原因である | 同じ直接Executorで成功・失敗入力を比較し、成功ケースが正しく`FOUND`になることを確認する。 |

## 予定する履歴

| 順序 | コミットの目的 | 期待する状態 |
| --- | --- | --- |
| 1 | CompletableFuture例外ラップのバグを再現する | 契約テストが`UNKNOWN`判定とラップ例外により失敗する。 |
| 2 | 元の例外型を保持してリトライ判定する | 同じ検証が成功し、全体も成功する。 |
