package smartuse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Scores a CSV of feature vectors and compares with the expected Python scores.
 *
 * <p>CSV: header with the model's feature names, then expected_score, expected_fires.
 * Exits with status 1 if any score differs by more than the tolerance, or if a
 * decision differs away from the cutoff.
 *
 * <pre>java smartuse.ParityCheck MODEL_FILE VECTORS_CSV [TOLERANCE]</pre>
 */
public final class ParityCheck {

    public static void main(String[] args) throws IOException {
        SmartUseModel model = SmartUseModel.load(Path.of(args[0]));
        double tol = args.length > 2 ? Double.parseDouble(args[2]) : 1e-6;
        List<String> lines = Files.readAllLines(Path.of(args[1]), StandardCharsets.UTF_8);
        String[] header = lines.get(0).split(",");
        String[] features = model.features();
        for (int i = 0; i < features.length; i++) {
            if (!header[i].equals(features[i])) {
                throw new IllegalStateException("column " + i + " is " + header[i] + ", model expects " + features[i]);
            }
        }
        int n = features.length;
        double maxDiff = 0.0;
        int decisionMismatches = 0;
        for (int r = 1; r < lines.size(); r++) {
            String[] cells = lines.get(r).split(",");
            float[] x = new float[n];
            for (int i = 0; i < n; i++) {
                x[i] = Float.parseFloat(cells[i]);
            }
            double expected = Double.parseDouble(cells[n]);
            boolean expectedFires = Boolean.parseBoolean(cells[n + 1]);
            double got = model.score(x);
            maxDiff = Math.max(maxDiff, Math.abs(got - expected));
            boolean nearCutoff = Math.abs(expected - model.cutoff()) < tol;
            if (model.fires(x) != expectedFires && !nearCutoff) {
                decisionMismatches++;
            }
        }
        boolean ok = maxDiff <= tol && decisionMismatches == 0;
        System.out.printf("%s: %d vectors, max |java - python| = %.3e, decision mismatches = %d -> %s%n",
                model.pattern(), lines.size() - 1, maxDiff, decisionMismatches, ok ? "PASS" : "FAIL");
        System.exit(ok ? 0 : 1);
    }
}
