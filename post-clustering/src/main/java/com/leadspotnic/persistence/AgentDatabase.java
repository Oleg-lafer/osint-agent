package com.leadspotnic.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leadspotnic.model.ClusterExtraction;
import com.leadspotnic.model.ClusterSummary;
import com.leadspotnic.model.ConsolidatedSummary;
import com.leadspotnic.model.Post;
import com.leadspotnic.llm.PipelineUsage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** JDBC persistence for the existing AGENT pipeline tables. */
public final class AgentDatabase implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int WRITE_BATCH_SIZE = 100;
    private final Connection connection;

    public AgentDatabase(DatabaseConfig config) throws SQLException {
        this.connection = DriverManager.getConnection(
                config.jdbcUrl(), config.user(), config.password());
    }

    public long createRun(String postGroupId, String embeddingModel, String csvPath, String[] args)
            throws Exception {
        ObjectNode parameters = JSON.createObjectNode();
        if (csvPath != null) {
            parameters.put("csvPath", csvPath);
        }
        ArrayNode arguments = parameters.putArray("args");
        for (String arg : args) {
            arguments.add(arg);
        }

        String sql = """
                INSERT INTO AGENT_pipeline_runs
                    (post_group_id, status, embedding_model, parameters_json, started_at)
                VALUES (?, 'RUNNING', ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, postGroupId);
            statement.setString(2, embeddingModel);
            statement.setString(3, JSON.writeValueAsString(parameters));
            statement.setTimestamp(4, Timestamp.from(Instant.now()));
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("MySQL did not return the new pipeline run id");
                }
                return keys.getLong(1);
            }
        }
    }

    /** Inserts every accepted source occurrence and returns its DB row id by object identity. */
    public Map<Post, Long> insertPosts(long runId, List<Post> posts) throws Exception {
        return insertPosts(runId, posts, "CSV");
    }

    public Map<Post, Long> insertPosts(long runId, List<Post> posts, String sourceTable) throws Exception {
        String sql = """
                INSERT INTO AGENT_post_processing
                    (pipeline_run_id, source_table, source_post_id, normalized_text, processing_status)
                VALUES (?, ?, ?, ?, 'PENDING')
                """;
        Map<Post, Long> rowIds = new IdentityHashMap<>();
        Map<Post, String> sourceIds = sourceIds(posts);
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int queued = 0;
            for (Post post : posts) {
                statement.setLong(1, runId);
                statement.setString(2, sourceTable);
                statement.setString(3, sourceIds.get(post));
                statement.setString(4, post.getText());
                statement.addBatch();
                if (++queued % WRITE_BATCH_SIZE == 0) {
                    statement.executeBatch();
                    statement.clearBatch();
                }
            }
            if (queued % WRITE_BATCH_SIZE != 0) {
                statement.executeBatch();
            }

            Map<String, Long> rowsBySourceId = new LinkedHashMap<>();
            String select = """
                    SELECT id, source_post_id FROM AGENT_post_processing
                    WHERE pipeline_run_id = ? AND source_table = ?
                    """;
            try (PreparedStatement lookup = connection.prepareStatement(select)) {
                lookup.setLong(1, runId);
                lookup.setString(2, sourceTable);
                try (ResultSet rows = lookup.executeQuery()) {
                    while (rows.next()) {
                        rowsBySourceId.put(rows.getString("source_post_id"), rows.getLong("id"));
                    }
                }
            }
            for (Post post : posts) {
                Long rowId = rowsBySourceId.get(sourceIds.get(post));
                if (rowId == null) {
                    throw new SQLException("Could not find the persisted row for post " + post.getPostId());
                }
                rowIds.put(post, rowId);
            }
            connection.commit();
            return rowIds;
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    static Map<Post, String> sourceIds(List<Post> posts) {
        Map<Post, String> result = new IdentityHashMap<>();
        Map<Long, Integer> occurrences = new LinkedHashMap<>();
        for (Post post : posts) {
            int occurrence = occurrences.merge(post.getPostId(), 1, Integer::sum);
            result.put(post, Long.toString(post.getPostId())
                    + (occurrence == 1 ? "" : "#" + occurrence));
        }
        return result;
    }

    public void saveEmbeddings(Map<Post, Long> rowIds) throws Exception {
        String sql = "UPDATE AGENT_post_processing SET embedding = ?, processing_status = 'EMBEDDED' WHERE id = ?";
        inTransaction(() -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int queued = 0;
                for (Map.Entry<Post, Long> entry : rowIds.entrySet()) {
                    statement.setString(1, JSON.writeValueAsString(entry.getKey().getEmbedding()));
                    statement.setLong(2, entry.getValue());
                    statement.addBatch();
                    if (++queued % WRITE_BATCH_SIZE == 0) {
                        statement.executeBatch();
                        statement.clearBatch();
                    }
                }
                if (queued % WRITE_BATCH_SIZE != 0) {
                    statement.executeBatch();
                }
            }
        });
    }

    /** Saves only final clusters, after oversized-cluster splitting and renumbering. */
    public Map<Integer, Long> saveClusters(long runId, Map<Integer, List<Post>> clusters,
                                           Map<Post, Long> rowIds) throws Exception {
        String insert = """
                INSERT INTO AGENT_clusters (PreProcessing_run_id, cluster_number, post_count)
                VALUES (?, ?, ?)
                """;
        String assign = """
                UPDATE AGENT_post_processing
                SET cluster_id = ?, processing_status = 'CLUSTERED'
                WHERE id = ?
                """;
        Map<Integer, Long> clusterRows = new LinkedHashMap<>();
        inTransaction(() -> {
            try (PreparedStatement clusterStatement = connection.prepareStatement(
                    insert, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement postStatement = connection.prepareStatement(assign)) {
                List<Integer> numbers = new ArrayList<>(clusters.keySet());
                numbers.sort(Integer::compareTo);
                for (int number : numbers) {
                    clusterStatement.setLong(1, runId);
                    clusterStatement.setInt(2, number);
                    clusterStatement.setInt(3, clusters.get(number).size());
                    clusterStatement.executeUpdate();
                    try (ResultSet keys = clusterStatement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("MySQL did not return a cluster id");
                        }
                        long clusterRow = keys.getLong(1);
                        clusterRows.put(number, clusterRow);
                        for (Post post : clusters.get(number)) {
                            Long postRow = rowIds.get(post);
                            if (postRow == null) {
                                throw new SQLException("No persisted row for post " + post.getPostId());
                            }
                            postStatement.setLong(1, clusterRow);
                            postStatement.setLong(2, postRow);
                            postStatement.addBatch();
                        }
                    }
                }
                postStatement.executeBatch();
            }
        });
        return clusterRows;
    }

    public void saveSummary(long clusterRowId, ClusterSummary summary) throws Exception {
        updateJson("UPDATE AGENT_clusters SET cluster_summary = ? WHERE id = ?",
                clusterRowId, summary);
    }

    public void saveExtraction(long clusterRowId, ClusterExtraction extraction) throws Exception {
        updateJson("UPDATE AGENT_clusters SET entities_and_evidence = ? WHERE id = ?",
                clusterRowId, extraction);
    }

    private void updateJson(String sql, long id, Object value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, JSON.writeValueAsString(value));
            statement.setLong(2, id);
            statement.executeUpdate();
        }
    }

    public void completeRun(long runId, String overview) throws SQLException {
        completeRun(runId, overview, new PipelineUsage().snapshot(0), 0);
    }

    public void completeRun(long runId, String overview, PipelineUsage.Snapshot usage,
                            int processedPostCount) throws SQLException {
        String sql = """
                UPDATE AGENT_pipeline_runs
                SET status = 'COMPLETED', dataset_overview = ?, completed_at = ?, duration_ms = ?,
                    input_tokens = ?, output_tokens = ?, total_tokens = ?, estimated_cost_usd = ?,
                    usage_details = ?, processed_post_count = ?
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, overview);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, usage.durationMs());
            statement.setLong(4, usage.inputTokens());
            statement.setLong(5, usage.outputTokens());
            statement.setLong(6, usage.totalTokens());
            statement.setBigDecimal(7, usage.estimatedCostUsd());
            statement.setString(8, usage.usageDetailsJson());
            statement.setInt(9, processedPostCount);
            statement.setLong(10, runId);
            statement.executeUpdate();
        }
    }

    public void failRun(long runId, Throwable error) throws SQLException {
        String sql = """
                UPDATE AGENT_pipeline_runs
                SET status = 'FAILED', error_message = ?, completed_at = ?
                WHERE id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            String message = error == null ? "Pipeline stopped before completion" : error.toString();
            statement.setString(1, message.length() > 20_000 ? message.substring(0, 20_000) : message);
            statement.setTimestamp(2, Timestamp.from(Instant.now()));
            statement.setLong(3, runId);
            statement.executeUpdate();
        }
    }

    public DatabaseRun loadRun(Long requestedRunId) throws Exception {
        RunHeader header = findRun(requestedRunId);
        List<ConsolidatedSummary.ClusterEntry> clusters = new ArrayList<>();
        List<ClusterExtraction> extractions = new ArrayList<>();

        String clusterSql = """
                SELECT cluster_number, post_count, cluster_summary, entities_and_evidence
                FROM AGENT_clusters
                WHERE PreProcessing_run_id = ? AND cluster_summary IS NOT NULL
                ORDER BY cluster_number
                """;
        try (PreparedStatement statement = connection.prepareStatement(clusterSql)) {
            statement.setLong(1, header.id());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    int clusterNumber = rows.getInt("cluster_number");
                    ClusterSummary summary = JSON.readValue(
                            rows.getString("cluster_summary"), ClusterSummary.class);
                    clusters.add(new ConsolidatedSummary.ClusterEntry(
                            clusterNumber, rows.getInt("post_count"), summary));
                    String extractionJson = rows.getString("entities_and_evidence");
                    if (extractionJson != null) {
                        extractions.add(JSON.readValue(extractionJson, ClusterExtraction.class));
                    }
                }
            }
        }
        if (clusters.isEmpty()) {
            throw new IllegalStateException("Pipeline run " + header.id() + " has no summarized clusters");
        }

        int totalPosts;
        String countSql = "SELECT COUNT(*) FROM AGENT_post_processing WHERE pipeline_run_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(countSql)) {
            statement.setLong(1, header.id());
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                totalPosts = row.getInt(1);
            }
        }

        Map<Long, float[]> embeddings = loadEmbeddings(header.id());
        ConsolidatedSummary kb = new ConsolidatedSummary(
                totalPosts, clusters.size(), header.overview(), clusters);
        return new DatabaseRun(header.id(), header.postGroupId(), kb, List.copyOf(extractions),
                Map.copyOf(embeddings), header.csvPath());
    }

    private RunHeader findRun(Long requestedRunId) throws Exception {
        String eligibility = "status = 'COMPLETED' AND dataset_overview IS NOT NULL "
                + "AND EXISTS (SELECT 1 FROM AGENT_clusters c "
                + "WHERE c.PreProcessing_run_id = AGENT_pipeline_runs.id AND c.cluster_summary IS NOT NULL)";
        String sql = requestedRunId == null
                ? "SELECT id, post_group_id, dataset_overview, parameters_json FROM AGENT_pipeline_runs WHERE "
                    + eligibility + " ORDER BY completed_at DESC, id DESC LIMIT 1"
                : "SELECT id, post_group_id, dataset_overview, parameters_json FROM AGENT_pipeline_runs WHERE id = ? AND "
                    + eligibility;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (requestedRunId != null) {
                statement.setLong(1, requestedRunId);
            }
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException(requestedRunId == null
                            ? "No completed summarized pipeline run exists"
                            : "Pipeline run " + requestedRunId + " is not completed and summarized");
                }
                String parametersJson = row.getString("parameters_json");
                String csvPath = null;
                if (parametersJson != null) {
                    JsonNode parameters = JSON.readTree(parametersJson);
                    csvPath = parameters.path("csvPath").asText(null);
                }
                return new RunHeader(row.getLong("id"), row.getString("post_group_id"),
                        row.getString("dataset_overview"), csvPath);
            }
        }
    }

    private Map<Long, float[]> loadEmbeddings(long runId) throws Exception {
        Map<Long, float[]> embeddings = new LinkedHashMap<>();
        String sql = """
                SELECT source_post_id, embedding FROM AGENT_post_processing
                WHERE pipeline_run_id = ? AND embedding IS NOT NULL
                ORDER BY id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String sourceId = rows.getString("source_post_id");
                    int suffix = sourceId.indexOf('#');
                    long contentId = Long.parseLong(suffix < 0 ? sourceId : sourceId.substring(0, suffix));
                    embeddings.putIfAbsent(contentId,
                            JSON.readValue(rows.getString("embedding"), float[].class));
                }
            }
        }
        return embeddings;
    }

    /** Applies DB vectors only when every post is covered, preventing mixed embedding sources. */
    public static boolean applyEmbeddingsIfComplete(Iterable<Post> posts, Map<Long, float[]> embeddings) {
        List<Post> all = new ArrayList<>();
        for (Post post : posts) {
            all.add(post);
            if (!embeddings.containsKey(post.getPostId())) {
                return false;
            }
        }
        for (Post post : all) {
            post.setEmbedding(embeddings.get(post.getPostId()));
        }
        return true;
    }

    public static Optional<DatabaseRun> tryLoadPreferredRun() {
        try {
            Optional<DatabaseConfig> config = DatabaseConfig.fromEnvironment();
            if (config.isEmpty()) {
                return Optional.empty();
            }
            Long requested = requestedRunId(System.getenv());
            try (AgentDatabase database = new AgentDatabase(config.get())) {
                DatabaseRun run = database.loadRun(requested);
                System.out.println("Database: loaded pipeline run " + run.id());
                return Optional.of(run);
            }
        } catch (Exception e) {
            System.out.println("Database unavailable or unusable; using local files: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Completed summarized runs available to online chat, newest first. */
    public List<AvailableRun> listAvailableRuns() throws SQLException {
        String sql = """
                SELECT runs.id, runs.post_group_id, runs.completed_at,
                       (SELECT COUNT(*) FROM AGENT_clusters clusters
                        WHERE clusters.PreProcessing_run_id = runs.id
                          AND clusters.cluster_summary IS NOT NULL) AS cluster_count,
                       (SELECT COUNT(*) FROM AGENT_post_processing posts
                        WHERE posts.pipeline_run_id = runs.id) AS post_count
                FROM AGENT_pipeline_runs runs
                WHERE runs.status = 'COMPLETED' AND runs.dataset_overview IS NOT NULL
                  AND EXISTS (SELECT 1 FROM AGENT_clusters clusters
                              WHERE clusters.PreProcessing_run_id = runs.id
                                AND clusters.cluster_summary IS NOT NULL)
                ORDER BY runs.completed_at DESC, runs.id DESC
                """;
        List<AvailableRun> runs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                Timestamp completed = rows.getTimestamp("completed_at");
                runs.add(new AvailableRun(rows.getLong("id"), rows.getString("post_group_id"),
                        completed == null ? null : completed.toInstant(), rows.getInt("post_count"),
                        rows.getInt("cluster_count")));
            }
        }
        return List.copyOf(runs);
    }

    /** Exact texts and embeddings persisted for a run, used by selectable historical agents. */
    public List<Post> loadPostsForRun(long runId) throws Exception {
        String sql = """
                SELECT source_post_id, normalized_text, embedding
                FROM AGENT_post_processing
                WHERE pipeline_run_id = ?
                ORDER BY id
                """;
        Map<Long, Post> posts = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String sourceId = rows.getString("source_post_id");
                    int suffix = sourceId.indexOf('#');
                    long postId = Long.parseLong(suffix < 0 ? sourceId : sourceId.substring(0, suffix));
                    String text = rows.getString("normalized_text");
                    Post post = posts.computeIfAbsent(postId,
                            ignored -> Post.persisted(postId, text));
                    String embedding = rows.getString("embedding");
                    if (embedding != null && post.getEmbedding() == null) {
                        post.setEmbedding(JSON.readValue(embedding, float[].class));
                    }
                }
            }
        }
        return List.copyOf(posts.values());
    }

    /** Loads the selected database run without permitting the server's local-file fallback. */
    public static DatabaseRun loadRequiredRun() throws Exception {
        DatabaseConfig config = DatabaseConfig.fromEnvironment()
                .orElseThrow(() -> new IllegalStateException(
                        "DB_CREDENTIALS_FILE is required in database-only mode"));
        Long requested = requestedRunId(System.getenv());
        try (AgentDatabase database = new AgentDatabase(config)) {
            DatabaseRun run = database.loadRun(requested);
            System.out.println("Database: loaded required pipeline run " + run.id());
            return run;
        }
    }

    static Long requestedRunId(Map<String, String> environment) {
        String value = environment.get("AGENT_PIPELINE_RUN_ID");
        return value == null || value.isBlank() ? null : Long.parseLong(value.trim());
    }

    private void inTransaction(SqlWork work) throws Exception {
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            work.run();
            connection.commit();
        } catch (Exception e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private record RunHeader(long id, String postGroupId, String overview, String csvPath) {}

    public record AvailableRun(long id, String postGroupId, Instant completedAt,
                               int postCount, int clusterCount) {}

    @FunctionalInterface
    private interface SqlWork {
        void run() throws Exception;
    }
}
