package com.leadspotting.pipeline.D_post_clustering;

import com.leadspotting.pipeline.B_post_embedding.Embedder;

import com.leadspotting.model.Post;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline (no API). Plants an oversized cluster and checks the splitter guarantees the cap.
 * Random vectors don't form meaningful sub-clusters, so this mostly exercises the safety net
 * — which is exactly the "no cluster > maxSize" guarantee we care about.
 */
class ClusterSplitterTest {

    @Test
    void noClusterExceedsTheCapAfterSplitting() {
        Random rnd = new Random(1);
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            float[] v = new float[32];
            for (int d = 0; d < 32; d++) {
                v[d] = (float) rnd.nextGaussian();
            }
            Post p = new Post("author", "post " + i, LocalDate.of(2026, 1, 1));
            p.setEmbedding(Embedder.normalise(v));
            p.setClusterId(0); // one big blob
            posts.add(p);
        }

        ClusterSplitter.splitOversized(posts, 100, 15, 0.0, 3.0);

        Map<Integer, List<Post>> byCluster = Clusters.byCluster(posts);
        assertTrue(byCluster.values().stream().allMatch(c -> c.size() <= 100),
                "every cluster must be <= 100 after splitting");
        assertTrue(byCluster.size() >= 3,
                "the 250-post blob should end up as several clusters");
    }
}
