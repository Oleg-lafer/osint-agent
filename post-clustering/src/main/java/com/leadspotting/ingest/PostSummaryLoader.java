package com.leadspotting.ingest;

import com.leadspotting.model.Post;
import com.leadspotting.persistence.DatabaseConfig;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reads recent matching summaries from the company post_summary table. */
public final class PostSummaryLoader {
    public static final int DEFAULT_LOOKBACK_DAYS = 60;
    public static final int DEFAULT_POST_LIMIT = 3000;
    public static final String DEFAULT_SEARCH_TERM = "airport";

    private PostSummaryLoader() {}

    public static List<Post> load(DatabaseConfig config, int lookbackDays, String searchTerm, int postLimit,
                                  CsvLoader.Options options) throws Exception {
        if (lookbackDays <= 0 || postLimit <= 0) {
            throw new IllegalArgumentException("lookbackDays and postLimit must be positive");
        }
        if (searchTerm == null || searchTerm.isBlank()) {
            throw new IllegalArgumentException("searchTerm must not be blank");
        }

        String sql = """
                SELECT summary, creation_time
                FROM post_summary
                WHERE DATEDIFF(NOW(), creation_time) < ?
                  AND summary LIKE ?
                ORDER BY creation_time DESC
                LIMIT ?
                """;
        List<Post> posts = new ArrayList<>();
        Set<String> seenTexts = new HashSet<>();
        int tooShort = 0;
        int duplicates = 0;

        try (Connection connection = DriverManager.getConnection(
                    config.jdbcUrl(), config.user(), config.password());
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, lookbackDays);
            statement.setString(2, "%" + searchTerm.trim() + "%");
            statement.setInt(3, postLimit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String text = rows.getString("summary");
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
                    posts.add(new Post("post_summary", text,
                            timestamp == null ? null : timestamp.toLocalDateTime().toLocalDate()));
                }
            }
        }
        System.out.printf("Loaded %d posts from post_summary (last %d days, search term '%s', newest %d rows; "
                        + "skipped %d under %d chars, %d duplicate texts)%n",
                posts.size(), lookbackDays, searchTerm, postLimit,
                tooShort, options.minTextLength(), duplicates);
        return posts;
    }
}
