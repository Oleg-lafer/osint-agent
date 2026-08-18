package com.leadspotnic.persistence;

import com.leadspotnic.llm.OpenAi;
import com.leadspotnic.model.ClusterExtraction;
import com.leadspotnic.model.ClusterSummary;
import com.leadspotnic.model.Post;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Best-effort DB mirror of the existing in-memory/file pipeline. */
public final class DatabasePipeline implements AutoCloseable {

    private AgentDatabase database;
    private long runId;
    private Map<Post, Long> postRows = new IdentityHashMap<>();
    private Map<Integer, Long> clusterRows = Map.of();
    private String sourceTable = "CSV";

    private DatabasePipeline() {}

    public static DatabasePipeline start(String csvPath, String[] args) {
        DatabasePipeline pipeline = new DatabasePipeline();
        try {
            Optional<DatabaseConfig> config = DatabaseConfig.fromEnvironment();
            if (config.isEmpty()) {
                return pipeline;
            }
            pipeline.database = new AgentDatabase(config.get());
            pipeline.sourceTable = csvPath == null ? "post_qualification" : "CSV";
            String postGroupId = postGroupId(args, System.getenv());
            pipeline.runId = pipeline.database.createRun(
                    postGroupId, OpenAi.EMBED_MODEL, csvPath, args);
            System.out.println("Database: started pipeline run " + pipeline.runId
                    + " for post group " + postGroupId);
        } catch (Exception e) {
            pipeline.disable("could not start persistence", e);
        }
        return pipeline;
    }

    public void postsLoaded(List<Post> posts) {
        execute("could not save accepted posts", () ->
                postRows = database.insertPosts(runId, posts, sourceTable));
    }

    public void embeddingsReady() {
        execute("could not save embeddings", () -> database.saveEmbeddings(postRows));
    }

    public void clustersReady(Map<Integer, List<Post>> clusters) {
        execute("could not save clusters", () ->
                clusterRows = database.saveClusters(runId, clusters, postRows));
    }

    public void summariesReady(Map<Integer, ClusterSummary> summaries) {
        for (Map.Entry<Integer, ClusterSummary> entry : summaries.entrySet()) {
            Long rowId = clusterRows.get(entry.getKey());
            if (rowId != null) {
                execute("could not save summary for cluster " + entry.getKey(), () ->
                        database.saveSummary(rowId, entry.getValue()));
            }
        }
    }

    public void extractionsReady(Map<Integer, ClusterExtraction> extractions) {
        for (Map.Entry<Integer, ClusterExtraction> entry : extractions.entrySet()) {
            Long rowId = clusterRows.get(entry.getKey());
            if (rowId != null) {
                execute("could not save extraction for cluster " + entry.getKey(), () ->
                        database.saveExtraction(rowId, entry.getValue()));
            }
        }
    }

    public void complete(String overview) {
        execute("could not complete pipeline run", () -> database.completeRun(runId, overview));
        if (database != null) {
            System.out.println("Database: completed pipeline run " + runId);
        }
    }

    public void fail(Throwable error) {
        if (database == null) {
            return;
        }
        try {
            database.failRun(runId, error);
        } catch (Exception dbError) {
            System.out.println("Database warning: could not mark run " + runId
                    + " failed: " + dbError.getMessage());
        }
    }

    public boolean enabled() {
        return database != null;
    }

    static String postGroupId(String[] args, Map<String, String> environment) {
        for (String arg : args) {
            if (arg.startsWith("--post-group-id=")) {
                return requirePostGroupId(arg.substring("--post-group-id=".length()));
            }
        }
        String configured = environment.get("POST_GROUP_ID");
        return configured == null || configured.isBlank()
                ? UUID.randomUUID().toString()
                : requirePostGroupId(configured);
    }

    private static String requirePostGroupId(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("post_group_id must not be blank");
        }
        if (normalized.length() > 255) {
            throw new IllegalArgumentException("post_group_id must not exceed 255 characters");
        }
        return normalized;
    }

    private void execute(String description, DatabaseWork work) {
        if (database == null) {
            return;
        }
        try {
            work.run();
        } catch (Exception e) {
            disable(description, e);
        }
    }

    private void disable(String description, Exception error) {
        System.out.println("Database warning: " + description + "; continuing locally: "
                + error.getMessage());
        if (database != null && runId != 0) {
            try {
                database.failRun(runId, error);
            } catch (Exception ignored) {
                // The original failure may be a lost connection, so this is best effort only.
            }
        }
        closeQuietly();
        database = null;
    }

    private void closeQuietly() {
        if (database != null) {
            try {
                database.close();
            } catch (Exception ignored) {
                // Persistence is optional; there is no useful recovery action here.
            }
        }
    }

    @Override
    public void close() {
        closeQuietly();
        database = null;
    }

    @FunctionalInterface
    private interface DatabaseWork {
        void run() throws Exception;
    }
}
