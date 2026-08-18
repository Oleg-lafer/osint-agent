package com.leadspotnic.summarize;

import com.leadspotnic.model.Post;

import java.util.List;

/**
 * Step 2.4: the prompt sent to the chat model for one cluster.
 *
 * Kept in its own file so the wording is easy to find and tweak — Ori set the four-field
 * schema as a starting point and expects it to evolve ("אחרכך נבין מה רוצים").
 *
 * INSTRUCTIONS is the fixed part; render() turns a cluster's posts into the text that
 * follows it. How many posts to render is decided by the caller (Step 2.5), not here.
 */
public final class ClusterPrompt {

    private ClusterPrompt() {
        // Utility holder: never instantiated.
    }

    /** Long post bodies cost tokens without changing the cluster summary, so each is capped. */
    private static final int MAX_POST_CHARS = 500;

    public static final String INSTRUCTIONS = """
            You are analyzing a group of social-media posts that were automatically
            grouped together because they are related. Read the posts below and
            answer four questions about the group as a whole:

              who   - the people or organizations the posts are about
              what  - the subject or event the posts are about
              where - where it took place
              when  - when it happened

            Rules:
            - Write every answer in English only. Never reply in Arabic, even if all the
              posts are in Arabic. Translate names of people and places into their common
              English form.
            - Base every answer only on the posts below. Do not invent anything.
            - If the posts don't make the "where" or "when" clear, leave that field empty.
            - Keep each answer to a short phrase or one sentence.

            Posts:
            """;

    /**
     * Renders the given posts as one line each: "- [date] text", with the date omitted
     * when the post has none. The date is included because it is the only reliable basis
     * for the "when" answer.
     */
    public static String render(List<Post> posts) {
        StringBuilder sb = new StringBuilder();
        for (Post post : posts) {
            sb.append("- ");
            if (post.getPublishDate() != null) {
                sb.append('[').append(post.getPublishDate()).append("] ");
            }
            sb.append(post.preview(MAX_POST_CHARS)).append('\n');
        }
        return sb.toString();
    }

    /** The full prompt: fixed instructions followed by the rendered posts. */
    public static String forCluster(List<Post> posts) {
        return INSTRUCTIONS + render(posts);
    }
}
