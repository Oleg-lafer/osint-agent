package com.leadspotnic.cluster;

import com.leadspotnic.model.Post;

import nl.cwts.networkanalysis.Clustering;
import nl.cwts.networkanalysis.LeidenAlgorithm;
import nl.cwts.networkanalysis.Network;
import nl.cwts.util.LargeDoubleArray;
import nl.cwts.util.LargeIntArray;

import java.util.List;
import java.util.Random;

/**
 * Step 1.7: runs Leiden over the similarity graph and stamps a clusterId onto every post.
 *
 * Leiden looks for groups of nodes that are more densely connected to each other than to
 * the rest of the graph. On this graph, "densely connected" means "these posts are all
 * about the same thing" — so a community is a topic.
 *
 * This uses the reference implementation from CWTS, written by the authors of the Leiden
 * paper. Leiden is the successor to Louvain and exists because Louvain could return
 * communities that were internally disconnected — clusters that weren't really clusters.
 */
public class Clusterer {

    /** How many clusters you get. Higher = more, smaller, tighter topics. */
    private final double resolution;

    /** Leiden restarts and refines; more iterations, more stable output. */
    private static final int ITERATIONS = 10;

    /** Fixed seed: two runs on the same graph must give the same clusters, or nothing is debuggable. */
    private static final long SEED = 42;

    public Clusterer(double resolution) {
        this.resolution = resolution;
    }

    /** Writes a clusterId onto every post and returns how many clusters were found. */
    public int cluster(List<Post> posts, SimilarityGraph graph) {
        List<SimilarityGraph.Edge> edges = graph.getEdges();

        int[] sources = new int[edges.size()];
        int[] targets = new int[edges.size()];
        double[] weights = new double[edges.size()];
        for (int i = 0; i < edges.size(); i++) {
            sources[i] = edges.get(i).source();
            targets[i] = edges.get(i).target();
            weights[i] = edges.get(i).weight();
        }

        LargeIntArray[] edgeArray = { new LargeIntArray(sources), new LargeIntArray(targets) };

        // setNodeWeightsToTotalEdgeWeights = true selects modularity rather than raw CPM.
        // Modularity judges a community against what you'd expect by chance given how
        // connected its posts are, which is the sane default when node degrees vary a lot —
        // and here they vary enormously, because a boilerplate post like "📸 From the
        // archives" has far more near-identical neighbours than a real one.
        Network network = new Network(
                graph.getNodeCount(),
                true,
                edgeArray,
                new LargeDoubleArray(weights),
                false,  // edges aren't sorted; let Network sort them
                true);  // check integrity — a malformed graph should fail loudly

        // The library optimises CPM internally. This rescaling is what turns the resolution
        // into the familiar modularity scale, where 1.0 is the standard default.
        double scaled = resolution
                / (2 * network.getTotalEdgeWeight() + network.getTotalEdgeWeightSelfLinks());

        LeidenAlgorithm leiden = new LeidenAlgorithm(
                scaled, ITERATIONS, LeidenAlgorithm.DEFAULT_RANDOMNESS, new Random(SEED));

        Clustering clustering = leiden.findClustering(network);
        clustering.orderClustersByNNodes(); // cluster 0 is the biggest — makes reports readable

        int[] assignment = clustering.getClusters();
        for (int i = 0; i < posts.size(); i++) {
            posts.get(i).setClusterId(assignment[i]);
        }

        System.out.printf("Leiden: %d clusters (resolution=%.2f)%n",
                clustering.getNClusters(), resolution);
        return clustering.getNClusters();
    }
}
