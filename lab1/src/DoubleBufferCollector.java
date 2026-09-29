import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

// Этап 4: двойная буферизация
final class DoubleBufferCollector implements MetricsCollector {
    private static final int NOWHERE = -1;

    static final class ThreadBuffers {
        final long[][] buckets = new long[2][BUCKETS];
        final long[] count = new long[2];
        final long[] sum = new long[2];
        final long[] min = {Long.MAX_VALUE, Long.MAX_VALUE};
        final long[] max = {0, 0};

        // NOWHERE - поток вне буферов, 0 или 1 - поток прямо сейчас пишет в этот буфер
        final AtomicInteger inside = new AtomicInteger(NOWHERE);
    }

    // false - эксперимент без шага 3 (без повторной проверки active в record())
    private final boolean recheck;

    private volatile int active = 0;

    private final Object snapLock = new Object();
    private final List<ThreadBuffers> allBuffers = new ArrayList<>();

    private final long[] totalBuckets = new long[BUCKETS];
    private long totalCount;
    private long totalSum;
    private long totalMin = Long.MAX_VALUE;
    private long totalMax = 0;

    private final ThreadLocal<ThreadBuffers> myBuffers = ThreadLocal.withInitial(() -> {
        ThreadBuffers tb = new ThreadBuffers();
        synchronized (snapLock) {
            allBuffers.add(tb);
        }
        return tb;
    });

    DoubleBufferCollector(boolean recheck) {
        this.recheck = recheck;
    }

    @Override
    public void record(long value) {
        ThreadBuffers my = myBuffers.get();

        // Рукопожатие Деккера: set(b) и оба чтения active должны быть seq_cst.
        int b;
        while (true) {
            b = active;                            // 1. какой буфер сейчас активен
            my.inside.set(b);                      // 2. объявляем: пишу в b
            if (!recheck || active == b) {         // 3. читатель не успел переключить active?
                break;
            }
            my.inside.setRelease(NOWHERE);         // 4. успел: сбрасываем inside и повторяем
        }

        my.buckets[b][MetricsCollector.bucketOf(value)]++;
        my.count[b]++;
        my.sum[b] += value;
        if (value < my.min[b]) my.min[b] = value;
        if (value > my.max[b]) my.max[b] = value;

        // 5. вышли из буфера
        my.inside.setRelease(NOWHERE);
    }

    @Override
    public Snapshot snapshot() {
        synchronized (snapLock) {
            int old = active;
            active = 1 - old;

            for (ThreadBuffers tb : allBuffers) {
                while (tb.inside.get() == old) {
                    Thread.onSpinWait();
                }

                long[] frozen = tb.buckets[old];
                for (int i = 0; i < BUCKETS; i++) {
                    totalBuckets[i] += frozen[i];
                }
                totalCount += tb.count[old];
                totalSum += tb.sum[old];
                totalMin = Math.min(totalMin, tb.min[old]);
                totalMax = Math.max(totalMax, tb.max[old]);

                Arrays.fill(frozen, 0);
                tb.count[old] = 0;
                tb.sum[old] = 0;
                tb.min[old] = Long.MAX_VALUE; // не 0: иначе min навсегда останется равным 0
                tb.max[old] = 0;
            }

            return Snapshot.of(totalBuckets.clone(), totalCount, totalSum, totalMin, totalMax);
        }
    }
}
