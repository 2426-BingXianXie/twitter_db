package twitter.api;

import twitter.util.Config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * JDBC-backed {@link TwitterAPI} implementation targeting MySQL.
 *
 * <p>The instance retains a single {@link Connection} together with two
 * cached {@link PreparedStatement}s (one for inserts, one for timeline
 * queries) for its entire lifetime. No connection pool is used; the
 * benchmark is intended to measure raw JDBC throughput.
 *
 * <p>The JDBC URL must include {@code rewriteBatchedStatements=false}
 * so that the driver does not coalesce single-row inserts into
 * multi-row statements.
 */
public final class MySQLTwitterAPI implements TwitterAPI, AutoCloseable {

    private static final String INSERT_TWEET_SQL =
            "INSERT INTO tweet (user_id, tweet_text) VALUES (?, ?)";

    private static final String SELECT_TIMELINE_SQL = """
            SELECT t.tweet_id, t.user_id, t.tweet_ts, t.tweet_text
              FROM tweet t
              JOIN follows f ON f.followee_id = t.user_id
             WHERE f.follower_id = ?
             ORDER BY t.tweet_ts DESC
             LIMIT 10
            """;

    private static final String DDL_TWEET = """
            CREATE TABLE IF NOT EXISTS tweet (
              tweet_id   INT AUTO_INCREMENT PRIMARY KEY,
              user_id    INT NOT NULL,
              tweet_ts   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
              tweet_text VARCHAR(140) NOT NULL,
              INDEX idx_user_ts (user_id, tweet_ts DESC)
            )
            """;

    private static final String DDL_FOLLOWS = """
            CREATE TABLE IF NOT EXISTS follows (
              follower_id INT NOT NULL,
              followee_id INT NOT NULL,
              INDEX idx_follower (follower_id),
              INDEX idx_followee (followee_id)
            )
            """;

    private final Connection conn;
    private final PreparedStatement insertTweet;
    private final PreparedStatement selectTimeline;

    /**
     * Opens a JDBC connection using credentials supplied by
     * {@link Config} and prepares the statements used by
     * {@link #postTweet} and {@link #getTimeline}.
     *
     * @throws IllegalStateException if the connection cannot be opened
     */
    public MySQLTwitterAPI() {
        Properties props = Config.load();
        try {
            this.conn = DriverManager.getConnection(
                    props.getProperty("jdbc.url"),
                    props.getProperty("jdbc.user"),
                    props.getProperty("jdbc.password"));
            this.conn.setAutoCommit(true);
            this.insertTweet = conn.prepareStatement(INSERT_TWEET_SQL);
            this.selectTimeline = conn.prepareStatement(SELECT_TIMELINE_SQL);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to open MySQL connection", e);
        }
    }

    /**
     * Creates the {@code tweet} and {@code follows} tables if they do
     * not already exist.
     *
     * @param reset if {@code true}, both tables are dropped before
     *              being recreated; intended only for fresh benchmark
     *              runs
     */
    public void initSchema(boolean reset) {
        try (Statement st = conn.createStatement()) {
            if (reset) {
                st.execute("DROP TABLE IF EXISTS tweet");
                st.execute("DROP TABLE IF EXISTS follows");
            }
            st.execute(DDL_TWEET);
            st.execute(DDL_FOLLOWS);
        } catch (SQLException e) {
            throw new IllegalStateException("initSchema failed", e);
        }
    }

    @Override
    public void postTweet(Tweet t) {
        try {
            insertTweet.setInt(1, t.userId());
            insertTweet.setString(2, t.text());
            insertTweet.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("postTweet failed", e);
        }
    }

    @Override
    public List<Tweet> getTimeline(Integer userId) {
        try {
            selectTimeline.setInt(1, userId);
            try (ResultSet rs = selectTimeline.executeQuery()) {
                List<Tweet> out = new ArrayList<>(10);
                while (rs.next()) {
                    out.add(new Tweet(
                            rs.getInt("tweet_id"),
                            rs.getInt("user_id"),
                            rs.getTimestamp("tweet_ts"),
                            rs.getString("tweet_text")));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("getTimeline failed for userId=" + userId, e);
        }
    }

    /**
     * Exposes the underlying JDBC connection so that bulk loaders may
     * execute their own transactions.
     *
     * <p>This method is intentionally absent from {@link TwitterAPI}
     * because it supports data setup only, not benchmark operations.
     *
     * @return the live {@link Connection} owned by this instance
     */
    public Connection rawConnectionForBulkLoad() {
        return conn;
    }

    /**
     * Returns every distinct {@code follower_id} present in the
     * {@code follows} table.
     *
     * @return an array of follower identifiers; empty if no rows exist
     */
    public int[] loadAllFollowers() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT DISTINCT follower_id FROM follows")) {
            int[] buf = new int[1024];
            int n = 0;
            while (rs.next()) {
                if (n == buf.length) {
                    int[] grown = new int[buf.length * 2];
                    System.arraycopy(buf, 0, grown, 0, n);
                    buf = grown;
                }
                buf[n++] = rs.getInt(1);
            }
            int[] out = new int[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("loadAllFollowers failed", e);
        }
    }

    @Override
    public void close() {
        closeQuietly(selectTimeline);
        closeQuietly(insertTweet);
        closeQuietly(conn);
    }

    /**
     * Closes {@code c} and suppresses any exception. Used during
     * shutdown, where surfacing a close failure would obscure the
     * primary cause of the shutdown.
     */
    private static void closeQuietly(AutoCloseable c) {
        if (c == null) return;
        try { c.close(); } catch (Exception ignored) {}
    }
}
