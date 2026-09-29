// Этап 0: однопоточная база, без какой-либо синхронизации.
final class SingleThreadCollector implements MetricsCollector {
    private final long[] buckets = new long[BUCKETS];
    private long count;
    private long sum;
    private long min = Long.MAX_VALUE;
    private long max = 0;

    @Override
    public void record(long value) {
        buckets[MetricsCollector.bucketOf(value)]++;
        count++;
        sum += value;
        if (value < min) min = value;
        if (value > max) max = value;
    }

    @Override
    public Snapshot snapshot() {
        return Snapshot.of(buckets.clone(), count, sum, min, max);
    }
}
