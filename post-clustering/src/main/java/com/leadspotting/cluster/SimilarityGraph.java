package com.leadspotting.cluster;

import com.leadspotting.model.Post;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Step 1.6: turns a pile of vectors into a graph, which is what Leiden actually consumes.
 *
 * Every post becomes a node. Two posts get an edge if they are among each other's most
 * similar posts, weighted by how similar they are. Dense regions become generated clusters.
 *
 * Why k-nearest-neighbours rather than "an edge between every pair above a threshold":
 * a threshold alone is a single global knob, and post similarity isn't globally calibrated —
 * a tight group like football sits at 0.7 while a diffuse one like politics sits at 0.45.
 * One threshold either fragments diffuse groups or fuses tight ones. Taking each
 * post's top k neighbours instead lets every post connect to its own neighbourhood, and the
 * threshold then only serves to cut genuinely unrelated pairs.
 */
public class SimilarityGraph {

    /** An undirected edge, already deduplicated. */
    public record Edge(int source, int target, double weight) {}

    private final List<Edge> edges;
    private final int nodeCount;

    private SimilarityGraph(List<Edge> edges, int nodeCount) {
        this.edges = edges;
        this.nodeCount = nodeCount;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    /**
     * @param k             how many neighbours each post may connect to
     * @param minSimilarity edges below this cosine are dropped as unrelated
     */
    public static SimilarityGraph build(List<Post> posts, int k, double minSimilarity) {
        int n = posts.size();

        // One flat array rather than n float[]s: the inner loop below runs ~n²·1536 times,
        // and contiguous memory is what makes that bearable.
        float[][] vectors = new float[n][];
        for (int i = 0; i < n; i++) {
            vectors[i] = posts.get(i).getEmbedding();
            if (vectors[i] == null) {
                throw new IllegalStateException("Post " + posts.get(i).getPostId() + " has no embedding");
            }
        }

        // neighbours[i] = the k posts most similar to post i.
        int[][] neighbours = new int[n][];
        double[][] similarities = new double[n][];

        IntStream.range(0, n).parallel().forEach(i -> {
            int[] bestIndex = new int[k];
            double[] bestScore = new double[k];
            int found = 0;

            for (int j = 0; j < n; j++) {
                if (i == j) {
                    continue;
                }
                double score = Vectors.dot(vectors[i], vectors[j]);
                if (score < minSimilarity) {
                    continue;
                }

                // Insertion into a k-sized top list. k is small (~15), so this beats
                // sorting all n scores for every one of the n posts.
                if (found < k) {
                    int pos = found++;
                    while (pos > 0 && bestScore[pos - 1] < score) {
                        bestScore[pos] = bestScore[pos - 1];
                        bestIndex[pos] = bestIndex[pos - 1];
                        pos--;
                    }
                    bestScore[pos] = score;
                    bestIndex[pos] = j;
                } else if (score > bestScore[k - 1]) {
                    int pos = k - 1;
                    while (pos > 0 && bestScore[pos - 1] < score) {
                        bestScore[pos] = bestScore[pos - 1];
                        bestIndex[pos] = bestIndex[pos - 1];
                        pos--;
                    }
                    bestScore[pos] = score;
                    bestIndex[pos] = j;
                }
            }

            neighbours[i] = Arrays.copyOf(bestIndex, found);
            similarities[i] = Arrays.copyOf(bestScore, found);
        });

        // "i lists j" and "j lists i" are the same undirected edge, so keep it once.
        // Symmetrising by union rather than intersection matters: a post on the edge of a
        // a group may name a hub post as its neighbour without the hub naming it back, and
        // dropping that edge would strand the post as a singleton.
        List<Edge> edges = new ArrayList<>();
        HashSet<Long> seen = new HashSet<>();
        for (int i = 0; i < n; i++) {
            for (int slot = 0; slot < neighbours[i].length; slot++) {
                int j = neighbours[i][slot];
                long key = (long) Math.min(i, j) * n + Math.max(i, j);
                if (seen.add(key)) {
                    edges.add(new Edge(Math.min(i, j), Math.max(i, j), similarities[i][slot]));
                }
            }
        }

        long isolated = IntStream.range(0, n).filter(i -> neighbours[i].length == 0).count();
        System.out.printf("Graph: %d nodes, %d edges (k=%d, minSimilarity=%.2f), %d posts with no neighbour%n",
                n, edges.size(), k, minSimilarity, isolated);

        return new SimilarityGraph(edges, n);
    }
}
