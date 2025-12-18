package com.dripswap.bff.sync;

import com.dripswap.bff.config.SubgraphProperties;
import com.dripswap.bff.entity.SyncStatus;
import com.dripswap.bff.repository.SyncStatusRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

/**
 * Subgraph 数据同步服务
 * 手动触发全量同步，从 The Graph 同步数据到 PostgreSQL
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubgraphSyncService {
    
    private final SubgraphClient subgraphClient;
    private final SubgraphProperties subgraphProperties;
    private final SyncStatusRepository syncStatusRepository;
    private final UniswapFactorySyncHandler uniswapFactorySyncHandler;
    private final BundleSyncHandler bundleSyncHandler;
    private final TokenSyncHandler tokenSyncHandler;
    private final TokenMinuteDataSyncHandler tokenMinuteDataSyncHandler;
    private final TokenDayDataSyncHandler tokenDayDataSyncHandler;
    private final TokenHourDataSyncHandler tokenHourDataSyncHandler;
    private final PairSyncHandler pairSyncHandler;
    private final PairTokenLookupSyncHandler pairTokenLookupSyncHandler;
    private final UserSyncHandler userSyncHandler;
    private final TransactionSyncHandler transactionSyncHandler;
    private final SwapSyncHandler swapSyncHandler;
    private final MintSyncHandler mintSyncHandler;
    private final BurnSyncHandler burnSyncHandler;
    private final BridgeTransferSyncHandler bridgeTransferSyncHandler;
    private final BridgeConfigEventSyncHandler bridgeConfigEventSyncHandler;
    private final UniswapDayDataSyncHandler uniswapDayDataSyncHandler;
    private final PairDayDataSyncHandler pairDayDataSyncHandler;
    private final PairHourDataSyncHandler pairHourDataSyncHandler;
    
    /**
     * 手动触发全量同步
     * 可通过 REST API 或启动时调用
     */
    public void syncAll() {
        log.info("=== Starting FULL Subgraph sync ===");
        
        for (SubgraphProperties.ChainConfig chain : subgraphProperties.getChains()) {
            if (!chain.isEnabled()) {
                log.info("Chain {} is disabled, skipping", chain.getId());
                continue;
            }
            
            try {
                log.info("Starting sync for chain: {}", chain.getId());
                syncChain(chain);
                log.info("Successfully synced chain: {}", chain.getId());
            } catch (Exception e) {
                log.error("Failed to sync chain {}: {}", chain.getId(), e.getMessage(), e);
            }
        }
        
        log.info("=== Subgraph FULL sync completed ===");
    }
    
    /**
     * 同步单个链的数据
     */
    @Transactional
    public void syncChain(SubgraphProperties.ChainConfig chain) {
        log.info("Syncing chain: {}", chain.getId());
        
        String chainId = chain.getId();
        int batchSize = subgraphProperties.getBatchSize();
        
        // 1. 同步核心实体
        runStep(chainId, "uniswapFactories", () -> syncUniswapFactories(chain, batchSize));
        runStep(chainId, "bundles", () -> syncBundles(chain, batchSize));
        runStep(chainId, "tokens", () -> syncTokens(chain, batchSize));
        runStep(chainId, "pairs", () -> syncPairs(chain, batchSize));
        runStep(chainId, "users", () -> syncUsers(chain, batchSize));
        runStep(chainId, "transactions", () -> syncTransactions(chain, batchSize));
        runStep(chainId, "pairTokenLookups", () -> syncPairTokenLookups(chain, batchSize));
        
        // 2. 同步事件实体
        runStep(chainId, "swaps", () -> syncSwaps(chain, batchSize));
        runStep(chainId, "mints", () -> syncMints(chain, batchSize));
        runStep(chainId, "burns", () -> syncBurns(chain, batchSize));
        
        // 3. 同步 Bridge 实体
        runStep(chainId, "bridgeTransfers", () -> syncBridgeTransfers(chain, batchSize));
        runStep(chainId, "bridgeConfigEvents", () -> syncBridgeConfigEvents(chain, batchSize));
        
        // 4. 同步时间聚合数据
        runStep(chainId, "uniswapDayData", () -> syncUniswapDayData(chain, batchSize));
        runStep(chainId, "tokenMinuteData", () -> syncTokenMinuteData(chain, batchSize));
        runStep(chainId, "tokenHourData", () -> syncTokenHourData(chain, batchSize));
        runStep(chainId, "tokenDayData", () -> syncTokenDayData(chain, batchSize));
        runStep(chainId, "pairDayData", () -> syncPairDayData(chain, batchSize));
        runStep(chainId, "pairHourData", () -> syncPairHourData(chain, batchSize));
        // ... 其他聚合数据
        
        log.info("Chain {} sync completed", chainId);
    }

    private void runStep(String chainId, String entityType, Runnable step) {
        String key = chainId + ":" + entityType;
        SyncStatus status = syncStatusRepository.findById(key).orElseGet(() -> {
            SyncStatus created = new SyncStatus();
            created.setKey(key);
            created.setChainId(chainId);
            created.setEntityType(entityType);
            return created;
        });
        status.setSyncStatus("running");
        status.setSyncStartTime(java.time.LocalDateTime.now());
        status.setSyncEndTime(null);
        status.setErrorMessage(null);
        syncStatusRepository.save(status);

        try {
            step.run();
            status.setSyncStatus("completed");
        } catch (Exception e) {
            status.setSyncStatus("failed");
            status.setErrorMessage(e.getMessage());
            log.error("Sync step failed: chain={}, entityType={}, error={}", chainId, entityType, e.getMessage(), e);
        } finally {
            status.setSyncEndTime(java.time.LocalDateTime.now());
            syncStatusRepository.save(status);
        }
    }

    private void syncUniswapFactories(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing uniswapFactories for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              uniswapFactories(first: $first, skip: $skip, orderBy: id) {
                id
                pairCount
                totalVolumeUSD
                totalVolumeETH
                untrackedVolumeUSD
                totalLiquidityUSD
                totalLiquidityETH
                txCount
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("uniswapFactories");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                uniswapFactorySyncHandler.handleFactories(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync uniswapFactories at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }

    private void syncBundles(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing bundles for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              bundles(first: $first, skip: $skip, orderBy: id) {
                id
                ethPrice
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("bundles");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                bundleSyncHandler.handleBundles(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync bundles at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }

    private void syncUsers(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing users for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              users(first: $first, skip: $skip, orderBy: id) {
                id
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("users");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                userSyncHandler.handleUsers(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync users at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }

    private void syncTransactions(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing transactions for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              transactions(first: $first, skip: $skip, orderBy: timestamp, orderDirection: desc) {
                id
                blockNumber
                timestamp
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("transactions");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                transactionSyncHandler.handleTransactions(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync transactions at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }

    private void syncPairTokenLookups(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing pairTokenLookups for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              pairTokenLookups(first: $first, skip: $skip, orderBy: id) {
                id
                pair { id }
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("pairTokenLookups");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                pairTokenLookupSyncHandler.handlePairTokenLookups(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync pairTokenLookups at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }
    
    /**
     * 同步 Tokens
     */
    private void syncTokens(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing tokens for chain: {}", chain.getId());
        
        String query = """
            query($first: Int!, $skip: Int!) {
              tokens(first: $first, skip: $skip, orderBy: id) {
                id
                symbol
                name
                decimals
                totalSupply
                tradeVolume
                tradeVolumeUSD
                untrackedVolumeUSD
                txCount
                totalLiquidity
                derivedETH
              }
            }
            """;
        
        int skip = 0;
        boolean hasMore = true;
        
        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(
                    chain.getEndpoint(),
                    query,
                    batchSize,
                    skip
                );
                
                JsonNode tokens = data.get("tokens");
                if (tokens == null || !tokens.isArray() || tokens.size() == 0) {
                    hasMore = false;
                    break;
                }
                
                // 处理并保存数据
                tokenSyncHandler.handleTokens(chain.getId(), tokens);
                
                skip += batchSize;
                log.debug("Synced {} tokens, skip={}", tokens.size(), skip);
                
                if (tokens.size() < batchSize) {
                    hasMore = false;
                }
                
            } catch (Exception e) {
                log.error("Failed to sync tokens at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
        
        log.info("Tokens sync completed for chain: {}", chain.getId());
    }
    
    /**
     * 同步 Pairs
     */
    private void syncPairs(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing pairs for chain: {}", chain.getId());
        
        String query = """
            query($first: Int!, $skip: Int!) {
              pairs(first: $first, skip: $skip, orderBy: id) {
                id
                token0 { id }
                token1 { id }
                reserve0
                reserve1
                totalSupply
                reserveETH
                reserveUSD
                trackedReserveETH
                token0Price
                token1Price
                volumeToken0
                volumeToken1
                volumeUSD
                untrackedVolumeUSD
                txCount
                liquidityProviderCount
                createdAtTimestamp
                createdAtBlockNumber
              }
            }
            """;
        
        int skip = 0;
        boolean hasMore = true;
        
        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(
                    chain.getEndpoint(),
                    query,
                    batchSize,
                    skip
                );
                
                JsonNode pairs = data.get("pairs");
                if (pairs == null || !pairs.isArray() || pairs.size() == 0) {
                    hasMore = false;
                    break;
                }
                
                pairSyncHandler.handlePairs(chain.getId(), pairs);
                
                skip += batchSize;
                log.debug("Synced {} pairs, skip={}", pairs.size(), skip);
                
                if (pairs.size() < batchSize) {
                    hasMore = false;
                }
                
            } catch (Exception e) {
                log.error("Failed to sync pairs at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
        
        log.info("Pairs sync completed for chain: {}", chain.getId());
    }
    
    /**
     * 同步 Swaps
     */
    private void syncSwaps(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing swaps for chain: {}", chain.getId());
        
        String query = """
            query($first: Int!, $skip: Int!) {
              swaps(first: $first, skip: $skip, orderBy: timestamp, orderDirection: desc) {
                id
                transaction { id blockNumber timestamp }
                timestamp
                pair { id }
                sender
                from
                to
                amount0In
                amount1In
                amount0Out
                amount1Out
                logIndex
                amountUSD
              }
            }
            """;
        
        int skip = 0;
        boolean hasMore = true;
        
        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(
                    chain.getEndpoint(),
                    query,
                    batchSize,
                    skip
                );
                
                JsonNode swaps = data.get("swaps");
                if (swaps == null || !swaps.isArray() || swaps.size() == 0) {
                    hasMore = false;
                    break;
                }
                
                swapSyncHandler.handleSwaps(chain.getId(), swaps);
                
                skip += batchSize;
                log.debug("Synced {} swaps, skip={}", swaps.size(), skip);
                
                if (swaps.size() < batchSize) {
                    hasMore = false;
                }
                
            } catch (Exception e) {
                log.error("Failed to sync swaps at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
        
        log.info("Swaps sync completed for chain: {}", chain.getId());
    }
    
    private void syncMints(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing mints for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              mints(first: $first, skip: $skip, orderBy: timestamp, orderDirection: desc) {
                id
                transaction { id blockNumber timestamp }
                timestamp
                pair { id }
                to
                liquidity
                sender
                amount0
                amount1
                logIndex
                amountUSD
                feeTo
                feeLiquidity
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("mints");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                mintSyncHandler.handleMints(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync mints at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }
    
    private void syncBurns(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing burns for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              burns(first: $first, skip: $skip, orderBy: timestamp, orderDirection: desc) {
                id
                transaction { id blockNumber timestamp }
                timestamp
                pair { id }
                sender
                liquidity
                amount0
                amount1
                to
                logIndex
                amountUSD
                feeTo
                feeLiquidity
                needsComplete
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("burns");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                burnSyncHandler.handleBurns(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync burns at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }
    
    private void syncBridgeTransfers(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing bridgeTransfers for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              bridgeTransfers(first: $first, skip: $skip, orderBy: timestamp, orderDirection: desc) {
                id
                txHash
                blockNumber
                timestamp
                messageId
                sender
                token
                pool
                amount
                dstSelector
                receiverChainName
                receiver
                payInLink
                ccipFee
                serviceFeePaid
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("bridgeTransfers");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                bridgeTransferSyncHandler.handleBridgeTransfers(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync bridgeTransfers at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }

    private void syncBridgeConfigEvents(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing bridgeConfigEvents for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              bridgeConfigEvents(first: $first, skip: $skip, orderBy: timestamp, orderDirection: desc) {
                id
                eventName
                token
                pool
                minAmount
                maxAmount
                nativeAllowed
                linkAllowed
                newFee
                newCollector
                blockNumber
                timestamp
                transactionHash
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("bridgeConfigEvents");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                bridgeConfigEventSyncHandler.handleBridgeConfigEvents(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync bridgeConfigEvents at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }
    
    private void syncUniswapDayData(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing uniswapDayData for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              uniswapDayDatas(first: $first, skip: $skip, orderBy: date, orderDirection: desc) {
                id
                date
                dailyVolumeETH
                dailyVolumeUSD
                dailyVolumeUntracked
                totalVolumeETH
                totalVolumeUSD
                totalLiquidityETH
                totalLiquidityUSD
                txCount
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("uniswapDayDatas");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                uniswapDayDataSyncHandler.handleUniswapDayData(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync uniswapDayDatas at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }

    private void syncTokenDayData(SubgraphProperties.ChainConfig chain, int batchSize) {
        if (chain.getEndpointV2Tokens() == null || chain.getEndpointV2Tokens().isBlank()) {
            log.info("Chain {} has no endpoint-v2-tokens configured, skipping tokenDayData sync", chain.getId());
            return;
        }

        log.info("Syncing tokenDayData for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $lastId: ID!) {
              tokenDayDatas(first: $first, where: { id_gt: $lastId }, orderBy: id, orderDirection: asc) {
                id
                date
                token { id }
                dailyVolumeToken
                dailyVolumeETH
                dailyVolumeUSD
                dailyTxns
                totalLiquidityToken
                totalLiquidityETH
                totalLiquidityUSD
                priceUSD
              }
            }
            """;

        String lastId = "";
        boolean hasMore = true;

        while (hasMore) {
            try {
                Map<String, Object> variables = new HashMap<>();
                variables.put("first", batchSize);
                variables.put("lastId", lastId);
                JsonNode data = subgraphClient.query(chain.getEndpointV2Tokens(), query, variables);

                JsonNode nodes = data.get("tokenDayDatas");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                tokenDayDataSyncHandler.handleTokenDayData(chain.getId(), nodes);

                lastId = nodes.get(nodes.size() - 1).get("id").asText();
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync tokenDayDatas after lastId={}: {}", lastId, e.getMessage(), e);
                hasMore = false;
            }
        }
    }

    private void syncTokenHourData(SubgraphProperties.ChainConfig chain, int batchSize) {
        if (chain.getEndpointV2Tokens() == null || chain.getEndpointV2Tokens().isBlank()) {
            log.info("Chain {} has no endpoint-v2-tokens configured, skipping tokenHourData sync", chain.getId());
            return;
        }

        log.info("Syncing tokenHourData for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $lastId: ID!) {
              tokenHourDatas(first: $first, where: { id_gt: $lastId }, orderBy: id, orderDirection: asc) {
                id
                periodStartUnix
                token { id }
                volume
                volumeUSD
                untrackedVolumeUSD
                totalValueLocked
                totalValueLockedUSD
                priceUSD
                feesUSD
                open
                high
                low
                close
              }
            }
            """;

        String lastId = "";
        boolean hasMore = true;

        while (hasMore) {
            try {
                Map<String, Object> variables = new HashMap<>();
                variables.put("first", batchSize);
                variables.put("lastId", lastId);
                JsonNode data = subgraphClient.query(chain.getEndpointV2Tokens(), query, variables);

                JsonNode nodes = data.get("tokenHourDatas");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                tokenHourDataSyncHandler.handleTokenHourData(chain.getId(), nodes);

                lastId = nodes.get(nodes.size() - 1).get("id").asText();
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync tokenHourDatas after lastId={}: {}", lastId, e.getMessage(), e);
                hasMore = false;
            }
        }
    }

    private void syncTokenMinuteData(SubgraphProperties.ChainConfig chain, int batchSize) {
        if (chain.getEndpointV2Tokens() == null || chain.getEndpointV2Tokens().isBlank()) {
            log.info("Chain {} has no endpoint-v2-tokens configured, skipping tokenMinuteData sync", chain.getId());
            return;
        }

        log.info("Syncing tokenMinuteData for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $lastId: ID!) {
              tokenMinuteDatas(first: $first, where: { id_gt: $lastId }, orderBy: id, orderDirection: asc) {
                id
                periodStartUnix
                token { id }
                volume
                volumeUSD
                untrackedVolumeUSD
                totalValueLocked
                totalValueLockedUSD
                priceUSD
                feesUSD
                open
                high
                low
                close
              }
            }
            """;

        String lastId = "";
        boolean hasMore = true;

        while (hasMore) {
            try {
                Map<String, Object> variables = new HashMap<>();
                variables.put("first", batchSize);
                variables.put("lastId", lastId);
                JsonNode data = subgraphClient.query(chain.getEndpointV2Tokens(), query, variables);

                JsonNode tokenMinuteDatas = data.get("tokenMinuteDatas");
                if (tokenMinuteDatas == null || !tokenMinuteDatas.isArray() || tokenMinuteDatas.size() == 0) {
                    hasMore = false;
                    break;
                }

                tokenMinuteDataSyncHandler.handleTokenMinuteData(chain.getId(), tokenMinuteDatas);

                lastId = tokenMinuteDatas.get(tokenMinuteDatas.size() - 1).get("id").asText();
                log.debug("Synced {} tokenMinuteDatas, lastId={}", tokenMinuteDatas.size(), lastId);

                if (tokenMinuteDatas.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error(
                    "Failed to sync tokenMinuteDatas after lastId={} for chain {}: {}",
                    lastId,
                    chain.getId(),
                    e.getMessage(),
                    e
                );
                hasMore = false;
            }
        }

        log.info("tokenMinuteData sync completed for chain: {}", chain.getId());
    }
    
    private void syncPairDayData(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing pairDayData for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              pairDayDatas(first: $first, skip: $skip, orderBy: date, orderDirection: desc) {
                id
                date
                pairAddress
                token0 { id }
                token1 { id }
                reserve0
                reserve1
                totalSupply
                reserveUSD
                dailyVolumeToken0
                dailyVolumeToken1
                dailyVolumeUSD
                dailyTxns
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("pairDayDatas");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                pairDayDataSyncHandler.handlePairDayData(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync pairDayDatas at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }

    private void syncPairHourData(SubgraphProperties.ChainConfig chain, int batchSize) {
        log.info("Syncing pairHourData for chain: {}", chain.getId());

        String query = """
            query($first: Int!, $skip: Int!) {
              pairHourDatas(first: $first, skip: $skip, orderBy: hourStartUnix, orderDirection: desc) {
                id
                hourStartUnix
                pair { id }
                reserve0
                reserve1
                totalSupply
                reserveUSD
                hourlyVolumeToken0
                hourlyVolumeToken1
                hourlyVolumeUSD
                hourlyTxns
              }
            }
            """;

        int skip = 0;
        boolean hasMore = true;

        while (hasMore) {
            try {
                JsonNode data = subgraphClient.queryWithPagination(chain.getEndpoint(), query, batchSize, skip);
                JsonNode nodes = data.get("pairHourDatas");
                if (nodes == null || !nodes.isArray() || nodes.size() == 0) {
                    hasMore = false;
                    break;
                }

                pairHourDataSyncHandler.handlePairHourData(chain.getId(), nodes);

                skip += batchSize;
                if (nodes.size() < batchSize) {
                    hasMore = false;
                }
            } catch (Exception e) {
                log.error("Failed to sync pairHourDatas at skip={}: {}", skip, e.getMessage(), e);
                hasMore = false;
            }
        }
    }
}
