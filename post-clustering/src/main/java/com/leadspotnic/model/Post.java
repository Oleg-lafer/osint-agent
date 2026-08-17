package com.leadspotnic.model;

import com.leadspotnic.util.Hashing;

import java.time.LocalDate;

/**
 * One post flowing through the Step 1 pipeline.
 *
 * Fields mirror the export schema: profile_name, id, text, publish_date.
 *
 * The lifecycle of a Post:
 *   1. Created from a CSV row  -> the schema fields are set.
 *   2. Embedded                -> embedding is filled in, either from an embedding
 *                                 column in the CSV or from the OpenAi API.
 *   3. Clustered               -> Leiden assigns it a clusterId.
 */
public class Post {

    /**
     * The export's "id" column identifies the author, not the post: 5,861 rows share
     * only 1,592 id values. So posts get a synthetic id, derived from their content
     * rather than their row number â€” a re-export with rows added, removed or reordered
     * still yields the same id for the same post, which keeps the embedding cache valid.
     */
    private final long postId;

    private final String profileName;

    private final String text;

    /** Date only; the export carries no clock time. Null when the column is blank. */
    private final LocalDate publishDate;

    // Filled in during the embedding step. Null until then.
    private float[] embedding;

    // Filled in during the Leiden step. -1 means "not yet clustered".
    private int clusterId = -1;

    public Post(String profileName, String text, LocalDate publishDate) {
        this.profileName = profileName;
        this.text = text;
        this.publishDate = publishDate;
        this.postId = contentId(profileName, text, publishDate);
    }

    /** A stable id from the identifying fields, so the same post always hashes the same way. */
    private static long contentId(String profileName, String text, LocalDate publishDate) {
        return Hashing.sha256Long(profileName + " " + publishDate + " " + text);
    }

    public long getPostId() {
        return postId;
    }

    public String getProfileName() {
        return profileName;
    }

    public String getText() {
        return text;
    }

    public LocalDate getPublishDate() {
        return publishDate;
    }

    public float[] getEmbedding() {
        return embedding;
    }

    public void setEmbedding(float[] embedding) {
        this.embedding = embedding;
    }

    public int getClusterId() {
        return clusterId;
    }

    public void setClusterId(int clusterId) {
        this.clusterId = clusterId;
    }

    /** Enough text to recognise a post in console output, without flooding it. */
    public String preview(int maxChars) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= maxChars ? flat : flat.substring(0, maxChars) + "â€¦";
    }

    @Override
    public String toString() {
        return "Post{id=" + postId + ", by='" + profileName + "', text='" + preview(60) + "'}";
    }
}
