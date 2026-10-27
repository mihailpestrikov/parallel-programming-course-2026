package lab2;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/* Стратегии выбора следующего потока.
 * FirstAlive — этап 1, Replay — этап 2, RandomPick — этап 3, Dfs — этап 4 (ограничение bound — этап 5).
 * enabled — номера потоков, которые могут сделать шаг, по возрастанию; last — прошлый выбранный (-1 в начале). */
public final class Strategies {
    private Strategies() { }

    /** Всегда первый поток из enabled. */
    public static final class FirstAlive implements Strategy {
        public int pick(List<Integer> enabled, int last) {
            return enabled.get(0);
        }
    }

    /** Проигрывает трассу: на i-м решении выбирает поток trace[i] ('A' -> 0, 'B' -> 1). */
    public static final class Replay implements Strategy {
        private final String trace;
        private int pos;

        public Replay(String trace) { this.trace = trace; }

        /** Сбросить позицию в трассе перед прогоном. */
        public void beginRun() { pos = 0; }

        public int pick(List<Integer> enabled, int last) {
            if (pos >= trace.length())
                throw new IllegalStateException("трасса " + trace + " кончилась раньше прогона: на решении "
                        + (pos + 1) + " могут ходить " + letters(enabled));
            char c = trace.charAt(pos);
            int t = c - 'A';
            if (!enabled.contains(t))
                throw new IllegalStateException("трасса " + trace + ", решение " + (pos + 1) + ": поток " + c
                        + " сейчас не может ходить, могут " + letters(enabled));
            pos++;
            return t;
        }
    }

    /** Случайный поток: enabled.get(rnd.nextInt(enabled.size())), rnd = new java.util.Random(seed). */
    public static final class RandomPick implements Strategy {
        private final Random rnd;

        public RandomPick(long seed) { rnd = new Random(seed); }

        public int pick(List<Integer> enabled, int last) {
            return enabled.get(rnd.nextInt(enabled.size()));
        }
    }

    /** Stateless DFS по дереву выборов: каждый прогон с нуля, повторяет префикс прошлого и берёт следующий вариант.
     *  bound — максимум вытеснений (этап 5); для dfs runner передаёт Integer.MAX_VALUE. */
    public static final class Dfs implements Strategy {
        private static final class Fork {
            final List<Integer> options;
            int chosen;

            Fork(List<Integer> options) { this.options = options; }
        }

        private final int bound;
        private final List<Fork> path = new ArrayList<>();
        private int depth;
        private int preemptions;

        public Dfs(int bound) { this.bound = bound; }

        /** Подготовиться к новому прогону (позиция в пути, счётчик вытеснений). */
        public void beginRun() {
            depth = 0;
            preemptions = 0;
        }

        public int pick(List<Integer> enabled, int last) {
            boolean lastCanRun = enabled.contains(last);
            if (depth == path.size()) {
                List<Integer> options = List.copyOf(enabled);
                if (lastCanRun && preemptions >= bound) options = List.of(last);
                path.add(new Fork(options));
            }
            Fork f = path.get(depth);
            int t = f.options.get(f.chosen);
            if (!enabled.contains(t)) throw new IllegalStateException("DFS: прогон разошёлся с прошлым на решении " + (depth + 1));
            if (lastCanRun && t != last) preemptions++;
            depth++;
            return t;
        }

        /** Выбрать следующий путь; false — дерево обойдено. */
        public boolean endRun() {
            while (!path.isEmpty()) {
                Fork f = path.get(path.size() - 1);
                if (f.chosen + 1 < f.options.size()) {
                    f.chosen++;
                    return true;
                }
                path.remove(path.size() - 1);
            }
            return false;
        }
    }

    private static String letters(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (sb.length() > 0) sb.append(", ");
            sb.append((char) ('A' + id));
        }
        return sb.toString();
    }
}
