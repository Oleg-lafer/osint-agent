package com.leadspotnic;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.leadspotnic.cluster.Clusterer;
import com.leadspotnic.cluster.ClusterSplitter;
import com.leadspotnic.cluster.Clusters;
import com.leadspotnic.cluster.Embedder;
import com.leadspotnic.cluster.SimilarityGraph;
import com.leadspotnic.ingest.CsvLoader;
import com.leadspotnic.ingest.PostQualificationLoader;
import com.leadspotnic.model.ClusterExtraction;
import com.leadspotnic.model.ClusterSummary;
import com.leadspotnic.model.ConsolidatedSummary;
import com.leadspotnic.model.Post;
import com.leadspotnic.persistence.DatabasePipeline;
import com.leadspotnic.persistence.DatabaseConfig;
import com.leadspotnic.summarize.Consolidator;
import com.leadspotnic.summarize.Extractor;
import com.leadspotnic.summarize.KnowledgeBase;
import com.leadspotnic.summarize.Summarizer;

/**
 * Entry point for the Step 1 pipeline:
 *   load CSV -> (embeddings) -> build similarity graph -> Leiden -> cluster ids
 *
 * Embeddings are expected to arrive *in* the CSV â€” per Ori, generating them is not part
 * of this pipeline. --embed exists only so the toy CSV, which has no embedding column,
 * can still be run end to end.
 *
 * Usage:
 *   (no args)              the bundled toy CSV, whose four topics are known ground truth
 *   <path/to/export.csv>   the real export
 *   --embed                fetch missing vectors from OpenAi (needs a key, costs money)
 *   --summarize            Step 2: summarise each cluster via OpenAi (needs a key, costs money)
 *   --extract              Task 2: extract entities (what/who/where + post ids) per cluster (costs money)
 *   --k=15                 how many neighbours each post may connect to in the graph
 *   --min-sim=0.25         drop graph edges below this cosine similarity
 *   --resolution=3.0       Leiden: higher gives more, smaller, tighter clusters
 *   --max-cluster=100      cap cluster size; bigger clusters are split into sub-clusters
 *   --split-resolution=1.0 how hard to split an oversized cluster (lower = fewer sub-clusters)
 */
public class App {

    public static void main(String[] args) throws Exception {
        String csvPath = csvPath(args);
        try (DatabasePipeline database = DatabasePipeline.start(csvPath, args)) {
            try {
                run(args, csvPath, database);
            } catch (Exception e) {
                database.fail(e);
                throw e;
            }
        }
    }

    private static void run(String[] args, String csvPath, DatabasePipeline database)
            throws Exception {
        List<String> flags = Arrays.asList(args);
        boolean embed = flags.contains("--embed");
        boolean summarize = flags.contains("--summarize");
        boolean extract = flags.contains("--extract");
        int k = intFlag(flags, "--k=", 15);
        // 0.25 was calibrated on the toy CSV: at 0.35 a third of the posts had no neighbour
        // at all and Leiden had to leave them as singletons. Arabic embeddings may sit on a
        // different scale â€” check "posts with no neighbour" in the graph line before trusting it.
        double minSimilarity = doubleFlag(flags, "--min-sim=", 0.25);
        // 1.0 is modularity's textbook default, but on this corpus it fused unrelated topics
        // into a 1,007-post catch-all. 3.0 yields 43 clusters that hold up when you read them.
        double resolution = doubleFlag(flags, "--resolution=", 3.0);
        int maxClusterSize = intFlag(flags, "--max-cluster=", 100);
        // Splitting uses a LOWER resolution than the main pass: we want the fewest sub-clusters
        // that get an oversized cluster under the cap, not to shatter it into fragments.
        double splitResolution = doubleFlag(flags, "--split-resolution=", 1.0);

        // How long each step takes. Note the durations reflect the cache: embeddings and
        // summaries are near-instant once cached, and the API-fetch runs are much slower.
        Map<String, Long> timings = new LinkedHashMap<>();
        long t;

        t = System.currentTimeMillis();
        var sourceConfig = DatabaseConfig.fromEnvironment();
        List<Post> posts = csvPath != null
                ? CsvLoader.loadFromFile(Path.of(csvPath), CsvLoader.Options.teamPolicy())
                : sourceConfig.isPresent()
                    ? PostQualificationLoader.load(sourceConfig.get(),
                        envInt("WATCH_LIST_ID", PostQualificationLoader.DEFAULT_WATCH_LIST_ID),
                        envInt("POST_LOOKBACK_DAYS", PostQualificationLoader.DEFAULT_LOOKBACK_DAYS),
                        CsvLoader.Options.teamPolicy())
                    : CsvLoader.loadFromClasspath("/posts.csv", CsvLoader.Options.teamPolicy());
        database.postsLoaded(posts);
        timings.put(csvPath == null && sourceConfig.isPresent() ? "Load database posts" : "Load CSV",
                System.currentTimeMillis() - t);

        t = System.currentTimeMillis();
        if (!new Embedder().embedAll(posts, embed)) {
            database.fail(new IllegalStateException("Not every accepted post has an embedding"));
            return;
        }
        database.embeddingsReady();
        timings.put("Embeddings", System.currentTimeMillis() - t);

        t = System.currentTimeMillis();
        SimilarityGraph graph = SimilarityGraph.build(posts, k, minSimilarity);
        timings.put("Similarity graph (Step 1.6)", System.currentTimeMillis() - t);

        t = System.currentTimeMillis();
        new Clusterer(resolution).cluster(posts, graph);
        timings.put("Leiden clustering (Step 1.7)", System.currentTimeMillis() - t);

        // Task 1: no cluster larger than maxClusterSize â€” split the big ones into sub-clusters.
        t = System.currentTimeMillis();
        ClusterSplitter.splitOversized(posts, maxClusterSize, k, minSimilarity, splitResolution);
        timings.put("Split oversized clusters", System.currentTimeMillis() - t);

        Map<Integer, List<Post>> byCluster = Clusters.byCluster(posts);
        database.clustersReady(byCluster);
        report(byCluster, intFlag(flags, "--samples=", 4));

        // Task 2: entity extraction (what/who/where + post ids) for each cluster.
        if (extract) {
            t = System.currentTimeMillis();
            Map<Integer, ClusterExtraction> extractions = new Extractor().extractAll(byCluster);
            KnowledgeBase.saveEntities(extractions.values());
            database.extractionsReady(extractions);
            timings.put("Entity extraction (Task 2)", System.currentTimeMillis() - t);
        }

        if (summarize) {
            t = System.currentTimeMillis();
            Map<Integer, ClusterSummary> summaries = new Summarizer().summarizeAll(byCluster);
            database.summariesReady(summaries);
            timings.put("Cluster summaries (Step 2)", System.currentTimeMillis() - t);
            printSummaries(byCluster, summaries);

            // Step 3: merge the per-cluster summaries into one consolidated summary and
            // write it to disk for Step 4 to read.
            t = System.currentTimeMillis();
            ConsolidatedSummary kb = new Consolidator().consolidate(byCluster, summaries);
            KnowledgeBase.save(kb);
            database.complete(kb.overview());
            timings.put("Consolidation (Step 3)", System.currentTimeMillis() - t);
            System.out.println("\n=== Overview ===\n" + kb.overview());
        } else {
            database.complete(null);
        }

        printTimings(timings);
    }

    private static String csvPath(String[] args) {
        return Arrays.stream(args)
                .filter(arg -> !arg.startsWith("--"))
                .findFirst()
                .orElse(null);
    }

    /** Prints how long each step took, plus the total â€” the timing documentation. */
    private static void printTimings(Map<String, Long> timings) {
        System.out.println("\n=== Timing (this run) ===");
        long total = 0;
        for (Map.Entry<String, Long> step : timings.entrySet()) {
            System.out.printf("  %-30s %6.2f s%n", step.getKey(), step.getValue() / 1000.0);
            total += step.getValue();
        }
        System.out.printf("  %-30s %6.2f s%n", "Total", total / 1000.0);
    }

    /** Step 2: print each cluster's who/what/where/when, largest cluster first. */
    private static void printSummaries(Map<Integer, List<Post>> byCluster,
                                       Map<Integer, ClusterSummary> summaries) {
        List<Integer> ids = new ArrayList<>(summaries.keySet());
        ids.sort((a, b) -> byCluster.get(b).size() - byCluster.get(a).size());

        System.out.println("\n=== Cluster summaries ===");
        for (int id : ids) {
            ClusterSummary s = summaries.get(id);
            System.out.printf("%ncluster %d â€” %d posts%n", id, byCluster.get(id).size());
            System.out.println("  who   : " + s.who());
            System.out.println("  what  : " + s.what());
            System.out.println("  where : " + s.where());
            System.out.println("  when  : " + s.when());
        }
    }

    /**
     * Leiden always returns *some* clustering, and a bad one looks exactly like a good one
     * from the outside â€” a tidy list of integers. The only way to know whether it worked is
     * to read the posts it grouped together.
     */
    private static void report(Map<Integer, List<Post>> byCluster, int samples) {
        List<Integer> ids = new ArrayList<>(byCluster.keySet());
        ids.sort((a, b) -> byCluster.get(b).size() - byCluster.get(a).size());

        long singletons = byCluster.values().stream().filter(cluster -> cluster.size() == 1).count();
        System.out.printf("%n%d clusters, %d of them a single post.%n", ids.size(), singletons);

        System.out.println("\nLargest clusters:");
        for (int id : ids.subList(0, Math.min(10, ids.size()))) {
            List<Post> cluster = byCluster.get(id);
            if (cluster.size() == 1) {
                continue;
            }
            System.out.printf("%n  cluster %d â€” %d posts%n", id, cluster.size());
            for (Post post : cluster.subList(0, Math.min(samples, cluster.size()))) {
                System.out.println("      " + post.preview(90));
            }
        }
    }

    private static int intFlag(List<String> flags, String prefix, int fallback) {
        return flags.stream().filter(flag -> flag.startsWith(prefix))
                .findFirst().map(flag -> Integer.parseInt(flag.substring(prefix.length())))
                .orElse(fallback);
    }

    private static double doubleFlag(List<String> flags, String prefix, double fallback) {
        return flags.stream().filter(flag -> flag.startsWith(prefix))
                .findFirst().map(flag -> Double.parseDouble(flag.substring(prefix.length())))
                .orElse(fallback);
    }

    private static int envInt(String name, int fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
    }
}
