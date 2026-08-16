package jp.tonbiattack.debuglab;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface RemoteCatalog {

    CompletableFuture<String> fetchSku(String sku);
}
