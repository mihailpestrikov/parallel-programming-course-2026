import java.util.concurrent.atomic.AtomicLong;

// Этап 2: корзина b защищена локом группы b % 16, сводка на атомиках
final class StripedCollector implements MetricsCollector {
    private static final int STRIPES = 16;

    private final long[] buckets = new long[BUCKETS];
    private final Object[] locks = new Object[STRIPES];

    private final AtomicLong count = new AtomicLong();
    private final AtomicLong sum = new AtomicLong();
    private final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong max = new AtomicLong(0);

    StripedCollector() {
        for (int i = 0; i < STRIPES; i++) {
            locks[i] = new Object();
        }
    }

    @Override
    public void record(long value) {
        int b = MetricsCollector.bucketOf(value);
        // Лок берётся не на всю запись, а только на инкремент своей корзины
        synchronized (locks[b % STRIPES]) {
            buckets[b]++;
        }

        // Сводка общая на все потоки и не под локом шарда
        // поэтому защищаем каждое поле своим атомиком
        count.incrementAndGet();
        sum.addAndGet(value);

        long cur = min.get();
        while (value < cur && !min.compareAndSet(cur, value)) {
            cur = min.get();
        }
        cur = max.get();
        while (value > cur && !max.compareAndSet(cur, value)) {
            cur = max.get();
        }
    }

    @Override
    public Snapshot snapshot() {
        long[] copy = new long[BUCKETS];
        // Снимок берёт локи шардов по очереди, а не все разом. Из-за этого корзины
        // с разных шардов в одном snapshot() могут быть сделаны в разные моменты
        // времени, а sum/count независимо от корзин.
        // Отсюда почти гарантированный sum(buckets) != count в стресс-тесте.
        for (int s = 0; s < STRIPES; s++) {
            synchronized (locks[s]) {
                for (int b = s; b < BUCKETS; b += STRIPES) {
                    copy[b] = buckets[b];
                }
            }
        }
        return Snapshot.of(copy, count.get(), sum.get(), min.get(), max.get());
    }
}
