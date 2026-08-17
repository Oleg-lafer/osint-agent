package com.leadspotnic.cluster;

/**
 * Shared vector math for the embedding pipeline: cosine (as a dot product on unit vectors) and
 * normalisation to unit length. One home for both, since the similarity graph and the two
 * search indexes all need them.
 */
public final class Vectors {

    private Vectors() {
    }

    /** Vectors are unit length, so the dot product IS the cosine similarity. */
    public static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    /** Scale to unit length so cosine similarity reduces to a dot product. Zero vectors pass through. */
    public static float[] normalise(float[] vector) {
        double sumOfSquares = 0;
        for (float value : vector) {
            sumOfSquares += value * value;
        }
        double magnitude = Math.sqrt(sumOfSquares);
        if (magnitude == 0) {
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / magnitude);
        }
        return vector;
    }
}
