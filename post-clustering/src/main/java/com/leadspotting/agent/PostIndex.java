package com.leadspotting.agent;

import com.leadspotting.cluster.Embedder;
import com.leadspotting.cluster.Vectors;
import com.leadspotting.model.Post;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Task 3: semantic search over the individual posts.
 *
 * The sibling of ClusterIndex — same embed-then-rank-by-cosine retrieval — but over the posts
 * instead of the generated cluster summaries. It is the fallback the agent uses when a question
 * is about something no entity captured: there are no post ids to drill into, so we search the
 * raw posts by meaning instead.
 *
 * The post vectors already exist in the on-disk cache (the pipeline computed them), so building
 * this index is free and offline — embedAll(posts, false) only reads the cache, never the API.
 * The one API call per search is embedding the query. Post and query vectors are unit length
 * (Embedder normalises both), so the dot product is the cosine similarity.
 */
public class PostIndex {

    /** One post and how well it matched the query (1.0 = identical direction). */
    public record Match(Post post, double score) {}

    private final List<Post> posts;
    private final float[][] vectors;
    private final Embedder embedder;

    public PostIndex(PostStore store, Embedder embedder) throws IOException, InterruptedException {
        this.embedder = embedder;

        // Posts loaded from the CSV have no embedding column; fill the vectors from the cache.
        // allowFetch=false keeps it offline and free — the pipeline already cached every post.
        List<Post> all = new ArrayList<>(store.all());
        embedder.embedAll(all, false);

        // Keep only posts that actually got a vector, so a cache gap can't crash search.
        this.posts = new ArrayList<>(all.size());
        List<float[]> kept = new ArrayList<>(all.size());
        for (Post post : all) {
            if (post.getEmbedding() != null) {
                posts.add(post);
                kept.add(post.getEmbedding());
            }
        }
        this.vectors = kept.toArray(new float[0][]);
        System.out.println("Post index: " + posts.size() + " posts searchable"
                + (posts.size() < all.size() ? " (" + (all.size() - posts.size()) + " had no cached vector)" : ""));
    }

    /** The top-k posts most similar to the query, best first. */
    public List<Match> search(String query, int k) throws IOException, InterruptedException {
        float[] q = embedder.embed(query);

        List<Match> matches = new ArrayList<>(posts.size());
        for (int i = 0; i < posts.size(); i++) {
            matches.add(new Match(posts.get(i), Vectors.dot(q, vectors[i])));
        }
        matches.sort((a, b) -> Double.compare(b.score(), a.score()));
        return matches.subList(0, Math.min(k, matches.size()));
    }
}
