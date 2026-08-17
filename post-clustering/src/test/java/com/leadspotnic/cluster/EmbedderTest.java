package com.leadspotnic.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbedderTest {

    @Test
    void normaliseScalesToUnitLength() {
        float[] v = Embedder.normalise(new float[]{3f, 4f});   // length 5
        assertEquals(0.6f, v[0], 1e-6);
        assertEquals(0.8f, v[1], 1e-6);

        double magnitude = Math.sqrt(v[0] * v[0] + v[1] * v[1]);
        assertEquals(1.0, magnitude, 1e-6);
    }

    @Test
    void normaliseLeavesTheZeroVectorAlone() {
        float[] v = Embedder.normalise(new float[]{0f, 0f, 0f});
        assertEquals(0f, v[0]);
        assertEquals(0f, v[1]);
        assertEquals(0f, v[2]);
    }
}
