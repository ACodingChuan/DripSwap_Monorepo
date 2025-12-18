package com.dripswap.bff.sync;

import com.dripswap.bff.entity.PairTokenLookup;
import com.dripswap.bff.repository.PairTokenLookupRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PairTokenLookupSyncHandler {

    private final PairTokenLookupRepository pairTokenLookupRepository;

    @Transactional
    public void handlePairTokenLookups(String chainId, JsonNode lookupsNode) {
        if (lookupsNode == null || !lookupsNode.isArray()) {
            return;
        }

        List<PairTokenLookup> lookups = new ArrayList<>();
        for (JsonNode node : lookupsNode) {
            try {
                lookups.add(parseLookup(chainId, node));
            } catch (Exception e) {
                log.error("Failed to parse pairTokenLookup: {}", node, e);
            }
        }

        if (!lookups.isEmpty()) {
            pairTokenLookupRepository.saveAll(lookups);
            log.info("Saved {} pairTokenLookups for chain: {}", lookups.size(), chainId);
        }
    }

    private PairTokenLookup parseLookup(String chainId, JsonNode node) {
        PairTokenLookup lookup = new PairTokenLookup();
        lookup.setId(node.get("id").asText());
        lookup.setChainId(chainId);
        lookup.setPairId(node.get("pair").get("id").asText().toLowerCase());
        return lookup;
    }
}

