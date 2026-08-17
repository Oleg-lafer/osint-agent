package com.leadspotnic.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Optional MySQL configuration loaded without putting secrets in the repository. */
public record DatabaseConfig(String host, int port, String database, String user, String password) {

    private static final Pattern ENTRY = Pattern.compile(
            "^\\s*([A-Za-z_]+)\\s*[:=]\\s*\\\"?([^\\\",]+)\\\"?\\s*,?\\s*$");

    public static Optional<DatabaseConfig> fromEnvironment() throws IOException {
        return fromEnvironment(System.getenv());
    }

    static Optional<DatabaseConfig> fromEnvironment(Map<String, String> environment) throws IOException {
        String credentialsFile = environment.get("DB_CREDENTIALS_FILE");
        if (credentialsFile == null || credentialsFile.isBlank()) {
            return Optional.empty();
        }

        Map<String, String> credentials = parse(Path.of(credentialsFile));
        String host = required(credentials, "host");
        String user = required(credentials, "user");
        String password = required(credentials, "password");
        String database = valueOrDefault(environment.get("DB_NAME"),
                valueOrDefault(credentials.get("db"), "leadspot_main"));
        int port = Integer.parseInt(valueOrDefault(environment.get("DB_PORT"), "3306"));
        return Optional.of(new DatabaseConfig(host, port, database, user, password));
    }

    static Map<String, String> parse(Path path) throws IOException {
        Map<String, String> values = new HashMap<>();
        for (String line : Files.readAllLines(path)) {
            Matcher matcher = ENTRY.matcher(line);
            if (matcher.matches()) {
                values.put(matcher.group(1).toLowerCase(), matcher.group(2));
            }
        }
        return values;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Database credentials file is missing '" + key + "'");
        }
        return value;
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=true&requireSSL=true&serverTimezone=UTC&rewriteBatchedStatements=true";
    }
}
