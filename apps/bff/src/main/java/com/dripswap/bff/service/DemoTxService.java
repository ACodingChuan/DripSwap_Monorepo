package com.dripswap.bff.service;

import com.dripswap.bff.modules.rest.dto.DemoTxRequest;
import com.dripswap.bff.modules.rest.dto.DemoTxResponse;
import com.dripswap.bff.modules.rest.model.DemoTx;
import com.dripswap.bff.repository.DemoTxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Service for demo transaction operations.
 * Provides write operations with idempotency protection using Redis.
 */
@Service
@Transactional
public class DemoTxService {

    private static final Logger logger = LoggerFactory.getLogger(DemoTxService.class);
    private static final String IDEMPOTENCY_KEY_PREFIX = "demo_tx:";
    private static final long IDEMPOTENCY_TTL_MINUTES = 5;

    private final DemoTxRepository demoTxRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public DemoTxService(DemoTxRepository demoTxRepository,
                        RedisTemplate<String, String> redisTemplate) {
        this.demoTxRepository = demoTxRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * Submit a demo transaction with idempotency protection.
     * Uses Redis SETNX for duplicate detection.
     *
     * @param request Demo transaction request
     * @return Response with success status and message
     */
    public DemoTxResponse submitDemoTx(DemoTxRequest request) {
        try {
            String txHash = request.getTxHash();
            String chainId = request.getChainId();

            logger.info("Submitting demo transaction: txHash={}, chainId={}", txHash, chainId);

            // Idempotency check using Redis SETNX
            if (!tryAcquireIdempotencyLock(txHash)) {
                logger.warn("Duplicate demo transaction detected: txHash={}, chainId={}", txHash, chainId);
                return new DemoTxResponse(false, txHash, "duplicate");
            }

            // Write to database
            DemoTx demoTx = new DemoTx(chainId, txHash, "pending");
            DemoTx saved = demoTxRepository.save(demoTx);

            logger.info("Demo transaction saved successfully: id={}, txHash={}, chainId={}",
                    saved.getId(), txHash, chainId);

            return new DemoTxResponse(true, txHash, "success");
        } catch (Exception e) {
            logger.error("Error submitting demo transaction: {}", e.getMessage(), e);
            return new DemoTxResponse(false, request.getTxHash(), "error: " + e.getMessage());
        }
    }

    /**
     * Try to acquire idempotency lock using Redis SETNX.
     * Returns true if lock acquired (new transaction), false if already exists.
     *
     * @param txHash Transaction hash
     * @return true if this is a new transaction, false if duplicate
     */
    private boolean tryAcquireIdempotencyLock(String txHash) {
        try {
            String key = IDEMPOTENCY_KEY_PREFIX + txHash;
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, "1",
                    IDEMPOTENCY_TTL_MINUTES, TimeUnit.MINUTES);
            return acquired != null && acquired;
        } catch (Exception e) {
            logger.error("Error checking idempotency lock: {}", e.getMessage());
            // Fallback to database check if Redis fails
            return !demoTxRepository.findByTxHash(txHash).isPresent();
        }
    }

    /**
     * Get recent demo transactions by chain.
     *
     * @param chainId Chain identifier
     * @param limit   Number of records to fetch
     * @return List of recent demo transactions
     */
    public List<DemoTx> getRecentByChain(String chainId, int limit) {
        try {
            logger.debug("Fetching {} recent demo transactions for chain: {}", limit, chainId);
            List<DemoTx> transactions = demoTxRepository.findByChainId(chainId);
            return transactions.stream()
                    .sorted((a, b) -> b.getId().compareTo(a.getId()))
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            logger.error("Error fetching recent transactions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get all recent demo transactions (all chains).
     *
     * @param limit Number of records to fetch
     * @return List of recent demo transactions
     */
    public List<DemoTx> getRecent(int limit) {
        try {
            logger.debug("Fetching {} recent demo transactions (all chains)", limit);
            return demoTxRepository.findAll().stream()
                    .sorted((a, b) -> b.getId().compareTo(a.getId()))
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            logger.error("Error fetching recent transactions: {}", e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * Get demo transaction by tx_hash.
     *
     * @param txHash Transaction hash
     * @return Demo transaction or null
     */
    public DemoTx getByTxHash(String txHash) {
        try {
            logger.debug("Fetching demo transaction: txHash={}", txHash);
            return demoTxRepository.findByTxHash(txHash).orElse(null);
        } catch (Exception e) {
            logger.error("Error fetching transaction: {}", e.getMessage(), e);
            return null;
        }
    }
}
