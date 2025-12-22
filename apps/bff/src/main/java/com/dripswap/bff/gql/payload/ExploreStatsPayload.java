package com.dripswap.bff.gql.payload;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.List;

@Value
@Builder
public class ExploreStatsPayload {
    String chainId;
    BigDecimal tvlUsd;
    BigDecimal volume24hUsd;
    BigDecimal fees24hUsd;
    List<ExploreSeriesPointPayload> tvlSeries;
    List<ExploreSeriesPointPayload> volumeSeries;
}
