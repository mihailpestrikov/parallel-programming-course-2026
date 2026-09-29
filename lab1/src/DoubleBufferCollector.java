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

    private final Object listLock = new Object();
    private final List<ThreadBuffers> allBuffers = new ArrayList<>();

    private final long[] totalBuckets = new long[BUCKETS];
    private long totalCount;
    private long totalSum;
    private long totalMin = Long.MAX_VALUE;
    private long totalMax = 0;

    private final ThreadLocal<ThreadBuffers> myBuffers = ThreadLocal.withInitial(() -> {
        ThreadBuffers tb = new ThreadBuffers();
        synchronized (listLock) {
            allBuffers.add(tb);
        }
        return tb;
    });

    DoubleBufferCollector(boolean recheck) {
        this.recheck = recheck;
    }

    @Override
    public void record(long value) {
        // У каждого потока свой ThreadBuffers с двумя наборами полей
        // пока читатель сливает буфер 0, писатель пишет в буфер 1
        // так работает синк мапа в го
        ThreadBuffers my = myBuffers.get();

        int b;
        while (true) {
            b = active;                            // какой буфер сейчас активен
            my.inside.set(b);                      // объявляем: пишу в b
            if (!recheck || active == b) {         // читатель не успел переключить active?
                break;                             // если синканулись с читалем, идем дальше
            }
            my.inside.setRelease(NOWHERE);         // успел сбрасываем inside и повторяем
        }

        // буфер личный для потока
        my.buckets[b][MetricsCollector.bucketOf(value)]++;
        my.count[b]++;
        my.sum[b] += value;
        if (value < my.min[b]) my.min[b] = value;
        if (value > my.max[b]) my.max[b] = value;

        // вышли из буфера, говорим читателю буфер b снова свободен для слияния
        my.inside.setRelease(NOWHERE);
    }

    @Override
    public Snapshot snapshot() {
        synchronized (listLock) {
            int old = active;
            active = 1 - old;

            for (ThreadBuffers tb : allBuffers) {
                // ждем пока писатель уйдет в другой буффер
                while (tb.inside.get() == old) {
                    Thread.onSpinWait();
                }

                // в олд буфер никто не пишет, сливем данные
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
                tb.min[old] = Long.MAX_VALUE;
                tb.max[old] = 0;
            }

            return Snapshot.of(totalBuckets.clone(), totalCount, totalSum, totalMin, totalMax);
        }
    }
}
