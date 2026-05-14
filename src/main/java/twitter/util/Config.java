package twitter.util;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/**
 * Loads {@code db.properties} from the classpath and exposes typed
 * accessors for string, integer, and long values with default
 * fallbacks.
 *
 * <p>Used by {@link twitter.api.MySQLTwitterAPI} for JDBC credentials
 * and by the benchmark entry points for file paths and iteration
 * counts.
 */
public final class Config {

    private static final String RESOURCE_NAME = "db.properties";

    private Config() {}

    /**
     * Loads the {@code db.properties} resource from the classpath.
     *
     * @return the parsed {@link Properties}
     * @throws IllegalStateException if the resource is missing or
     *         cannot be read
     */
    public static Properties load() {
        ClassLoader cl = Objects.requireNonNullElse(
                Thread.currentThread().getContextClassLoader(),
                Config.class.getClassLoader());
        try (InputStream in = cl.getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException(
                        RESOURCE_NAME + " not on classpath. Copy "
                                + "src/main/resources/db.properties.example to "
                                + "src/main/resources/db.properties and fill in credentials.");
            }
            Properties p = new Properties();
            p.load(in);
            return p;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE_NAME, e);
        }
    }

    /**
     * Returns the integer value associated with {@code key}, or
     * {@code fallback} if the key is missing or blank.
     */
    public static int getInt(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : Integer.parseInt(v.trim());
    }

    /**
     * Returns the long value associated with {@code key}, or
     * {@code fallback} if the key is missing or blank.
     */
    public static long getLong(Properties p, String key, long fallback) {
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : Long.parseLong(v.trim());
    }

    /**
     * Returns the string value associated with {@code key}, or
     * {@code fallback} if the key is missing or blank.
     */
    public static String get(Properties p, String key, String fallback) {
        String v = p.getProperty(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
