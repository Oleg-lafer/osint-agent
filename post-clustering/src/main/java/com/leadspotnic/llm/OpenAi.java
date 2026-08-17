package com.leadspotnic.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Single source of truth for the direct OpenAI API connection and model choices. */
public final class OpenAi {

    private OpenAi() {
    }

    public static final String BASE_URL = "https://api.openai.com/v1";
    public static final String EMBEDDINGS_URL = BASE_URL + "/embeddings";
    public static final String CHAT_MODEL = "gpt-4o-mini";
    public static final String EMBED_MODEL = "text-embedding-3-small";

    public static ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(BASE_URL)
                .apiKey(apiKey())
                .modelName(CHAT_MODEL)
                .temperature(0.0)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    /** Environment variables take precedence; a local gitignored .env is the fallback. */
    public static String apiKey() {
        String key = System.getenv("OPENAI_API_KEY");
        if (key == null || key.isBlank()) {
            key = readDotEnv("OPENAI_API_KEY");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is not set. Add it to the environment or post-clustering/.env.");
        }
        return key;
    }

    static String readDotEnv(String name) {
        Path file = Path.of(".env");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator < 1 || !trimmed.substring(0, separator).trim().equals(name)) {
                    continue;
                }
                String value = trimmed.substring(separator + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                return value;
            }
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file.toAbsolutePath(), e);
        }
    }
}
