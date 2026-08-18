package com.leadspotnic.ingest;

import com.leadspotnic.model.Post;
import com.leadspotnic.cluster.Embedder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the raw posts out of the exported CSV.
 *
 * Schema: profile_name, id, text, publish_date — plus an optional "embedding"
 * column, which the enriched export carries and the basic one does not.
 *
 * Post text is multi-line and quoted, so the parser has to tolerate that.
 */
public class CsvLoader {

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()                // first row holds the column names
            .setSkipHeaderRecord(true)  // ...and isn't data
            .setTrim(true)
            .build();

    private static final List<String> REQUIRED_COLUMNS = List.of("profile_name", "text", "publish_date");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The two noise knobs, so a change of policy is a one-line change at the call site. */
    public record Options(int minTextLength, boolean dropDuplicateTexts) {

        /**
         * Ori, 2026-07-14: drop the short posts; keep the duplicates for now.
         *
         * Duplicates are still an open question on their side — collapsing them throws
         * away how often a text appeared, on which platform and on which day, and they
         * haven't decided what they want that to mean yet. So they stay in.
         */
        public static Options teamPolicy() {
            return new Options(15, false);
        }

        /** Clusters the corpus exactly as exported, noise and all. */
        public static Options keepEverything() {
            return new Options(1, false);
        }
    }

    /** Reads a CSV bundled in src/main/resources. */
    public static List<Post> loadFromClasspath(String resourceName, Options options) throws IOException {
        InputStream in = CsvLoader.class.getResourceAsStream(resourceName);
        if (in == null) {
            throw new IllegalArgumentException("Resource not found on classpath: " + resourceName);
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parse(reader, options);
        }
    }

    /** Reads a CSV sitting anywhere on disk — for the full export, which is too big to bundle. */
    public static List<Post> loadFromFile(Path path, Options options) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return parse(reader, options);
        }
    }

    private static List<Post> parse(Reader reader, Options options) throws IOException {
        List<Post> posts = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();

        int tooShort = 0;
        int duplicates = 0;
        int preEmbedded = 0;

        try (CSVParser parser = CSVParser.parse(reader, FORMAT)) {

            // Without this, a schema mismatch reads as "every row is empty" rather than
            // as an error, and the pipeline quietly clusters nothing.
            for (String column : REQUIRED_COLUMNS) {
                if (!parser.getHeaderMap().containsKey(column)) {
                    throw new IOException("CSV is missing required column '" + column
                            + "'. Columns found: " + parser.getHeaderMap().keySet());
                }
            }

            for (CSVRecord record : parser) {
                String text = get(record, "text");

                // Normalised once and used for both checks below: "hi   there" and
                // "hi there" are the same post as far as clustering cares.
                String normalised = text.replaceAll("\\s+", " ").trim();

                // A single emoji or a bare name carries too little clustering signal. Left in, these posts
                // embed to near-arbitrary directions, and Leiden either collects them
                // into a junk cluster or smears them through the real ones.
                if (normalised.length() < options.minTextLength()) {
                    tooShort++;
                    continue;
                }

                // Identical reshares would otherwise form an artificially dense blob in
                // the similarity graph and distort the communities around it.
                if (options.dropDuplicateTexts() && !seenTexts.add(normalised)) {
                    duplicates++;
                    continue;
                }

                Post post = new Post(
                        get(record, "profile_name"),
                        text,
                        parseDate(get(record, "publish_date")));

                // The enriched export ships vectors alongside the posts; the basic one
                // doesn't. Where they're supplied, use them: they're free, and they're
                // the model the rest of the team is standardised on.
                String vector = get(record, "embedding");
                if (!vector.isBlank()) {
                    post.setEmbedding(parseVector(vector));
                    preEmbedded++;
                }

                posts.add(post);
            }
        }

        System.out.println("Loaded " + posts.size() + " posts"
                + " (skipped " + tooShort + " under " + options.minTextLength() + " chars, "
                + duplicates + " duplicate texts)"
                + (preEmbedded > 0 ? " — " + preEmbedded + " arrived with an embedding" : ""));
        return posts;
    }

    /** Optional columns come back empty rather than blowing up — exports vary. */
    private static String get(CSVRecord record, String column) {
        return record.isMapped(column) && record.isSet(column) ? record.get(column) : "";
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim()); // the export's format is already ISO: 2026-06-24
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** A vector cell, as a JSON array: "[0.013, -0.244, ...]". */
    private static float[] parseVector(String value) throws IOException {
        JsonNode array = JSON.readTree(value);
        if (!array.isArray() || array.isEmpty()) {
            throw new IOException("Expected the embedding column to hold a JSON array, got: "
                    + value.substring(0, Math.min(60, value.length())));
        }
        float[] embedding = new float[array.size()];
        for (int i = 0; i < array.size(); i++) {
            embedding[i] = (float) array.get(i).asDouble();
        }
        return Embedder.normalise(embedding);
    }
}
