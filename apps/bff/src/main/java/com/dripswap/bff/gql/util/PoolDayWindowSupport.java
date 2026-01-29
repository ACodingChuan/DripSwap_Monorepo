package com.dripswap.bff.gql.util;

import com.dripswap.bff.gql.dto.PoolDayWindowStats;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/**
 * Helper for computing rolling pool stats from PairDayData windows.
 */
public final class PoolDayWindowSupport {

    private PoolDayWindowSupport() {
    }

    public record DayRow(Integer date, BigDecimal reserveUsd, BigDecimal dailyVolumeUsd, Integer dailyTxns) {
    }

    public record DayWindow(int todayStart, int dayFrom) {
        public static DayWindow now() {
            long nowSec = Instant.now().getEpochSecond();
            int todayStart = (int) (nowSec - (nowSec % 86_400));
            int dayFrom = todayStart - (86_400 * 7);
            return new DayWindow(todayStart, dayFrom);
        }
    }

    public static PoolDayWindowStats compute(DayWindow window, List<DayRow> days) {
        if (days == null || days.isEmpty()) {
            return PoolDayWindowStats.builder().build();
        }

        days.sort((a, b) -> {
            if (a == null || a.date == null) return 1;
            if (b == null || b.date == null) return -1;
            return Integer.compare(b.date, a.date);
        });

        DayRow latest = days.get(0);

        BigDecimal volume24hUsd = safeBigDecimal(latest == null ? null : latest.dailyVolumeUsd);
        Integer tx24hCount = latest == null ? null : latest.dailyTxns;

        BigDecimal tvlChange1d = null;
        if (latest != null && latest.reserveUsd != null) {
            // Compare today's reserveUSD vs yesterday's reserveUSD (first row with date <= today-86400)
            int targetDate = window.todayStart - 86_400;
            BigDecimal prev = null;
            for (int i = 1; i < days.size(); i++) {
                DayRow d = days.get(i);
                if (d == null || d.date == null) continue;
                if (d.date <= targetDate) {
                    prev = d.reserveUsd;
                    break;
                }
            }
            if (prev != null && prev.compareTo(BigDecimal.ZERO) != 0) {
                tvlChange1d = latest.reserveUsd.subtract(prev)
                        .divide(prev, 8, RoundingMode.HALF_UP)
                        .multiply(new BigDecimal("100"));
            }
        }

        BigDecimal volume1wUsd = BigDecimal.ZERO;
        int count = 0;
        for (DayRow d : days) {
            if (d == null || d.date == null) continue;
            if (d.date < window.dayFrom) continue;
            volume1wUsd = volume1wUsd.add(safeBigDecimal(d.dailyVolumeUsd));
            count++;
        }
        BigDecimal volume1w = count == 0 ? null : volume1wUsd;

        return PoolDayWindowStats.builder()
                .latestDate(latest == null ? null : latest.date)
                .volume24hUsd(volume24hUsd)
                .tx24hCount(tx24hCount)
                .tvlChange1d(tvlChange1d)
                .volume1wUsd(volume1w)
                .build();
    }

    private static BigDecimal safeBigDecimal(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
