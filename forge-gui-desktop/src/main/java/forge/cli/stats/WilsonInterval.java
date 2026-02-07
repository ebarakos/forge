package forge.cli.stats;

/**
 * Wilson score confidence interval for binomial proportions.
 * More accurate than the normal approximation for small sample sizes
 * and for proportions near 0 or 1.
 */
public final class WilsonInterval {

    private WilsonInterval() {}

    /**
     * Calculate the Wilson score confidence interval.
     *
     * @param successes number of successes (wins)
     * @param total     total number of trials (games)
     * @param z         z-score for desired confidence level (1.96 for 95%)
     * @return double[2] = {lower bound, upper bound} as percentages (0-100)
     */
    public static double[] calculate(int successes, int total, double z) {
        if (total == 0) {
            return new double[]{0.0, 100.0};
        }

        double n = total;
        double p = successes / n;
        double z2 = z * z;

        double denominator = 1.0 + z2 / n;
        double center = (p + z2 / (2.0 * n)) / denominator;
        double spread = (z / denominator) * Math.sqrt(p * (1.0 - p) / n + z2 / (4.0 * n * n));

        double lower = Math.max(0.0, center - spread) * 100.0;
        double upper = Math.min(1.0, center + spread) * 100.0;

        return new double[]{lower, upper};
    }

    /**
     * Calculate the 95% Wilson score confidence interval.
     *
     * @param successes number of successes (wins)
     * @param total     total number of trials (games)
     * @return double[2] = {lower bound, upper bound} as percentages (0-100)
     */
    public static double[] calculate95(int successes, int total) {
        return calculate(successes, total, 1.96);
    }

    /**
     * Format a confidence interval as a string like "52.3% [45.1%, 59.4%]".
     *
     * @param winRate   win rate percentage (0-100)
     * @param ci        confidence interval from calculate()
     * @return formatted string
     */
    public static String format(double winRate, double[] ci) {
        return String.format("%.1f%% [%.1f%%, %.1f%%]", winRate, ci[0], ci[1]);
    }
}
