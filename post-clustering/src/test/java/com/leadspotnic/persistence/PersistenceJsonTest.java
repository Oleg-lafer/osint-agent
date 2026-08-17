package com.leadspotnic.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadspotnic.model.ClusterExtraction;
import com.leadspotnic.model.ClusterSummary;
import com.leadspotnic.model.Entity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PersistenceJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void databaseJsonRoundTripsCurrentSummaryAndExtractionModels() throws Exception {
        ClusterSummary summary = new ClusterSummary("who", "what", "where", "when");
        ClusterExtraction extraction = new ClusterExtraction(7,
                List.of(new Entity("Subject", "topic", "Context", List.of(11L, 12L))),
                List.of(new Entity("Person", "person", "Context", List.of(11L))),
                List.of());

        assertEquals(summary, JSON.readValue(JSON.writeValueAsString(summary), ClusterSummary.class));
        assertEquals(extraction,
                JSON.readValue(JSON.writeValueAsString(extraction), ClusterExtraction.class));
    }
}
