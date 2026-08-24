package com.leadspotting.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OpenAiTest {

    @TempDir
    Path tempDir;

    @Test
    void readsTrimmedRawKey() throws Exception {
        Path file = tempDir.resolve("OPEN_AI.txt");
        Files.writeString(file, "  test-key\r\n");

        assertEquals("test-key", OpenAi.readKeyFile(file));
    }

    @Test
    void rejectsMissingKeyFile() {
        assertThrows(IllegalStateException.class,
                () -> OpenAi.readKeyFile(tempDir.resolve("missing.txt")));
    }
}
