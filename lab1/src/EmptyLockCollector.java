// Этап 1: пустой лок, record() ничего не записывает.
final class EmptyLockCollector implements MetricsCollector {

    @Override
    public synchronized void record(long value) {
    }

    @Override
    public synchronized Snapshot snapshot() {
        return Snapshot.of(new long[BUCKETS], 0, 0, 0, 0);
    }
}
