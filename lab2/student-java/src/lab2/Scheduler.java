package lab2;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

/* Этап 1: планировщик, который ведёт настоящие потоки строго по одному (передача хода через семафоры).
 * Этап 2: обнаружение deadlock в главном цикле и корректное завершение застрявших потоков.
 * Внутреннее устройство (свои классы, семафоры, вспомогательные методы для Shared и SchedLock) придумываете сами. */
public final class Scheduler {
    /** Если true, каждая операция с общими данными добавляет строку в log. Выставляет runner. */
    boolean logging;
    /** Лог прогона: строки "<поток>  <операция>", например "A  lock mu", "A  balance -> 100". */
    final List<String> log = new ArrayList<>();
    /** null, если deadlock не было; иначе "held={a:A, b:B}" (этап 2). */
    String deadlock;

    private final List<Worker> workers = new ArrayList<>();
    private final List<SchedLock> locks = new ArrayList<>();
    private final StringBuilder trace = new StringBuilder();
    private final Semaphore schedSem = new Semaphore(0);
    private boolean aborted;

    private static final ThreadLocal<Worker> CURRENT = new ThreadLocal<>();

    private static final class Worker {
        final Scheduler s;
        final int id;
        final Semaphore sem = new Semaphore(0);
        Thread thread;
        boolean touched = true;     // трогал ли общие данные с прошлой паузы
        BooleanSupplier canRun;
        boolean done;

        Worker(Scheduler s, int id) { this.s = s; this.id = id; }
    }

    private static final class Aborted extends RuntimeException { }

    public Scheduler() { }

    /** Зарегистрировать поток. Потоки получают буквы A, B, C… в порядке вызова spawn. */
    public void spawn(Runnable body) {
        Worker w = new Worker(this, workers.size());
        w.thread = new Thread(() -> {
            CURRENT.set(w);
            try {
                body.run();
            } catch (Aborted e) {
            } finally {
                w.done = true;
                schedSem.release();
            }
        }, "sched-" + letter(w.id));
        workers.add(w);
    }

    /** Запустить потоки и вести их по одному: на каждой паузе спросить strategy.pick(enabled, last). */
    public void run(Strategy strategy) {
        try {
            for (Worker w : workers) {
                w.thread.start();
                schedSem.acquireUninterruptibly();
            }

            int last = -1;
            while (true) {
                List<Integer> enabled = new ArrayList<>();
                boolean alive = false;
                for (Worker w : workers) {
                    if (w.done) continue;
                    alive = true;
                    if (w.canRun == null || w.canRun.getAsBoolean()) enabled.add(w.id);
                }
                if (!alive) break;
                if (enabled.isEmpty()) {
                    deadlock = heldLocks();
                    break;
                }

                int next = strategy.pick(enabled, last);
                trace.append(letter(next));
                last = next;
                workers.get(next).sem.release();
                schedSem.acquireUninterruptibly();
            }
        } finally {
            stopStuckThreads();
        }
    }

    /** Трасса прогона: буквы потоков, выбранных во всех решениях подряд, например "AAAABB". */
    public String traceString() {
        return trace.toString();
    }

    /** window() из тестовых программ: пауза перед следующей операцией с общими данными обязательна. */
    public static void point() {
        afterOp("window");
    }

    /** Ждать, пока cond не станет true. До этого поток не попадает в enabled. */
    public static void await(BooleanSupplier cond) {
        beforeOp(cond);
        afterOp("await done");
    }

    // пауза перед операцией, поток не в enabled
    static void beforeOp(BooleanSupplier canRun) {
        Worker w = current();
        boolean can = canRun == null || canRun.getAsBoolean();
        if (!w.touched && can) return;
        w.canRun = canRun;
        w.s.schedSem.release();
        w.sem.acquireUninterruptibly();
        if (w.s.aborted) throw new Aborted();
        w.touched = false;
    }

    // после операции строка в лог и отметка, что следующая операция встанет на паузу
    static void afterOp(String what) {
        Worker w = current();
        w.touched = true;
        if (w.s.logging) w.s.log.add(letter(w.id) + "  " + what);
    }

    static int currentId() {
        return current().id;
    }

    void addLock(SchedLock lock) {
        locks.add(lock);
    }

    private static Worker current() {
        Worker w = CURRENT.get();
        if (w == null) throw new IllegalStateException("операция с общими данными вне потока планировщика");
        if (w.s.aborted) throw new Aborted();
        return w;
    }

    // будит застрявшие после deadlock потоки, чтобы они вышли через Aborted
    private void stopStuckThreads() {
        aborted = true;
        for (Worker w : workers) {
            if (!w.done) w.sem.release();
        }
        for (Worker w : workers) {
            try {
                w.thread.join();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // занятые локи в порядке создания
    private String heldLocks() {
        StringBuilder sb = new StringBuilder("held={");
        String sep = "";
        for (SchedLock l : locks) {
            if (l.owner < 0) continue;
            sb.append(sep).append(l.name).append(':').append(letter(l.owner));
            sep = ", ";
        }
        return sb.append('}').toString();
    }

    private static char letter(int id) {
        return (char) ('A' + id);
    }
}
