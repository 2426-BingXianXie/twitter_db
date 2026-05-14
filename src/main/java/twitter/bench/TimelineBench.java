package twitter.bench;

import twitter.api.MySQLTwitterAPI;
import twitter.api.Tweet;
import twitter.api.TwitterAPI;
import twitter.util.Config;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Benchmarks the {@link TwitterAPI#getTimeline} operation by issuing
 * repeated queries against randomly selected follower identifiers.
 *
 * <p>During setup, every distinct {@code follower_id} in the
 * {@code follows} table is loaded into an {@code int[]}. The timed
 * loop then selects identifiers uniformly at random and invokes
 * {@code getTimeline}. The cumulative size of the returned lists is
 * accumulated into a counter that is referenced after the loop, which
 * prevents the JIT compiler from eliminating the calls as dead code.
 *
 * <p>{@link PostTweetBench} must be run prior to this benchmark;
 * otherwise the {@code tweet} table is empty and the measured rate
 * does not reflect realistic query cost.
 */
public final class TimelineBench {

    public static void main(String[] args) throws Exception {
        Properties props = Config.load();
        int iterations = Config.getInt(props, "bench.timelineIterations", 10_000);

        MySQLTwitterAPI impl = new MySQLTwitterAPI();
        int[] followers = impl.loadAllFollowers();
        if (followers.length == 0) {
            impl.close();
            throw new IllegalStateException(
                    "follows table is empty. Run FollowsLoader first.");
        }
        int warmupIterations = Math.min(1000, iterations / 10);
        System.err.printf(
                "TimelineBench: %,d distinct followers loaded; %,d warmup + %,d timed iterations%n",
                followers.length, warmupIterations, iterations);

        try (TwitterAPI api = impl) {
            warmup(api, followers, warmupIterations);

            ThreadLocalRandom rng = ThreadLocalRandom.current();
            long tweetsSeen = 0;
            long startNanos = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                int follower = followers[rng.nextInt(followers.length)];
                List<Tweet> timeline = api.getTimeline(follower);
                tweetsSeen += timeline.size();
            }
            long totalNanos = System.nanoTime() - startNanos;
            double totalSeconds = totalNanos / 1e9;
            double callsPerSec = iterations / totalSeconds;

            System.out.println();
            System.out.printf("Iterations          : %,d%n", iterations);
            System.out.printf("Warmup iterations   : %,d (excluded from timing)%n", warmupIterations);
            System.out.printf("Total elapsed       : %,.3f s (%,d ms)%n",
                    totalSeconds, totalNanos / 1_000_000L);
            System.out.printf("getTimeline/sec     : %,.1f%n", callsPerSec);
            System.out.printf("Tweets read total   : %,d (avg %.2f per timeline)%n",
                    tweetsSeen, ((double) tweetsSeen) / iterations);
        }
    }

    /**
     * Executes {@code n} untimed {@code getTimeline} calls so that the
     * JIT compiler and database caches reach a steady state before the
     * measured loop begins.
     */
    private static void warmup(TwitterAPI api, int[] followers, int n) {
        if (n <= 0) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        long sink = 0;
        for (int i = 0; i < n; i++) {
            sink += api.getTimeline(followers[rng.nextInt(followers.length)]).size();
        }
        if (sink == Long.MIN_VALUE) System.err.println("unreachable");
        System.err.printf("  (warmup complete: %,d iterations)%n", n);
    }
}
