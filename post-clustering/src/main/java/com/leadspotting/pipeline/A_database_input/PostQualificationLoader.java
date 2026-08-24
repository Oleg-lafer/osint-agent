package com.leadspotting.pipeline.A_database_input;

import com.leadspotting.model.Post;
import com.leadspotting.database.DatabaseConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads recent posts directly from the company post_qualification table. */
public final class PostQualificationLoader {
    public static final int DEFAULT_WATCH_LIST_ID = 1406;
    public static final int DEFAULT_LOOKBACK_DAYS = 14;
    public static final int DEFAULT_POST_LIMIT = 3000;

    private PostQualificationLoader() {}

    public static List<Post> load(DatabaseConfig config, int watchListId, int lookbackDays, int postLimit,
                                  CsvLoader.Options options) throws Exception {
        if (watchListId <= 0 || lookbackDays <= 0 || postLimit <= 0) {
            throw new IllegalArgumentException("watchListId, lookbackDays, and postLimit must be positive");
        }
        String sql = """
                SELECT userId, content, creation_time
                FROM post_qualification
                WHERE watch_list_id = ?
                  AND DATEDIFF(NOW(), creation_time) < ?
                ORDER BY creation_time DESC, postId DESC
                LIMIT ?
                """;
        List<Post> posts = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();
        int tooShort = 0;
        int duplicates = 0;

        try (Connection connection = DriverManager.getConnection(
                    config.jdbcUrl(), config.user(), config.password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, watchListId);
            statement.setInt(2, lookbackDays);
            statement.setInt(3, postLimit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String text = rows.getString("content");
                    text = text == null ? "" : text;
                    String normalised = text.replaceAll("\\s+", " ").trim();
                    if (normalised.length() < options.minTextLength()) {
                        tooShort++;
                        continue;
                    }
                    if (options.dropDuplicateTexts() && !seenTexts.add(normalised)) {
                        duplicates++;
                        continue;
                    }
                    var timestamp = rows.getTimestamp("creation_time");
                    posts.add(new Post(Long.toString(rows.getLong("userId")), text,
                            timestamp == null ? null : timestamp.toLocalDateTime().toLocalDate()));
                }
            }
        }
        System.out.printf("Loaded %d posts from post_qualification for watch list %d "
                        + "(last %d days, newest %d rows; skipped %d under %d chars, %d duplicate texts)%n",
                posts.size(), watchListId, lookbackDays, postLimit,
                tooShort, options.minTextLength(), duplicates);
        return posts;
    }
}
