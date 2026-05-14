package twitter.loader;

import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvValidationException;
import twitter.api.MySQLTwitterAPI;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;

/**
 * Bulk-loads {@code follows.csv} into the {@code follows} table using
 * batched JDBC inserts wrapped in a single transaction.
 *
 * <p>This loader is part of the setup pipeline and is not measured by
 * the benchmark, so batching is permitted. Both tables are dropped and
 * recreated on entry; this class should therefore be run before
 * {@link twitter.bench.PostTweetBench}.
 *
 * <p>The CSV is expected to contain a header row of
 * {@code USER_ID,FOLLOWS_ID}, where the first column is the follower
 * and the second is the followee.
 */
public final class FollowsLoader {

    private static final int BATCH_SIZE = 1000;

    public static void main(String[] args) throws Exception {
        String csvPath = (args.length > 0) ? args[0] : "hw1_data/follows.csv";

        long start = System.nanoTime();
        int rowsLoaded;
        try (MySQLTwitterAPI api = new MySQLTwitterAPI()) {
            api.initSchema(true);
            rowsLoaded = bulkLoad(api.rawConnectionForBulkLoad(), Path.of(csvPath));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        System.out.printf(
                "FollowsLoader done: %,d rows loaded from %s in %,d ms%n",
                rowsLoaded, csvPath, elapsedMs);
    }

    /**
     * Inserts the contents of {@code csvPath} into the {@code follows}
     * table using batched prepared-statement inserts within a single
     * transaction. The connection's auto-commit setting is restored
     * before this method returns.
     *
     * @return the number of rows inserted
     */
    private static int bulkLoad(Connection conn, Path csvPath)
            throws IOException, CsvValidationException, java.sql.SQLException {

        boolean priorAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);

        int total = 0;
        try (FileReader fr = new FileReader(csvPath.toFile());
             CSVReader reader = new CSVReaderBuilder(fr).withSkipLines(1).build();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO follows (follower_id, followee_id) VALUES (?, ?)")) {

            String[] row;
            int batched = 0;
            while ((row = reader.readNext()) != null) {
                if (row.length < 2) continue;
                ps.setInt(1, Integer.parseInt(row[0].trim())); // USER_ID (follower)
                ps.setInt(2, Integer.parseInt(row[1].trim())); // FOLLOWS_ID (followee)
                ps.addBatch();
                batched++;
                total++;
                if (batched >= BATCH_SIZE) {
                    ps.executeBatch();
                    batched = 0;
                }
            }
            if (batched > 0) {
                ps.executeBatch();
            }
            conn.commit();
        } catch (RuntimeException | java.sql.SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(priorAutoCommit);
        }

        return total;
    }
}
