package com.haoyu.inboundsim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;

/**
 * Deterministic randomness: a random stream is a pure function of the secret seed and a key such as
 * (purchase order, stage). The simulator can therefore restart at any moment and recompute exactly
 * the same "truth" without storing any state, and replays hit the API's idempotency keys.
 */
public final class Rng {

    private Rng() {}

    public static SplittableRandom of(String seed, Object... key) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(seed.getBytes(StandardCharsets.UTF_8));
            for (Object k : key) {
                sha.update((byte) '|');
                sha.update(String.valueOf(k).getBytes(StandardCharsets.UTF_8));
            }
            byte[] h = sha.digest();
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (h[i] & 0xff);
            return new SplittableRandom(v);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Standard normal via Box-Muller. */
    public static double gaussian(SplittableRandom r) {
        double u1 = Math.max(r.nextDouble(), 1e-12), u2 = r.nextDouble();
        return Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
    }

    /** Poisson(lambda) by inversion; lambda here is a few per hour, so this is cheap. */
    public static int poisson(SplittableRandom r, double lambda) {
        double l = Math.exp(-lambda), p = 1;
        int k = 0;
        do {
            k++;
            p *= r.nextDouble();
        } while (p > l);
        return k - 1;
    }

    public static int binomial(SplittableRandom r, int n, double p) {
        int k = 0;
        for (int i = 0; i < n; i++) if (r.nextDouble() < p) k++;
        return k;
    }
}
