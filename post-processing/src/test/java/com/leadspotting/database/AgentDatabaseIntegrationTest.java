package com.leadspotting.database;

import com.leadspotting.model.ClusterExtraction;
import com.leadspotting.model.ClusterSummary;
import com.leadspotting.model.Entity;
import com.leadspotting.model.Post;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Explicitly opt-in because it briefly writes a synthetic run to RDS, then deletes it. */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class AgentDatabaseIntegrationTest {

    @Test
    void writesAndReloadsACompleteSyntheticRun() throws Exception {
        DatabaseConfig config = DatabaseConfig.fromEnvironment().orElseThrow();
        long runId = 0;
        long secondRunId = 0;
        try {
            Post first = new Post("Smoke A", "First synthetic database smoke-test post",
                    LocalDate.of(2026, 8, 12));
            Post second = new Post("Smoke B", "Second synthetic database smoke-test post",
                    LocalDate.of(2026, 8, 12));
            first.setEmbedding(new float[] {1, 0});
            second.setEmbedding(new float[] {0, 1});
            List<Post> posts = List.of(first, second);

            try (AgentDatabase database = new AgentDatabase(config)) {
                runId = database.createRun("smoke-test-group", "smoke-test-model", "smoke-test.csv",
                        new String[] {"--smoke-test"});
                Map<Post, Long> postRows = database.insertPosts(runId, posts);
                database.saveEmbeddings(postRows);

                Map<Integer, List<Post>> clusters = new LinkedHashMap<>();
                clusters.put(0, posts);
                Map<Integer, Long> clusterRows = database.saveClusters(runId, clusters, postRows);
                database.saveSummary(clusterRows.get(0),
                        new ClusterSummary("Smoke users", "DB smoke test", "", "2026-08-12"));
                database.saveExtraction(clusterRows.get(0), new ClusterExtraction(0,
                        List.of(new Entity("DB smoke test", "subject", "Synthetic evidence",
                                List.of(first.getPostId()))),
                        List.of(), List.of()));
                database.completeRun(runId, "Synthetic database integration smoke test.");

                DatabaseRun loaded = database.loadRun(runId);
                assertEquals(2, loaded.knowledgeBase().totalPosts());
                assertEquals(1, loaded.knowledgeBase().clusterCount());
                assertEquals(1, loaded.extractions().size());
                assertEquals(2, loaded.embeddings().size());
                assertEquals("smoke-test.csv", loaded.csvPath());
                assertEquals("smoke-test-group", loaded.postGroupId());

                secondRunId = database.createRun("smoke-test-group", "smoke-test-model",
                        "smoke-test.csv", new String[] {"--smoke-test"});
                assertTrue(secondRunId != runId);

                try (var connection = DriverManager.getConnection(
                        config.jdbcUrl(), config.user(), config.password());
                     var cluster = connection.prepareStatement(
                             "SELECT PreProcessing_run_id FROM AGENT_clusters WHERE id = ?")) {
                    cluster.setLong(1, clusterRows.get(0));
                    try (var row = cluster.executeQuery()) {
                        assertTrue(row.next());
                        assertEquals(runId, row.getLong("PreProcessing_run_id"));
                    }

                    boolean foreignKeyPreserved = false;
                    try (var keys = connection.getMetaData().getImportedKeys(
                            connection.getCatalog(), null, "AGENT_clusters")) {
                        while (keys.next()) {
                            if ("PreProcessing_run_id".equals(keys.getString("FKCOLUMN_NAME"))
                                    && "AGENT_pipeline_runs".equals(keys.getString("PKTABLE_NAME"))
                                    && "id".equals(keys.getString("PKCOLUMN_NAME"))) {
                                foreignKeyPreserved = true;
                            }
                        }
                    }
                    assertTrue(foreignKeyPreserved);

                    boolean uniqueIndexPreserved = false;
                    try (var indexes = connection.getMetaData().getIndexInfo(
                            connection.getCatalog(), null, "AGENT_clusters", true, false)) {
                        while (indexes.next()) {
                            if ("uq_agent_topic_cluster".equals(indexes.getString("INDEX_NAME"))
                                    && "PreProcessing_run_id".equals(indexes.getString("COLUMN_NAME"))) {
                                uniqueIndexPreserved = true;
                            }
                        }
                    }
                    assertTrue(uniqueIndexPreserved);

                    try (var groupedRuns = connection.prepareStatement(
                            "SELECT COUNT(*), COUNT(DISTINCT id) FROM AGENT_pipeline_runs "
                                    + "WHERE post_group_id = ?")) {
                        groupedRuns.setString(1, "smoke-test-group");
                        try (var row = groupedRuns.executeQuery()) {
                            assertTrue(row.next());
                            assertEquals(2, row.getInt(1));
                            assertEquals(2, row.getInt(2));
                        }
                    }
                }
            }
        } finally {
            if (runId != 0 || secondRunId != 0) {
                try (var connection = DriverManager.getConnection(
                        config.jdbcUrl(), config.user(), config.password());
                    var delete = connection.prepareStatement(
                             "DELETE FROM AGENT_pipeline_runs WHERE id = ?")) {
                    if (secondRunId != 0) {
                        delete.setLong(1, secondRunId);
                        assertEquals(1, delete.executeUpdate());
                    }
                    if (runId != 0) {
                        delete.setLong(1, runId);
                        assertEquals(1, delete.executeUpdate());
                    }
                }
            }
        }
    }
}
