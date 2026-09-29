// Этап 1: однопоточный коллектор под одним общим локом (монитор этого объекта)
final class SyncCollector implements MetricsCollector {
    private final SingleThreadCollector inner = new SingleThreadCollector();

    @Override
    public synchronized void record(long value) {
        inner.record(value);
    }

    @Override
    public synchronized Snapshot snapshot() {
        return inner.snapshot();
    }
}
