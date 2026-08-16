package jp.tonbiattack.debuglab;

import java.util.concurrent.CompletableFuture;

public final class CatalogGateway {

    private final RemoteCatalog remoteCatalog;

    public CatalogGateway(RemoteCatalog remoteCatalog) {
        this.remoteCatalog = remoteCatalog;
    }

    public LookupResult lookup(String sku) {
        CompletableFuture<LookupResult> result = remoteCatalog.fetchSku(sku)
                .handle((fetchedSku, error) -> {
                    if (error == null) {
                        return LookupResult.found(fetchedSku);
                    }

                    System.out.printf("observed_error_type=%s, cause_type=%s%n",
                            error.getClass().getSimpleName(),
                            error.getCause() == null ? "<none>"
                                    : error.getCause().getClass().getSimpleName());

                    if (error instanceof InventoryUnavailableException) {
                        return LookupResult.retry(error);
                    }
                    return LookupResult.unknown(error);
                });

        return result.join();
    }
}
