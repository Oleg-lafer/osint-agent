package com.leadspotnic.model;

import java.util.List;

/**
 * Task 2: one thing mentioned inside a cluster — a topic, a person/organization, or a location —
 * with the exact posts that mention it.
 *
 * One flexible shape covers all three categories:
 *   what  → name = the topic,           type = "topic",              context = a short summary
 *   who   → name = the person/org,      type = "person"/"organization", context = how mentioned
 *   where → name = the place,           type = "city"/"region"/…,    context = why mentioned
 *
 * postIds are real post ids (the agent uses them in Task 3 to drill down to the actual posts).
 */
public record Entity(String name, String type, String context, List<Long> postIds) {
}
