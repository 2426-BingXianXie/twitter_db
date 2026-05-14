package twitter.api;

import java.util.List;

/**
 * Defines the two storage operations exercised by the benchmark:
 * inserting a tweet and reading a home timeline.
 *
 * <p>Benchmark classes depend only on this interface, allowing the
 * underlying storage engine to be replaced without modifying any
 * benchmark logic.
 */
public interface TwitterAPI extends AutoCloseable {

    /**
     * Inserts a single tweet into the underlying store.
     *
     * <p>Implementations must perform exactly one write per call and
     * must not batch internally. The implementation is responsible for
     * assigning the tweet identifier and timestamp.
     *
     * @param t the tweet to insert; its {@code tweetId} and {@code ts}
     *          fields are ignored
     */
    void postTweet(Tweet t);

    /**
     * Returns the ten most recent tweets posted by users that
     * {@code userId} follows, ordered from newest to oldest.
     *
     * @param userId the identifier of the user whose timeline is
     *               requested
     * @return a list containing at most ten tweets; never {@code null}
     */
    List<Tweet> getTimeline(Integer userId);

    /**
     * Releases any underlying resources, such as JDBC connections and
     * prepared statements.
     */
    @Override
    void close();
}
