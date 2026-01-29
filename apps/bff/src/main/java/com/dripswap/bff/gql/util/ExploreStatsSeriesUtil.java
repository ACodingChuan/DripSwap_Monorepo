package com.dripswap.bff.gql.util;

import com.dripswap.bff.gql.dto.ExploreSeriesPointPayload;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class ExploreStatsSeriesUtil {

    private ExploreStatsSeriesUtil() {
    }

    public static BigDecimal safeBigDecimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public static List<ExploreSeriesPointPayload> padSeriesIfNeeded(
            List<ExploreSeriesPointPayload> series,
            BigDecimal fallbackValueUsd
    ) {
        if (series == null || series.isEmpty()) return synthesizeDailySeries(BigDecimal.ZERO, BigDecimal.ZERO);
        if (series.size() >= 2) return series;

        ExploreSeriesPointPayload only = series.get(0);
        int prevDate = only.getDate() - 86_400;
        BigDecimal valueUsd = safeBigDecimal(fallbackValueUsd);
        if (valueUsd.compareTo(BigDecimal.ZERO) == 0) {
            valueUsd = safeBigDecimal(only.getValueUsd());
        }

        List<ExploreSeriesPointPayload> padded = new ArrayList<>(2);
        padded.add(ExploreSeriesPointPayload.builder()
                .date(prevDate)
                .valueUsd(valueUsd)
                .build());
        padded.add(only);
        return padded;
    }

    public static List<ExploreSeriesPointPayload> synthesizeDailySeries(BigDecimal currentValueUsd, BigDecimal previousValueUsd) {
        long now = Instant.now().getEpochSecond();
        int todayStart = (int) (now - (now % 86_400));
        int yesterdayStart = todayStart - 86_400;
        return List.of(
                ExploreSeriesPointPayload.builder()
                        .date(yesterdayStart)
                        .valueUsd(safeBigDecimal(previousValueUsd))
                        .build(),
                ExploreSeriesPointPayload.builder()
                        .date(todayStart)
                        .valueUsd(safeBigDecimal(currentValueUsd))
                        .build()
        );
    }
}
