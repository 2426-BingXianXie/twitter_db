package twitter.bench;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import twitter.api.MySQLTwitterAPI;
import twitter.api.Tweet;
import twitter.api.TwitterAPI;
import twitter.util.Config;

import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Benchmarks the {@link TwitterAPI#postTweet} operation by streaming
 * {@code tweet.csv} and inserting each row individually.
 *
 * <p>Every iteration performs exactly one {@code executeUpdate};
 * batching is not permitted. Progress is reported to {@code stderr}
 * once every 50,000 rows. Per-checkpoint throughput data is buffered
 * in memory during the run and flushed to
 * {@code bench-logs/post_tweet_bench.csv} after the timed loop
 * completes, so no I/O occurs inside the measured section.
 *
 * <p>{@link twitter.loader.FollowsLoader} must be run prior to this
 * benchmark to ensure that the {@code tweet} table starts empty.
 */
public final class PostTweetBench {

    public static void main(String[] args) throws Exception {
        Properties props = Config.load();
        Path tweetCsv = Path.of(Config.get(props, "data.tweetCsv", "hw1_data/tweet.csv"));
        long tweetLimit = Config.getLong(props, "bench.tweetLimit", 1_000_000L);
        int checkpointEvery = Config.getInt(props, "bench.progressEvery", 10_000);
        int stderrEvery = 50_000;
        Path logDir = Path.of(Config.get(props, "bench.logDir", "bench-logs"));
        Files.createDirectories(logDir);
        Path checkpointCsv = logDir.resolve("post_tweet_bench.csv");

        int expectedCheckpoints = (int) ((tweetLimit / checkpointEvery) + 2);
        long[] checkpointCount = new long[expectedCheckpoints];
        long[] checkpointNanos = new long[expectedCheckpoints];
        int checkpointIdx = 0;

        System.err.printf("PostTweetBench: streaming up to %,d tweets from %s%n",
                tweetLimit, tweetCsv);

        try (TwitterAPI api = new MySQLTwitterAPI();
             FileReader fr = new FileReader(tweetCsv.toFile());
             CSVReader reader = new CSVReaderBuilder(fr).withSkipLines(1).build()) {

            long posted = 0;
            String[] row;
            long startNanos = System.nanoTime();
            checkpointCount[checkpointIdx] = 0;
            checkpointNanos[checkpointIdx] = 0;
            checkpointIdx++;

            while ((row = reader.readNext()) != null && posted < tweetLimit) {
                if (row.length < 2) continue;
                int userId = Integer.parseInt(row[0].trim());
                String text = row[1];
                if (text.length() > 140) {
                    text = text.substring(0, 140);
                }
                api.postTweet(Tweet.forPosting(userId, text));
                posted++;

                if (posted % checkpointEvery == 0) {
                    long delta = System.nanoTime() - startNanos;
                    if (checkpointIdx < checkpointNanos.length) {
                        checkpointCount[checkpointIdx] = posted;
                        checkpointNanos[checkpointIdx] = delta;
                        checkpointIdx++;
                    }
                    if (posted % stderrEvery == 0) {
                        double tps = posted / (delta / 1e9);
                        System.err.printf("  %,d posted | elapsed %,d ms | avg %.1f tweets/sec%n",
                                posted, delta / 1_000_000L, tps);
                    }
                }
            }

            long totalNanos = System.nanoTime() - startNanos;
            double totalSeconds = totalNanos / 1e9;
            double tweetsPerSec = posted / totalSeconds;

            writeCheckpoints(checkpointCsv, checkpointCount, checkpointNanos, checkpointIdx);

            System.out.println();
            System.out.printf("Total tweets posted : %,d%n", posted);
            System.out.printf("Total elapsed       : %,.3f s (%,d ms)%n",
                    totalSeconds, totalNanos / 1_000_000L);
            System.out.printf("postTweet/sec       : %,.1f%n", tweetsPerSec);
            System.out.printf("Checkpoints written : %s%n", checkpointCsv);
        }
    }

    /**
     * Writes per-checkpoint throughput data to a CSV file. Each row
     * records the cumulative tweet count, elapsed time, the running
     * average rate, and the rate observed in the most recent window.
     */
    private static void writeCheckpoints(Path out, long[] counts, long[] nanos, int n)
            throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(out)) {
            w.write("posted,elapsed_ns,elapsed_ms,tweets_per_sec_so_far,window_tweets_per_sec\n");
            for (int i = 1; i < n; i++) {
                long windowCount = counts[i] - counts[i - 1];
                long windowNs = nanos[i] - nanos[i - 1];
                double windowRate = windowNs == 0 ? 0.0 : windowCount / (windowNs / 1e9);
                double totalRate = nanos[i] == 0 ? 0.0 : counts[i] / (nanos[i] / 1e9);
                w.write(String.format("%d,%d,%d,%.2f,%.2f%n",
                        counts[i], nanos[i], nanos[i] / 1_000_000L, totalRate, windowRate));
            }
        }
    }
}
