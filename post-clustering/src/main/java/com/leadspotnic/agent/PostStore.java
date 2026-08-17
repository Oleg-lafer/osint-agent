package com.leadspotnic.agent;

import com.leadspotnic.ingest.CsvLoader;
import com.leadspotnic.ingest.PostQualificationLoader;
import com.leadspotnic.model.Post;
import com.leadspotnic.persistence.DatabaseConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Task 3: every post in memory, looked up by id.
 *
 * The entities and summaries only carry post *ids* — to keep them small. When the agent needs
 * the real text of a post (drill-down), it has the id but not the content; this store closes
 * that gap. Given an id, it returns the whole Post in one map lookup.
 *
 * Built once at server startup from the same CSV the pipeline reads, so the ids here are the
 * exact ids stored in entities.json. A post's id is a hash of author + date + text, so only
 * fully identical posts share an id and collapse to one entry — harmless for lookup, as they
 * are the same post anyway.
 */
public final class PostStore {

    private final Map<Long, Post> byId;

    public PostStore(List<Post> posts) {
        // LinkedHashMap keeps CSV order, which makes all() deterministic for tests and filtering.
        Map<Long, Post> map = new LinkedHashMap<>();
        for (Post post : posts) {
            map.put(post.getPostId(), post);
        }
        this.byId = Collections.unmodifiableMap(map);
    }

    /** Loads the real export the pipeline uses, applying the same team noise policy. */
    public static PostStore fromCsv(Path csvPath) throws IOException {
        return new PostStore(CsvLoader.loadFromFile(csvPath, CsvLoader.Options.teamPolicy()));
    }

    public static PostStore fromDatabase(DatabaseConfig config, int watchListId, int lookbackDays,
                                         int postLimit)
            throws Exception {
        return new PostStore(PostQualificationLoader.load(config, watchListId, lookbackDays, postLimit,
                CsvLoader.Options.teamPolicy()));
    }

    /** Fallback for tests / demos: the bundled toy CSV on the classpath. */
    public static PostStore fromClasspath(String resourceName) throws IOException {
        return new PostStore(CsvLoader.loadFromClasspath(resourceName, CsvLoader.Options.teamPolicy()));
    }

    /** The full post for one id, or null if it isn't in the store. */
    public Post get(long postId) {
        return byId.get(postId);
    }

    /**
     * The posts for a list of ids, in the order given. Ids that aren't present are skipped
     * rather than throwing — a stale id in entities.json shouldn't crash an answer.
     */
    public List<Post> byIds(Collection<Long> postIds) {
        List<Post> posts = new ArrayList<>();
        for (Long id : postIds) {
            Post post = byId.get(id);
            if (post != null) {
                posts.add(post);
            }
        }
        return posts;
    }

    /** Every post, for tools that scan the whole set (semantic search, filtering). */
    public Collection<Post> all() {
        return byId.values();
    }

    public int size() {
        return byId.size();
    }
}
