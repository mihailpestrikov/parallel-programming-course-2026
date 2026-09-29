import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

// Этап 3: у каждого потока свои корзины и сводка, snapshot() складывает их
final class ThreadLocalCollector implements MetricsCollector {

    static final class ThreadState {
        final AtomicLongArray buckets = new AtomicLongArray(BUCKETS);
        final AtomicLong count = new AtomicLong();
        final AtomicLong sum = new AtomicLong();
        final AtomicLong min = new AtomicLong(Long.MAX_VALUE);
        final AtomicLong max = new AtomicLong(0);
    }

    private final List<ThreadState> allStates = new ArrayList<>();
    private final Object listLock = new Object();

    // Поле экземпляра, а не static иначе новый коллектор писал бы в состояния старого
    private final ThreadLocal<ThreadState> myState = ThreadLocal.withInitial(() -> {
        ThreadState s = new ThreadState();
        synchronized (listLock) {
            allStates.add(s);
        }
        return s;
    });

    @Override
    public void record(long value) {
        // Каждый вызов record() всегда попадает в свой ThreadState,
        // поэтому здесь нет гонки писателей, всегда ровно один и тот же поток
        ThreadState s = myState.get();
        int b = MetricsCollector.bucketOf(value);

        // Писатель один, поэтому без CAS читаем plain, пишем с release
        // setRelease - защита от перестановок jit
        s.buckets.setRelease(b, s.buckets.getPlain(b) + 1);
        s.count.setRelease(s.count.getPlain() + 1);
        s.sum.setRelease(s.sum.getPlain() + value);

        if (value < s.min.getPlain()) s.min.setRelease(value);
        if (value > s.max.getPlain()) s.max.setRelease(value);
    }

    @Override
    public Snapshot snapshot() {
        List<ThreadState> states;
        synchronized (listLock) {
            states = new ArrayList<>(allStates);
        }

        long[] out = new long[BUCKETS];
        long count = 0;
        long sum = 0;
        long min = Long.MAX_VALUE;
        long max = 0;
        // Обходим состояния всех потоков
        // никакой блокировки писателей нет, поэтому сумма всегда потенциально рваная
        for (ThreadState s : states) {
            for (int i = 0; i < BUCKETS; i++) {
                // get() у AtomicLongArray/AtomicLong full volatile-чтение
                // синхронизируется с setRelease писателя
                // берем конкретное значение bucket, видим и все записи, что были до него
                // в потоке-писателе. но не гарантирует свежесть остальных полей
                out[i] += s.buckets.get(i);
            }
            count += s.count.get();
            sum += s.sum.get();
            min = Math.min(min, s.min.get());
            max = Math.max(max, s.max.get());
        }
        return Snapshot.of(out, count, sum, min, max);
    }
}
