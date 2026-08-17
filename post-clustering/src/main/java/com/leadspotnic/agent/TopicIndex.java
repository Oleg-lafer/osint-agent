package com.leadspotnic.agent;

import com.leadspotnic.model.ClusterSummary;
import com.leadspotnic.model.ConsolidatedSummary;
import com.leadspotnic.cluster.Embedder;
import com.leadspotnic.cluster.Vectors;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 4.3: the searchable index over the topics.
 *
 * At startup it embeds each topic's summary into a vector (reusing the same OpenAi model
 * as the posts, so the vectors are comparable). search() then embeds a user query and ranks
 * the topics by cosine similarity â€” the RAG retrieval the agent uses for specific questions.
 */
public class TopicIndex {

    /** One topic and how well it matched the query (1.0 = identical direction). */
    public record Match(ConsolidatedSummary.TopicEntry topic, double score) {}

    private final List<ConsolidatedSummary.TopicEntry> topics;
    private final float[][] vectors;
    private final Embedder embedder;

    public TopicIndex(ConsolidatedSummary kb, Embedder embedder) throws IOException, InterruptedException {
        this.embedder = embedder;
        this.topics = kb.topics();
        this.vectors = new float[topics.size()][];
        for (int i = 0; i < topics.size(); i++) {
            vectors[i] = embedder.embed(topicText(topics.get(i)));
        }
        System.out.println("Topic index: embedded " + topics.size() + " topics");
    }

    /** The top-k topics most similar to the query, best first. */
    public List<Match> search(String query, int k) throws IOException, InterruptedException {
        float[] q = embedder.embed(query);

        List<Match> matches = new ArrayList<>(topics.size());
        for (int i = 0; i < topics.size(); i++) {
            matches.add(new Match(topics.get(i), Vectors.dot(q, vectors[i])));
        }
        matches.sort((a, b) -> Double.compare(b.score(), a.score()));
        return matches.subList(0, Math.min(k, matches.size()));
    }

    /**
     * The specific topics the user picked, as matches (score 1.0), in the given order.
     * Unknown ids are skipped. Used when the chat is scoped to chosen topics rather than searched.
     */
    public List<Match> byIds(List<Integer> topicIds) {
        Map<Integer, ConsolidatedSummary.TopicEntry> byId = new HashMap<>();
        for (ConsolidatedSummary.TopicEntry topic : topics) {
            byId.put(topic.clusterId(), topic);
        }
        List<Match> result = new ArrayList<>();
        for (int id : topicIds) {
            ConsolidatedSummary.TopicEntry topic = byId.get(id);
            if (topic != null) {
                result.add(new Match(topic, 1.0));
            }
        }
        return result;
    }

    /** What of a topic gets embedded: its who/what/where, the query-relevant fields. */
    private static String topicText(ConsolidatedSummary.TopicEntry topic) {
        ClusterSummary s = topic.summary();
        return s.what() + ". " + s.who() + " " + s.where();
    }
}
