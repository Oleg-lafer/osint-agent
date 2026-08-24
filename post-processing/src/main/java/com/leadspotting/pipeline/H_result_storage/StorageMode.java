package com.leadspotting.pipeline.H_result_storage;

import java.util.Locale;
import java.util.Map;

/** Selects the durable outputs used by the preprocessing pipeline and chat server. */
public enum StorageMode {
    DATABASE(false, true),
    LOCAL(true, false),
    BOTH(true, true);

    private final boolean local;
    private final boolean database;

    StorageMode(boolean local, boolean database) {
        this.local = local;
        this.database = database;
    }

    public boolean writesLocal() {
        return local;
    }

    public boolean writesDatabase() {
        return database;
    }

    public static StorageMode fromEnvironment() {
        return fromEnvironment(System.getenv());
    }

    static StorageMode fromEnvironment(Map<String, String> environment) {
        String configured = environment.get("STORAGE_MODE");
        if (configured == null || configured.isBlank()) {
            return DATABASE;
        }
        try {
            return valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "STORAGE_MODE must be database, local, or both; got: " + configured);
        }
    }
}
