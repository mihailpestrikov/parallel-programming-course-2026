import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public class Bench {
    static final int VALUES = 1 << 20;
    static final double ZIPF_S = 1.15;
    static final long SEED = 42;

    static final int WARMUP_SEC = Integer.getInteger("warmup", 5);
    static final int RUN_SEC = Integer.getInteger("sec", 5);
    static final int RUNS = Integer.getInteger("runs", 5);

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 2) {
            System.err.println("использование: java -cp out Bench <коллектор> <T...>");
            System.exit(1);
        }
        String impl = args[0];
        long[] values = Zipf.generate(VALUES, ZIPF_S, SEED);

        for (int a = 1; a < args.length; a++) {
            int threads = Integer.parseInt(args[a]);
            if (impl.equals("single") && threads > 1) {
                throw new IllegalArgumentException("single без синхронизации: только T = 1");
            }

            MetricsCollector collector = MetricsCollector.create(impl);
            double[] results = measurePoint(collector, values, threads);

            Snapshot s = collector.snapshot();
            System.err.printf(Locale.ROOT, "%s T=%d: count=%d p50=%d p99=%d%n",
                    impl, threads, s.count(), s.p50(), s.p99());

            StringBuilder line = new StringBuilder();
            line.append(impl).append(',').append(threads).append(',').append(mops(median(results)));
            for (double r : results) {
                line.append(',').append(mops(r));
            }
            System.out.println(line);
        }
    }

    static double[] measurePoint(MetricsCollector collector, long[] values, int threads)
            throws InterruptedException {
        run(collector, values, threads, WARMUP_SEC);
        double[] results = new double[RUNS];
        for (int r = 0; r < RUNS; r++) {
            results[r] = run(collector, values, threads, RUN_SEC);
        }
        return results;
    }

    static double run(MetricsCollector collector, long[] values, int threads, int seconds)
            throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);   // общий стартовый сигнал
        AtomicBoolean stop = new AtomicBoolean(false);   // общий сигнал остановки
        long[] ops = new long[threads];                  // свой счётчик на поток, без гонок
        Thread[] workers = new Thread[threads];

        for (int k = 0; k < threads; k++) {
            int id = k;
            workers[k] = new Thread(() -> {
                await(start);                            // все ждут одного countDown()
                ops[id] = workLoop(collector, values, id * 1000, stop);
            });
            workers[k].start();
        }

        long t0 = System.nanoTime();
        start.countDown();          // отпускаем всех разом - честный одновременный старт
        Thread.sleep(seconds * 1000L);
        stop.set(true);              // просим воркеров остановиться
        long t1 = System.nanoTime();
        for (Thread w : workers) {
            w.join();                // ждём реального завершения перед подсчётом
        }

        long total = 0;
        for (long n : ops) {
            total += n;               // складываем после join - гонок уже нет
        }
        return total / ((t1 - t0) / 1e9);
    }

    static long workLoop(MetricsCollector collector, long[] values, int i, AtomicBoolean stop) {
        long local = 0;
        // getOpaque - дешёвая проверка флага без полного барьера памяти,
        // не даёт JIT закешировать stop в регистре и никогда не перечитывать
        while (!stop.getOpaque()) {
            collector.record(values[i]);
            local++;
            if (++i == values.length) {
                i = 0;
            }
        }
        return local;
    }

    static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    static double median(double[] xs) {
        double[] sorted = xs.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    static String mops(double opsPerSec) {
        return String.format(Locale.ROOT, "%.2f", opsPerSec / 1e6);
    }
}
