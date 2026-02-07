package forge.cli;

import java.io.PrintStream;

/**
 * Character-based progress bar for stderr output.
 * Uses carriage return (\r) to update in-place on a terminal.
 */
public class ProgressBar {
    private final PrintStream out;
    private final int total;
    private final int barWidth;
    private final long startTime;
    private int current;

    public ProgressBar(PrintStream out, int total) {
        this(out, total, 30);
    }

    public ProgressBar(PrintStream out, int total, int barWidth) {
        this.out = out;
        this.total = total;
        this.barWidth = barWidth;
        this.startTime = System.currentTimeMillis();
        this.current = 0;
    }

    /**
     * Update the progress bar to show the given count.
     */
    public synchronized void update(int completed) {
        this.current = completed;
        render();
    }

    /**
     * Increment progress by one and render.
     */
    public synchronized void increment() {
        this.current++;
        render();
    }

    private void render() {
        double fraction = total > 0 ? (double) current / total : 0;
        int filled = (int) (fraction * barWidth);

        StringBuilder bar = new StringBuilder();
        bar.append('[');
        for (int i = 0; i < barWidth; i++) {
            if (i < filled) {
                bar.append('#');
            } else if (i == filled) {
                bar.append('>');
            } else {
                bar.append(' ');
            }
        }
        bar.append(']');

        String pct = String.format("%3.0f%%", fraction * 100);

        // Calculate ETA
        String eta;
        if (current > 0) {
            long elapsed = System.currentTimeMillis() - startTime;
            long remaining = (long) (elapsed * (total - current) / (double) current);
            eta = formatDuration(remaining);
        } else {
            eta = "--:--";
        }

        out.printf("\r%s %s %d/%d  ETA: %s", bar, pct, current, total, eta);
        out.flush();
    }

    /**
     * Finish the progress bar - print final state and move to next line.
     */
    public synchronized void finish() {
        this.current = total;
        render();
        long elapsed = System.currentTimeMillis() - startTime;
        out.printf("  [%s]%n", formatDuration(elapsed));
        out.flush();
    }

    private static String formatDuration(long ms) {
        long secs = ms / 1000;
        if (secs < 60) {
            return String.format("0:%02d", secs);
        } else if (secs < 3600) {
            return String.format("%d:%02d", secs / 60, secs % 60);
        } else {
            return String.format("%d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
        }
    }
}
