package com.leadspotnic.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Explicitly opt-in because it briefly writes a chat session and messages to RDS. */
@EnabledIfEnvironmentVariable(named = "RUN_DB_TESTS", matches = "true")
class ChatDatabaseIntegrationTest {

    @Test
    void sessionOwnsOnePipelineRunAndMessagesInheritIt() throws Exception {
        DatabaseConfig config = DatabaseConfig.fromEnvironment().orElseThrow();
        long runId;
        try (AgentDatabase database = new AgentDatabase(config)) {
            runId = database.listAvailableRuns().get(0).id();
        }

        ChatDatabase chats = new ChatDatabase(config);
        String sessionId = chats.createSession("integration-test", runId);
        try {
            assertEquals(runId, chats.loadSession(sessionId).pipelineRunId());
            long assistantId = chats.beginTurn(sessionId, "Synthetic question", List.of());
            chats.completeTurn(assistantId, "Synthetic answer", 1, List.of(), List.of(), List.of());

            List<com.leadspotnic.agent.Agent.ChatMessage> history = chats.loadHistory(sessionId);
            assertEquals(2, history.size());
            assertEquals("Synthetic question", history.get(0).content());
            assertEquals("Synthetic answer", history.get(1).content());

            try (var connection = DriverManager.getConnection(
                    config.jdbcUrl(), config.user(), config.password());
                 var columns = connection.getMetaData().getColumns(
                         connection.getCatalog(), null, "AGENT_chat_messages", "pipeline_run_id")) {
                assertFalse(columns.next());
            }
        } finally {
            try (var connection = DriverManager.getConnection(
                    config.jdbcUrl(), config.user(), config.password());
                 var delete = connection.prepareStatement(
                         "DELETE FROM AGENT_chat_sessions WHERE id = ?")) {
                delete.setString(1, sessionId);
                assertEquals(1, delete.executeUpdate());
            }
        }
    }
}
