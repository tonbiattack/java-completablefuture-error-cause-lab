package jp.tonbiattack.debuglab;

public record LookupResult(LookupOutcome outcome, String sku, Throwable cause) {

    public static LookupResult found(String sku) {
        return new LookupResult(LookupOutcome.FOUND, sku, null);
    }

    public static LookupResult retry(Throwable cause) {
        return new LookupResult(LookupOutcome.RETRY, null, cause);
    }

    public static LookupResult unknown(Throwable cause) {
        return new LookupResult(LookupOutcome.UNKNOWN, null, cause);
    }
}
