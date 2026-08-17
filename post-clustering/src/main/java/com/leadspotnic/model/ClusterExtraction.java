package com.leadspotnic.model;

import java.util.List;

/**
 * Task 2: one cluster's entity breakdown across the three categories, each a list of entities
 * linked to their posts (like a book index: an entity points to the posts that mention it).
 */
public record ClusterExtraction(
        int clusterId,
        List<Entity> what,
        List<Entity> who,
        List<Entity> where
) {
}
