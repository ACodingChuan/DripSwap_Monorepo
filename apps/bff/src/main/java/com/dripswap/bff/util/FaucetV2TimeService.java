package com.dripswap.bff.util;

import java.time.Instant;

public final class FaucetV2TimeService {
    private static final long DAY_SECONDS = 24L * 60L * 60L;

    private FaucetV2TimeService() {}

    public static long dayFromEpochSeconds(long epochSeconds) {
        return epochSeconds / DAY_SECONDS;
    }

    public static Instant dayStartInstant(long day) {
        return Instant.ofEpochSecond(day * DAY_SECONDS);
    }

    public static long hourBucketFromEpochSeconds(long epochSeconds) {
        return epochSeconds / 3600L;
    }

    public static Instant hourStartInstant(long hourBucket) {
        return Instant.ofEpochSecond(hourBucket * 3600L);
    }
}

