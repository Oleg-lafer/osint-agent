package com.leadspotnic.cluster;

import com.leadspotnic.model.Post;
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
 * Plants four well-separated topics as noisy vectors and checks the graph + Leiden recover
 * them. The whole test is offline â€” vectors are generated locally, no OpenAi call â€” so it
 * verifies the clustering logic itself, the way the toy CSV did but without an API key.
 */
class ClusteringTest {

    @Test
    void recoversFourPlantedTopics() {
        Random random = new Random(1);   // fixed seed: the test is deterministic
        int dims = 64;
        int topics = 4;
        int perTopic = 30;

        // A random centre per topic; each post is a small wobble around its centre.
        float[][] centres = new float[topics][dims];
        for (float[] centre : centres) {
            for (int d = 0; d < dims; d++) {
                centre[d] = (float) random.nextGaussian();
            }
        }

        List<Post> posts = new ArrayList<>();
        List<Integer> plantedTopic = new ArrayList<>();
        for (int t = 0; t < topics; t++) {
            for (int i = 0; i < perTopic; i++) {
                float[] vector = new float[dims];
                for (int d = 0; d < dims; d++) {
                    vector[d] = centres[t][d] + (float) (random.nextGaussian() * 0.5);
                }
                Post post = new Post("author" + t, "topic " + t + " post " + i, LocalDate.of(2026, 1, 1));
                post.setEmbedding(Embedder.normalise(vector));
                posts.add(post);
                plantedTopic.add(t);
            }
        }

        SimilarityGraph graph = SimilarityGraph.build(posts, 15, 0.35);
        new Clusterer(1.0).cluster(posts, graph);

        // Every post of a planted topic must land in the same cluster (purity)...
        Map<Integer, Integer> topicToCluster = new HashMap<>();
        for (int i = 0; i < posts.size(); i++) {
            int topic = plantedTopic.get(i);
            int cluster = posts.get(i).getClusterId();
            if (topicToCluster.containsKey(topic)) {
                assertEquals(topicToCluster.get(topic), cluster,
                        "planted topic " + topic + " was split across clusters");
            } else {
                topicToCluster.put(topic, cluster);
            }
        }

        // ...and the four topics must map to four distinct clusters.
        Set<Integer> distinctClusters = new HashSet<>(topicToCluster.values());
        assertEquals(topics, distinctClusters.size());
    }
}
