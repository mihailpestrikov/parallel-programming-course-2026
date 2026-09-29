import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

// Стресс-тест согласованности
public class StressTest {
    static final int WRITERS = Integer.getInteger("writers", 4);
    static final int SNAPSHOTS = Integer.getInteger("snapshots", 10_000);
    static final int ROUNDS = Integer.getInteger("rounds", 3);

    public static void main(String[] args) throws InterruptedException {
        String[] impls = args.length > 0
                ? args
                : new String[]{"striped", "threadlocal", "double", "double-norecheck"};
        long[] values = Zipf.generate(Bench.VALUES, Bench.ZIPF_S, Bench.SEED);

        for (String impl : impls) {
            checkSingleThreaded(impl, values);
        }

        System.out.println("impl,round,broken_pct,sum_less,sum_more,final_diff");
        for (String impl : impls) {
            for (int round = 1; round <= ROUNDS; round++) {
                runRound(impl, values, round);
            }
        }
    }

    static void checkSingleThreaded(String impl, long[] values) {
        MetricsCollector expected = new SingleThreadCollector();
        MetricsCollector actual = MetricsCollector.create(impl);
        for (long v : values) {
            expected.record(v);
            actual.record(v);
        }
        Snapshot e = expected.snapshot();
        Snapshot a = actual.snapshot();
        boolean same = Arrays.equals(e.buckets(), a.buckets())
                && e.count() == a.count() && e.sum() == a.sum()
                && e.min() == a.min() && e.max() == a.max()
                && e.p50() == a.p50() && e.p99() == a.p99();
        if (!same) {
            throw new AssertionError(impl + ": однопоточный снимок не совпал с эталоном");
        }
        System.err.printf(Locale.ROOT, "%s: однопоточная сверка OK (count=%d p50=%d p99=%d)%n",
                impl, a.count(), a.p50(), a.p99());
    }

    static void runRound(String impl, long[] values, int round) throws InterruptedException {
        MetricsCollector collector = MetricsCollector.create(impl);
        AtomicBoolean stop = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(WRITERS);
        long[] calls = new long[WRITERS];
        Thread[] writers = new Thread[WRITERS];

        for (int k = 0; k < WRITERS; k++) {
            int id = k;
            writers[k] = new Thread(() -> {
                started.countDown();
                calls[id] = Bench.workLoop(collector, values, id * 1000, stop);
            });
            writers[k].start();
        }
        started.await();

        int sumLess = 0;
        int sumMore = 0;
        for (int n = 0; n < SNAPSHOTS; n++) {
            Snapshot s = collector.snapshot();
            long bucketsSum = 0;
            for (long x : s.buckets()) {
                bucketsSum += x;
            }
            if (bucketsSum < s.count()) {
                sumLess++;
            } else if (bucketsSum > s.count()) {
                sumMore++;
            }
        }

        stop.set(true);
        for (Thread w : writers) {
            w.join();
        }
        long totalCalls = 0;
        for (long c : calls) {
            totalCalls += c;
        }

        // Два снимка у этапа 4 так сливаются оба буфера, для остальных ничего не меняется
        collector.snapshot();
        long finalCount = collector.snapshot().count();

        double brokenPct = 100.0 * (sumLess + sumMore) / SNAPSHOTS;
        System.out.printf(Locale.ROOT, "%s,%d,%.2f,%d,%d,%d%n",
                impl, round, brokenPct, sumLess, sumMore, finalCount - totalCalls);
    }
}
