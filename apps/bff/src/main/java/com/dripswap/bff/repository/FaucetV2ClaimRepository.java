package com.dripswap.bff.repository;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FaucetV2ClaimRepository {
    StoredRequest findByIdempotency(@Param("key") String idempotencyKey);

    int insertClaimRequest(ClaimSubmittedParam param);

    int upsertUserDaily(@Param("chainId") long chainId, @Param("user") String user, @Param("day") long day);

    int upsertIpDaily(@Param("chainId") long chainId, @Param("ipHash") String ipHash, @Param("day") long day);

    int upsertTokenDailyIssued(
            @Param("chainId") long chainId,
            @Param("token") String token,
            @Param("day") long day,
            @Param("amount") BigInteger amount
    );

    int decrementVaultBalance(
            @Param("chainId") long chainId,
            @Param("token") String token,
            @Param("amount") BigInteger amount
    );

    List<PendingReceipt> findPendingReceipts();

    int updateStatus(@Param("id") UUID requestId, @Param("status") String status);

    RollbackRow findRollbackRow(@Param("id") UUID requestId);

    int updateCheckAt(@Param("id") UUID requestId, @Param("checkAt") Instant checkAt);

    int countActiveByTokenDay(@Param("chainId") long chainId, @Param("token") String token, @Param("day") long day);

    int countPendingByToken(@Param("chainId") long chainId, @Param("token") String token);

    record ClaimSubmittedParam(
            UUID requestId,
            long chainId,
            String user,
            String token,
            BigInteger amount,
            long day,
            BigInteger claimNonce,
            String idempotencyKey,
            String deviceId,
            String ipHash,
            String txHash,
            Instant checkAt
    ) {}
}
