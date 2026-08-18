package com.leadspotnic.model;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.List;

/**
 * Step 3: the master consolidated summary — the whole knowledge base in one object.
 *
 * Holds both halves the brief asks to keep: the big-picture overview on top, and every
 * individual cluster summary underneath. Step 4 reads the overview for high-level questions
 * and the per-cluster entries for specific ones.
 */
public record ConsolidatedSummary(
        int totalPosts,
        @JsonAlias("topicCount") int clusterCount,
        String overview,          // the big-picture paragraph
        @JsonAlias("topics") List<ClusterEntry> clusters
) {

    /** One generated cluster, its post count, and its Step 2 summary. */
    public record ClusterEntry(int clusterId, int postCount, ClusterSummary summary) {
    }
}
