package com.leadspotting.model;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class PostTest {

    @Test
    void persistedPostKeepsItsDatabaseContentId() {
        Post post = Post.persisted(123456789L, "Persisted text");

        assertEquals(123456789L, post.getPostId());
        assertEquals("Persisted text", post.getText());
    }

    @Test
    void identicalContentGetsTheSameId() {
        Post a = new Post("Alice", "hello world", LocalDate.of(2026, 1, 1));
        Post b = new Post("Alice", "hello world", LocalDate.of(2026, 1, 1));
        assertEquals(a.getPostId(), b.getPostId());
    }

    @Test
    void differentTextGetsADifferentId() {
        Post a = new Post("Alice", "hello world", LocalDate.of(2026, 1, 1));
        Post b = new Post("Alice", "goodbye world", LocalDate.of(2026, 1, 1));
        assertNotEquals(a.getPostId(), b.getPostId());
    }

    @Test
    void theIdIsPositive() {
        Post a = new Post("Bob", "some post text", LocalDate.of(2026, 2, 2));
        assertEquals(true, a.getPostId() >= 0);
    }
}
