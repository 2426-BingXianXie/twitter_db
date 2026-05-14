package twitter.api;

import java.sql.Timestamp;

/**
 * Immutable record representing a single tweet.
 *
 * <p>When constructing a tweet to insert, {@code tweetId} is {@code 0}
 * and {@code ts} is {@code null}; both fields are populated by the
 * database. When read back from a timeline query, every field holds a
 * valid value.
 */
public record Tweet(int tweetId, int userId, Timestamp ts, String text) {

    /**
     * Creates a {@code Tweet} suitable for insertion via
     * {@link TwitterAPI#postTweet(Tweet)}.
     *
     * @param userId the author's user identifier
     * @param text   the tweet body (truncated to 140 characters by the
     *               caller if necessary)
     * @return a new {@code Tweet} whose {@code tweetId} is {@code 0}
     *         and {@code ts} is {@code null}
     */
    public static Tweet forPosting(int userId, String text) {
        return new Tweet(0, userId, null, text);
    }
}
