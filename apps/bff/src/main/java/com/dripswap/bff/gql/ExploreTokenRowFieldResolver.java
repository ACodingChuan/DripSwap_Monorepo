package com.dripswap.bff.gql;

import com.dripswap.bff.entity.Bundle;
import com.dripswap.bff.entity.ChainEntityId;
import com.dripswap.bff.entity.TokenDayData;
import com.dripswap.bff.entity.TokenHourData;
import com.dripswap.bff.gql.payload.ExploreTokenRowPayload;
import com.dripswap.bff.repository.BundleRepository;
import com.dripswap.bff.repository.TokenDayDataRepository;
import com.dripswap.bff.repository.TokenHourDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * GraphQL field resolver for ExploreTokenRow type.
 * Handles lazy loading of associated entities (Bundle, TokenHourData, TokenDayData)
 * and computed fields (priceUsd, change1h, change1d, fdvUsd, volume24hUsd).
 */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ExploreTokenRowFieldResolver {

    private final BundleRepository bundleRepository;
    private final TokenHourDataRepository tokenHourDataRepository;
    private final TokenDayDataRepository tokenDayDataRepository;

    /**
     * Compute priceUsd = derivedETH * bundle.ethPrice.
     * This is GraphQL computed field - only calculated when requested.
     */
    @SchemaMapping(typeName = "ExploreTokenRow", field = "priceUsd")
    public BigDecimal priceUsd(ExploreTokenRowPayload tokenRow) {
        if (tokenRow.getDerivedETH() == null) {
            return null;
        }
        Bundle bundle = bundle(tokenRow);
        if (bundle == null || bundle.getEthPrice() == null) {
            return null;
        }
        return tokenRow.getDerivedETH().multiply(bundle.getEthPrice());
    }
    
    /**
     * Compute 1h price change percentage.
     * Uses currentHourData (open vs close).
     */
    @SchemaMapping(typeName = "ExploreTokenRow", field = "change1h")
    public BigDecimal change1h(ExploreTokenRowPayload tokenRow) {
        TokenHourData hourData = currentHourData(tokenRow);
        if (hourData == null || hourData.getOpen() == null || hourData.getClose() == null) {
            return null;
        }
        BigDecimal open = hourData.getOpen();
        BigDecimal close = hourData.getClose();
        if (open.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        // (close - open) / open * 100
        return close.subtract(open)
                .divide(open, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }
    
    /**
     * Compute 1d price change percentage.
     * Uses rolling 24h window based on token_hour_data close price.
     */
    @SchemaMapping(typeName = "ExploreTokenRow", field = "change1d")
    public BigDecimal change1d(ExploreTokenRowPayload tokenRow) {
        TokenHourData latestHour = tokenHourDataRepository
                .findFirstByChainIdAndTokenIdOrderByPeriodStartUnixDesc(tokenRow.getChainId(), tokenRow.getId())
                .orElse(null);

        if (latestHour == null || latestHour.getPeriodStartUnix() == null) {
            return null;
        }

        int latestHourStart = latestHour.getPeriodStartUnix();
        long nowSec = System.currentTimeMillis() / 1000;
        if (latestHourStart < (int) (nowSec - 2 * 86400L)) {
            return null;
        }

        TokenHourData prevHour = tokenHourDataRepository
                .findFirstByChainIdAndTokenIdAndPeriodStartUnixLessThanEqualOrderByPeriodStartUnixDesc(
                        tokenRow.getChainId(),
                        tokenRow.getId(),
                        latestHourStart - 86400
                )
                .orElse(null);

        if (prevHour == null || prevHour.getClose() == null || latestHour.getClose() == null) {
            return null;
        }

        BigDecimal base = prevHour.getClose();
        BigDecimal current = latestHour.getClose();
        if (base.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }

        return current.subtract(base)
                .divide(base, 8, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
    }
    
    /**
     * Compute Fully Diluted Valuation (FDV) in USD.
     * FDV = (totalSupply / 10^decimals) * priceUsd
     */
    @SchemaMapping(typeName = "ExploreTokenRow", field = "fdvUsd")
    public BigDecimal fdvUsd(ExploreTokenRowPayload tokenRow) {
        if (tokenRow.getTotalSupply() == null || tokenRow.getDecimals() == null) {
            return null;
        }
        BigDecimal priceUsd = priceUsd(tokenRow);
        if (priceUsd == null || priceUsd.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        // scaledSupply = totalSupply / 10^decimals
        BigDecimal divisor = BigDecimal.TEN.pow(tokenRow.getDecimals());
        BigDecimal scaledSupply = tokenRow.getTotalSupply().divide(divisor, 8, RoundingMode.HALF_UP);
        return scaledSupply.multiply(priceUsd);
    }
    
    /**
     * Compute 24h volume in USD.
     * Uses rolling 24h sum based on token_hour_data.volumeUsd.
     */
    @SchemaMapping(typeName = "ExploreTokenRow", field = "volume24hUsd")
    public BigDecimal volume24hUsd(ExploreTokenRowPayload tokenRow) {
        TokenHourData latestHour = tokenHourDataRepository
                .findFirstByChainIdAndTokenIdOrderByPeriodStartUnixDesc(tokenRow.getChainId(), tokenRow.getId())
                .orElse(null);

        if (latestHour == null || latestHour.getPeriodStartUnix() == null) {
            return null;
        }

        int latestHourStart = latestHour.getPeriodStartUnix();
        long nowSec = System.currentTimeMillis() / 1000;
        if (latestHourStart < (int) (nowSec - 2 * 86400L)) {
            return null;
        }

        List<TokenHourData> last24h = tokenHourDataRepository
                .findByChainIdAndTokenIdAndPeriodStartUnixBetweenOrderByPeriodStartUnixAsc(
                        tokenRow.getChainId(),
                        tokenRow.getId(),
                        latestHourStart - 86400,
                        latestHourStart
                );

        BigDecimal sum = BigDecimal.ZERO;
        for (TokenHourData row : last24h) {
            sum = sum.add(row.getVolumeUsd() == null ? BigDecimal.ZERO : row.getVolumeUsd());
        }
        return sum;
    }

    /**
     * Resolve bundle field - provides ETH price for price calculation.
     */
    @SchemaMapping(typeName = "ExploreTokenRow", field = "bundle")
    public Bundle bundle(ExploreTokenRowPayload tokenRow) {
        ChainEntityId id = new ChainEntityId();
        id.setId("1");
        id.setChainId(tokenRow.getChainId());
        return bundleRepository.findById(id).orElse(null);
    }

    /**
     * Resolve currentHourData field - provides data for 1h change calculation.
     * Only returns data if there's activity in the CURRENT hour.
     */
    @SchemaMapping(typeName = "ExploreTokenRow", field = "currentHourData")
    public TokenHourData currentHourData(ExploreTokenRowPayload tokenRow) {
        int currentTimestamp = (int) (System.currentTimeMillis() / 1000);
        int currentHourIndex = currentTimestamp / 3600;
        int currentHourStart = currentHourIndex * 3600;

        return tokenHourDataRepository
                .findByChainIdAndTokenIdAndPeriodStartUnix(
                        tokenRow.getChainId(),
                        tokenRow.getId(),
                        currentHourStart
                )
                .orElse(null);
    }

    /**
     * Resolve latestDayData field - provides latest day data for 1d change calculation.
     */
    @SchemaMapping(typeName = "ExploreTokenRow", field = "latestDayData")
    public TokenDayData latestDayData(ExploreTokenRowPayload tokenRow) {
        return tokenDayDataRepository
                .findFirstByChainIdAndTokenIdOrderByDateDesc(
                        tokenRow.getChainId(),
                        tokenRow.getId()
                )
                .orElse(null);
    }

    /**
     * Resolve previousDayData field - provides previous day data for 1d change calculation.
     */
    @SchemaMapping(typeName = "ExploreTokenRow", field = "previousDayData")
    public TokenDayData previousDayData(ExploreTokenRowPayload tokenRow) {
        // First get latest day data
        TokenDayData latestDay = tokenDayDataRepository
                .findFirstByChainIdAndTokenIdOrderByDateDesc(
                        tokenRow.getChainId(),
                        tokenRow.getId()
                )
                .orElse(null);

        if (latestDay == null) {
            return null;
        }

        // Then get previous day (24h before latest)
        return tokenDayDataRepository
                .findFirstByChainIdAndTokenIdAndDateLessThanEqualOrderByDateDesc(
                        tokenRow.getChainId(),
                        tokenRow.getId(),
                        latestDay.getDate() - 86400
                )
                .orElse(null);
    }
}
