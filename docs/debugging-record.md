# デバッグ記録: CompletableFutureの例外ラップでリトライ判定が失われる問題

## 目的

Java 21で、`CompletableFuture`が失敗を`CompletionException`として表す境界により、元のドメイン例外を直接分類できずリトライ判定が失われる理由を、実行可能な最小例で確認する。

> 契約: `InventoryUnavailableException`で失敗するカタログ取得に対して、元の例外を保持した`RETRY`を得る。バグ状態では`UNKNOWN`と`CompletionException`になる。

## 実行環境と再現境界

| 項目 | 内容 |
| --- | --- |
| 言語処理系 | Java 21 |
| 難易度プロファイル | 実践・上級。直接の判定結果に加え、後続処理が受け取る原因例外を観測し、例外ラップ・Executor・成功入力の競合仮説を比較する。 |
| ビルド・テスト方法 | `mvn clean test` |
| 使用する依存関係 | Java標準ライブラリ、JUnit Jupiter 5.11.4 |
| 使用しないもの | Spring Boot、DI、HTTP、DB、外部サービス、実ネットワーク |
| 公開境界 | `CatalogGateway.lookup(String)` |
| 最終観測 | `LookupResult.outcome()`と`LookupResult.cause()` |
| 決定性の確保 | `Runnable::run`をExecutorに使い、sleep・乱数・外部接続を使わない。 |

この境界を選んだ理由は、非同期サービス層で起こり得る例外分類の問題を、フレームワークを介さずJava標準ライブラリの`CompletableFuture`だけで直接観測できるためである。

## 最初に観測した事実

| 観測順 | 事実 | 得られた証拠 |
| --- | --- | --- |
| 1 | 入力は`InventoryUnavailableException("inventory-replica-unavailable")`で失敗する固定`RemoteCatalog`だった。 | `CatalogGatewayTest`のArrange節。 |
| 2 | `CatalogGateway.lookup("sku-42")`の直接結果は`UNKNOWN`だった。 | `expected: <RETRY> but was: <UNKNOWN>`。 |
| 3 | `handle`内で観測した例外型は`CompletionException`、そのcause型は`InventoryUnavailableException`だった。 | `evidence/01-broken-test-output.txt`の`observed_error_type`出力。 |
| 4 | 結果に保持された原因は元の例外ではなく`CompletionException`だった。 | `assertSame`の失敗差分。 |

バグ状態のコミットは`a35d951`である。`mvn test`を実行すると、`Multiple Failures`として`RETRY`と元の原因例外を期待するアサーションが失敗する。依存解決、設定、無関係なコンパイル失敗は、この観測に含めない。

## 競合仮説と検証

| 仮説 | 予測 | 検証 | 結果 |
| --- | --- | --- | --- |
| `handle`が元のドメイン例外を直接受け取る | `error instanceof InventoryUnavailableException`が真で、結果は`RETRY`になる | 失敗入力で`LookupResult.outcome()`を確認する | `UNKNOWN`だったため除外。 |
| 失敗が`CompletionException`に包まれ、直接の型判定が失敗する | 観測例外は`CompletionException`で、`getCause()`がドメイン例外になる | `handle`内で外側とcauseの型を出力する | 外側は`CompletionException`、causeは`InventoryUnavailableException`で支持。 |
| Executorやスケジューリングが失敗原因 | 成功入力も失敗するか、結果が不安定になる | 同じ直接Executorで成功入力を実行する | `FOUND`とSKUを得たため除外。 |

## 確定した原因

`CompletableFuture.handle`は、元のstageが正常・例外完了のどちらでも結果と例外を引数にして実行される。[1] このラボの`RemoteCatalog`は`CompletableFuture.supplyAsync`のSupplier内で`InventoryUnavailableException`を投げるため、`handle`で観測した例外は`CompletionException`だった。

`CompletionException`は、完了中に発生したエラーまたは例外を表す実行時例外であり、causeを保持するコンストラクタを持つ。[2] 不具合状態はこの外側の`CompletionException`を直接`instanceof InventoryUnavailableException`で分類した。したがって、ドメイン例外に基づく`RETRY`判定と、原因例外を保持する後続処理の両方が失われた。

この結論は、上の最小実験と[CompletableFuture API][1]、[CompletionException API][2]の両方で裏づける。ラボ内で直接観測した事実は例外型とアサーション差分であり、ラップ例外の一般的意味は公式文書から説明している。

## 最小修正

`CatalogGateway`に、`CompletionException`かつcauseが存在する場合だけ一層unwrapする小さな関数を追加した。

```java
private Throwable unwrapCompletionException(Throwable error) {
    if (error instanceof CompletionException && error.getCause() != null) {
        return error.getCause();
    }
    return error;
}
```

`handle`では、この戻り値をリトライ判定と`LookupResult.cause()`へ渡す。修正は外側の例外表現を原因例外に戻す一点だけを対象にする。Springの例外変換、HTTPリトライ、バックオフ、依存追加、公開APIの変更は含めない。修正コミットは`ce19844`である。

## 回帰保証

| 守ること | テストまたは診断 | 修正後の結果 |
| --- | --- | --- |
| 一時的な在庫障害をリトライ対象にする | `unavailableInventory_keepsDomainCauseAndRequestsRetry` | `RETRY`になり、元の`InventoryUnavailableException`を保持。 |
| 成功時に例外原因を持たない | `availableInventory_returnsFoundWithoutCause` | `FOUND`、SKU、`null` causeを確認。 |
| 外側のラップと原因を区別する | 固定Executor下の観測出力 | `CompletionException`と`InventoryUnavailableException`を別々に確認。 |

固定済みの状態で、`mvn clean test`を実行し、2テスト成功、失敗0、エラー0を確認した。

## 再現手順

```bash
# 修正済み状態を検証する
mvn clean test

# バグ状態を確認する。作業中の変更は先に退避する
git switch --detach a35d951
mvn test

# 修正済み状態へ戻る
git switch main
```

## スコープと注意点

このラボは、Java 21の`CompletableFuture`で発生した`CompletionException`を一層unwrapしてドメイン例外を分類する条件に限って再現・修正を確認した。複数層のラップ、`get()`が送出する`ExecutionException`、キャンセル、タイムアウト、スタックトレースの記録、例外を使わないエラー表現、性能、セキュリティ、Spring統合には同じ結論を自動的に拡張しない。

## References

[1]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html "CompletableFuture — Java SE 21"
[2]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletionException.html "CompletionException — Java SE 21"
