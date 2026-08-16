# CompletableFutureの例外ラップでリトライ判定が失われるデバッグラボ

Java 21の`CompletableFuture`が失敗時に表す`CompletionException`を原因例外と取り違え、ドメイン上はリトライ可能な障害を`UNKNOWN`として扱う不具合を、**失敗する契約テスト → 証拠の確認 → 最小修正 → 回帰テスト**の順に学ぶフレームワーク非依存のデバッグ題材である。

## この題材で守る契約

> `InventoryUnavailableException`で失敗する非同期カタログ取得に対して、元の例外を原因として保持した`RETRY`結果を返す。

バグ状態では、`CompletionException`を直接分類して`UNKNOWN`結果を返し、後続処理が元のドメイン例外を識別できない。原因は`CompletableFuture.handle`で受ける例外表現と`CompletionException`のcauseであり、Spring Boot、DI、HTTP、ORM、外部サービスは使用しない。

## 最短の開始手順

固定済みの状態では、次を実行する。

```bash
mvn clean test
```

2テストが成功し、`InventoryUnavailableException`を`RETRY`として返すことを確認する。

## バグを再現する

バグ状態はコミット`a35d951`に保存してある。作業中の変更を退避してから、次を実行する。

```bash
git switch --detach a35d951
mvn test
```

`unavailableInventory_keepsDomainCauseAndRequestsRetry`が、`expected: <RETRY> but was: <UNKNOWN>`および原因例外の不一致で失敗する。確認後は既定ブランチへ戻る。

```bash
git switch main
```

## 観測の要約

| 観測点 | バグ状態 | 修正後 |
| --- | --- | --- |
| 直接結果 | `UNKNOWN` | `RETRY` |
| 後続処理が受け取る原因 | `CompletionException` | 元の`InventoryUnavailableException` |
| 検証コマンド | `mvn test`が契約アサーションで失敗 | `mvn clean test`が2テスト成功 |

詳細な仮説、証拠、原因、修正、回帰保証は[`docs/debugging-record.md`](docs/debugging-record.md)に記録する。

## 構成

```text
src/main/java/                         実装
src/test/java/                         契約テスト
README.md                              開始手順
docs/topic-brief.md                    題材企画
docs/debugging-record.md               調査記録
evidence/                              修正前・修正後の実行出力
```

## 前提条件

| 項目 | バージョンまたは条件 |
| --- | --- |
| 言語処理系 | JDK 21 |
| ビルド・テストツール | Maven 3.8以上、JUnit Jupiter 5.11.4 |
| 外部サービス | 不要 |

## スコープ

このラボは、`CompletionException`をunwrapせずにドメイン例外を分類する問題だけを扱う。Springの`@Async`、HTTPのリトライ回数、指数バックオフ、タイムアウト、キャンセル、例外ロギング、監視設計についての一般的な推奨を意味しない。

## References

1. [CompletableFuture — Java SE 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletableFuture.html)
2. [CompletionException — Java SE 21](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/concurrent/CompletionException.html)
