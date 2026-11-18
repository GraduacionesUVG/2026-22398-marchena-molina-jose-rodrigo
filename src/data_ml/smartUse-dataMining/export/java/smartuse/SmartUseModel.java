package smartuse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;

/**
 * One SmartUse pattern model (F or S): the merged ensemble of the XGBoost seeds.
 *
 * <p>score(x) = calibrator(sigmoid(mean over seeds of (baseMargin + sum of tree leaves))).
 * Tree semantics follow XGBoost: features and thresholds are float32; at an internal
 * node go to "yes" if x &lt; threshold, else "no"; NaN goes to "missing".
 * The calibrator is either none ("raw") or Platt: sigmoid(a * logit(p) + b).
 *
 * <p>The feature vector must follow {@link #features()} exactly (see
 * README.md, section 9). Model files are written by experiments/export.py.
 */
public final class SmartUseModel {

    private static final double EPS = 1e-6;

    private final String pattern;
    private final String[] features;
    private final boolean platt;
    private final double plattA;
    private final double plattB;
    private final double cutoff;
    private final double[] baseMargins;
    private final Tree[][] trees;

    private static final class Tree {
        int[] feature;
        float[] threshold;
        int[] yes;
        int[] no;
        int[] missing;
        double[] leaf;

        double leafFor(float[] x) {
            int node = 0;
            while (feature[node] >= 0) {
                float v = x[feature[node]];
                if (Float.isNaN(v)) {
                    node = missing[node];
                } else if (v < threshold[node]) {
                    node = yes[node];
                } else {
                    node = no[node];
                }
            }
            return leaf[node];
        }
    }

    private SmartUseModel(String pattern, String[] features, boolean platt, double plattA, double plattB,
                          double cutoff, double[] baseMargins, Tree[][] trees) {
        this.pattern = pattern;
        this.features = features;
        this.platt = platt;
        this.plattA = plattA;
        this.plattB = plattB;
        this.cutoff = cutoff;
        this.baseMargins = baseMargins;
        this.trees = trees;
    }

    /** Loads a model file (format in experiments/export.py). */
    public static SmartUseModel load(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        Iterator<String> it = lines.iterator();
        if (!it.next().equals("smartuse-model 1")) {
            throw new IOException("not a smartuse-model 1 file: " + path);
        }
        String pattern = it.next().split(" ")[1];
        String[] featureLine = it.next().split(" ");
        String[] features = featureLine[2].split(",");
        if (features.length != Integer.parseInt(featureLine[1])) {
            throw new IOException("feature count mismatch in " + path);
        }
        String[] cal = it.next().split(" ");
        boolean platt = cal[1].equals("platt");
        double a = platt ? Double.parseDouble(cal[2]) : 1.0;
        double b = platt ? Double.parseDouble(cal[3]) : 0.0;
        double cutoff = Double.parseDouble(it.next().split(" ")[1]);
        int nSeeds = Integer.parseInt(it.next().split(" ")[1]);
        double[] bases = new double[nSeeds];
        Tree[][] trees = new Tree[nSeeds][];
        for (int s = 0; s < nSeeds; s++) {
            String[] seedLine = it.next().split(" ");
            bases[s] = Double.parseDouble(seedLine[1]);
            int nTrees = Integer.parseInt(seedLine[2]);
            trees[s] = new Tree[nTrees];
            for (int k = 0; k < nTrees; k++) {
                int n = Integer.parseInt(it.next().split(" ")[1]);
                Tree t = new Tree();
                t.feature = new int[n];
                t.threshold = new float[n];
                t.yes = new int[n];
                t.no = new int[n];
                t.missing = new int[n];
                t.leaf = new double[n];
                for (int i = 0; i < n; i++) {
                    String[] r = it.next().split(" ");
                    t.feature[i] = Integer.parseInt(r[0]);
                    t.threshold[i] = Float.parseFloat(r[1]);
                    t.yes[i] = Integer.parseInt(r[2]);
                    t.no[i] = Integer.parseInt(r[3]);
                    t.missing[i] = Integer.parseInt(r[4]);
                    t.leaf[i] = Double.parseDouble(r[5]);
                }
                trees[s][k] = t;
            }
        }
        return new SmartUseModel(pattern, features, platt, a, b, cutoff, bases, trees);
    }

    /** Mean margin over seeds. */
    public double margin(float[] x) {
        if (x.length != features.length) {
            throw new IllegalArgumentException("expected " + features.length + " features, got " + x.length);
        }
        double sum = 0.0;
        for (int s = 0; s < trees.length; s++) {
            double m = baseMargins[s];
            for (Tree t : trees[s]) {
                m += t.leafFor(x);
            }
            sum += m;
        }
        return sum / trees.length;
    }

    /** Calibrated probability that the pattern's label is positive. */
    public double score(float[] x) {
        double p = sigmoid(margin(x));
        if (platt) {
            p = Math.min(Math.max(p, EPS), 1 - EPS);
            p = sigmoid(plattA * Math.log(p / (1 - p)) + plattB);
        }
        return p;
    }

    /** True when the score is above the operating cutoff. */
    public boolean fires(float[] x) {
        return score(x) > cutoff;
    }

    public String pattern() {
        return pattern;
    }

    public String[] features() {
        return features.clone();
    }

    public double cutoff() {
        return cutoff;
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }
}
