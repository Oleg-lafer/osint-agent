package com.leadspotting.pipeline.F_cluster_summarization;

import com.leadspotting.pipeline.B_post_embedding.Embedder;

import com.leadspotting.model.Post;
import com.leadspotting.model.ClusterSummary;
import com.leadspotting.llm.OpenAi;
import com.leadspotting.util.Hashing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 2: sends each cluster's posts to a chat model and gets back a ClusterSummary.
 *
 * The Step 2 counterpart of Embedder â€” send text to OpenAi, get something back â€” but the
 * reply is a structured who/what/where/when instead of a vector.
 *
 * "Force the reply into JSON" is handled by LangChain4j's AiServices: because ClusterAnalyst
 * returns a ClusterSummary, the library appends the JSON shape to the prompt and parses the
 * answer straight into the record. That is the framework tip from the brief, done for us.
 *
 * Results are cached to disk keyed by the prompt, so re-running while tuning costs nothing and
 * a single cluster that fails or returns bad JSON is skipped rather than crashing the run.
 */
public class Summarizer {

    /** Step 2.5: how many of a cluster's posts to send. The rest are usually repetitive. */
    private static final int SAMPLE_SIZE = 20;

    private static final Path CACHE_FILE = Path.of("summaries-cache.json");

    /**
     * The typed door to the model. AiServices generates the JSON handling behind it.
     *
     * The system message carries more weight than a line in the prompt body: on the
     * densest-Arabic clusters the model was slipping back into Arabic despite the
     * in-prompt rule, so the English-only constraint is repeated here as a hard rule.
     */
    interface ClusterAnalyst {
        @SystemMessage("""
                You are a data analyst summarising social-media posts.
                Respond ONLY in English â€” never in Arabic or any other language, even when
                every post is in Arabic. Translate names of people and places into their
                common English form (for example: ÙÙŠØ±ÙˆØ² -> Fairuz, Ø¨ÙŠØ±ÙˆØª -> Beirut).
                """)
        ClusterSummary analyze(String prompt);
    }

    private final ObjectMapper json = new ObjectMapper();

    // Built lazily: a fully-cached run needs neither the API key nor a network call.
    private ClusterAnalyst analyst;

    /**
     * Summarises every cluster, from cache where possible and from the model otherwise.
     * One cluster failing (a network hiccup, malformed JSON) is logged and skipped, not fatal.
     *
     * @return a map from cluster id to its summary (clusters that failed are absent)
     */
    public Map<Integer, ClusterSummary> summarizeAll(Map<Integer, List<Post>> byCluster) throws IOException {
        Map<String, ClusterSummary> cache = loadCache();
        Map<Integer, ClusterSummary> results = new LinkedHashMap<>();

        List<Integer> ids = new ArrayList<>(byCluster.keySet());
        Collections.sort(ids);

        int fromCache = 0;
        int fetched = 0;
        int failed = 0;

        for (int id : ids) {
            List<Post> cluster = byCluster.get(id);
            String prompt = ClusterPrompt.forCluster(sample(cluster));
            String key = hash(prompt);

            ClusterSummary cached = cache.get(key);
            if (cached != null) {
                results.put(id, cached);
                fromCache++;
                continue;
            }

            try {
                ClusterSummary summary = analyst().analyze(prompt);
                cache.put(key, summary);
                results.put(id, summary);
                fetched++;
            } catch (Exception e) {
                System.out.println("  cluster " + id + ": summary failed, skipped â€” " + e.getMessage());
                failed++;
            }
        }

        saveCache(cache);
        System.out.printf("Summaries: %d from cache, %d fetched, %d failed%n", fromCache, fetched, failed);
        return results;
    }

    /** Summarises one cluster â€” used for spot checks; summarizeAll is the normal path. */
    public ClusterSummary summarize(List<Post> cluster) {
        return analyst().analyze(ClusterPrompt.forCluster(sample(cluster)));
    }

    private static List<Post> sample(List<Post> cluster) {
        return cluster.subList(0, Math.min(SAMPLE_SIZE, cluster.size()));
    }

    private ClusterAnalyst analyst() {
        if (analyst == null) {
            analyst = AiServices.create(ClusterAnalyst.class, OpenAi.chatModel());
        }
        return analyst;
    }

    /** SHA-256 of the prompt, hex. Same prompt -> same key, so a changed sample re-summarises. */
    private static String hash(String prompt) {
        return Hashing.sha256Hex(prompt);
    }

    private Map<String, ClusterSummary> loadCache() throws IOException {
        Map<String, ClusterSummary> cache = new HashMap<>();
        if (!Files.exists(CACHE_FILE)) {
            return cache;
        }
        JsonNode root = json.readTree(Files.readString(CACHE_FILE));

        // A cache built with a different model may read differently; don't mix.
        if (!OpenAi.CHAT_MODEL.equals(root.path("model").asText())) {
            System.out.println("Summaries: cache was built with another model, re-summarising");
            return cache;
        }

        JsonNode summaries = root.path("summaries");
        summaries.fieldNames().forEachRemaining(key -> {
            try {
                cache.put(key, json.treeToValue(summaries.get(key), ClusterSummary.class));
            } catch (Exception e) {
                // A corrupt entry just gets refetched.
            }
        });
        return cache;
    }

    private void saveCache(Map<String, ClusterSummary> cache) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("model", OpenAi.CHAT_MODEL);
        ObjectNode summaries = root.putObject("summaries");
        cache.forEach((key, summary) -> summaries.set(key, json.valueToTree(summary)));
        Files.writeString(CACHE_FILE, json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }
}
