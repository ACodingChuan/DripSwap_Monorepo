package com.dripswap.bff.sync;

import com.dripswap.bff.entity.TokenMinuteData;
import com.dripswap.bff.repository.TokenMinuteDataRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TokenMinuteDataSyncHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void savesParsedRows() throws Exception {
        TokenMinuteDataRepository repo = mock(TokenMinuteDataRepository.class);
        TokenMinuteDataSyncHandler handler = new TokenMinuteDataSyncHandler(repo);

        JsonNode array = objectMapper.readTree("""
            [
              {
                "id":"0xabc-123",
                "periodStartUnix":123,
                "token":{"id":"0xABC"},
                "volume":"1.5",
                "volumeUSD":"2.5",
                "untrackedVolumeUSD":"3.5",
                "totalValueLocked":"4",
                "totalValueLockedUSD":"5",
                "priceUSD":"6",
                "feesUSD":"7",
                "open":"8",
                "high":"9",
                "low":"10",
                "close":"11"
              }
            ]
            """);

        handler.handleTokenMinuteData("scroll-sepolia", array);

        ArgumentCaptor<List<TokenMinuteData>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());

        List<TokenMinuteData> saved = captor.getValue();
        assertNotNull(saved);
        assertEquals(1, saved.size());

        TokenMinuteData row = saved.get(0);
        assertEquals("scroll-sepolia", row.getChainId());
        assertEquals("0xabc-123", row.getId());
        assertEquals(123, row.getPeriodStartUnix());
        assertEquals("0xabc", row.getTokenId());
        assertEquals(new BigDecimal("1.5"), row.getVolume());
        assertEquals(new BigDecimal("2.5"), row.getVolumeUsd());
        assertEquals(new BigDecimal("11"), row.getClose());
    }
}

