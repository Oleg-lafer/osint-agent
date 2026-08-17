package com.leadspotnic.persistence;

import com.leadspotnic.model.ClusterExtraction;
import com.leadspotnic.model.ConsolidatedSummary;

import java.util.List;
import java.util.Map;

/** A completed pipeline run reconstructed from the three AGENT tables for server startup. */
public record DatabaseRun(
        long id,
        ConsolidatedSummary knowledgeBase,
        List<ClusterExtraction> extractions,
        Map<Long, float[]> embeddings,
        String csvPath) {
}
