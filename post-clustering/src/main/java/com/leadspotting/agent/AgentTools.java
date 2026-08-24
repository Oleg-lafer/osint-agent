package com.leadspotting.agent;

import com.leadspotting.model.Post;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Task 3: the tools the agent may call to reach individual posts.
 *
 * These are plain methods; LangChain4j exposes them to the model (via the @Tool annotation) and
 * runs the one the model asks for, feeding the result back so it can answer from real posts. The
 * model decides whether to call any of them — for a general question it answers from the summaries
 * and calls nothing; only a question needing specific detail triggers a tool. That decision is
 * steered by the Assistant's system message, not hard-coded here.
 *
 *   loadPosts   — drill-down: the ids came from an entity, fetch those exact posts.
 *   searchPosts — semantic search: no ids, find posts by meaning when no entity was captured.
 *   filterPosts — structured filter: exact fields (author, date range).
 */
public class AgentTools {

    /** How many posts a search or filter returns, so the model's context isn't flooded. */
    private static final int MAX_RESULTS = 8;

    /** How much of each post's text to include — enough to judge relevance, capped for tokens. */
    private static final int PREVIEW_CHARS = 500;

    private final PostStore posts;
    private final PostIndex postIndex;

    public AgentTools(PostStore posts, PostIndex postIndex) {
        this.posts = posts;
        this.postIndex = postIndex;
    }

    @Tool("""
            Load the full text of specific posts by their ids. Use this when the entities give you
            the post ids for a subject and you need the exact wording. Returns each post's author,
            date and text.
            """)
    public String loadPosts(@P("the post ids to load") List<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return "No post ids given.";
        }
        List<Post> found = posts.byIds(postIds);
        if (found.isEmpty()) {
            return "None of those post ids are in the dataset.";
        }
        return format(found);
    }

    @Tool("""
            Search all posts by meaning and return the most relevant ones. Use this when the
            question is about something the cluster summaries and entities do not cover, so there
            are no post ids to load. Returns the closest posts with author, date and text.
            """)
    public String searchPosts(@P("what to search for, in natural language") String query) {
        try {
            List<PostIndex.Match> hits = postIndex.search(query, MAX_RESULTS);
            if (hits.isEmpty()) {
                return "No posts matched that search.";
            }
            List<Post> found = new ArrayList<>();
            for (PostIndex.Match m : hits) {
                found.add(m.post());
            }
            return format(found);
        } catch (Exception e) {
            return "Search failed: " + e.getMessage();
        }
    }

    @Tool("""
            Find posts matching exact fields: author name and/or a date range (inclusive).
            Dates are ISO yyyy-MM-dd; pass null for any filter you don't want to apply. Use this
            for questions like "posts by X" or "posts from July". Returns the matching posts.
            """)
    public String filterPosts(@P(value = "author name to match (substring, case-insensitive), or null", required = false) String author,
                              @P(value = "earliest date yyyy-MM-dd inclusive, or null", required = false) String fromDate,
                              @P(value = "latest date yyyy-MM-dd inclusive, or null", required = false) String toDate) {
        LocalDate from;
        LocalDate to;
        try {
            from = parseOrNull(fromDate);
            to = parseOrNull(toDate);
        } catch (DateTimeParseException e) {
            return "Bad date format — use yyyy-MM-dd.";
        }
        String needle = (author == null || author.isBlank()) ? null : author.toLowerCase();

        List<Post> matches = new ArrayList<>();
        for (Post post : posts.all()) {
            if (needle != null && !post.getProfileName().toLowerCase().contains(needle)) {
                continue;
            }
            LocalDate date = post.getPublishDate();
            if (from != null && (date == null || date.isBefore(from))) {
                continue;
            }
            if (to != null && (date == null || date.isAfter(to))) {
                continue;
            }
            matches.add(post);
            if (matches.size() >= MAX_RESULTS) {
                break;
            }
        }
        if (matches.isEmpty()) {
            return "No posts matched those filters.";
        }
        return format(matches);
    }

    private static LocalDate parseOrNull(String date) {
        return (date == null || date.isBlank()) ? null : LocalDate.parse(date.trim());
    }

    /** One block per post: author, date, and the (capped) text — what the model reads. */
    private static String format(List<Post> found) {
        StringBuilder sb = new StringBuilder();
        for (Post post : found) {
            sb.append("- [").append(post.getProfileName())
              .append(", ").append(post.getPublishDate()).append("] ")
              .append(post.preview(PREVIEW_CHARS)).append('\n');
        }
        return sb.toString();
    }
}
