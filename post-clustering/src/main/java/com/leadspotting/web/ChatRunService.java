package com.leadspotting.web;

import com.leadspotting.chat_agent.Agent;
import com.leadspotting.chat_agent.PostIndex;
import com.leadspotting.chat_agent.PostStore;
import com.leadspotting.chat_agent.ClusterIndex;
import com.leadspotting.pipeline.B_post_embedding.Embedder;
import com.leadspotting.database.AgentDatabase;
import com.leadspotting.database.DatabaseConfig;
import com.leadspotting.database.DatabaseRun;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Lists preprocessing runs and lazily builds one cached answering context per selected run. */
final class ChatRunService {
    private final Optional<DatabaseConfig> config;
    private final long defaultRunId;
    private final Map<Long, RunContext> contexts = new ConcurrentHashMap<>();

    ChatRunService(Optional<DatabaseConfig> config, RunContext initialContext) {
        this.config = config;
        this.defaultRunId = initialContext.pipelineRunId();
        contexts.put(defaultRunId, initialContext);
    }

    long defaultRunId() {
        return defaultRunId;
    }

    List<AgentDatabase.AvailableRun> availableRuns() throws Exception {
        if (config.isEmpty()) {
            return fallbackRunList();
        }
        try (AgentDatabase database = new AgentDatabase(config.orElseThrow())) {
            return database.listAvailableRuns();
        } catch (Exception e) {
            System.out.println("Chat run listing unavailable; exposing the loaded default only: "
                    + e.getClass().getSimpleName());
            return fallbackRunList();
        }
    }

    private List<AgentDatabase.AvailableRun> fallbackRunList() {
        RunContext context = contexts.get(defaultRunId);
        return List.of(new AgentDatabase.AvailableRun(defaultRunId, null, null,
                context.knowledgeBase().totalPosts(), context.knowledgeBase().clusterCount()));
    }

    RunContext context(long runId) throws RunUnavailableException {
        RunContext cached = contexts.get(runId);
        if (cached != null) return cached;
        if (config.isEmpty()) throw new RunUnavailableException(runId);
        try {
            RunContext loaded = load(runId);
            RunContext existing = contexts.putIfAbsent(runId, loaded);
            return existing == null ? loaded : existing;
        } catch (Exception e) {
            throw new RunUnavailableException(runId, e);
        }
    }

    private RunContext load(long runId) throws Exception {
        DatabaseRun run;
        PostStore posts;
        try (AgentDatabase database = new AgentDatabase(config.orElseThrow())) {
            run = database.loadRun(runId);
            posts = new PostStore(database.loadPostsForRun(runId));
        }
        if (!AgentDatabase.applyEmbeddingsIfComplete(posts.all(), run.embeddings())) {
            throw new IllegalStateException("Pipeline run " + runId + " has incomplete embeddings");
        }
        Embedder embedder = new Embedder();
        ClusterIndex clusters = new ClusterIndex(run.knowledgeBase(), embedder);
        PostIndex postIndex = new PostIndex(posts, embedder);
        Agent agent = new Agent(run.knowledgeBase(), clusters, run.extractions(), posts, postIndex);
        System.out.println("Chat: loaded selectable pipeline run " + runId);
        return new RunContext(runId, run.knowledgeBase(), clusters, agent);
    }

    record RunContext(long pipelineRunId,
                      com.leadspotting.model.ConsolidatedSummary knowledgeBase,
                      ClusterIndex clusters,
                      Agent agent) {}

    static final class RunUnavailableException extends Exception {
        RunUnavailableException(long runId) {
            super("Pipeline run " + runId + " is not available for chat");
        }

        RunUnavailableException(long runId, Throwable cause) {
            super("Pipeline run " + runId + " is not available for chat", cause);
        }
    }
}
