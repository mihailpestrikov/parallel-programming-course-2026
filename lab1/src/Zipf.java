import java.util.Random;

final class Zipf {
    static final int MAX_VALUE = 1023;

    static long[] generate(int n, double s, long seed) {
        double[] cdf = new double[MAX_VALUE + 1];
        double total = 0;
        for (int k = 1; k <= MAX_VALUE; k++) {
            total += 1.0 / Math.pow(k, s);
            cdf[k] = total;
        }

        Random rnd = new Random(seed);
        long[] values = new long[n];
        for (int i = 0; i < n; i++) {
            double u = rnd.nextDouble() * total;
            int k = 1;
            while (cdf[k] < u) {
                k++;
            }
            values[i] = k;
        }
        return values;
    }
}
