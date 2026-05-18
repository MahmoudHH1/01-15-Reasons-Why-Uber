package com.team01.uber.tests.fixtures;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Awaitility-lite — poll until a condition is true or a deadline elapses.
 *
 * <p>Async event verification (Observer writes, RabbitMQ consumer state) is the primary use case.
 * Defaults: 10 s timeout, 200 ms poll interval.
 */
public final class Eventually {

    private Eventually() {}

    public static void await(BooleanSupplier condition) {
        await(Duration.ofSeconds(10), Duration.ofMillis(200), null, condition);
    }

    public static void await(Duration timeout, BooleanSupplier condition) {
        await(timeout, Duration.ofMillis(200), null, condition);
    }

    public static void await(Duration timeout, String description, BooleanSupplier condition) {
        await(timeout, Duration.ofMillis(200), description, condition);
    }

    public static void await(Duration timeout, Duration pollInterval, String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long polls = 0;
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            polls++;
            try {
                if (condition.getAsBoolean()) return;
            } catch (Throwable t) {
                last = t;
            }
            sleep(pollInterval.toMillis());
        }
        StringBuilder msg = new StringBuilder("Eventually(timeout=").append(timeout)
                .append(", polls=").append(polls).append(") did not become true");
        if (description != null) msg.append(" — ").append(description);
        if (last != null) msg.append(" (last error: ").append(last).append(")");
        throw new AssertionError(msg.toString());
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
