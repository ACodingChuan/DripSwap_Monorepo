package com.dripswap.bff.modules.subgraph.service;

import com.dripswap.bff.config.SubgraphProperties;
import com.dripswap.bff.modules.subgraph.SubgraphGraphClient;
import com.dripswap.bff.modules.subgraph.model.*;
import com.dripswap.bff.modules.subgraph.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class SubgraphSyncService {

    private static final Logger log = LoggerFactory.getLogger(SubgraphSyncService.class);

    private final SubgraphGraphClient graphClient;
    private final SubgraphProperties subgraphProperties;
    private final PairCacheRepository pairCacheRepository;
    private final TokenMetaRepository tokenMetaRepository;
    private final VTokenStateRepository vTokenStateRepository;
    private final BridgeTxRepository bridgeTxRepository;
    private final BridgeLegRepository bridgeLegRepository;
    private final SyncCursorRepository syncCursorRepository;

    public SubgraphSyncService(SubgraphGraphClient graphClient,
                               SubgraphProperties subgraphProperties,
                               PairCacheRepository pairCacheRepository,
                               TokenMetaRepository tokenMetaRepository,
                               VTokenStateRepository vTokenStateRepository,
                               BridgeTxRepository bridgeTxRepository,
                               BridgeLegRepository bridgeLegRepository,
                               SyncCursorRepository syncCursorRepository) {
        this.graphClient = graphClient;
        this.subgraphProperties = subgraphProperties;
        this.pairCacheRepository = pairCacheRepository;
        this.tokenMetaRepository = tokenMetaRepository;
        this.vTokenStateRepository = vTokenStateRepository;
        this.bridgeTxRepository = bridgeTxRepository;
        this.bridgeLegRepository = bridgeLegRepository;
        this.syncCursorRepository = syncCursorRepository;
    }

    @Scheduled(fixedDelayString = "${subgraph.sync-interval-ms:120000}")
    public void syncAll() {
        log.info("========== Subgraph Sync Task Started ==========");
        log.info("Configured chains: {}", subgraphProperties.getChains().stream()
                .map(c -> c.getId() + "(enabled=" + c.isEnabled() + ")")
                .toList());
        
        subgraphProperties.getChains().stream()
                .filter(SubgraphProperties.ChainConfig::isEnabled)
                .forEach(chain -> {
                    try {
                        log.info("Starting sync for chain: {}", chain.getId());
                        syncChain(chain);
                        log.info("Sync completed for chain: {}", chain.getId());
                    } catch (Exception e) {
                        log.error("Subgraph sync failed for chain {}: {}", chain.getId(), e.getMessage(), e);
                    }
                });
        log.info("========== Subgraph Sync Task Finished ==========");
    }

    private void syncChain(SubgraphProperties.ChainConfig chain) {
        log.info("Subgraph sync start, chain={} endpoint={}", chain.getId(), chain.getEndpoint());
        log.info("Syncing Pairs...");
        syncPairs(chain);
        log.info("Syncing Tokens...");
        syncTokens(chain);
        log.info("Syncing VTokens...");
        syncVTokens(chain);
        log.info("Syncing Bridge Transfers...");
        syncBridgeTransfers(chain);
        log.info("Chain {} sync completed", chain.getId());
    }

    @Transactional
    protected void syncPairs(SubgraphProperties.ChainConfig chain) {
        log.info("[syncPairs] Starting for chain: {}", chain.getId());
        SyncCursorEntity cursor = getOrCreateCursor(chain.getId(), "pair");
        long lastTs = Optional.ofNullable(cursor.getLastSyncTimestamp()).orElse(0L);
        log.info("[syncPairs] lastSyncTimestamp={}, batchSize={}", lastTs, subgraphProperties.getBatchSize());
        int batch = subgraphProperties.getBatchSize();
        boolean hasMore = true;
        int totalSynced = 0;
        while (hasMore) {
            Map<String, Object> vars = Map.of(
                    "skip", 0,
                    "first", batch,
                    "createdAfter", lastTs
            );
            String query = """
                    query($skip:Int!,$first:Int!,$createdAfter:BigInt!) {
                      pairs(
                        skip:$skip,
                        first:$first,
                        orderBy: createdAtTimestamp,
                        orderDirection: asc,
                        where:{ createdAtTimestamp_gte: $createdAfter }
                      ) {
                        id
                        token0 { id }
                        token1 { id }
                        reserve0
                        reserve1
                        volumeToken0
                        volumeToken1
                        createdAtTimestamp
                      }
                    }
                    """;
            log.debug("[syncPairs] Querying with vars: {}", vars);
            Optional<JsonNode> response = graphClient.query(chain.getId(), query, vars);
            if (response.isEmpty()) {
                log.warn("[syncPairs] Empty response from subgraph");
                break;
            }
            if (response.get().get("data") == null) {
                log.warn("[syncPairs] No 'data' field in response: {}", response.get());
                break;
            }
            JsonNode nodes = response.get().path("data").path("pairs");
            log.info("[syncPairs] Received {} pairs from subgraph", nodes.size());
            if (!nodes.isArray() || nodes.size() == 0) {
                log.info("[syncPairs] No more pairs to sync");
                hasMore = false;
                break;
            }
            long maxTs = lastTs;
            for (JsonNode n : nodes) {
                PairCacheEntity entity = pairCacheRepository
                        .findByChainIdAndAddress(chain.getId(), toLower(n.path("id").asText()))
                        .orElseGet(PairCacheEntity::new);
                entity.setChainId(chain.getId());
                entity.setAddress(toLower(n.path("id").asText()));
                entity.setToken0Address(toLower(n.path("token0").path("id").asText()));
                entity.setToken1Address(toLower(n.path("token1").path("id").asText()));
                entity.setReserve0(toBigDecimal(n.path("reserve0")));
                entity.setReserve1(toBigDecimal(n.path("reserve1")));
                entity.setVolumeToken0(toBigDecimal(n.path("volumeToken0")));
                entity.setVolumeToken1(toBigDecimal(n.path("volumeToken1")));
                entity.setLiquidity(BigDecimal.ZERO); // placeholder; can be filled later
                pairCacheRepository.save(entity);
                totalSynced++;
                maxTs = Math.max(maxTs, n.path("createdAtTimestamp").asLong(lastTs));
            }
            cursor.setLastSyncTimestamp(maxTs);
            syncCursorRepository.save(cursor);
            log.info("[syncPairs] Batch saved. maxTimestamp={}, continue={}", maxTs, nodes.size() >= batch);
            hasMore = nodes.size() >= batch;
        }
        log.info("[syncPairs] Completed. Total pairs synced: {}", totalSynced);
    }

    @Transactional
    protected void syncTokens(SubgraphProperties.ChainConfig chain) {
        log.info("[syncTokens] Starting for chain: {}", chain.getId());
        SyncCursorEntity cursor = getOrCreateCursor(chain.getId(), "token");
        String lastId = Optional.ofNullable(cursor.getLastSyncedId()).orElse("");
        log.info("[syncTokens] lastSyncedId={}, batchSize={}", lastId, subgraphProperties.getBatchSize());
        int batch = subgraphProperties.getBatchSize();
        boolean hasMore = true;
        int totalSynced = 0;
        while (hasMore) {
            Map<String, Object> vars = Map.of(
                    "first", batch,
                    "lastId", lastId
            );
            String query = """
                    query($first:Int!,$lastId:ID!) {
                      tokens(
                        first:$first,
                        orderBy: id,
                        orderDirection: asc,
                        where:{ id_gt: $lastId }
                      ) {
                        id
                        symbol
                        name
                        decimals
                        totalSupply
                      }
                    }
                    """;
            Optional<JsonNode> response = graphClient.query(chain.getId(), query, vars);
            if (response.isEmpty()) {
                log.warn("[syncTokens] Empty response from subgraph");
                break;
            }
            if (response.get().get("data") == null) {
                log.warn("[syncTokens] No 'data' field in response: {}", response.get());
                break;
            }
            JsonNode nodes = response.get().path("data").path("tokens");
            log.info("[syncTokens] Received {} tokens from subgraph", nodes.size());
            if (!nodes.isArray() || nodes.size() == 0) {
                log.info("[syncTokens] No more tokens to sync");
                hasMore = false;
                break;
            }
            for (JsonNode n : nodes) {
                String addr = toLower(n.path("id").asText());
                TokenMetaEntity entity = tokenMetaRepository.findByChainIdAndAddress(chain.getId(), addr)
                        .orElseGet(TokenMetaEntity::new);
                entity.setChainId(chain.getId());
                entity.setAddress(addr);
                entity.setSymbol(n.path("symbol").asText());
                entity.setName(n.path("name").asText());
                entity.setDecimals(n.path("decimals").asInt());
                entity.setTotalSupply(toBigDecimal(n.path("totalSupply")));
                entity.setSyncedAt(Instant.now());
                tokenMetaRepository.save(entity);
                totalSynced++;
                lastId = addr;
            }
            cursor.setLastSyncedId(lastId);
            syncCursorRepository.save(cursor);
            log.info("[syncTokens] Batch saved. lastId={}, continue={}", lastId, nodes.size() >= batch);
            hasMore = nodes.size() >= batch;
        }
        log.info("[syncTokens] Completed. Total tokens synced: {}", totalSynced);
    }

    @Transactional
    protected void syncVTokens(SubgraphProperties.ChainConfig chain) {
        log.info("[syncVTokens] Starting for chain: {}", chain.getId());
        SyncCursorEntity cursor = getOrCreateCursor(chain.getId(), "vtoken");
        String lastId = Optional.ofNullable(cursor.getLastSyncedId()).orElse("");
        log.info("[syncVTokens] lastSyncedId={}, batchSize={}", lastId, subgraphProperties.getBatchSize());
        int batch = subgraphProperties.getBatchSize();
        boolean hasMore = true;
        int totalSynced = 0;
        while (hasMore) {
            Map<String, Object> vars = Map.of(
                    "first", batch,
                    "lastId", lastId
            );
            String query = """
                    query($first:Int!,$lastId:ID!) {
                      vtokens(
                        first:$first,
                        orderBy: id,
                        orderDirection: asc,
                        where:{ id_gt: $lastId }
                      ) {
                        id
                        symbol
                        name
                        decimals
                        totalSupply
                        totalMinted
                        totalBurned
                      }
                    }
                    """;
            Optional<JsonNode> response = graphClient.query(chain.getId(), query, vars);
            if (response.isEmpty()) {
                log.warn("[syncVTokens] Empty response from subgraph");
                break;
            }
            if (response.get().get("data") == null) {
                log.warn("[syncVTokens] No 'data' field in response: {}", response.get());
                break;
            }
            JsonNode nodes = response.get().path("data").path("vtokens");
            log.info("[syncVTokens] Received {} vtokens from subgraph", nodes.size());
            if (!nodes.isArray() || nodes.size() == 0) {
                log.info("[syncVTokens] No more vtokens to sync");
                hasMore = false;
                break;
            }
            for (JsonNode n : nodes) {
                String addr = toLower(n.path("id").asText());
                VTokenStateEntity entity = vTokenStateRepository.findByChainIdAndAddress(chain.getId(), addr)
                        .orElseGet(VTokenStateEntity::new);
                entity.setChainId(chain.getId());
                entity.setAddress(addr);
                entity.setSymbol(n.path("symbol").asText());
                entity.setName(n.path("name").asText());
                entity.setDecimals(n.path("decimals").asInt());
                entity.setTotalSupply(toBigDecimal(n.path("totalSupply")));
                entity.setTotalMinted(toBigDecimal(n.path("totalMinted")));
                entity.setTotalBurned(toBigDecimal(n.path("totalBurned")));
                vTokenStateRepository.save(entity);
                totalSynced++;
                lastId = addr;
            }
            cursor.setLastSyncedId(lastId);
            syncCursorRepository.save(cursor);
            log.info("[syncVTokens] Batch saved. lastId={}, continue={}", lastId, nodes.size() >= batch);
            hasMore = nodes.size() >= batch;
        }
        log.info("[syncVTokens] Completed. Total vtokens synced: {}", totalSynced);
    }

    @Transactional
    protected void syncBridgeTransfers(SubgraphProperties.ChainConfig chain) {
        SyncCursorEntity cursor = getOrCreateCursor(chain.getId(), "bridge_transfer");
        long lastBlock = Optional.ofNullable(cursor.getLastSyncBlockNumber()).orElse(chain.getStartBlock());
        int batch = subgraphProperties.getBatchSize();
        boolean hasMore = true;
        while (hasMore) {
            Map<String, Object> vars = Map.of(
                    "first", batch,
                    "lastBlock", lastBlock
            );
            String query = """
                    query($first:Int!,$lastBlock:BigInt!) {
                      bridgeTransfers(
                        first:$first,
                        orderBy: blockNumber,
                        orderDirection: asc,
                        where:{ blockNumber_gt: $lastBlock }
                      ) {
                        id
                        messageId
                        sender
                        receiver
                        token
                        amount
                        pool
                        payInLink
                        ccipFee
                        serviceFeePaid
                        status
                        blockNumber
                        timestamp
                        transactionHash
                      }
                    }
                    """;
            Optional<JsonNode> response = graphClient.query(chain.getId(), query, vars);
            if (response.isEmpty() || response.get().get("data") == null) {
                break;
            }
            JsonNode nodes = response.get().path("data").path("bridgeTransfers");
            if (!nodes.isArray() || nodes.size() == 0) {
                hasMore = false;
                break;
            }
            long maxBlock = lastBlock;
            for (JsonNode n : nodes) {
                String status = n.path("status").asText();
                long blockNumber = n.path("blockNumber").asLong(lastBlock);
                if ("Initiated".equalsIgnoreCase(status)) {
                    BridgeTxEntity entity = bridgeTxRepository.findByChainIdAndMessageId(chain.getId(), n.path("messageId").asText())
                            .orElseGet(BridgeTxEntity::new);
                    entity.setChainId(chain.getId());
                    entity.setMessageId(n.path("messageId").asText());
                    entity.setSender(toLower(n.path("sender").asText()));
                    entity.setReceiver(toLowerOrNull(n.path("receiver").asText()));
                    entity.setToken(toLower(n.path("token").asText()));
                    entity.setAmount(toBigDecimal(n.path("amount")));
                    entity.setPool(toLowerOrNull(n.path("pool").asText()));
                    entity.setPayInLink(n.path("payInLink").isMissingNode() ? null : n.path("payInLink").asBoolean());
                    entity.setCcipFee(toBigDecimal(n.path("ccipFee")));
                    entity.setServiceFeePaid(toBigDecimal(n.path("serviceFeePaid")));
                    entity.setStatus(status);
                    entity.setBlockNumber(blockNumber);
                    entity.setBlockTimestamp(n.path("timestamp").asLong());
                    entity.setTxHash(n.path("transactionHash").asText());
                    bridgeTxRepository.save(entity);
                } else {
                    BridgeLegEntity leg = bridgeLegRepository.findByChainIdAndTxHashAndLogIndex(
                                    chain.getId(),
                                    n.path("transactionHash").asText(),
                                    n.has("logIndex") ? n.path("logIndex").asInt() : 0)
                            .orElseGet(BridgeLegEntity::new);
                    leg.setChainId(chain.getId());
                    leg.setTxHash(n.path("transactionHash").asText());
                    leg.setLogIndex(n.has("logIndex") ? n.path("logIndex").asInt() : 0);
                    leg.setPool(toLowerOrNull(n.path("pool").asText()));
                    leg.setToken(toLower(n.path("token").asText()));
                    long remoteSelector = n.path("destinationChainSelector").asLong(
                            n.path("sourceChainSelector").asLong(0)
                    );
                    leg.setRemoteChainSelector(remoteSelector == 0 ? null : remoteSelector);
                    leg.setSender(toLowerOrNull(n.path("sender").asText()));
                    leg.setRecipient(toLowerOrNull(n.path("receiver").asText()));
                    leg.setAmount(toBigDecimal(n.path("amount")));
                    leg.setLegType(status);
                    leg.setCorrelationType("None");
                    leg.setMessageId(null);
                    leg.setBlockNumber(blockNumber);
                    leg.setBlockTimestamp(n.path("timestamp").asLong());
                    bridgeLegRepository.save(leg);
                }
                maxBlock = Math.max(maxBlock, blockNumber);
            }
            cursor.setLastSyncBlockNumber(maxBlock);
            syncCursorRepository.save(cursor);
            hasMore = nodes.size() >= batch;
        }
    }

    private SyncCursorEntity getOrCreateCursor(String chainId, String dataType) {
        return syncCursorRepository.findByChainIdAndDataType(chainId, dataType)
                .orElseGet(() -> {
                    SyncCursorEntity c = new SyncCursorEntity();
                    c.setChainId(chainId);
                    c.setDataType(dataType);
                    c.setLastSyncBlockNumber(0L);
                    c.setLastSyncTimestamp(0L);
                    c.setErrorCount(0);
                    return syncCursorRepository.save(c);
                });
    }

    private BigDecimal toBigDecimal(JsonNode node) {
        if (node == null || node.isMissingNode()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(node.asText("0"));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private String toLower(String address) {
        return address == null ? null : address.toLowerCase();
    }

    private String toLowerOrNull(String address) {
        if (address == null || address.isBlank()) return null;
        return address.toLowerCase();
    }
}
