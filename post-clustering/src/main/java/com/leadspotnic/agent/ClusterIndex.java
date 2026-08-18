package com.leadspotnic.agent;

import com.leadspotnic.cluster.Embedder;
import com.leadspotnic.cluster.Vectors;
import com.leadspotnic.model.ClusterSummary;
import com.leadspotnic.model.ConsolidatedSummary;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Searchable vector index over the generated post clusters. */
public class ClusterIndex {
    public record Match(ConsolidatedSummary.ClusterEntry cluster, double score) {}

    private final List<ConsolidatedSummary.ClusterEntry> clusters;
    private final float[][] vectors;
    private final Embedder embedder;

    public ClusterIndex(ConsolidatedSummary kb, Embedder embedder)
            throws IOException, InterruptedException {
        this.embedder = embedder;
        this.clusters = kb.clusters();
        this.vectors = new float[clusters.size()][];
        for (int i = 0; i < clusters.size(); i++) {
            vectors[i] = embedder.embed(clusterText(clusters.get(i)));
        }
        System.out.println("Cluster index: embedded " + clusters.size() + " clusters");
    }

    public List<Match> search(String query, int k) throws IOException, InterruptedException {
        float[] queryVector = embedder.embed(query);
        List<Match> matches = new ArrayList<>(clusters.size());
        for (int i = 0; i < clusters.size(); i++) {
            matches.add(new Match(clusters.get(i), Vectors.dot(queryVector, vectors[i])));
        }
        matches.sort((a, b) -> Double.compare(b.score(), a.score()));
        return matches.subList(0, Math.min(k, matches.size()));
    }

    private static String clusterText(ConsolidatedSummary.ClusterEntry cluster) {
        ClusterSummary summary = cluster.summary();
        return summary.what() + ". " + summary.who() + " " + summary.where();
    }
}
