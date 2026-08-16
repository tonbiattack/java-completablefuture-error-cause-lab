# Javaの`CompletableFuture`でリトライ判定が消える理由：`CompletionException`の原因を最小再現から追う

非同期の依存先が一時的に使えないとき、呼び出し側はリトライ可能な障害として扱いたいことがあります。Javaの`CompletableFuture`で結果を`handle`するコードは、次のように素直に書けます。

```java
future.handle((value, error) -> {
    if (error instanceof InventoryUnavailableException) {
        return LookupResult.retry(error);
    }
    return LookupResult.unknown(error);
});
```

しかし、`InventoryUnavailableException`を投げたにもかかわらず、この判定は`UNKNOWN`になります。原因は、`handle`が受け取る`Throwable`がドメイン例外そのものではなく、`CompletionException`としてラップされているためです。

本稿ではJava 21の標準ライブラリだけで、例外の外側の型、cause、後続のリトライ判定をそれぞれ観測します。Springの`@Async`やTaskExecutor、HTTP、DBは使いません。ただし、Springアプリケーションのサービス層で`CompletableFuture`を返す場合にも、同じJava標準ライブラリの境界で起きる問題です。

## この記事で守る契約

カタログ取得が`InventoryUnavailableException`で失敗した場合、ゲートウェイは次の2つを同時に満たす必要があります。

| 観測点 | 期待 |
|---|---|
| 直接の結果 | `LookupOutcome.RETRY` |
| 後続処理が受け取る原因 | 元の`InventoryUnavailableException`インスタンス |

値だけを返す処理なら、`RETRY`だけを確認してもよいかもしれません。しかし実務では、後続のログ、障害分類、リトライ方針が原因型を読むことがあります。そのため、このラボでは判定値と原因例外を別々にアサートします。

## 既存題材との差分

既存のJava教材には、Springの`@Async`でself-invocationがAOP proxyを迂回する問題があります。そちらはSpring Beanの呼び出し境界と実行スレッドを扱います。

今回の題材はSpringを一切使いません。`Runnable::run`という直接Executorを使い、**`CompletableFuture`の例外表現と原因例外の分類**だけを扱います。非同期に見えるサービス層の問題を、フレームワークの設定やライフサイクルから切り離して検証することが目的です。

## 最小再現プロジェクト

プロジェクトは [`java-completablefuture-error-cause-lab`](https://github.com/tonbiattack/java-completablefuture-error-cause-lab) にあります。

```text
src/main/java/jp/tonbiattack/debuglab/CatalogGateway.java
src/main/java/jp/tonbiattack/debuglab/RemoteCatalog.java
src/main/java/jp/tonbiattack/debuglab/InventoryUnavailableException.java
src/main/java/jp/tonbiattack/debuglab/LookupResult.java
src/test/java/jp/tonbiattack/debuglab/CatalogGatewayTest.java
docs/topic-brief.md
docs/debugging-record.md
evidence/01-broken-test-output.txt
evidence/02-fixed-test-output.txt
```

テストでは、非同期実行に`Runnable::run`を渡しています。Supplierは同じ呼び出し順序で確実に実行されるため、`sleep`や実ネットワークに頼らず例外の表現だけを観測できます。

```java
private static final Executor DIRECT_EXECUTOR = Runnable::run;

RemoteCatalog catalog = sku -> CompletableFuture.supplyAsync(() -> {
    throw failure;
}, DIRECT_EXECUTOR);
```

### 不具合状態を実行する

不具合状態はコミット`a35d951`に固定しています。

```bash
git switch --detach a35d951
mvn test
```

失敗するテストは、判定と原因保持の両方を表す契約テストです。

```java
LookupResult actual = gateway.lookup("sku-42");

assertAll(
    () -> assertEquals(LookupOutcome.RETRY, actual.outcome()),
    () -> assertSame(failure, actual.cause(),
        "後続処理は元のドメイン例外を識別できるべき")
);
```

実際の結果は次のとおりです。

```text
observed_error_type=CompletionException, cause_type=InventoryUnavailableException
expected: <RETRY> but was: <UNKNOWN>
expected: <InventoryUnavailableException> but was: <CompletionException>
```

`CompletionException`のcauseには元の例外があるのに、外側の例外を直接判定・保持したため、契約が2つとも破れています。完全な出力は [`evidence/01-broken-test-output.txt`](https://github.com/tonbiattack/java-completablefuture-error-cause-lab/blob/main/evidence/01-broken-test-output.txt) に保存しています。

## 調査：何を観測し、どの仮説を除外したか

「リトライにならない」という結果だけでは、どの段階で情報が失われたか分かりません。そこで成功ケース、外側の例外型、cause型を分離して観測しました。

| 仮説 | 予測 | 最小実験 | 結果 | 判定 |
|---|---|---|---|---|
| `handle`は元のドメイン例外を直接受け取る | `error instanceof InventoryUnavailableException`が真 | 失敗入力で結果を観測 | `UNKNOWN`になる | 棄却 |
| `CompletionException`がドメイン例外をラップする | 外側が`CompletionException`で、causeがドメイン例外 | `error`と`error.getCause()`の型を出力 | この組み合わせを観測 | 採用 |
| Executorが失敗を発生させている | 成功入力も失敗・不安定になる | 同じ直接Executorで成功入力を実行 | `FOUND`とSKUを返す | 棄却 |

`CompletableFuture`は、依存するstageが例外完了した場合に`handle`を例外引数付きで実行します。[1] また、`join()`は例外完了時に`CompletionException`を直接送出する簡便なAPIとして定義されています。[1]

> `CompletionException`は、結果またはタスクの完了中にエラーや例外が発生したときの実行時例外であり、causeを保持できます。[2]

したがって、`handle`で受け取った`Throwable`を「元の業務例外」と仮定して型判定するのは安全ではありません。

## 修正：causeを分類・保持する

修正は、`CompletionException`でありcauseがあるときだけ、一層目のcauseを取り出すことです。

```java
private Throwable unwrapCompletionException(Throwable error) {
    if (error instanceof CompletionException && error.getCause() != null) {
        return error.getCause();
    }
    return error;
}
```

`handle`の中では、戻り値の`domainCause`に対してリトライ対象かを判定し、同じ`domainCause`を`LookupResult`へ保存します。

```java
Throwable domainCause = unwrapCompletionException(error);

if (domainCause instanceof InventoryUnavailableException) {
    return LookupResult.retry(domainCause);
}
return LookupResult.unknown(domainCause);
```

| 項目 | 修正前 | 修正後 |
|---|---|---|
| 分類対象 | 外側の`CompletionException` | unwrap後の原因例外 |
| 一時的在庫障害の結果 | `UNKNOWN` | `RETRY` |
| `LookupResult.cause()` | `CompletionException` | `InventoryUnavailableException` |

この修正は、すべての例外をリトライ可能にするものではありません。また、任意の深さのラップを再帰的に外すものでもありません。ラボで観測した`CompletionException`一層の境界だけを対象にします。

## 回帰テスト

修正後も最初の失敗テストは残しています。加えて、正常取得時に原因が残らない対照ケースを保持します。

| テスト | 固定する契約 |
|---|---|
| `unavailableInventory_keepsDomainCauseAndRequestsRetry` | `RETRY`と元のドメイン例外を両方保持する。 |
| `availableInventory_returnsFoundWithoutCause` | 正常取得では`FOUND`、SKU、`null`のcauseを返す。 |

```bash
git switch main
mvn clean test
```

修正後は2テスト成功、失敗0、エラー0でした。出力は [`evidence/02-fixed-test-output.txt`](https://github.com/tonbiattack/java-completablefuture-error-cause-lab/blob/main/evidence/02-fixed-test-output.txt) に保存しています。修正コミットは`ce19844`です。

## Springで使うときの境界

このラボはSpringを使いません。Springの`@Async`、WebClient、`@ControllerAdvice`などには、それぞれ追加の例外変換境界があります。そのため、本稿の一層unwrapを、すべてのSpring例外処理へ機械的に適用してはいけません。

一方で、Springアプリケーション内のコードが`CompletableFuture.handle`、`exceptionally`、`whenComplete`を直接使う場合、標準ライブラリ側の外側例外とcauseの区別は同じように確認できます。まずフレームワークから切り離したこの再現で例外表現を確認してから、アプリケーションの追加境界を調査すると切り分けが速くなります。

## まとめ

覚える判断規則は3つです。

1. `CompletableFuture`の失敗で受け取る`Throwable`は、必ずしもドメイン例外そのものではありません。
2. リトライや障害分類に例外型を使うなら、外側の例外と`getCause()`を別々に観測します。
3. 判定結果だけでなく、後続処理が読む原因例外も回帰テストで固定します。

## References

[1]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html "CompletableFuture — Java SE 21"
[2]: https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletionException.html "CompletionException — Java SE 21"
