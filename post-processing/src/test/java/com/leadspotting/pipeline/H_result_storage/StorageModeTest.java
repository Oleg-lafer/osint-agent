package com.leadspotting.pipeline.H_result_storage;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageModeTest {

    @Test
    void defaultsToDatabase() {
        StorageMode mode = StorageMode.fromEnvironment(Map.of());

        assertEquals(StorageMode.DATABASE, mode);
        assertTrue(mode.writesDatabase());
        assertFalse(mode.writesLocal());
    }

    @Test
    void acceptsExplicitLocalAndBothModes() {
        assertEquals(StorageMode.LOCAL,
                StorageMode.fromEnvironment(Map.of("STORAGE_MODE", " local ")));
        assertEquals(StorageMode.BOTH,
                StorageMode.fromEnvironment(Map.of("STORAGE_MODE", "BoTh")));
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class,
                () -> StorageMode.fromEnvironment(Map.of("STORAGE_MODE", "fallback")));
    }
}
