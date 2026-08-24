package com.leadspotting.pipeline.D_post_clustering;

import com.leadspotting.pipeline.B_post_embedding.Embedder;
import com.leadspotting.pipeline.C_similarity_graph.SimilarityGraph;

import com.leadspotting.model.Post;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Plants four well-separated groups as noisy vectors and checks the graph + Leiden recover
 * them. The whole test is offline â€” vectors are generated locally, no OpenAi call â€” so it
 * verifies the clustering logic itself, the way the toy CSV did but without an API key.
 */
class ClusteringTest {

    @Test
    void recoversFourPlantedGroups() {
        Random random = new Random(1);   // fixed seed: the test is deterministic
        int dims = 64;
        int groups = 4;
        int perGroup = 30;

        // A random centre per group; each post is a small wobble around its centre.
        float[][] centres = new float[groups][dims];
        for (float[] centre : centres) {
            for (int d = 0; d < dims; d++) {
                centre[d] = (float) random.nextGaussian();
            }
        }

        List<Post> posts = new ArrayList<>();
        List<Integer> plantedGroup = new ArrayList<>();
        for (int t = 0; t < groups; t++) {
            for (int i = 0; i < perGroup; i++) {
                float[] vector = new float[dims];
                for (int d = 0; d < dims; d++) {
                    vector[d] = centres[t][d] + (float) (random.nextGaussian() * 0.5);
                }
                Post post = new Post("author" + t, "group " + t + " post " + i, LocalDate.of(2026, 1, 1));
                post.setEmbedding(Embedder.normalise(vector));
                posts.add(post);
                plantedGroup.add(t);
            }
        }

        SimilarityGraph graph = SimilarityGraph.build(posts, 15, 0.35);
        new Clusterer(1.0).cluster(posts, graph);

        // Every post of a planted group must land in the same cluster (purity)...
        Map<Integer, Integer> groupToCluster = new HashMap<>();
        for (int i = 0; i < posts.size(); i++) {
            int group = plantedGroup.get(i);
            int cluster = posts.get(i).getClusterId();
            if (groupToCluster.containsKey(group)) {
                assertEquals(groupToCluster.get(group), cluster,
                        "planted group " + group + " was split across clusters");
            } else {
                groupToCluster.put(group, cluster);
            }
        }

        // ...and the four groups must map to four distinct clusters.
        Set<Integer> distinctClusters = new HashSet<>(groupToCluster.values());
        assertEquals(groups, distinctClusters.size());
    }
}
