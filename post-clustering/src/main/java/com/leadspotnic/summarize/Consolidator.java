package com.leadspotnic.summarize;

import com.leadspotnic.model.Post;
import com.leadspotnic.model.ClusterSummary;
import com.leadspotnic.model.ConsolidatedSummary;
import com.leadspotnic.llm.OpenAi;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Step 3: merges the individual cluster summaries into one ConsolidatedSummary.
 *
 * Two halves:
 *   - mechanical: wrap every cluster into a TopicEntry (its id, size, and Step 2 summary)
 *   - LLM: one call that turns those summaries into a single big-picture overview paragraph
 *
 * The overview is what Step 4 will read for high-level questions; the per-topic entries are
 * what it will search for specific ones.
 */
public class Consolidator {

    private static final String OVERVIEW_INSTRUCTIONS = """
            Below are the topics found across a dataset of social-media posts, each with how
            many posts it holds. Write ONE short paragraph (3-5 sentences) in English that
            describes what the dataset as a whole is about and its main themes. Base it only
            on the topics listed â€” do not invent anything.

            Topics:
            """;

    /** The typed door for the overview call: returns a plain paragraph, not a record. */
    interface Editor {
        @SystemMessage("""
                You are a data analyst. Respond in English, as a single concise paragraph.
                No lists, no headings, no preamble â€” just the paragraph.
                """)
        String writeOverview(String prompt);
    }

    private Editor editor;

    /**
     * @param byCluster  every cluster's posts (for sizes and the total)
     * @param summaries  every cluster's Step 2 summary
     */
    public ConsolidatedSummary consolidate(Map<Integer, List<Post>> byCluster,
                                           Map<Integer, ClusterSummary> summaries) {
        // --- mechanical half: one TopicEntry per summarised cluster, largest first ---
        List<Integer> ids = new ArrayList<>(summaries.keySet());
        ids.sort((a, b) -> byCluster.get(b).size() - byCluster.get(a).size());

        List<ConsolidatedSummary.TopicEntry> topics = new ArrayList<>();
        for (int id : ids) {
            int postCount = byCluster.get(id).size();
            topics.add(new ConsolidatedSummary.TopicEntry(id, postCount, summaries.get(id)));
        }

        int totalPosts = byCluster.values().stream().mapToInt(List::size).sum();

        // --- LLM half: one overview paragraph from the topic summaries ---
        String overview = editor().writeOverview(OVERVIEW_INSTRUCTIONS + renderTopics(topics));

        return new ConsolidatedSummary(totalPosts, topics.size(), overview, topics);
    }

    /** One line per topic: "- what [N posts]", the material the overview is written from. */
    private static String renderTopics(List<ConsolidatedSummary.TopicEntry> topics) {
        StringBuilder sb = new StringBuilder();
        for (ConsolidatedSummary.TopicEntry topic : topics) {
            sb.append("- ").append(topic.summary().what())
              .append(" [").append(topic.postCount()).append(" posts]\n");
        }
        return sb.toString();
    }

    private Editor editor() {
        if (editor == null) {
            editor = AiServices.create(Editor.class, OpenAi.chatModel());
        }
        return editor;
    }
}
