package com.leadspotnic.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void absentCredentialsPathDisablesDatabase() throws Exception {
        assertTrue(DatabaseConfig.fromEnvironment(Map.of()).isEmpty());
    }

    @Test
    void readsCredentialFileAndUsesDefaults() throws Exception {
        Path credentials = tempDir.resolve("database.txt");
        Files.writeString(credentials, """
                host="db.example.com",
                user=agent,
                password="secret",
                charset="utf8mb4",
                """);

        DatabaseConfig config = DatabaseConfig.fromEnvironment(
                Map.of("DB_CREDENTIALS_FILE", credentials.toString())).orElseThrow();

        assertEquals("db.example.com", config.host());
        assertEquals(3306, config.port());
        assertEquals("leadspot_main", config.database());
        assertEquals("agent", config.user());
        assertEquals("secret", config.password());
    }

    @Test
    void environmentOverridesDatabaseAndPort() throws Exception {
        Path credentials = tempDir.resolve("database.txt");
        Files.writeString(credentials, "host=db\nuser=agent\npassword=secret\ndb=from-file\n");
        Map<String, String> environment = new HashMap<>();
        environment.put("DB_CREDENTIALS_FILE", credentials.toString());
        environment.put("DB_NAME", "chosen_schema");
        environment.put("DB_PORT", "3307");

        DatabaseConfig config = DatabaseConfig.fromEnvironment(environment).orElseThrow();

        assertEquals("chosen_schema", config.database());
        assertEquals(3307, config.port());
    }
}
