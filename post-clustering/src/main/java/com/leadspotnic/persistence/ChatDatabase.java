package com.leadspotnic.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leadspotnic.agent.Agent;
import com.leadspotnic.llm.OpenAi;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Best-effort persistence for online chat sessions and their ordered transcripts. */
public final class ChatDatabase {
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final int HISTORY_LIMIT = 10;
    private final DatabaseConfig config;

    public ChatDatabase(DatabaseConfig config) {
        this.config = config;
    }

    public static Optional<ChatDatabase> fromEnvironment() {
        try {
            return DatabaseConfig.fromEnvironment().map(ChatDatabase::new);
        } catch (Exception e) {
            System.out.println("Chat persistence unavailable: " + e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    public String createSession(String userId) throws SQLException {
        String id = UUID.randomUUID().toString();
        String sql = "INSERT INTO AGENT_chat_sessions (id, user_id, status) VALUES (?, ?, 'ACTIVE')";
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.setString(2, blankToNull(userId));
            statement.executeUpdate();
        }
        return id;
    }

    /** Loads only completed messages and returns the newest bounded window chronologically. */
    public List<Agent.ChatMessage> loadHistory(String sessionId) throws SQLException {
        requireActive(sessionId);
        String sql = """
                SELECT role, content FROM AGENT_chat_messages
                WHERE session_id = ? AND status = 'COMPLETED' AND content IS NOT NULL
                ORDER BY sequence_number DESC LIMIT ?
                """;
        List<Agent.ChatMessage> history = new ArrayList<>();
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            statement.setInt(2, HISTORY_LIMIT);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    history.add(new Agent.ChatMessage(rows.getString("role"), rows.getString("content")));
                }
            }
        }
        Collections.reverse(history);
        return List.copyOf(history);
    }

    /** Atomically reserves consecutive sequence numbers for the user and assistant messages. */
    public long beginTurn(String sessionId, String query, List<Integer> topicIds,
                          Long pipelineRunId) throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                lockActiveSession(connection, sessionId);
                int next = nextSequence(connection, sessionId);
                insertMessage(connection, sessionId, next, "USER", query, "COMPLETED", null,
                        topicIds, null);
                long assistantId = insertMessage(connection, sessionId, next + 1, "ASSISTANT", null,
                        "PENDING", pipelineRunId, topicIds, OpenAi.CHAT_MODEL);
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE AGENT_chat_sessions SET updated_at = ? WHERE id = ?")) {
                    update.setTimestamp(1, Timestamp.from(Instant.now()));
                    update.setString(2, sessionId);
                    update.executeUpdate();
                }
                connection.commit();
                return assistantId;
            } catch (Exception e) {
                connection.rollback();
                throw e;
            }
        }
    }

    public void completeTurn(long messageId, String answer, long elapsedMs, List<Integer> topicIds,
                             Object sources, Object researchLog) throws Exception {
        String sql = """
                UPDATE AGENT_chat_messages SET content = ?, topic_ids = ?, sources = ?, research_log = ?,
                    elapsed_ms = ?, status = 'COMPLETED', error_message = NULL
                WHERE id = ? AND role = 'ASSISTANT'
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, answer);
            statement.setString(2, JSON.writeValueAsString(topicIds));
            statement.setString(3, JSON.writeValueAsString(sources));
            statement.setString(4, JSON.writeValueAsString(researchLog));
            statement.setLong(5, elapsedMs);
            statement.setLong(6, messageId);
            statement.executeUpdate();
        }
    }

    public void failTurn(long messageId, Throwable error) throws SQLException {
        String sql = """
                UPDATE AGENT_chat_messages SET status = 'FAILED', error_message = ?
                WHERE id = ? AND role = 'ASSISTANT'
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sanitizedError(error));
            statement.setLong(2, messageId);
            statement.executeUpdate();
        }
    }

    public void requireActive(String sessionId) throws SQLException {
        try (Connection connection = connection()) {
            requireActive(connection, sessionId, false);
        }
    }

    private void lockActiveSession(Connection connection, String sessionId) throws SQLException {
        requireActive(connection, sessionId, true);
    }

    private void requireActive(Connection connection, String sessionId, boolean lock) throws SQLException {
        String sql = "SELECT status FROM AGENT_chat_sessions WHERE id = ?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SessionNotFoundException(sessionId);
                if (!"ACTIVE".equals(row.getString("status"))) throw new SessionClosedException(sessionId);
            }
        }
    }

    private static int nextSequence(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(sequence_number), 0) + 1 FROM AGENT_chat_messages WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getInt(1);
            }
        }
    }

    private static long insertMessage(Connection connection, String sessionId, int sequence,
                                      String role, String content, String status, Long pipelineRunId,
                                      List<Integer> topicIds, String model) throws Exception {
        String sql = """
                INSERT INTO AGENT_chat_messages
                    (session_id, sequence_number, role, content, pipeline_run_id, topic_ids, model, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, sessionId);
            statement.setInt(2, sequence);
            statement.setString(3, role);
            statement.setString(4, content);
            if (pipelineRunId == null) statement.setNull(5, java.sql.Types.BIGINT);
            else statement.setLong(5, pipelineRunId);
            statement.setString(6, JSON.writeValueAsString(topicIds == null ? List.of() : topicIds));
            statement.setString(7, model);
            statement.setString(8, status);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("MySQL did not return the chat message id");
                return keys.getLong(1);
            }
        }
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(config.jdbcUrl(), config.user(), config.password());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String sanitizedError(Throwable error) {
        if (error == null) return "Chat request failed";
        return "Chat request failed (" + error.getClass().getSimpleName() + ")";
    }

    public static final class SessionNotFoundException extends SQLException {
        public SessionNotFoundException(String ignored) { super("Chat session was not found"); }
    }

    public static final class SessionClosedException extends SQLException {
        public SessionClosedException(String ignored) { super("Chat session is closed"); }
    }
}
