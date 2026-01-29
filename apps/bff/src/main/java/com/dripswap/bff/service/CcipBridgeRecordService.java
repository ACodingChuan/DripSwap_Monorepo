package com.dripswap.bff.service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.dripswap.bff.controller.dto.CcipBridgeRecordCreateRequest;
import com.dripswap.bff.repository.CcipBridgeRecord;
import com.dripswap.bff.repository.CcipBridgeRecordRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CcipBridgeRecordService {
    private final CcipBridgeRecordRepository repo;
    private static final java.util.regex.Pattern ADDRESS = java.util.regex.Pattern.compile("^0x[0-9a-fA-F]{40}$");
    private static final java.util.regex.Pattern MESSAGE_ID = java.util.regex.Pattern.compile("^0x[0-9a-fA-F]{64}$");

    public CcipBridgeRecordService(CcipBridgeRecordRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public CcipBridgeRecord createOrGet(CcipBridgeRecordCreateRequest req) {
        String messageId = normalizeHex(req.messageId());
        String user = normalizeAddress(req.userAddress());
        String tokenAddr = normalizeAddress(req.tokenAddress());
        String tokenSymbol = req.tokenSymbol() == null ? "" : req.tokenSymbol().trim();

        if (messageId == null || messageId.isBlank()) throw new IllegalArgumentException("messageId is required");
        if (user == null || user.isBlank()) throw new IllegalArgumentException("userAddress is required");
        if (tokenAddr == null || tokenAddr.isBlank()) throw new IllegalArgumentException("tokenAddress is required");
        if (tokenSymbol.isBlank()) throw new IllegalArgumentException("tokenSymbol is required");
        if (req.fromChainId() <= 0) throw new IllegalArgumentException("fromChainId is required");
        if (req.toChainId() <= 0) throw new IllegalArgumentException("toChainId is required");

        CcipBridgeRecord existing = repo.findByMessageId(messageId);
        if (existing != null) return existing;

        CcipBridgeRecord row = new CcipBridgeRecord();
        row.setId(UUID.randomUUID());
        row.setMessageId(messageId);
        row.setUserAddress(user);
        row.setTokenSymbol(tokenSymbol);
        row.setTokenAddress(tokenAddr);
        row.setFromChainId(req.fromChainId());
        row.setToChainId(req.toChainId());
        row.setSourceTxHash(normalizeHex(req.sourceTxHash()));

        try {
            repo.insert(row);
        } catch (DuplicateKeyException e) {
            CcipBridgeRecord raced = repo.findByMessageId(messageId);
            if (raced != null) return raced;
            throw e;
        }

        return repo.findByMessageId(messageId);
    }

    public CcipBridgeRecord findByMessageId(String messageId) {
        String mid = normalizeHex(messageId);
        if (mid == null || mid.isBlank()) throw new IllegalArgumentException("messageId is required");
        return repo.findByMessageId(mid);
    }

    public List<CcipBridgeRecord> findByUserAddress(String userAddress, int limit) {
        String u = normalizeAddress(userAddress);
        if (u == null || u.isBlank()) throw new IllegalArgumentException("userAddress is required");
        int lim = Math.min(200, Math.max(1, limit));
        return repo.findByUserAddress(u, lim);
    }

    public List<CcipBridgeRecord> search(String q, int limit) {
        if (q == null || q.isBlank()) throw new IllegalArgumentException("q is required");
        String v = normalizeHex(q);
        int lim = Math.min(200, Math.max(1, limit));

        if (MESSAGE_ID.matcher(v).matches()) {
            CcipBridgeRecord row = repo.findByMessageId(v);
            return row == null ? List.of() : List.of(row);
        }
        if (ADDRESS.matcher(v).matches()) {
            return repo.findByUserAddress(normalizeAddress(v), lim);
        }
        throw new IllegalArgumentException("q must be a userAddress (0x..40) or messageId (0x..64)");
    }

    private static String normalizeAddress(String addr) {
        return addr == null ? null : addr.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeHex(String hex) {
        if (hex == null) return null;
        String v = hex.trim();
        if (v.isBlank()) return v;
        return v.toLowerCase(Locale.ROOT);
    }
}
