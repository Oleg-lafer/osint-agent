package com.leadspotnic.model;

import java.util.List;

/**
 * Step 3: the master consolidated summary — the whole knowledge base in one object.
 *
 * Holds both halves the brief asks to keep: the big-picture overview on top, and every
 * individual cluster summary underneath. Step 4 reads the overview for high-level questions
 * and the per-topic entries for specific ones.
 */
public record ConsolidatedSummary(
        int totalPosts,
        int topicCount,
        String overview,          // the big-picture paragraph
        List<TopicEntry> topics   // every topic, each with its full who/what/where/when
) {

    /** One topic: which cluster it is, how many posts, and its Step 2 summary. */
    public record TopicEntry(int clusterId, int postCount, ClusterSummary summary) {
    }
}
