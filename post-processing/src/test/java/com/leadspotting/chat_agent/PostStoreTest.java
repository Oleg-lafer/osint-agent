package com.leadspotting.chat_agent;

import com.leadspotting.model.Post;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PostStoreTest {

    private static Post post(String author, String text) {
        return new Post(author, text, LocalDate.of(2026, 1, 1));
    }

    @Test
    void getReturnsThePostForItsId() {
        Post a = post("Alice", "hello world");
        PostStore store = new PostStore(List.of(a));
        assertEquals(a, store.get(a.getPostId()));
    }

    @Test
    void getReturnsNullForAnUnknownId() {
        PostStore store = new PostStore(List.of(post("Alice", "hello world")));
        assertNull(store.get(-999L));
    }

    @Test
    void byIdsPreservesRequestedOrderAndSkipsMissing() {
        Post a = post("Alice", "first post");
        Post b = post("Bob", "second post");
        PostStore store = new PostStore(List.of(a, b));

        // Ask for b, then a, then a missing id — result is [b, a], missing dropped.
        List<Post> got = store.byIds(List.of(b.getPostId(), a.getPostId(), -1L));
        assertEquals(List.of(b, a), got);
    }

    @Test
    void fullyIdenticalPostsCollapseToOneEntry() {
        // Post id hashes author + date + text, so only fully identical posts share an id.
        Post a = post("Alice", "same text");
        Post b = post("Alice", "same text");
        PostStore store = new PostStore(List.of(a, b));
        assertEquals(1, store.size());
    }

    @Test
    void sameTextDifferentAuthorAreKeptSeparate() {
        Post a = post("Alice", "same text");
        Post b = post("Bob", "same text");
        PostStore store = new PostStore(List.of(a, b));
        assertEquals(2, store.size());
    }
}
