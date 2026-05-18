package com.team01.uber.tests.fixtures;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Redis state-verification helpers — mirrors {@code redis_keys} / {@code redis_count_keys} /
 * {@code redis_flush_pattern} from {@code tests/lib/common.sh}.
 *
 * <p>Connects to {@code localhost:6379} with the {@code redispass} password used by
 * {@code docker-compose.yaml}. Override via {@code -Dredis.host=...} / {@code -Dredis.port=...} /
 * {@code -Dredis.password=...}.
 */
public final class Redis {

    private static volatile JedisPool pool;

    private Redis() {}

    private static JedisPool pool() {
        if (pool == null) {
            synchronized (Redis.class) {
                if (pool == null) {
                    String host = System.getProperty("redis.host", "localhost");
                    int port = Integer.parseInt(System.getProperty("redis.port", "6379"));
                    String password = System.getProperty("redis.password", "redispass");
                    pool = new JedisPool(host, port, null, password);
                }
            }
        }
        return pool;
    }

    public static Set<String> keys(String pattern) {
        try (Jedis j = pool().getResource()) {
            Set<String> out = new HashSet<>();
            String cursor = ScanParams.SCAN_POINTER_START;
            ScanParams params = new ScanParams().match(pattern).count(500);
            do {
                ScanResult<String> result = j.scan(cursor, params);
                out.addAll(result.getResult());
                cursor = result.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
            return out;
        }
    }

    public static int countKeys(String pattern) {
        return keys(pattern).size();
    }

    public static boolean exists(String key) {
        try (Jedis j = pool().getResource()) {
            return j.exists(key);
        }
    }

    public static long ttl(String key) {
        try (Jedis j = pool().getResource()) {
            return j.ttl(key);
        }
    }

    public static long flushPattern(String pattern) {
        Set<String> ks = keys(pattern);
        if (ks.isEmpty()) return 0;
        try (Jedis j = pool().getResource()) {
            return j.del(ks.toArray(new String[0]));
        }
    }

    public static List<String> keysSorted(String pattern) {
        List<String> ks = new ArrayList<>(keys(pattern));
        ks.sort(String::compareTo);
        return ks;
    }
}
