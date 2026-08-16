package jp.tonbiattack.debuglab;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

class CatalogGatewayTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void unavailableInventory_keepsDomainCauseAndRequestsRetry() {
        InventoryUnavailableException failure =
                new InventoryUnavailableException("inventory-replica-unavailable");
        RemoteCatalog catalog = sku -> CompletableFuture.supplyAsync(() -> {
            throw failure;
        }, DIRECT_EXECUTOR);
        CatalogGateway gateway = new CatalogGateway(catalog);

        LookupResult actual = gateway.lookup("sku-42");

        assertAll(
                () -> assertEquals(LookupOutcome.RETRY, actual.outcome()),
                () -> assertSame(failure, actual.cause(),
                        "後続処理は元のドメイン例外を識別できるべき"));
    }

    @Test
    void availableInventory_returnsFoundWithoutCause() {
        RemoteCatalog catalog = sku -> CompletableFuture.supplyAsync(
                () -> sku + "-available", DIRECT_EXECUTOR);
        CatalogGateway gateway = new CatalogGateway(catalog);

        LookupResult actual = gateway.lookup("sku-7");

        assertEquals(LookupOutcome.FOUND, actual.outcome());
        assertEquals("sku-7-available", actual.sku());
        assertNull(actual.cause());
    }
}
