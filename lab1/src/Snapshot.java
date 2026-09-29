public record Snapshot(
        long[] buckets,
        long count,
        long sum,
        long min,
        long max,
        long p50,
        long p99
) {
    static Snapshot of(long[] buckets, long count, long sum, long min, long max) {
        return new Snapshot(buckets, count, sum, min, max,
                percentile(buckets, count, 0.50),
                percentile(buckets, count, 0.99));
    }

    static long percentile(long[] buckets, long count, double q) {
        double threshold = count * q;
        long accumulated = 0;
        for (int i = 0; i < buckets.length; i++) {
            accumulated += buckets[i];
            if (accumulated >= threshold) {
                return i * 4L;
            }
        }
        return (buckets.length - 1) * 4L;
    }
}
