package com.leadspotnic.agent;

import com.leadspotnic.model.ClusterExtraction;
import com.leadspotnic.model.ClusterSummary;
import com.leadspotnic.model.ConsolidatedSummary;
import com.leadspotnic.model.Entity;
import com.leadspotnic.llm.OpenAi;

import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.tool.ToolExecution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Step 4.4: the agent that answers a user question.
 *
 * Routing, done robustly. For every question it retrieves the few most-related topics, then
 * hands the LLM both those topics and the dataset overview, and lets the model answer from
 * whichever fits: the overview for high-level questions, the specific topics for granular ones.
 *
 * Why top-k rather than a hard "specific vs high-level" threshold: retrieval scores aren't
 * calibrated the same across questions (politics matched at 0.68, football at 0.25), so a
 * fixed cutoff misfires. Handing the model a handful of candidates lets it pick the relevant
 * one even when the top-ranked match is wrong â€” the right topic is still in the shortlist.
 */
public class Agent {

    /** How many candidate topics to put in front of the model. */
    private static final int TOP_K = 5;

    /** A question, the answer, the topics used ("sources"), and a plain-language research log. */
    public record Answer(String text, List<TopicIndex.Match> sources, List<ResearchStep> researchLog) {}

    /** One readable step in the "how I investigated this" log â€” no technical jargon. */
    public record ResearchStep(String title, String detail) {}

    /** The narrated log â€” a wrapper so the model returns a JSON object with a steps array. */
    public record ResearchLog(List<ResearchStep> steps) {}

    interface Assistant {
        @SystemMessage("""
                You answer questions about a dataset of social-media posts, in English.

                You are given an overall summary, the most related topics, and â€” for each topic â€”
                its entities (people, places, subjects) with the ids of the posts that mention
                them. You also have tools that reach the individual posts.

                Decide how much depth the question needs:
                - For a general or overview question, answer from the summaries and entities you
                  were given. Do NOT call any tool.
                - Only when the question needs specific detail the summaries don't contain â€”
                  exact wording, what a particular post said, quotes â€” use a tool:
                    * loadPosts   when the entities already give you the relevant post ids.
                    * searchPosts when the subject isn't in the topics/entities at all.
                    * filterPosts when the user asks by author or date range.

                Important: if the question asks about a subject you don't see in the topics or
                entities, do NOT answer that you can't find it. Call searchPosts first to look
                through the individual posts, and only say there is nothing if that search comes
                back empty.

                When a tool returns posts, answer FROM them: summarise and quote what they say,
                even if they aren't a perfect match. Don't demand an exact match and give up â€”
                if you retrieved relevant posts, use them. Only reply that there is nothing when
                a tool genuinely returned no posts.

                Use only the information you were given or that a tool returned. Be concise.
                """)
        Result<String> respond(String prompt);
    }

    /**
     * Turns the factual action trace into a readable log. It only rephrases the actions we hand
     * it â€” told never to add or invent â€” so the log stays accurate while reading naturally, and
     * it adapts on its own as the agent's set of actions grows.
     */
    interface Narrator {
        @SystemMessage("""
                You are given the exact actions a research assistant took to answer a question.
                Rewrite them into a short, readable "research log" the user can audit.

                Rules:
                - Describe ONLY the actions listed. Never add, invent, or assume a step.
                - Plain, first-person language ("I ..."). No technical jargon â€” never say
                  embeddings, vectors, clustering, cosine, model, index, or database.
                - Stay substantive: drop trivial framing like "I understood the question" or
                  "I wrote the answer".
                - A few short steps, each a brief title and a one-sentence detail.
                """)
        ResearchLog narrate(String actions);
    }

    private final ConsolidatedSummary kb;
    private final TopicIndex index;
    // Task 3 drill-down: entities carry the post ids (keyed by cluster so we can attach them to
    // the topics a question matched), the store turns an id into the real post, and the tools
    // let the model reach the posts on demand.
    private final Map<Integer, ClusterExtraction> entitiesByCluster;
    private final AgentTools tools;
    private Assistant assistant;
    private Narrator narrator;

    public Agent(ConsolidatedSummary kb, TopicIndex index,
                 List<ClusterExtraction> entities, PostStore posts, PostIndex postIndex) {
        this.kb = kb;
        this.index = index;
        this.entitiesByCluster = new LinkedHashMap<>();
        for (ClusterExtraction e : entities) {
            entitiesByCluster.put(e.clusterId(), e);
        }
        this.tools = new AgentTools(posts, postIndex);
    }

    /**
     * @param topicIds if non-empty, the answer is scoped to exactly these topics (the user
     *                 picked them); otherwise the agent retrieves the most relevant ones itself.
     */
    public Answer answer(String question, List<Integer> topicIds) throws IOException, InterruptedException {
        boolean scoped = topicIds != null && !topicIds.isEmpty();
        List<TopicIndex.Match> matches = scoped
                ? index.byIds(topicIds)
                : index.search(question, TOP_K);
        String prompt = buildPrompt(question, matches, scoped);
        Result<String> result = assistant().respond(prompt);

        // The topic-selection trace, plus a line for each tool the model chose to run â€” so the
        // research log reflects whether it drilled into posts or answered from the summaries.
        List<String> actions = new ArrayList<>(buildActionTrace(matches, scoped, kb.topicCount()));
        actions.addAll(actions.size() - 1, describeToolUse(result.toolExecutions()));
        return new Answer(result.content(), matches, narrate(actions));
    }

    /**
     * The factual record of what the agent actually did â€” plain statements, deterministic, and
     * unit-tested. As the agent gains new abilities (reading raw posts, quoting, comparingâ€¦),
     * each one adds its own entry here, and the narrator describes it without any new template.
     */
    static List<String> buildActionTrace(List<TopicIndex.Match> matches, boolean scoped, int totalTopics) {
        List<String> actions = new ArrayList<>();
        String topicNames = joinTopicNames(matches);

        if (scoped) {
            actions.add("Used the " + matches.size() + " topic" + (matches.size() == 1 ? "" : "s")
                    + " the user chose" + (topicNames.isEmpty() ? "." : ": " + topicNames + "."));
        } else {
            actions.add("Looked across all " + totalTopics + " topics found in the dataset.");
            if (!matches.isEmpty()) {
                actions.add("Selected the " + matches.size() + " most related to the question: "
                        + topicNames + ".");
            }
        }
        actions.add("Read the summary of each selected topic, plus the overall summary of the dataset.");
        actions.add("Wrote an answer based on them.");
        return actions;
    }

    /**
     * Turns the factual trace into the readable log via the model. The model only rephrases the
     * recorded actions; if the call fails, we fall back to the raw actions so the log never
     * breaks the answer.
     */
    private List<ResearchStep> narrate(List<String> actions) {
        try {
            ResearchLog narrated = narrator().narrate(String.join("\n", actions));
            if (narrated != null && narrated.steps() != null && !narrated.steps().isEmpty()) {
                return narrated.steps();
            }
        } catch (Exception e) {
            System.out.println("Research log narration failed, using the raw actions: " + e.getMessage());
        }
        List<ResearchStep> fallback = new ArrayList<>();
        for (String action : actions) {
            fallback.add(new ResearchStep("", action));
        }
        return fallback;
    }

    private static String joinTopicNames(List<TopicIndex.Match> matches) {
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) {
                names.append("; ");
            }
            names.append(matches.get(i).topic().summary().what());
        }
        return names.toString();
    }

    private String buildPrompt(String question, List<TopicIndex.Match> matches, boolean scoped) {
        StringBuilder sb = new StringBuilder();
        sb.append("Overall summary of the dataset:\n").append(kb.overview()).append("\n\n");
        sb.append(scoped
                ? "The user has chosen to focus on these topics â€” answer from them:\n"
                : "Topics most related to the question:\n");
        for (TopicIndex.Match m : matches) {
            ClusterSummary s = m.topic().summary();
            sb.append("- [").append(m.topic().postCount()).append(" posts] ")
              .append("what: ").append(s.what())
              .append("; who: ").append(s.who());
            if (!s.where().isBlank()) {
                sb.append("; where: ").append(s.where());
            }
            sb.append('\n');
            appendEntities(sb, m.topic().clusterId());
        }
        sb.append("\nQuestion: ").append(question);
        return sb.toString();
    }

    /**
     * Lists a topic's entities with the ids of the posts behind each â€” the drill-down map the
     * model uses to call loadPosts. Kept compact: a capped set of ids per entity, so the prompt
     * stays small even for a big cluster.
     */
    private void appendEntities(StringBuilder sb, int clusterId) {
        ClusterExtraction extraction = entitiesByCluster.get(clusterId);
        if (extraction == null) {
            return;
        }
        appendEntityLine(sb, "what", extraction.what());
        appendEntityLine(sb, "who", extraction.who());
        appendEntityLine(sb, "where", extraction.where());
    }

    private static final int MAX_IDS_PER_ENTITY = 12;

    /** A few entities per category is enough context without bloating the prompt. */
    private static final int MAX_ENTITIES_PER_CATEGORY = 6;

    private static void appendEntityLine(StringBuilder sb, String label, List<Entity> entities) {
        if (entities == null || entities.isEmpty()) {
            return;
        }
        int shownEntities = Math.min(entities.size(), MAX_ENTITIES_PER_CATEGORY);
        for (int n = 0; n < shownEntities; n++) {
            Entity e = entities.get(n);
            // The name, its substantive context (the answer source), then the post ids to quote.
            sb.append("    ").append(label).append(": ").append(e.name());
            if (e.context() != null && !e.context().isBlank()) {
                sb.append(" â€” ").append(e.context());
            }
            sb.append(" (post ids: ");
            List<Long> ids = e.postIds();
            int shown = Math.min(ids.size(), MAX_IDS_PER_ENTITY);
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(ids.get(i));
            }
            if (ids.size() > shown) {
                sb.append(",â€¦");
            }
            sb.append(")\n");
        }
    }

    /** One readable line per tool the model actually ran, for the research log. */
    private static List<String> describeToolUse(List<ToolExecution> executions) {
        List<String> lines = new ArrayList<>();
        if (executions == null) {
            return lines;
        }
        boolean loaded = false;
        boolean searched = false;
        boolean filtered = false;
        for (ToolExecution ex : executions) {
            switch (ex.request().name()) {
                case "loadPosts" -> loaded = true;
                case "searchPosts" -> searched = true;
                case "filterPosts" -> filtered = true;
                default -> { }
            }
        }
        if (loaded) {
            lines.add("Opened and read the specific posts behind the relevant entities.");
        }
        if (searched) {
            lines.add("Searched the individual posts by meaning to find relevant ones.");
        }
        if (filtered) {
            lines.add("Filtered the posts by the requested author or date range.");
        }
        return lines;
    }

    private Assistant assistant() {
        if (assistant == null) {
            assistant = AiServices.builder(Assistant.class)
                    .chatModel(OpenAi.chatModel())
                    .tools(tools)   // loadPosts / searchPosts / filterPosts, called on demand
                    .build();
        }
        return assistant;
    }

    private Narrator narrator() {
        if (narrator == null) {
            narrator = AiServices.create(Narrator.class, OpenAi.chatModel());
        }
        return narrator;
    }
}
