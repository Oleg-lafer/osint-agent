package com.leadspotting.model;

/**
 * Step 2: the structured summary of one cluster.
 *
 * The four fields are the schema Ori set on 2026-07-16, standing in for the Palantir
 * one that isn't available yet ("זה יהיה מספיק טוב, ואחרכך נבין מה רוצים"):
 *   who   — who the posts are about
 *   what  — what they are about
 *   where — where it took place
 *   when  — when it happened
 *
 * A field may come back empty: many posts have a clear who/what but no place or date,
 * and an honest blank is better than an invented location on factual-density work.
 *
 * This is the shape LangChain4j forces the model's reply into — the Step 2 counterpart
 * of Post, a plain container with no behaviour of its own.
 */
public record ClusterSummary(String who, String what, String where, String when) {
}
