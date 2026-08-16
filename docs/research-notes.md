# 調査メモ：CompletableFutureの例外ラップと原因型

## 既存題材との重複調査

`tonbiattack/qiita`、既存GitHubラボ、ローカルJavaプロジェクトを`CompletableFuture`、`CompletionException`、`exceptionally`、`handle`で検索した。

| 既存題材 | 主題 | 今回との差分 |
|---|---|---|
| `java-spring-async-self-invocation-lab` | Spring AOP proxyを経由しない`@Async`呼び出し | 今回はSpringを使用しない。`CompletableFuture`の例外表現と原因型の分類を扱う。 |
| `java-optional-eager-fallback-lab` | `Optional.orElse`と`orElseGet`の評価タイミング | 非同期・例外型・リトライ判定を扱わない。 |
| `java-threadlocal-context-leak-debug-lab` | スレッドプールでのThreadLocal残留 | 実行コンテキストではなく、例外ラップと原因型の保持を扱う。 |
| qiita記事・既存ラボの検索結果 | CompletableFutureの例外原因喪失を直接扱う記事・ラボは検出されなかった。 | 題材の重複なし。 |

## 公式JDK資料の根拠

| 資料 | 確認した契約 | 利用する説明 |
|---|---|---|
| [CompletableFuture — Java SE 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html) | `handle`は正常・例外完了のどちらでも、結果と例外を引数として実行される。`join()`は例外完了時に`CompletionException`を直接送出する。 | `handle`の例外引数をドメイン例外とみなせない理由。 |
| [CompletionException — Java SE 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletionException.html) | 非同期結果・タスクの完了中にエラーや例外が発生したときの例外であり、causeを保持するコンストラクタを持つ。 | `getCause()`で原因例外を辿る根拠。 |

## 採用する題材

`CatalogGateway.lookup(String)`が、在庫カタログの一時障害を`RETRY`として返す契約を扱う。失敗する`RemoteCatalog`は`InventoryUnavailableException`を投げる。バグ状態は`handle`で得た`Throwable`を直接判定するため、`CompletionException`が来ると`UNKNOWN`になり、原因もラッパーのまま結果へ保持される。

修正は、CompletionExceptionだけを1層unwrapし、原因例外に対して分類と保持を行うことに限定する。例外を握りつぶしたり、すべてをリトライ可能にしたり、Springの例外変換へ置き換えたりはしない。
