package com.leadspotting.database;

import com.leadspotting.llm.OpenAi;
import com.leadspotting.llm.PipelineUsage;
import com.leadspotting.model.ClusterExtraction;
import com.leadspotting.model.ClusterSummary;
import com.leadspotting.model.Post;
import com.leadspotting.pipeline.H_result_storage.StorageMode;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Database output for pipeline modes that require durable MySQL persistence. */
public final class DatabasePipeline implements AutoCloseable {

    private AgentDatabase database;
    private long runId;
    private Map<Post, Long> postRows = new IdentityHashMap<>();
    private Map<Integer, Long> clusterRows = Map.of();
    private String sourceTable = "CSV";
    private PipelineUsage usage;
    private long startedAtMs;
    private int processedPostCount;

    private DatabasePipeline() {}

    public static DatabasePipeline start(String csvPath, String[] args) {
        return start(csvPath, args, new PipelineUsage(), StorageMode.fromEnvironment());
    }

    public static DatabasePipeline start(String csvPath, String[] args, PipelineUsage usage) {
        return start(csvPath, args, usage, StorageMode.fromEnvironment());
    }

    public static DatabasePipeline start(String csvPath, String[] args, PipelineUsage usage,
                                         StorageMode storageMode) {
        DatabasePipeline pipeline = new DatabasePipeline();
        pipeline.usage = usage;
        pipeline.startedAtMs = System.currentTimeMillis();
        if (!storageMode.writesDatabase()) {
            return pipeline;
        }
        try {
            DatabaseConfig config = DatabaseConfig.fromEnvironment().orElseThrow(() ->
                    new IllegalStateException("STORAGE_MODE="
                            + storageMode.name().toLowerCase()
                            + " requires DB_CREDENTIALS_FILE"));
            pipeline.database = new AgentDatabase(config);
            pipeline.sourceTable = csvPath == null ? "post_qualification" : "CSV";
            String postGroupId = postGroupId(args, System.getenv());
            pipeline.runId = pipeline.database.createRun(
                    postGroupId, OpenAi.EMBED_MODEL, csvPath, args);
            System.out.println("Database: started pipeline run " + pipeline.runId
                    + " for post group " + postGroupId);
        } catch (Exception e) {
            pipeline.closeQuietly();
            throw new IllegalStateException("Could not start required database storage", e);
        }
        return pipeline;
    }

    public void postsLoaded(List<Post> posts) {
        postsLoaded(posts, sourceTable);
    }

    public void postsLoaded(List<Post> posts, String sourceTable) {
        processedPostCount += posts.size();
        execute("could not save accepted posts", () ->
                postRows.putAll(database.insertPosts(runId, posts, sourceTable)));
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
        execute("could not complete pipeline run", () -> database.completeRun(runId, overview,
                usage.snapshot(System.currentTimeMillis() - startedAtMs), processedPostCount));
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
            fail(e);
            throw new IllegalStateException("Database storage failed: " + description, e);
        }
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
