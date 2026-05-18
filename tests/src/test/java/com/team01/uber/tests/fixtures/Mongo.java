package com.team01.uber.tests.fixtures;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MongoDB state-verification helpers — mirrors {@code mongo_count} / {@code mongo_count_poll} /
 * {@code mongo_find_recent} / {@code mongo_clear} from {@code tests/lib/common.sh}.
 *
 * <p>Connection details match {@code docker-compose.yaml}: a singleton client per JVM, pointed
 * at {@code mongodb://root:rootpass@localhost:27017/ubermongo?authSource=admin}. Override the
 * full URI with {@code -Dmongo.uri=...} if you target a non-default stack.
 *
 * <p>Filter shape: a {@link java.util.Map} of field→value pairs joined by {@code $and}.
 * For richer queries, pass a {@link Bson} directly via {@link #count(String, Bson)}.
 */
public final class Mongo {

    private static final String DEFAULT_URI =
            "mongodb://root:rootpass@localhost:27017/ubermongo?authSource=admin";
    private static final String DEFAULT_DB = "ubermongo";

    private static volatile MongoClient client;

    private Mongo() {}

    private static MongoDatabase db() {
        if (client == null) {
            synchronized (Mongo.class) {
                if (client == null) {
                    client = MongoClients.create(System.getProperty("mongo.uri", DEFAULT_URI));
                }
            }
        }
        return client.getDatabase(System.getProperty("mongo.db", DEFAULT_DB));
    }

    private static Bson asFilter(Map<String, ?> spec) {
        if (spec == null || spec.isEmpty()) return new Document();
        List<Bson> parts = new ArrayList<>(spec.size());
        spec.forEach((k, v) -> parts.add(Filters.eq(k, v)));
        return parts.size() == 1 ? parts.get(0) : Filters.and(parts);
    }

    public static long count(String collection, Map<String, ?> filter) {
        return count(collection, asFilter(filter));
    }

    public static long count(String collection, Bson filter) {
        return db().getCollection(collection).countDocuments(filter);
    }

    public static long count(String collection) {
        return count(collection, (Bson) new Document());
    }

    /** Polls until count >= {@code expected} or timeout elapses. Returns the last observed count. */
    public static long countAtLeast(String collection, Map<String, ?> filter, long expected, Duration timeout) {
        Bson f = asFilter(filter);
        long deadline = System.nanoTime() + timeout.toNanos();
        long observed = 0;
        while (System.nanoTime() < deadline) {
            observed = count(collection, f);
            if (observed >= expected) return observed;
            sleep(200);
        }
        return observed;
    }

    public static List<Document> findRecent(String collection, Map<String, ?> filter, int limit) {
        Bson f = asFilter(filter);
        List<Document> out = new ArrayList<>(limit);
        db().getCollection(collection)
                .find(f)
                .sort(new Document("_id", -1))
                .limit(limit)
                .into(out);
        return out;
    }

    public static void clear(String collection) {
        db().getCollection(collection).deleteMany(new Document());
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
