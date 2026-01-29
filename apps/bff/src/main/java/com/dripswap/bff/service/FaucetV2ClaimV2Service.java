package com.dripswap.bff.service;

import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.dripswap.bff.config.FaucetV2Calibration;
import com.dripswap.bff.config.FaucetV2Properties;
import com.dripswap.bff.controller.dto.FaucetV2ClaimV2Request;
import com.dripswap.bff.controller.dto.FaucetV2ClaimV2Response;
import com.dripswap.bff.repository.ChainConfig;
import com.dripswap.bff.repository.FaucetV2ClaimRepository;
import com.dripswap.bff.repository.FaucetV2RiskRepository;
import com.dripswap.bff.repository.FaucetV2TokenRepository;
import com.dripswap.bff.repository.StoredRequest;
import com.dripswap.bff.repository.TokenSnapshot;
import com.dripswap.bff.repository.UserIpSnapshot;
import com.dripswap.bff.util.EvmKeys;
import com.dripswap.bff.util.FaucetV2Eip712Signer;
import com.dripswap.bff.util.FaucetV2TimeService;
import com.dripswap.bff.util.Hashing;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.DynamicBytes;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.StaticStruct;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.tx.RawTransactionManager;

@Service
public class FaucetV2ClaimV2Service {
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int DAILY_CAP_MULTIPLIER = 360;

    private final FaucetV2Properties props;
    private final FaucetV2ChainRegistry chainRegistry;
    private final FaucetV2CalibrationService calibrationService;
    private final FaucetV2CaptchaService captcha;
    private final FaucetV2ChainConfigCacheService chainConfigCache;
    private final FaucetV2ClaimRepository claimRepo;
    private final FaucetV2TokenRepository tokenRepo;
    private final FaucetV2RiskRepository riskRepo;
    private final FaucetV2ReceiptQueueService receiptQueue;
    private final FaucetV2OnchainService onchain;

    public FaucetV2ClaimV2Service(
            FaucetV2Properties props,
            FaucetV2ChainRegistry chainRegistry,
            FaucetV2CalibrationService calibrationService,
            FaucetV2CaptchaService captcha,
            FaucetV2ChainConfigCacheService chainConfigCache,
            FaucetV2ClaimRepository claimRepo,
            FaucetV2TokenRepository tokenRepo,
            FaucetV2RiskRepository riskRepo,
            FaucetV2ReceiptQueueService receiptQueue,
            FaucetV2OnchainService onchain
    ) {
        this.props = props;
        this.chainRegistry = chainRegistry;
        this.calibrationService = calibrationService;
        this.captcha = captcha;
        this.chainConfigCache = chainConfigCache;
        this.claimRepo = claimRepo;
        this.tokenRepo = tokenRepo;
        this.riskRepo = riskRepo;
        this.receiptQueue = receiptQueue;
        this.onchain = onchain;
    }

    public FaucetV2ClaimV2Response claim(FaucetV2ClaimV2Request req, String clientIp) {
        if (req.idempotencyKey() == null || req.idempotencyKey().isBlank()) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
        if (req.user() == null || req.user().isBlank()) {
            throw new IllegalArgumentException("user is required");
        }
        if (req.token() == null || req.token().isBlank()) {
            throw new IllegalArgumentException("token is required");
        }

        String idempotencyKey = req.idempotencyKey().trim();
        String user = normalizeAddress(req.user());
        String ipHash = hashIp(clientIp);

        ChainConfig chainCfg = chainConfigCache.load(req.chainId());
        if (chainCfg == null || !chainCfg.isEnabled()) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "chain disabled", null);
        }
        if (chainCfg.isPaused()) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "paused", null);
        }

        if (props.getCaptcha().isEnabled()) {
            if (!captcha.verify(req.captchaId(), req.captchaAnswer(), clientIp)) {
                return new FaucetV2ClaimV2Response(null, "REJECTED", "captcha required", null);
            }
        }

        StoredRequest existing = claimRepo.findByIdempotency(idempotencyKey);
        if (existing != null) {
            return new FaucetV2ClaimV2Response(existing.getRequestId(), existing.getStatus(), null, existing.getTxHash());
        }

        FaucetV2Properties.Chain chain = chainRegistry.get(req.chainId())
                .orElseThrow(() -> new IllegalArgumentException("unsupported chainId: " + req.chainId()));

        FaucetV2Calibration calib = calibrationService.get();

        long serverNowTs = Instant.now().getEpochSecond();
        long day = FaucetV2TimeService.dayFromEpochSeconds(serverNowTs);
        String dayYmd = LocalDate.ofEpochDay(day).format(DAY_FMT);

        TokenSnapshot token = loadTokenSnapshot(req.chainId(), req.token(), day);
        if (token == null) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "unsupported token", null);
        }

        UserIpSnapshot userIp = riskRepo.loadUserIpSnapshot(req.chainId(), user, ipHash, day);
        if (userIp == null) {
            userIp = new UserIpSnapshot();
        }
        if (userIp.isBlocked()) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "blocked", null);
        }

        if (!token.isWhitelist()) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "token not allowed", null);
        }

        if (userIp.getLastClaimAt() != null) {
            long elapsed = Math.max(0, serverNowTs - userIp.getLastClaimAt().getEpochSecond());
            if (elapsed < chainCfg.getUserCooldownSeconds()) {
                return new FaucetV2ClaimV2Response(null, "REJECTED", "cooldown", null);
            }
        }

        int nextUserCount = userIp.getUserClaimCount() + 1;
        if (nextUserCount > chainCfg.getUserDailyMax()) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "daily limit", null);
        }

        if (userIp.getIpClaimCount() + 1 > chainCfg.getIpDailyMax()) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "ip limit", null);
        }

        // Token global daily cap (count-based; include SUBMITTED to avoid oversubscription without needing rollback).
        int activeToday = claimRepo.countActiveByTokenDay(req.chainId(), token.getTokenAddress(), day);
        if (activeToday + 1 > DAILY_CAP_MULTIPLIER) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "daily cap", null);
        }

        // Vault inventory (include pending SUBMITTED txs as reserved; actual vault_balance_raw is decremented on CONFIRMED only).
        int pending = claimRepo.countPendingByToken(req.chainId(), token.getTokenAddress());
        BigInteger reserved = token.getSingleAmountRaw().multiply(BigInteger.valueOf(pending));
        BigInteger available = token.getVaultBalanceRaw().subtract(reserved);
        if (available.compareTo(token.getSingleAmountRaw()) < 0) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "vault low", null);
        }

        String signerPk = chain.getSignerPrivateKey();
        String relayerPk = chain.getRelayerPrivateKey();
        if (signerPk == null || signerPk.isBlank()) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "signer key missing", null);
        }
        if (relayerPk == null || relayerPk.isBlank()) {
            return new FaucetV2ClaimV2Response(null, "REJECTED", "relayer key missing", null);
        }

        Credentials signerCreds = EvmKeys.credentialsFromPrivateKey(signerPk);
        Credentials relayerCreds = EvmKeys.credentialsFromPrivateKey(relayerPk);
        if (chainCfg.getSignerAddress() != null && !chainCfg.getSignerAddress().isBlank()) {
            if (!chainCfg.getSignerAddress().equalsIgnoreCase(signerCreds.getAddress())) {
                return new FaucetV2ClaimV2Response(null, "REJECTED", "signer mismatch", null);
            }
        }
        if (chainCfg.getRelayerAddress() != null && !chainCfg.getRelayerAddress().isBlank()) {
            if (!chainCfg.getRelayerAddress().equalsIgnoreCase(relayerCreds.getAddress())) {
                return new FaucetV2ClaimV2Response(null, "REJECTED", "relayer mismatch", null);
            }
        }

        String eip712Name = chainCfg.getEip712Name() == null || chainCfg.getEip712Name().isBlank()
                ? calib.eip712Name()
                : chainCfg.getEip712Name();
        String eip712Version = chainCfg.getEip712Version() == null || chainCfg.getEip712Version().isBlank()
                ? calib.eip712Version()
                : chainCfg.getEip712Version();

        BigInteger claimNonce = new BigInteger(dayYmd + nextUserCount);
        BigInteger deadline = BigInteger.valueOf(serverNowTs + chainCfg.getClaimDeadlineSeconds());

        byte[] sig = FaucetV2Eip712Signer.signClaim(
                signerCreds.getEcKeyPair(),
                eip712Name,
                eip712Version,
                req.chainId(),
                chain.getFaucetAddress(),
                user,
                token.getTokenAddress(),
                token.getSingleAmountRaw(),
                day,
                claimNonce,
                deadline,
                false
        );

        String txHash;
        try {
            txHash = sendClaimWithSig(
                    req.chainId(),
                    relayerCreds,
                    chain.getFaucetAddress(),
                    user,
                    token.getTokenAddress(),
                    token.getSingleAmountRaw(),
                    day,
                    claimNonce,
                    deadline,
                    sig
            );
        } catch (Exception e) {
            return new FaucetV2ClaimV2Response(null, "FAILED", "tx submit failed: " + e.getMessage(), null);
        }

        UUID requestId = UUID.randomUUID();
        Instant checkAt = Instant.now().plusSeconds(10);
        try {
            saveSubmitted(
                    requestId,
                    req.chainId(),
                    user,
                    token.getTokenAddress(),
                    token.getSingleAmountRaw(),
                    day,
                    claimNonce,
                    idempotencyKey,
                    req.deviceId(),
                    ipHash,
                    txHash,
                    checkAt
            );
        } catch (DataIntegrityViolationException e) {
            StoredRequest raced = claimRepo.findByIdempotency(idempotencyKey);
            if (raced != null) {
                return new FaucetV2ClaimV2Response(raced.getRequestId(), raced.getStatus(), null, raced.getTxHash());
            }
            throw e;
        }

        receiptQueue.enqueue(requestId, req.chainId(), txHash, checkAt);
        return new FaucetV2ClaimV2Response(requestId, "SUBMITTED", null, txHash);
    }

    @Transactional
    protected void saveSubmitted(
            UUID requestId,
            long chainId,
            String user,
            String tokenAddress,
            BigInteger amountRaw,
            long day,
            BigInteger claimNonce,
            String idempotencyKey,
            String deviceId,
            String ipHash,
            String txHash,
            Instant checkAt
    ) {
        claimRepo.insertClaimRequest(new FaucetV2ClaimRepository.ClaimSubmittedParam(
                requestId,
                chainId,
                user,
                tokenAddress,
                amountRaw,
                day,
                claimNonce,
                idempotencyKey,
                deviceId,
                ipHash,
                txHash,
                checkAt
        ));
    }

    private TokenSnapshot loadTokenSnapshot(long chainId, String tokenOrSymbol, long day) {
        String token = normalizeAddress(tokenOrSymbol);
        TokenSnapshot snapshot = tokenRepo.findTokenSnapshot(chainId, token, tokenOrSymbol, day);
        if (snapshot == null) return null;
        snapshot.setTokenAddress(normalizeAddress(snapshot.getTokenAddress()));
        return snapshot;
    }

    private String sendClaimWithSig(
            long chainId,
            Credentials relayer,
            String faucetAddress,
            String user,
            String token,
            BigInteger amount,
            long day,
            BigInteger nonce,
            BigInteger deadline,
            byte[] sig
    ) throws Exception {
        Web3j web3 = onchain.web3(chainId);
        RawTransactionManager txm = new RawTransactionManager(web3, relayer, chainId);
        String data = encodeClaimWithSigData(user, token, amount, day, nonce, deadline, sig);
        var gasPriceResp = web3.ethGasPrice().send();
        BigInteger gasPrice = gasPriceResp.getGasPrice();
        if (gasPrice == null) gasPrice = BigInteger.ZERO;
        var sent = txm.sendTransaction(gasPrice, BigInteger.valueOf(500_000L), faucetAddress, data, BigInteger.ZERO);
        if (sent.hasError()) {
            throw new IllegalStateException(sent.getError().getMessage());
        }
        return sent.getTransactionHash();
    }

    private static String encodeClaimWithSigData(
            String user,
            String token,
            BigInteger amount,
            long day,
            BigInteger nonce,
            BigInteger deadline,
            byte[] sig
    ) {
        StaticStruct claimReq = new StaticStruct(
                new org.web3j.abi.datatypes.Address(user),
                new org.web3j.abi.datatypes.Address(token),
                new Uint256(amount),
                new Uint64(BigInteger.valueOf(day)),
                new Uint256(nonce),
                new Uint256(deadline),
                new org.web3j.abi.datatypes.Bool(false)
        );
        Function fn = new Function(
                "claimWithSig",
                List.of((Type) claimReq, new DynamicBytes(sig)),
                List.of()
        );
        return FunctionEncoder.encode(fn);
    }

    private String hashIp(String ip) {
        if (ip == null || ip.isBlank()) return null;
        String salt = props.getIpHashSalt();
        String material = (salt == null ? "" : salt) + "|" + ip;
        return Hashing.sha256Hex(material);
    }

    private static String normalizeAddress(String addr) {
        return addr == null ? null : addr.trim().toLowerCase(Locale.ROOT);
    }

}
