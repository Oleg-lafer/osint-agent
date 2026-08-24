package com.leadspotting.summarize;

import com.leadspotting.model.ClusterExtraction;
import com.leadspotting.model.ConsolidatedSummary;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Step 3 storage / Step 4 hand-off: writes and reads the ConsolidatedSummary as a local
 * JSON file.
 *
 * The brief asks to keep the consolidated summary and the individual cluster summaries
 * "accessible in a simple local file for the next step", and a ConsolidatedSummary holds
 * both, so one file is enough. Step 3 writes it; the Step 4 server reads it back at startup.
 */
public final class KnowledgeBase {

    private KnowledgeBase() {
        // Utility holder: never instantiated.
    }

    private static final Path FILE = Path.of("knowledge-base.json");
    private static final Path ENTITIES_FILE = Path.of("entities.json");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void save(ConsolidatedSummary summary) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(FILE.toFile(), summary);
        System.out.println("Knowledge base written to " + FILE.toAbsolutePath());
    }

    /** Reads the knowledge base back into memory — the reverse of save(). */
    public static ConsolidatedSummary load() throws IOException {
        if (!Files.exists(FILE)) {
            throw new IllegalStateException(FILE
                    + " not found. Run the pipeline with --summarize first to build it.");
        }
        return JSON.readValue(FILE.toFile(), ConsolidatedSummary.class);
    }

    /** Task 2: writes the per-cluster entity extractions (the agent's drill-down index). */
    public static void saveEntities(Collection<ClusterExtraction> extractions) throws IOException {
        JSON.writerWithDefaultPrettyPrinter().writeValue(ENTITIES_FILE.toFile(), extractions);
        System.out.println("Entities written to " + ENTITIES_FILE.toAbsolutePath());
    }

    /** Reads the entity extractions back — empty if the file hasn't been built yet. */
    public static List<ClusterExtraction> loadEntities() throws IOException {
        if (!Files.exists(ENTITIES_FILE)) {
            return List.of();
        }
        return JSON.readValue(ENTITIES_FILE.toFile(), new TypeReference<List<ClusterExtraction>>() {});
    }
}
