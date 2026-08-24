package com.leadspotting.database;

import com.leadspotting.model.Post;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDatabaseTest {

    private static Post post(String author, String text) {
        return new Post(author, text, LocalDate.of(2026, 8, 12));
    }

    @Test
    void appliesDatabaseEmbeddingsOnlyWhenEveryPostIsCovered() {
        Post first = post("A", "first sufficiently long post");
        Post second = post("B", "second sufficiently long post");
        float[] firstVector = {1, 0};

        boolean applied = AgentDatabase.applyEmbeddingsIfComplete(
                List.of(first, second), Map.of(first.getPostId(), firstVector));

        assertFalse(applied);
        assertNull(first.getEmbedding());
        assertNull(second.getEmbedding());
    }

    @Test
    void appliesCompleteDatabaseEmbeddingSet() {
        Post first = post("A", "first sufficiently long post");
        Post second = post("B", "second sufficiently long post");
        float[] firstVector = {1, 0};
        float[] secondVector = {0, 1};

        boolean applied = AgentDatabase.applyEmbeddingsIfComplete(List.of(first, second), Map.of(
                first.getPostId(), firstVector,
                second.getPostId(), secondVector));

        assertTrue(applied);
        assertArrayEquals(firstVector, first.getEmbedding());
        assertArrayEquals(secondVector, second.getEmbedding());
    }

    @Test
    void requestedRunIdIsOptionalAndParsed() {
        assertNull(AgentDatabase.requestedRunId(Map.of()));
        assertEquals(42L, AgentDatabase.requestedRunId(Map.of("AGENT_PIPELINE_RUN_ID", " 42 ")));
    }

    @Test
    void duplicateCsvOccurrencesReceiveUniqueDeterministicSourceIds() {
        Post first = post("A", "the same sufficiently long post");
        Post duplicate = post("A", "the same sufficiently long post");

        Map<Post, String> ids = AgentDatabase.sourceIds(List.of(first, duplicate));

        assertEquals(Long.toString(first.getPostId()), ids.get(first));
        assertEquals(first.getPostId() + "#2", ids.get(duplicate));
    }
}
