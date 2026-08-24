package com.leadspotting.chat_agent;

import com.leadspotting.model.ClusterSummary;
import com.leadspotting.model.ConsolidatedSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The research log is built from the real actions, in code, with no LLM — so it can be tested
 * offline. These checks pin the wording that changes between "searched" and "user-picked".
 */
class AgentResearchLogTest {

    private static ClusterIndex.Match match(int id, String what) {
        ClusterSummary summary = new ClusterSummary("some people", what, "", "2026");
        return new ClusterIndex.Match(new ConsolidatedSummary.ClusterEntry(id, 100, summary), 1.0);
    }

    private static final List<ClusterIndex.Match> MATCHES =
            List.of(match(0, "Football and the World Cup"), match(1, "Lebanese politics"));

    @Test
    void searchedTraceLooksAcrossAllClustersThenSelects() {
        List<String> trace = Agent.buildActionTrace(MATCHES, 41);

        assertTrue(trace.stream().anyMatch(a -> a.contains("all 41 clusters")));
        assertTrue(trace.stream().anyMatch(a -> a.contains("Football and the World Cup")));
    }

}
