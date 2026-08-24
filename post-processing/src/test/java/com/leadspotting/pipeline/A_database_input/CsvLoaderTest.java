package com.leadspotting.pipeline.A_database_input;

import com.leadspotting.model.Post;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvLoaderTest {

    @TempDir
    Path tmp;

    private static final String THREE_ROWS =
            "profile_name,id,text,publish_date\n"
            + "Alice,1,This is a normal length post about football,2026-06-01\n"
            + "Bob,2,hi,2026-06-02\n"                                             // too short (< 15)
            + "Carol,3,This is a normal length post about football,2026-06-03\n"; // duplicate of Alice's

    private Path write(String content) throws IOException {
        Path csv = tmp.resolve("posts.csv");
        Files.writeString(csv, content);
        return csv;
    }

    @Test
    void teamPolicyDropsShortPostsButKeepsDuplicates() throws IOException {
        List<Post> posts = CsvLoader.loadFromFile(write(THREE_ROWS), CsvLoader.Options.teamPolicy());
        assertEquals(2, posts.size());   // "hi" dropped; the duplicate stays
    }

    @Test
    void duplicatesAreDroppedWhenTheOptionIsOn() throws IOException {
        List<Post> posts = CsvLoader.loadFromFile(write(THREE_ROWS), new CsvLoader.Options(15, true));
        assertEquals(1, posts.size());   // short dropped and duplicate collapsed
    }

    @Test
    void aMissingRequiredColumnFailsLoudly() throws IOException {
        Path csv = write("profile_name,id,publish_date\nAlice,1,2026-06-01\n");  // no "text" column
        assertThrows(IOException.class,
                () -> CsvLoader.loadFromFile(csv, CsvLoader.Options.teamPolicy()));
    }
}
