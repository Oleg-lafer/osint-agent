package com.leadspotting.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.leadspotting.model.Post;

public final class ClusterSplitter {

    private ClusterSplitter() {}

    /**
     * Splits every cluster larger than maxSize into sub-clusters, then renumbers all clusters
     * with fresh unique ids.
     *
     * @param subResolution Leiden resolution used when re-clustering an oversized group
     *                      (higher = splits harder). Tune by watching the before/after sizes.
     * @return the new number of clusters
     */
    public static int splitOversized(List<Post> posts, int maxSize,
                                     int k, double minSimilarity, double subResolution) {
        // 1. collect the final leaf groups (each <= maxSize)
        List<List<Post>> finalGroups = new ArrayList<>();
        for (List<Post> group : Clusters.byCluster(posts).values()) {
            if (group.size() <= maxSize) {
                finalGroups.add(group);
            } else {
                splitRecursive(new ArrayList<>(group), maxSize, k, minSimilarity, subResolution, finalGroups);
            }
        }

        // 2. renumber: biggest cluster = id 0, so reports stay readable
        finalGroups.sort((a, b) -> b.size() - a.size());
        for (int id = 0; id < finalGroups.size(); id++) {
            for (Post p : finalGroups.get(id)) {
                p.setClusterId(id);
            }
        }
        System.out.printf("Split: %d clusters after capping at %d posts%n", finalGroups.size(), maxSize);
        return finalGroups.size();
    }

    private static void splitRecursive(List<Post> group, int maxSize, int k,
                                       double minSimilarity, double subResolution,
                                       List<List<Post>> out) {
        if (group.size() <= maxSize) {
            out.add(group);
            return;
        }
        // re-cluster just this group
        SimilarityGraph sub = SimilarityGraph.build(group, k, minSimilarity);
        new Clusterer(subResolution).cluster(group, sub);   // sets LOCAL clusterIds on these posts
        Collection<List<Post>> pieces = Clusters.byCluster(group).values();

        // no progress? (Leiden couldn't split it) -> hard-chunk to guarantee the cap
        boolean noProgress = pieces.size() <= 1
                || pieces.stream().anyMatch(p -> p.size() == group.size());
        if (noProgress) {
            for (int i = 0; i < group.size(); i += maxSize) {
                out.add(new ArrayList<>(group.subList(i, Math.min(i + maxSize, group.size()))));
            }
            return;
        }
        // otherwise recurse into each piece
        for (List<Post> piece : pieces) {
            splitRecursive(piece, maxSize, k, minSimilarity, subResolution, out);
        }
    }
}