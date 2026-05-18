package com.team01.uber.tests.fixtures;

import java.util.concurrent.ThreadLocalRandom;

public final class Nonce {

    private Nonce() {}

    public static String nonce() {
        return Long.toHexString(System.nanoTime()) + "-" + Long.toHexString(ThreadLocalRandom.current().nextLong());
    }

    public static String email(String prefix) {
        return prefix + "_" + nonce() + "@grader.testgen.io";
    }

    public static String phone() {
        long n = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L);
        return "+1" + n;
    }
}
