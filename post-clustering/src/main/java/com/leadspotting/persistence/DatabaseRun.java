package com.leadspotting.persistence;

import com.leadspotting.model.ClusterExtraction;
import com.leadspotting.model.ConsolidatedSummary;

import java.util.List;
import java.util.Map;

/** A completed pipeline run reconstructed from the three AGENT tables for server startup. */
public record DatabaseRun(
        long id,
        String postGroupId,
        ConsolidatedSummary knowledgeBase,
        List<ClusterExtraction> extractions,
        Map<Long, float[]> embeddings,
        String csvPath) {
}
