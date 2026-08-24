package com.leadspotting.web;

import com.leadspotting.model.ClusterExtraction;
import com.leadspotting.model.ConsolidatedSummary;
import com.leadspotting.pipeline.B_post_embedding.Embedder;
import com.leadspotting.pipeline.H_result_storage.KnowledgeBase;
import com.leadspotting.pipeline.H_result_storage.StorageMode;
import com.leadspotting.chat_agent.PostIndex;
import com.leadspotting.chat_agent.PostStore;
import com.leadspotting.chat_agent.ClusterIndex;
import com.leadspotting.chat_agent.Agent;
import com.leadspotting.database.AgentDatabase;
import com.leadspotting.database.DatabaseRun;
import com.leadspotting.database.DatabaseConfig;
import com.leadspotting.database.ChatDatabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.javalin.Javalin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Step 4.5: the web server. Loads the knowledge base once at startup, builds the search
 * index and the agent, then serves:
 *
 *   GET  /status  — is the dataset processed? (the frontend's data-source indicator)
 *   POST /chat     — { "query": "..." } -> { "answer": "...", "sources": [...] }
 *
 * Run it after the pipeline has produced knowledge-base.json:
 *   mvn -q compile exec:java -Dexec.mainClass=com.leadspotting.Server
 */
public class Server {

    private static final int PORT = 7070;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The default export, used when neither an argument nor POSTS_CSV overrides it. */
    private static final String DEFAULT_POSTS_CSV = "src/main/resources/posts.csv";

    public static void main(String[] args) throws Exception {
        StorageMode storageMode = StorageMode.fromEnvironment();
        if (envBoolean("DATABASE_ONLY") && storageMode.writesLocal()) {
            throw new IllegalStateException(
                    "DATABASE_ONLY=true conflicts with STORAGE_MODE="
                            + storageMode.name().toLowerCase());
        }
        boolean databaseOnly = storageMode == StorageMode.DATABASE;
        Optional<DatabaseRun> databaseRun = switch (storageMode) {
            case DATABASE -> Optional.of(AgentDatabase.loadRequiredRun());
            case BOTH -> AgentDatabase.tryLoadPreferredRun();
            case LOCAL -> Optional.empty();
        };

        if (databaseOnly && hasExplicitCsv(args)) {
            throw new IllegalStateException(
                    "POSTS_CSV and CSV arguments are forbidden in database-only mode");
        }
        if (databaseOnly && databaseRun.orElseThrow().csvPath() != null) {
            throw new IllegalStateException("Database-only mode requires a database-sourced pipeline run; run "
                    + databaseRun.orElseThrow().id() + " was created from CSV");
        }

        // The posts CSV is configuration, not code: a command-line argument wins, else the
        // POSTS_CSV environment variable, then the selected run's recorded path, then the default.
        String csvPath = postsCsvPath(args, databaseRun.map(DatabaseRun::csvPath).orElse(null));

        // Prefer a completed database run; retain the existing local-file path as fallback.
        ConsolidatedSummary kb = databaseRun
                .map(DatabaseRun::knowledgeBase)
                .orElseGet(Server::loadLocalKnowledgeBase);
        ClusterIndex index = new ClusterIndex(kb, new Embedder(storageMode.writesLocal()));

        // Task 3: also load the drill-down data — the entities (which carry post ids) and the
        // full posts (id -> text), so the agent can reach individual posts. The CSV is the same
        // export the pipeline reads; the ids match those in entities.json.
        List<ClusterExtraction> entities = databaseRun
                .map(DatabaseRun::extractions)
                .orElseGet(Server::loadLocalEntities);
        Optional<DatabaseConfig> sourceConfig = storageMode.writesDatabase()
                ? DatabaseConfig.fromEnvironment()
                : Optional.empty();
        PostStore posts;
        if (databaseRun.isPresent() && sourceConfig.isPresent()) {
            try (AgentDatabase database = new AgentDatabase(sourceConfig.orElseThrow())) {
                posts = new PostStore(database.loadPostsForRun(databaseRun.orElseThrow().id()));
            }
            System.out.println("Loading exact persisted posts for pipeline run "
                    + databaseRun.orElseThrow().id());
        } else {
            if (databaseOnly) {
                throw new IllegalStateException("Database-only mode could not load persisted run posts");
            }
            System.out.println("Loading posts from: " + csvPath);
            posts = PostStore.fromCsv(Path.of(csvPath));
        }
        if (databaseRun.isPresent()) {
            boolean complete = AgentDatabase.applyEmbeddingsIfComplete(
                    posts.all(), databaseRun.get().embeddings());
            if (databaseOnly && !complete) {
                throw new IllegalStateException("Database-only mode requires a database embedding for every "
                        + "loaded post; local embedding-cache fallback is disabled");
            }
            System.out.println(complete
                    ? "Database: applied all post embeddings for the selected run"
                    : "Database: embedding coverage is incomplete; using the local cache");
        }
        PostIndex postIndex = new PostIndex(posts, new Embedder(storageMode.writesLocal()));
        System.out.printf("Loaded %d entities' clusters and %d posts for drill-down.%n",
                entities.size(), posts.size());
        if (databaseOnly) {
            System.out.println("Data source: MySQL only");
            System.out.println("Database run: " + databaseRun.orElseThrow().id());
            System.out.println("Local fallback: disabled");
        }

        Agent agent = new Agent(kb, index, entities, posts, postIndex);
        Optional<ChatDatabase> chatDatabase = storageMode.writesDatabase()
                ? ChatDatabase.fromEnvironment()
                : Optional.empty();
        long initialRunId = databaseRun.map(DatabaseRun::id).orElse(-1L);
        ChatRunService runs = new ChatRunService(sourceConfig,
                new ChatRunService.RunContext(initialRunId, kb, index, agent));

        // Enable CORS so the React frontend (served from a different port) can call these
        // endpoints — browsers block cross-origin requests unless the server allows them.
        Javalin app = Javalin.create(config ->
                config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()))
        ).start(PORT);

        // Any uncaught error comes back as clean JSON rather than an HTML stack trace.
        app.exception(Exception.class, (e, ctx) -> {
            ObjectNode error = JSON.createObjectNode();
            error.put("error", e.getMessage());
            ctx.status(500).contentType("application/json").result(error.toString());
        });

        // Data-source indicator: confirms the CSV has been processed and summarised.
        app.get("/status", ctx -> {
            long requestedRun = queryRunId(ctx.queryParam("pipelineRunId"), runs.defaultRunId());
            ChatRunService.RunContext selected;
            try {
                selected = runs.context(requestedRun);
            } catch (ChatRunService.RunUnavailableException e) {
                jsonError(ctx, 404, e.getMessage());
                return;
            }
            ObjectNode status = JSON.createObjectNode();
            status.put("ready", true);
            status.put("pipelineRunId", selected.pipelineRunId());
            status.put("totalPosts", selected.knowledgeBase().totalPosts());
            status.put("clusterCount", selected.knowledgeBase().clusterCount());
            ctx.contentType("application/json").result(status.toString());
        });

        app.get("/runs", ctx -> {
            ArrayNode available = JSON.createArrayNode();
            for (AgentDatabase.AvailableRun run : runs.availableRuns()) {
                ObjectNode node = available.addObject();
                node.put("pipelineRunId", run.id());
                node.put("isDefault", run.id() == runs.defaultRunId());
                if (run.postGroupId() == null) node.putNull("postGroupId");
                else node.put("postGroupId", run.postGroupId());
                if (run.completedAt() == null) node.putNull("completedAt");
                else node.put("completedAt", run.completedAt().toString());
                node.put("postCount", run.postCount());
                node.put("clusterCount", run.clusterCount());
            }
            ctx.contentType("application/json").result(available.toString());
        });

        // The chat endpoint.
        app.post("/chat", ctx -> {
            JsonNode body = JSON.readTree(ctx.body());
            String query = body.path("query").asText("");
            if (query.isBlank()) {
                ObjectNode error = JSON.createObjectNode();
                error.put("error", "query is required");
                ctx.status(400).contentType("application/json").result(error.toString());
                return;
            }

            String requestedSessionId = body.path("sessionId").asText(null);
            String userId = body.path("userId").asText(null);
            Long requestedRunId = body.hasNonNull("pipelineRunId")
                    ? body.path("pipelineRunId").asLong() : null;
            String sessionId = null;
            Long pendingAssistantId = null;
            List<Agent.ChatMessage> history = List.of();
            long selectedRunId = requestedRunId == null ? runs.defaultRunId() : requestedRunId;
            boolean persistenceUsable = chatDatabase.isPresent();

            if (persistenceUsable && requestedSessionId != null && !requestedSessionId.isBlank()) {
                try {
                    ChatDatabase.ChatSession session = chatDatabase.orElseThrow()
                            .loadSession(requestedSessionId);
                    selectedRunId = session.pipelineRunId();
                    if (requestedRunId != null && requestedRunId != selectedRunId) {
                        jsonError(ctx, 409, "Chat session belongs to pipeline run " + selectedRunId);
                        return;
                    }
                } catch (ChatDatabase.SessionNotFoundException e) {
                    jsonError(ctx, 404, e.getMessage());
                    return;
                } catch (ChatDatabase.SessionClosedException e) {
                    jsonError(ctx, 409, e.getMessage());
                    return;
                } catch (Exception e) {
                    System.out.println("Chat persistence failed; answering statelessly: "
                            + e.getClass().getSimpleName());
                    persistenceUsable = false;
                }
            }

            ChatRunService.RunContext selectedRun;
            try {
                selectedRun = runs.context(selectedRunId);
            } catch (ChatRunService.RunUnavailableException e) {
                jsonError(ctx, 404, e.getMessage());
                return;
            }

            if (persistenceUsable) {
                try {
                    ChatDatabase database = chatDatabase.get();
                    sessionId = requestedSessionId == null || requestedSessionId.isBlank()
                            ? database.createSession(userId, selectedRunId)
                            : requestedSessionId;
                    history = database.loadHistory(sessionId);
                    pendingAssistantId = database.beginTurn(sessionId, query);
                } catch (ChatDatabase.SessionNotFoundException e) {
                    jsonError(ctx, 404, e.getMessage());
                    return;
                } catch (ChatDatabase.SessionClosedException e) {
                    jsonError(ctx, 409, e.getMessage());
                    return;
                } catch (Exception e) {
                    System.out.println("Chat persistence failed; answering statelessly: "
                            + e.getClass().getSimpleName());
                    sessionId = null;
                    pendingAssistantId = null;
                    history = List.of();
                }
            }

            long started = System.currentTimeMillis();
            Agent.Answer answer;
            try {
                answer = selectedRun.agent().answer(query, history);
            } catch (Exception e) {
                if (pendingAssistantId != null) {
                    try {
                        chatDatabase.orElseThrow().failTurn(pendingAssistantId, e);
                    } catch (Exception persistenceError) {
                        System.out.println("Could not record failed chat attempt: "
                                + persistenceError.getClass().getSimpleName());
                    }
                }
                throw e;
            }
            long elapsedMs = System.currentTimeMillis() - started;
            System.out.printf("/chat answered in %.2f s: \"%s\"%n", elapsedMs / 1000.0, query);

            ObjectNode response = JSON.createObjectNode();
            if (sessionId == null) response.putNull("sessionId");
            else response.put("sessionId", sessionId);
            response.put("answer", answer.text());
            response.put("elapsedMs", elapsedMs);
            ArrayNode sources = response.putArray("sources");
            for (ClusterIndex.Match m : answer.sources()) {
                ObjectNode source = sources.addObject();
                source.put("clusterId", m.cluster().clusterId());
                source.put("postCount", m.cluster().postCount());
                source.put("what", m.cluster().summary().what());
                source.put("score", Math.round(m.score() * 1000) / 1000.0);
            }
            ArrayNode researchLog = response.putArray("researchLog");
            for (Agent.ResearchStep step : answer.researchLog()) {
                ObjectNode node = researchLog.addObject();
                node.put("title", step.title());
                node.put("detail", step.detail());
            }
            if (pendingAssistantId != null) {
                try {
                    List<Integer> matchedClusterIds = answer.sources().stream()
                            .map(match -> match.cluster().clusterId()).toList();
                    chatDatabase.orElseThrow().completeTurn(
                            pendingAssistantId, answer.text(), elapsedMs, matchedClusterIds,
                            sources, researchLog);
                } catch (Exception e) {
                    System.out.println("Chat persistence failed after answering: "
                            + e.getClass().getSimpleName());
                    response.putNull("sessionId");
                }
            }
            ctx.contentType("application/json").result(JSON.writeValueAsString(response));
        });

        System.out.println("\nAgent ready on http://localhost:" + PORT);
        System.out.println("Try:  curl -s localhost:" + PORT + "/status");
        System.out.println("      curl -s -X POST localhost:" + PORT
                + "/chat -H 'Content-Type: application/json' -d '{\"query\":\"what are people talking about?\"}'");
    }

    /** The posts CSV path: argument, environment, selected run, then the existing default. */
    static String postsCsvPath(String[] args, String runCsvPath) {
        if (args.length > 0 && !args[0].isBlank()) {
            return args[0];
        }
        String fromEnv = System.getenv("POSTS_CSV");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        if (runCsvPath != null && !runCsvPath.isBlank()) {
            return runCsvPath;
        }
        return DEFAULT_POSTS_CSV;
    }

    private static void jsonError(io.javalin.http.Context ctx, int status, String message) {
        ObjectNode error = JSON.createObjectNode();
        error.put("error", message);
        ctx.status(status).contentType("application/json").result(error.toString());
    }

    private static long queryRunId(String value, long fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("pipelineRunId must be an integer");
        }
    }

    private static boolean hasExplicitCsv(String[] args) {
        if (args.length > 0 && !args[0].isBlank()) {
            return true;
        }
        String value = System.getenv("POSTS_CSV");
        return value != null && !value.isBlank();
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }

    private static boolean envBoolean(String name) {
        String value = System.getenv(name);
        return value != null && (value.equalsIgnoreCase("true") || value.equals("1"));
    }

    private static ConsolidatedSummary loadLocalKnowledgeBase() {
        try {
            return KnowledgeBase.load();
        } catch (Exception e) {
            throw new IllegalStateException("Could not load the local knowledge base", e);
        }
    }

    private static List<ClusterExtraction> loadLocalEntities() {
        try {
            return KnowledgeBase.loadEntities();
        } catch (Exception e) {
            throw new IllegalStateException("Could not load local entity extractions", e);
        }
    }
}
