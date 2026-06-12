package forge.ai.simulation;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Learned win-probability evaluator over {@link GameStateEvaluator} features,
 * trained offline on {@link EvalFeatureCollector} JSONL data and loaded from
 * a flat text export. Two formats, detected by the second line:
 *
 * <pre>
 * MLP:   line 1: feature names (comma-separated, defines input order)
 *        line 2: standardisation means; line 3: stds
 *        then per layer: "layer &lt;rows&gt; &lt;cols&gt;", rows weight lines, 1 bias line
 *        (ReLU hidden layers, sigmoid output)
 * GBDT:  line 1: feature names
 *        line 2: "gbdt &lt;n_trees&gt; &lt;baseline&gt;"
 *        then per tree: "tree &lt;n_nodes&gt;", one node per line:
 *        "&lt;feature_idx&gt; &lt;threshold&gt; &lt;left&gt; &lt;right&gt; &lt;is_leaf&gt; &lt;value&gt;"
 *        (walk from node 0: x[f] &lt;= thr ? left : right; sum leaves + baseline,
 *        then sigmoid)
 * </pre>
 *
 * The score is the win probability scaled to an int (0..1,000,000) so it
 * slots into the {@link GameStateEvaluator.Score} comparisons unchanged.
 */
public final class LearnedEvaluator {
    private final String[] featureNames;

    // MLP form
    private double[] mean;
    private double[] std;
    private final List<double[][]> weights = new ArrayList<>(); // [in][out] per layer
    private final List<double[]> biases = new ArrayList<>();

    // GBDT form: per tree, parallel node arrays
    private double gbdtBaseline;
    private final List<int[]> treeFeature = new ArrayList<>();
    private final List<double[]> treeThreshold = new ArrayList<>();
    private final List<int[]> treeLeft = new ArrayList<>();
    private final List<int[]> treeRight = new ArrayList<>();
    private final List<boolean[]> treeIsLeaf = new ArrayList<>();
    private final List<double[]> treeValue = new ArrayList<>();

    public static final int SCORE_SCALE = 1_000_000;

    private LearnedEvaluator(String[] featureNames) {
        this.featureNames = featureNames;
    }

    public static LearnedEvaluator load(String path) throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String[] names = r.readLine().trim().split(",");
            LearnedEvaluator le = new LearnedEvaluator(names);
            String second = r.readLine();
            if (second == null) {
                throw new IOException("truncated model file " + path);
            }
            if (second.trim().startsWith("gbdt ")) {
                le.loadGbdt(r, second.trim(), path);
            } else {
                le.loadMlp(r, second, path);
            }
            return le;
        }
    }

    private void loadGbdt(BufferedReader r, String header, String path) throws IOException {
        String[] head = header.split(" ");
        int nTrees = Integer.parseInt(head[1]);
        gbdtBaseline = Double.parseDouble(head[2]);
        for (int t = 0; t < nTrees; t++) {
            String line = r.readLine();
            if (line == null || !line.startsWith("tree ")) {
                throw new IOException("expected tree header in " + path + ", got: " + line);
            }
            int nNodes = Integer.parseInt(line.trim().split(" ")[1]);
            int[] feat = new int[nNodes];
            double[] thr = new double[nNodes];
            int[] left = new int[nNodes];
            int[] right = new int[nNodes];
            boolean[] leaf = new boolean[nNodes];
            double[] value = new double[nNodes];
            for (int i = 0; i < nNodes; i++) {
                String[] parts = r.readLine().trim().split(" ");
                feat[i] = Integer.parseInt(parts[0]);
                thr[i] = Double.parseDouble(parts[1]);
                left[i] = Integer.parseInt(parts[2]);
                right[i] = Integer.parseInt(parts[3]);
                leaf[i] = !"0".equals(parts[4]);
                value[i] = Double.parseDouble(parts[5]);
            }
            treeFeature.add(feat);
            treeThreshold.add(thr);
            treeLeft.add(left);
            treeRight.add(right);
            treeIsLeaf.add(leaf);
            treeValue.add(value);
        }
        if (treeFeature.isEmpty()) {
            throw new IOException("no trees in " + path);
        }
    }

    private void loadMlp(BufferedReader r, String meanLine, String path) throws IOException {
        mean = parseRow(meanLine, featureNames.length);
        std = parseRow(r.readLine(), featureNames.length);
        String line;
        while ((line = r.readLine()) != null) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (!line.startsWith("layer ")) {
                throw new IOException("expected layer header, got: " + line);
            }
            String[] parts = line.split(" ");
            int rows = Integer.parseInt(parts[1]);
            int cols = Integer.parseInt(parts[2]);
            double[][] w = new double[rows][];
            for (int i = 0; i < rows; i++) {
                w[i] = parseRow(r.readLine(), cols);
            }
            weights.add(w);
            biases.add(parseRow(r.readLine(), cols));
        }
        if (weights.isEmpty()) {
            throw new IOException("no layers in " + path);
        }
        int in = weights.get(0).length;
        if (in != featureNames.length) {
            throw new IOException("first layer expects " + in
                    + " inputs but header names " + featureNames.length + " features");
        }
    }

    private static double[] parseRow(String line, int expected) throws IOException {
        if (line == null) {
            throw new IOException("unexpected end of model file");
        }
        String[] parts = line.trim().split(",");
        if (parts.length != expected) {
            throw new IOException("expected " + expected + " values, got " + parts.length);
        }
        double[] out = new double[expected];
        for (int i = 0; i < expected; i++) {
            out[i] = Double.parseDouble(parts[i]);
        }
        return out;
    }

    /** Win probability for the feature map, in [0, 1]. */
    public double winProbability(Map<String, Integer> features) {
        double[] x = new double[featureNames.length];
        for (int i = 0; i < featureNames.length; i++) {
            Integer v = features.get(featureNames[i]);
            x[i] = v == null ? 0 : v;
        }
        return !treeFeature.isEmpty() ? gbdtForward(x) : mlpForward(x);
    }

    private double gbdtForward(double[] x) {
        double raw = gbdtBaseline;
        for (int t = 0; t < treeFeature.size(); t++) {
            int[] feat = treeFeature.get(t);
            double[] thr = treeThreshold.get(t);
            int[] left = treeLeft.get(t);
            int[] right = treeRight.get(t);
            boolean[] leaf = treeIsLeaf.get(t);
            int n = 0;
            while (!leaf[n]) {
                n = x[feat[n]] <= thr[n] ? left[n] : right[n];
            }
            raw += treeValue.get(t)[n];
        }
        return 1.0 / (1.0 + Math.exp(-raw));
    }

    private double mlpForward(double[] x) {
        double[] a = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            a[i] = (x[i] - mean[i]) / std[i];
        }
        for (int layer = 0; layer < weights.size(); layer++) {
            double[][] w = weights.get(layer);
            double[] b = biases.get(layer);
            double[] next = new double[b.length];
            for (int j = 0; j < b.length; j++) {
                double sum = b[j];
                for (int i = 0; i < w.length; i++) {
                    sum += a[i] * w[i][j];
                }
                next[j] = layer < weights.size() - 1 ? Math.max(0, sum) : sum;
            }
            a = next;
        }
        return 1.0 / (1.0 + Math.exp(-a[0]));
    }

    /** Win probability scaled to an int score (0..{@link #SCORE_SCALE}). */
    public int score(Map<String, Integer> features) {
        return (int) Math.round(winProbability(features) * SCORE_SCALE);
    }
}
