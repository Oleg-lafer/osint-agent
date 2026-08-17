package com.leadspotnic.summarize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leadspotnic.llm.OpenAi;
import com.leadspotnic.model.ClusterExtraction;
import com.leadspotnic.model.Entity;
import com.leadspotnic.model.Post;
import com.leadspotnic.util.Hashing;
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
 * Task 2: for each cluster, extracts entities in three categories (what / who / where), each
 * entity linked to the posts that mention it.
 *
 * The sibling of Summarizer â€” same OpenAi wiring, same LangChain4j forced-JSON, same disk
 * cache â€” but it makes three focused calls per cluster and returns entity lists instead of one
 * summary. The model cites post NUMBERS; this class maps them back to real post ids.
 */
public class Extractor {

    private static final Path CACHE_FILE = Path.of("extractions-cache.json");

    /** LLM output for one category: entities that cite post NUMBERS (mapped to ids afterwards). */
    public record ExtractedEntity(String name, String type, String context, List<Integer> postRefs) {}

    public record Extraction(List<ExtractedEntity> entities) {}

    interface Analyst {
        @SystemMessage("""
                You extract entities from the posts of one topic cluster and cite the posts they
                come from. Rules:
                - Use ONLY the numbered posts given. Cite each entity's posts by their numbers.
                - Do not invent entities or post numbers. Answer in English.
                - If nothing fits the category, return an empty list.
                """)
        Extraction extract(String prompt);
    }

    private final ObjectMapper json = new ObjectMapper();
    private Analyst analyst;

    /**
     * Extracts every cluster's entities. Cached so re-runs cost nothing, and the cache is saved
     * every few clusters â€” so a long run that's interrupted resumes from where it stopped.
     */
    public Map<Integer, ClusterExtraction> extractAll(Map<Integer, List<Post>> byCluster) throws IOException {
        Map<String, Extraction> cache = loadCache();

        List<Integer> ids = new ArrayList<>(byCluster.keySet());
        Collections.sort(ids);
        int total = ids.size();
        System.out.println("Entity extraction: " + total + " clusters, 3 calls each (this takes a few minutes)...");

        Map<Integer, ClusterExtraction> result = new LinkedHashMap<>();
        int emptyCategories = 0;

        for (int i = 0; i < total; i++) {
            int id = ids.get(i);
            List<Post> posts = byCluster.get(id);
            List<Entity> what = extractCategory(EntityPrompt.Category.WHAT, posts, cache);
            List<Entity> who = extractCategory(EntityPrompt.Category.WHO, posts, cache);
            List<Entity> where = extractCategory(EntityPrompt.Category.WHERE, posts, cache);
            result.put(id, new ClusterExtraction(id, what, who, where));

            for (List<Entity> list : List.of(what, who, where)) {
                if (list.isEmpty()) {
                    emptyCategories++;
                }
            }

            // Save progress and report every few clusters, so it's visible and resumable.
            if ((i + 1) % 5 == 0 || i == total - 1) {
                saveCache(cache);
                System.out.printf("  %d/%d clusters extracted%n", i + 1, total);
            }
        }

        System.out.printf("Entity extraction done: %d clusters (%d empty category results)%n",
                total, emptyCategories);
        return result;
    }

    private List<Entity> extractCategory(EntityPrompt.Category category, List<Post> posts,
                                         Map<String, Extraction> cache) {
        String prompt = EntityPrompt.forCategory(category, posts);
        String key = hash(prompt);

        Extraction extraction = cache.get(key);
        if (extraction == null) {
            try {
                extraction = analyst().extract(prompt);
                cache.put(key, extraction);
            } catch (Exception e) {
                System.out.println("  extraction failed for " + category + ": " + e.getMessage());
                return List.of();
            }
        }
        return toEntities(extraction, posts);
    }

    /** Map each entity's post NUMBERS back to real post ids, dropping any out-of-range number. */
    private static List<Entity> toEntities(Extraction extraction, List<Post> posts) {
        List<Entity> entities = new ArrayList<>();
        if (extraction == null || extraction.entities() == null) {
            return entities;
        }
        for (ExtractedEntity ee : extraction.entities()) {
            List<Long> postIds = new ArrayList<>();
            if (ee.postRefs() != null) {
                for (int ref : ee.postRefs()) {
                    if (ref >= 1 && ref <= posts.size()) {
                        postIds.add(posts.get(ref - 1).getPostId());
                    }
                }
            }
            entities.add(new Entity(ee.name(), ee.type(), ee.context(), postIds));
        }
        return entities;
    }

    private Analyst analyst() {
        if (analyst == null) {
            analyst = AiServices.create(Analyst.class, OpenAi.chatModel());
        }
        return analyst;
    }

    private static String hash(String prompt) {
        return Hashing.sha256Hex(prompt);
    }

    private Map<String, Extraction> loadCache() throws IOException {
        Map<String, Extraction> cache = new HashMap<>();
        if (!Files.exists(CACHE_FILE)) {
            return cache;
        }
        JsonNode root = json.readTree(Files.readString(CACHE_FILE));
        if (!OpenAi.CHAT_MODEL.equals(root.path("model").asText())) {
            System.out.println("Extractions: cache was built with another model, re-extracting");
            return cache;
        }
        JsonNode entries = root.path("extractions");
        entries.fieldNames().forEachRemaining(key -> {
            try {
                cache.put(key, json.treeToValue(entries.get(key), Extraction.class));
            } catch (Exception e) {
                // A corrupt entry just gets refetched.
            }
        });
        return cache;
    }

    private void saveCache(Map<String, Extraction> cache) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("model", OpenAi.CHAT_MODEL);
        ObjectNode entries = root.putObject("extractions");
        cache.forEach((key, extraction) -> entries.set(key, json.valueToTree(extraction)));
        Files.writeString(CACHE_FILE, json.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }
}
