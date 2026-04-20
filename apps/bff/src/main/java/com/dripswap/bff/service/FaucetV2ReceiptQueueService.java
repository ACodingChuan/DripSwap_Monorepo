package com.dripswap.bff.service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dripswap.bff.repository.FaucetV2ClaimRepository;
import com.dripswap.bff.repository.PendingReceipt;
import com.dripswap.bff.repository.RollbackRow;

@Service
public class FaucetV2ReceiptQueueService {
    private static final Logger log = LoggerFactory.getLogger(FaucetV2ReceiptQueueService.class);
    private static final long POLL_SECONDS = 10L;
    private static final long MAX_WAIT_SECONDS = 3L * 60L; // 3 minutes

    private final FaucetV2OnchainService onchain;
    private final FaucetV2ClaimRepository claimRepo;
    private final DelayQueue<ReceiptTask> queue = new DelayQueue<>();

    public FaucetV2ReceiptQueueService(FaucetV2OnchainService onchain, FaucetV2ClaimRepository claimRepo) {
        this.onchain = onchain;
        this.claimRepo = claimRepo;
        startWorker();
        loadPending();
    }

    public void enqueue(UUID requestId, long chainId, String txHash, Instant checkAt) {
        if (txHash == null || txHash.isBlank()) return;
        log.info("faucetv2 receipt enqueue requestId={} chainId={} txHash={} checkAt={}",
                requestId, chainId, txHash, checkAt);
        queue.offer(new ReceiptTask(requestId, chainId, txHash, checkAt, Instant.now()));
    }

    private void startWorker() {
        Thread t = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ReceiptTask task = queue.take();
                    handleTask(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.warn("receipt worker error: {}", e.getMessage());
                }
            }
        }, "faucetv2-receipt-worker");
        t.setDaemon(true);
        t.start();
    }

    private void loadPending() {
        for (PendingReceipt row : claimRepo.findPendingReceipts()) {
            ReceiptTask task = new ReceiptTask(
                    row.getRequestId(),
                    row.getChainId(),
                    row.getTxHash(),
                    row.getCheckAt() == null ? Instant.now() : row.getCheckAt(),
                    row.getCreatedAt() == null ? Instant.now() : row.getCreatedAt()
            );
            log.info("faucetv2 receipt reload pending requestId={} chainId={} txHash={} checkAt={} createdAt={}",
                    row.getRequestId(), row.getChainId(), row.getTxHash(), row.getCheckAt(), row.getCreatedAt());
            queue.offer(task);
        }
    }

    private void handleTask(ReceiptTask task) {
        String txHash = task.txHash();
        if (txHash == null || txHash.isBlank()) return;
        long ageSeconds = ageSeconds(task);

        var receiptResp = safeGetReceipt(task.chainId(), txHash, task.requestId());
        if (receiptResp == null) {
            // transient RPC failure; retry later until max wait
            if (shouldDrop(task)) {
                markDropped(task.requestId(), task.chainId(), txHash, ageSeconds, "receipt rpc failure");
                return;
            }
            reschedule(task);
            return;
        }
        if (receiptResp.getTransactionReceipt().isEmpty()) {
            var txResp = onchain.getTransactionByHash(task.chainId(), txHash);
            boolean txVisible = txResp != null && txResp.getTransaction().isPresent();
            if (txVisible) {
                var tx = txResp.getTransaction().get();
                log.info(
                        "faucetv2 receipt pending requestId={} chainId={} txHash={} ageSeconds={} txNonce={} blockHash={} blockNumber={}",
                        task.requestId(),
                        task.chainId(),
                        txHash,
                        ageSeconds,
                        tx.getNonce(),
                        tx.getBlockHash(),
                        tx.getBlockNumber()
                );
            } else {
                log.warn(
                        "faucetv2 receipt missing tx by hash requestId={} chainId={} txHash={} ageSeconds={}",
                        task.requestId(),
                        task.chainId(),
                        txHash,
                        ageSeconds
                );
            }
            if (shouldDrop(task)) {
                markDropped(task.requestId(), task.chainId(), txHash, ageSeconds,
                        txVisible ? "receipt missing after max wait but tx still queryable" : "tx hash not queryable after max wait");
                return;
            }
            reschedule(task);
            return;
        }

        var receipt = receiptResp.getTransactionReceipt().get();
        String status = receipt.getStatus();
        if (status != null && status.equalsIgnoreCase("0x1")) {
            confirmAndApply(task.requestId(), task.chainId(), txHash, ageSeconds, receipt.getBlockNumber(), receipt.getTransactionIndex());
            return;
        }
        markFailed(task.requestId(), task.chainId(), txHash, ageSeconds, status);
    }

    private org.web3j.protocol.core.methods.response.EthGetTransactionReceipt safeGetReceipt(
            long chainId,
            String txHash,
            UUID requestId
    ) {
        try {
            return onchain.web3(chainId).ethGetTransactionReceipt(txHash).send();
        } catch (Exception e) {
            log.warn("faucetv2 receipt check failed chainId={} requestId={} txHash={} err={}",
                    chainId, requestId, txHash, e.getMessage());
            return null;
        }
    }

    private void markFailed(UUID requestId, long chainId, String txHash, long ageSeconds, String receiptStatus) {
        log.warn("faucetv2 receipt failed requestId={} chainId={} txHash={} ageSeconds={} receiptStatus={}",
                requestId, chainId, txHash, ageSeconds, receiptStatus);
        claimRepo.updateStatus(requestId, "FAILED");
    }

    private void markDropped(UUID requestId, long chainId, String txHash, long ageSeconds, String reason) {
        log.warn("faucetv2 receipt dropped requestId={} chainId={} txHash={} ageSeconds={} reason={}",
                requestId, chainId, txHash, ageSeconds, reason);
        claimRepo.updateStatus(requestId, "DROPPED");
    }

    private void confirmAndApply(
            UUID requestId,
            long chainId,
            String txHash,
            long ageSeconds,
            java.math.BigInteger blockNumber,
            java.math.BigInteger transactionIndex
    ) {
        // Ensure idempotency: only apply once when the status flips from SUBMITTED -> CONFIRMED.
        int updated = claimRepo.updateStatus(requestId, "CONFIRMED");
        if (updated <= 0) return;

        RollbackRow row = claimRepo.findRollbackRow(requestId);
        if (row == null) return;

        log.info(
                "faucetv2 receipt confirmed requestId={} chainId={} txHash={} ageSeconds={} blockNumber={} txIndex={} user={} token={} amount={}",
                requestId,
                chainId,
                txHash,
                ageSeconds,
                blockNumber,
                transactionIndex,
                row.getUserAddress(),
                row.getTokenAddress(),
                row.getAmountRaw()
        );

        claimRepo.upsertUserDaily(row.getChainId(), row.getUserAddress(), row.getDay());
        claimRepo.upsertIpDaily(row.getChainId(), row.getIpHash(), row.getDay());
        claimRepo.upsertTokenDailyIssued(row.getChainId(), row.getTokenAddress(), row.getDay(), row.getAmountRaw());
        claimRepo.decrementVaultBalance(row.getChainId(), row.getTokenAddress(), row.getAmountRaw());
    }

    private boolean shouldDrop(ReceiptTask task) {
        Instant createdAt = task.createdAt();
        if (createdAt == null) return false;
        return ageSeconds(task) >= MAX_WAIT_SECONDS;
    }

    private void reschedule(ReceiptTask task) {
        Instant next = Instant.now().plusSeconds(POLL_SECONDS);
        claimRepo.updateCheckAt(task.requestId(), next);
        log.info("faucetv2 receipt reschedule requestId={} chainId={} txHash={} nextCheckAt={} ageSeconds={}",
                task.requestId(), task.chainId(), task.txHash(), next, ageSeconds(task));
        queue.offer(new ReceiptTask(task.requestId(), task.chainId(), task.txHash(), next, task.createdAt()));
    }

    private long ageSeconds(ReceiptTask task) {
        Instant createdAt = task.createdAt();
        if (createdAt == null) return 0L;
        return Duration.between(createdAt, Instant.now()).getSeconds();
    }

    private record ReceiptTask(
            UUID requestId,
            long chainId,
            String txHash,
            Instant checkAt,
            Instant createdAt
    ) implements Delayed {
        @Override
        public long getDelay(TimeUnit unit) {
            long diff = Duration.between(Instant.now(), checkAt).toMillis();
            return unit.convert(Math.max(0, diff), TimeUnit.MILLISECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            if (other == this) return 0;
            long diff = getDelay(TimeUnit.MILLISECONDS) - other.getDelay(TimeUnit.MILLISECONDS);
            return diff == 0 ? 0 : (diff < 0 ? -1 : 1);
        }
    }
}
