package com.leadspotnic.agent;

import com.leadspotnic.model.ClusterSummary;
import com.leadspotnic.model.ConsolidatedSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The research log is built from the real actions, in code, with no LLM — so it can be tested
 * offline. These checks pin the wording that changes between "searched" and "user-picked".
 */
class AgentResearchLogTest {

    private static TopicIndex.Match match(int id, String what) {
        ClusterSummary summary = new ClusterSummary("some people", what, "", "2026");
        return new TopicIndex.Match(new ConsolidatedSummary.TopicEntry(id, 100, summary), 1.0);
    }

    private static final List<TopicIndex.Match> MATCHES =
            List.of(match(0, "Football and the World Cup"), match(1, "Lebanese politics"));

    @Test
    void searchedTraceLooksAcrossAllTopicsThenSelects() {
        List<String> trace = Agent.buildActionTrace(MATCHES, false, 41);

        assertTrue(trace.stream().anyMatch(a -> a.contains("all 41 topics")));
        assertTrue(trace.stream().anyMatch(a -> a.contains("Football and the World Cup")));
    }

    @Test
    void scopedTraceUsesOnlyTheChosenTopics() {
        List<String> trace = Agent.buildActionTrace(MATCHES, true, 41);

        assertTrue(trace.stream().anyMatch(a -> a.contains("the user chose")));
        assertFalse(trace.stream().anyMatch(a -> a.contains("all 41 topics")));
    }
}
