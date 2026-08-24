package com.leadspotting.database;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabasePipelineTest {

    @Test
    void commandLinePostGroupOverridesEnvironment() {
        assertEquals("group-A", DatabasePipeline.postGroupId(
                new String[] {"--post-group-id=group-A"}, Map.of("POST_GROUP_ID", "group-B")));
    }

    @Test
    void environmentPostGroupCanBeReusedAcrossExecutions() {
        Map<String, String> environment = Map.of("POST_GROUP_ID", "group-A");
        assertEquals("group-A", DatabasePipeline.postGroupId(new String[0], environment));
        assertEquals("group-A", DatabasePipeline.postGroupId(new String[0], environment));
    }

    @Test
    void unconfiguredExecutionsReceiveDistinctGroupIds() {
        String first = DatabasePipeline.postGroupId(new String[0], Map.of());
        String second = DatabasePipeline.postGroupId(new String[0], Map.of());
        UUID.fromString(first);
        UUID.fromString(second);
        assertNotEquals(first, second);
    }

    @Test
    void rejectsBlankExplicitGroupId() {
        assertThrows(IllegalArgumentException.class, () -> DatabasePipeline.postGroupId(
                new String[] {"--post-group-id=  "}, Map.of()));
    }
}
