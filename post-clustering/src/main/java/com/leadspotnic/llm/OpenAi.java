package com.leadspotnic.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Single source of truth for the direct OpenAI API connection and model choices. */
public final class OpenAi {

    private static final Path API_KEY_FILE = Path.of("..", "KEYS_AND_CREDENTIALS", "OPEN_AI.txt");

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

    /** Reads the raw OpenAI key from the repository's gitignored credentials directory. */
    public static String apiKey() {
        return readKeyFile(API_KEY_FILE);
    }

    static String readKeyFile(Path file) {
        if (!Files.exists(file)) {
            throw new IllegalStateException("OpenAI key file does not exist: "
                    + file.toAbsolutePath().normalize());
        }
        try {
            String key = Files.readString(file).trim();
            if (key.isBlank()) {
                throw new IllegalStateException("OpenAI key file is empty: "
                        + file.toAbsolutePath().normalize());
            }
            return key;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read " + file.toAbsolutePath(), e);
        }
    }
}
