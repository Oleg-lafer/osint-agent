package com.leadspotting.cluster;

import com.leadspotting.model.Post;
import com.leadspotting.llm.OpenAi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * turns each Post's text into a vector via the OpenAi embeddings API.
 */
public class Embedder {

    /** The API accepts many texts per call; batching keeps us to a handful of round-trips. */
    private static final int BATCH_SIZE = 100;

    /**
     * The model rejects any input over 8,192 tokens, and one oversized post fails the whole
     * batch of 100. Arabic tokenises at roughly 0.8 tokens per character here, so 6,000
     * characters stays clear of the ceiling.
     *
     * This truncates only what we *send*; Post keeps its full text. It affects 9 of 5,402
     * posts, and a post's cluster signal is established long before its 6,000th character, which is
     * all the clustering needs from it.
     */
    private static final int MAX_CHARS = 6_000;

    private static final Path CACHE_FILE = Path.of("embeddings-cache.json");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Fills in post.setEmbedding(...) for every post: from the CSV if it supplied vectors,
     * then from the on-disk cache, and only then from the API.
     *
     * @param allowFetch whether calling the API is permitted. False is the normal case â€”
     *                   the enriched CSV is supposed to bring the vectors â€” so a run that
     *                   would silently spend money stops instead.
     * @return true if every post ends up with an embedding
     */
    public boolean embedAll(List<Post> posts, boolean allowFetch) throws IOException, InterruptedException {
        long preloaded = posts.stream().filter(post -> post.getEmbedding() != null).count();

        // Vectors from two different models can't share a graph â€” the cosine between
        // them is meaningless. So it's all-from-the-file or none-from-the-file, never
        // a mix of the enriched export's vectors and ones we fetched ourselves.
        if (preloaded > 0 && preloaded < posts.size()) {
            throw new IllegalStateException(preloaded + " of " + posts.size()
                    + " posts arrived with an embedding. Refusing to fetch the remaining "
                    + (posts.size() - preloaded) + " from a possibly different model â€” fix the input file.");
        }
        if (preloaded == posts.size()) {
            System.out.println("Embeddings: all " + posts.size() + " supplied by the CSV");
            return true;
        }

        Map<Long, float[]> cache = loadCache();

        List<Post> missing = new ArrayList<>();
        for (Post post : posts) {
            float[] cached = cache.get(post.getPostId());
            if (cached != null) {
                post.setEmbedding(cached);
            } else {
                missing.add(post);
            }
        }

        if (missing.isEmpty()) {
            System.out.println("Embeddings: all " + posts.size() + " loaded from cache");
            return true;
        }

        // Only a real API call needs permission. Anything the cache already covers is
        // free and offline, which is what makes tuning the clustering knobs cheap.
        if (!allowFetch) {
            System.out.println("Embeddings: " + missing.size() + " of " + posts.size()
                    + " posts have no embedding, and the CSV is expected to supply them."
                    + " Pass --embed to fetch them from OpenAi instead.");
            return false;
        }

        System.out.println("Embeddings: " + (posts.size() - missing.size()) + " cached, "
                + missing.size() + " to fetch from OpenAi");

        for (int start = 0; start < missing.size(); start += BATCH_SIZE) {
            List<Post> batch = missing.subList(start, Math.min(start + BATCH_SIZE, missing.size()));
            embedBatch(batch);
            for (Post post : batch) {
                cache.put(post.getPostId(), post.getEmbedding());
            }
        }

        saveCache(cache);
        System.out.println("Embeddings: cached to " + CACHE_FILE.toAbsolutePath());
        return true;
    }

    /**
     * Embeds a single piece of text and returns its unit vector.
     *
     * Used in Step 4: once per cluster to build the search index, and once per user query to
     * look it up. Same model and scale as the post embeddings, so the vectors are comparable.
     */
    public float[] embed(String text) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("model", OpenAi.EMBED_MODEL);
        body.put("input", text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OpenAi.EMBEDDINGS_URL))
                .header("Authorization", "Bearer " + OpenAi.apiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenAi returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode vector = json.readTree(response.body()).path("data").path(0).path("embedding");
        float[] embedding = new float[vector.size()];
        for (int i = 0; i < vector.size(); i++) {
            embedding[i] = (float) vector.get(i).asDouble();
        }
        return normalise(embedding);
    }

    /** One API call for a batch of posts. */
    private void embedBatch(List<Post> batch) throws IOException, InterruptedException {
        ObjectNode body = json.createObjectNode();
        body.put("model", OpenAi.EMBED_MODEL);
        ArrayNode input = body.putArray("input");
        for (Post post : batch) {
            String text = post.getText();
            input.add(text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(OpenAi.EMBEDDINGS_URL))
                .header("Authorization", "Bearer " + OpenAi.apiKey())
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("OpenAi returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = json.readTree(response.body());
        JsonNode usage = root.path("usage");
        OpenAi.recordEmbeddingUsage(usage.path("prompt_tokens").asLong(),
                usage.path("total_tokens").asLong());

        // OpenAi can answer 200 OK with an error object instead of data â€” an oversized
        // input, a rate limit, no credit. Without echoing the body, that surfaces as the
        // uninformative "got 0 embeddings" and you're left guessing.
        JsonNode data = root.path("data");
        if (data.size() != batch.size()) {
            String reply = response.body();
            throw new IOException("Asked for " + batch.size() + " embeddings, got " + data.size()
                    + ". Longest text in this batch: " + longestText(batch) + " chars."
                    + " Response was: " + reply.substring(0, Math.min(500, reply.length())));
        }

        // Pair each vector back to its post by the "index" field rather than by
        // position: mismatched pairings would silently poison every later step.
        for (JsonNode item : data) {
            int index = item.path("index").asInt();
            JsonNode vector = item.path("embedding");

            float[] embedding = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                embedding[i] = (float) vector.get(i).asDouble();
            }
            batch.get(index).setEmbedding(normalise(embedding));
        }
    }

    private static int longestText(List<Post> batch) {
        return batch.stream().mapToInt(post -> post.getText().length()).max().orElse(0);
    }

    /**
     * Scale to unit length so cosine similarity reduces to a dot product. Public because
     * CsvLoader needs it for vectors that arrive in the CSV, which have to land on the same
     * scale as the ones we fetch or the graph is garbage. Delegates to the shared Vectors util.
     */
    public static float[] normalise(float[] vector) {
        return Vectors.normalise(vector);
    }

    private Map<Long, float[]> loadCache() throws IOException {
        Map<Long, float[]> cache = new HashMap<>();
        if (!Files.exists(CACHE_FILE)) {
            return cache;
        }

        JsonNode root = json.readTree(Files.readString(CACHE_FILE));

        // A cache built with a different model has different dimensions and meaning.
        if (!OpenAi.EMBED_MODEL.equals(root.path("model").asText())) {
            System.out.println("Embeddings: cache was built with another model, refetching");
            return cache;
        }

        JsonNode vectors = root.path("vectors");
        vectors.fieldNames().forEachRemaining(id -> {
            JsonNode array = vectors.get(id);
            float[] embedding = new float[array.size()];
            for (int i = 0; i < array.size(); i++) {
                embedding[i] = (float) array.get(i).asDouble();
            }
            cache.put(Long.parseLong(id), embedding);
        });
        return cache;
    }

    private void saveCache(Map<Long, float[]> cache) throws IOException {
        ObjectNode root = json.createObjectNode();
        root.put("model", OpenAi.EMBED_MODEL);
        ObjectNode vectors = root.putObject("vectors");
        cache.forEach((id, embedding) -> {
            ArrayNode array = vectors.putArray(String.valueOf(id));
            for (float value : embedding) {
                array.add(value);
            }
        });
        Files.writeString(CACHE_FILE, json.writeValueAsString(root));
    }
}
