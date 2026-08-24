package com.leadspotting.cluster;

import com.leadspotting.model.Post;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Small shared helper for turning a flat list of clustered posts into per-cluster buckets.
 *
 * Both the console report (Step 1) and the Summarizer (Step 2) need the same arrangement —
 * posts grouped by clusterId — so the one line that produces it lives here rather than in
 * each caller.
 */
public final class Clusters {

    private Clusters() {
        // Utility holder: never instantiated.
    }

    /**
     * Groups posts by their clusterId.
     *
     * @return a map from cluster id to the posts in that cluster. Posts must already be
     *         clustered (clusterId != -1); an unclustered post would land under key -1.
     */
    public static Map<Integer, List<Post>> byCluster(List<Post> posts) {
        return posts.stream().collect(Collectors.groupingBy(Post::getClusterId));
    }
}
