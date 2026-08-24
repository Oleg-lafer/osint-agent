package com.leadspotting.chat_agent;

import com.leadspotting.model.Post;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline coverage for the two tools that don't touch the API. searchPosts embeds the query
 * live, so it's verified against the running server rather than here.
 */
class AgentToolsTest {

    private static Post post(String author, String text, LocalDate date) {
        return new Post(author, text, date);
    }

    private static AgentTools tools(List<Post> posts) {
        // PostIndex is null: these tests never call searchPosts.
        return new AgentTools(new PostStore(posts), null);
    }

    @Test
    void loadPostsReturnsTheRequestedPostsAndReportsMissing() {
        Post a = post("Alice", "the economy is collapsing", LocalDate.of(2026, 7, 1));
        AgentTools t = tools(List.of(a));

        String hit = t.loadPosts(List.of(a.getPostId()));
        assertTrue(hit.contains("economy is collapsing"));
        assertTrue(hit.contains("Alice"));

        String miss = t.loadPosts(List.of(-1L));
        assertTrue(miss.toLowerCase().contains("none"));
    }

    @Test
    void filterPostsByAuthor() {
        Post a = post("Alice", "post one", LocalDate.of(2026, 7, 1));
        Post b = post("Bob", "post two", LocalDate.of(2026, 7, 2));
        AgentTools t = tools(List.of(a, b));

        String byAlice = t.filterPosts("alice", null, null);
        assertTrue(byAlice.contains("post one"));
        assertFalse(byAlice.contains("post two"));
    }

    @Test
    void filterPostsByDateRangeInclusive() {
        Post june = post("Alice", "june post", LocalDate.of(2026, 6, 15));
        Post july = post("Alice", "july post", LocalDate.of(2026, 7, 15));
        AgentTools t = tools(List.of(june, july));

        String julyOnly = t.filterPosts(null, "2026-07-01", "2026-07-31");
        assertTrue(julyOnly.contains("july post"));
        assertFalse(julyOnly.contains("june post"));
    }

    @Test
    void filterPostsRejectsBadDate() {
        AgentTools t = tools(List.of(post("Alice", "x", LocalDate.of(2026, 7, 1))));
        assertTrue(t.filterPosts(null, "07/2026", null).toLowerCase().contains("date format"));
    }
}
