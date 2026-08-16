package jp.tonbiattack.debuglab;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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

                    Throwable domainCause = unwrapCompletionException(error);
                    System.out.printf("observed_error_type=%s, domain_cause_type=%s%n",
                            error.getClass().getSimpleName(),
                            domainCause.getClass().getSimpleName());

                    if (domainCause instanceof InventoryUnavailableException) {
                        return LookupResult.retry(domainCause);
                    }
                    return LookupResult.unknown(domainCause);
                });

        return result.join();
    }

    private Throwable unwrapCompletionException(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }
}
