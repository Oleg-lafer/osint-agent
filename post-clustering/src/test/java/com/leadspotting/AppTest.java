package com.leadspotting;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppTest {

    @Test
    void qualificationIsTheDefaultPostSource() {
        assertEquals("post-qualification", App.postSource(new String[0]));
    }

    @Test
    void summaryCanBeSelectedAsASeparatePostSource() {
        assertEquals("post-summary",
                App.postSource(new String[] {"--post-source=post-summary"}));
    }

    @Test
    void rejectsUnknownPostSource() {
        assertThrows(IllegalArgumentException.class,
                () -> App.postSource(new String[] {"--post-source=both"}));
    }
}
