package com.leadspotting.summarize;

import com.leadspotting.model.Post;

import java.util.List;

/**
 * Task 2: the prompt for entity extraction. Posts are numbered [1]…[n] so the model cites small
 * numbers (which it copies reliably) instead of the real 18-digit post ids — the Extractor maps
 * those numbers back to real ids afterwards.
 *
 * One prompt per category (what / who / where), kept separate so each call stays focused.
 */
public final class EntityPrompt {

    private EntityPrompt() {
        // Utility holder: never instantiated.
    }

    /**
     * How much of each post the model sees. Raised from 300 to 800 so the model has enough
     * material to write a substantive context, not just a label — Ori wanted "meat", and the
     * model can't summarise content it never read past the opening line.
     */
    private static final int MAX_POST_CHARS = 800;

    public enum Category {
        WHAT("""
                List the main SUBJECTS discussed. For each:
                  name    = a short label for the subject
                  type    = "subject"
                  context = a substantive summary (3-5 sentences) of what the posts actually
                            say about this subject: the specific claims, events, opinions and
                            details expressed — concrete content, not a generic description.
                  postRefs = the numbers of the posts that discuss it
                """),
        WHO("""
                List the PEOPLE and ORGANIZATIONS mentioned. For each:
                  name    = the person or organization
                  type    = "person" or "organization"
                  context = a substantive summary (2-4 sentences) of what the posts say about
                            them: what they did or said, the role or event they appear in, and
                            how they are portrayed — concrete details, not a one-line mention.
                  postRefs = the numbers of the posts that mention them
                """),
        WHERE("""
                List the LOCATIONS mentioned. For each:
                  name    = the place
                  type    = "city", "region", "country", or similar
                  context = a substantive summary (2-4 sentences) of what happens at or is said
                            about this place across the posts: the events, conditions or claims
                            tied to it — concrete details, not a one-line mention.
                  postRefs = the numbers of the posts that mention it
                """);

        final String instructions;

        Category(String instructions) {
            this.instructions = instructions;
        }
    }

    /** The instruction for one category, followed by the cluster's posts, numbered. */
    public static String forCategory(Category category, List<Post> posts) {
        StringBuilder sb = new StringBuilder();
        sb.append(category.instructions).append("\nPosts:\n");
        for (int i = 0; i < posts.size(); i++) {
            sb.append('[').append(i + 1).append("] ")
              .append(posts.get(i).preview(MAX_POST_CHARS)).append('\n');
        }
        return sb.toString();
    }
}
