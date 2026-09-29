public interface MetricsCollector {
    int BUCKETS = 256;

    void record(long value);

    Snapshot snapshot();

    static int bucketOf(long value) {
        return (int) Math.min(value / 4, BUCKETS - 1);
    }

    static MetricsCollector create(String name) {
        return switch (name) {
            case "single" -> new SingleThreadCollector();
            case "empty" -> new EmptyLockCollector();
            case "sync" -> new SyncCollector();
            case "striped" -> new StripedCollector();
            case "threadlocal" -> new ThreadLocalCollector();
            case "double" -> new DoubleBufferCollector(true);
            case "double-norecheck" -> new DoubleBufferCollector(false);
            default -> throw new IllegalArgumentException("неизвестный коллектор: " + name);
        };
    }
}
