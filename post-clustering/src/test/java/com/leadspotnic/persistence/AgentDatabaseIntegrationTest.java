package com.leadspotnic.persistence;

import com.leadspotnic.model.ClusterExtraction;
import com.leadspotnic.model.ClusterSummary;
import com.leadspotnic.model.Entity;
import com.leadspotnic.model.Post;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Explicitly opt-in because it briefly writes a synthetic run to RDS, then deletes it. */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class AgentDatabaseIntegrationTest {

    @Test
    void writesAndReloadsACompleteSyntheticRun() throws Exception {
        DatabaseConfig config = DatabaseConfig.fromEnvironment().orElseThrow();
        long runId = 0;
        try {
            Post first = new Post("Smoke A", "First synthetic database smoke-test post",
                    LocalDate.of(2026, 8, 12));
            Post second = new Post("Smoke B", "Second synthetic database smoke-test post",
                    LocalDate.of(2026, 8, 12));
            first.setEmbedding(new float[] {1, 0});
            second.setEmbedding(new float[] {0, 1});
            List<Post> posts = List.of(first, second);

            try (AgentDatabase database = new AgentDatabase(config)) {
                runId = database.createRun("smoke-test-model", "smoke-test.csv",
                        new String[] {"--smoke-test"});
                Map<Post, Long> postRows = database.insertPosts(runId, posts);
                database.saveEmbeddings(postRows);

                Map<Integer, List<Post>> clusters = new LinkedHashMap<>();
                clusters.put(0, posts);
                Map<Integer, Long> clusterRows = database.saveClusters(runId, clusters, postRows);
                database.saveSummary(clusterRows.get(0),
                        new ClusterSummary("Smoke users", "DB smoke test", "", "2026-08-12"));
                database.saveExtraction(clusterRows.get(0), new ClusterExtraction(0,
                        List.of(new Entity("DB smoke test", "topic", "Synthetic evidence",
                                List.of(first.getPostId()))),
                        List.of(), List.of()));
                database.completeRun(runId, "Synthetic database integration smoke test.");

                DatabaseRun loaded = database.loadRun(runId);
                assertEquals(2, loaded.knowledgeBase().totalPosts());
                assertEquals(1, loaded.knowledgeBase().topicCount());
                assertEquals(1, loaded.extractions().size());
                assertEquals(2, loaded.embeddings().size());
                assertEquals("smoke-test.csv", loaded.csvPath());
            }
        } finally {
            if (runId != 0) {
                try (var connection = DriverManager.getConnection(
                        config.jdbcUrl(), config.user(), config.password());
                    var delete = connection.prepareStatement(
                             "DELETE FROM AGENT_pipeline_runs WHERE id = ?")) {
                    delete.setLong(1, runId);
                    assertEquals(1, delete.executeUpdate());
                }
            }
        }
    }
}
