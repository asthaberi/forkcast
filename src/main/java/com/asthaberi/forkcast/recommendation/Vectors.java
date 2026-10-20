package com.asthaberi.forkcast.recommendation;

import java.util.List;

/** The little vector maths recommendations need. */
final class Vectors {

    private Vectors() {
    }

    /**
     * Weighted mean of unit-length copies of the vectors, so each one counts by its weight alone, not by its
     * length (Gemini vectors truncated to 768 dimensions aren't exactly unit length).
     */
    static float[] weightedMean(List<Weighted> vectors) {
        if (vectors.isEmpty()) {
            throw new IllegalArgumentException("No vectors to average");
        }
        float[] mean = new float[vectors.getFirst().vector().length];
        for (Weighted weighted : vectors) {
            double scale = weighted.weight() / norm(weighted.vector());
            for (int d = 0; d < mean.length; d++) {
                mean[d] += (float) (weighted.vector()[d] * scale);
            }
        }
        return mean;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int d = 0; d < a.length; d++) {
            dot += a[d] * b[d];
        }
        return dot / (norm(a) * norm(b));
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }

    record Weighted(float[] vector, double weight) {
    }
}
