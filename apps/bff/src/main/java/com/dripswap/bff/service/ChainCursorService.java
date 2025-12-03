package com.dripswap.bff.service;

import com.dripswap.bff.modules.chains.model.ChainCursor;
import com.dripswap.bff.repository.ChainCursorRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for managing chain cursor (track last processed block)
 */
@Slf4j
@Service
@Transactional
public class ChainCursorService {

    private final ChainCursorRepository chainCursorRepository;

    public ChainCursorService(ChainCursorRepository chainCursorRepository) {
        this.chainCursorRepository = chainCursorRepository;
    }

    /**
     * Get last processed block number for a chain
     * Returns -1 if no cursor exists (start from block 0)
     */
    public Long getLastProcessed(String chainId) {
        var cursor = chainCursorRepository.findByChainId(chainId);
        if (cursor.isPresent()) {
            return cursor.get().getLastBlockNumber();
        }
        return -1L;
    }

    /**
     * Update cursor to the latest processed block number
     */
    public void updateCursor(String chainId, Long blockNumber) {
        var existingCursor = chainCursorRepository.findByChainId(chainId);
        ChainCursor cursor;

        if (existingCursor.isPresent()) {
            cursor = existingCursor.get();
            cursor.setLastBlockNumber(blockNumber);
        } else {
            cursor = new ChainCursor();
            cursor.setChainId(chainId);
            cursor.setLastBlockNumber(blockNumber);
        }

        chainCursorRepository.save(cursor);
    }

    /**
     * Initialize cursor for a chain if it doesn't exist
     */
    public ChainCursor initializeIfNotExists(String chainId, Long startBlockNumber) {
        var existing = chainCursorRepository.findByChainId(chainId);
        if (existing.isPresent()) {
            return existing.get();
        }

        ChainCursor newCursor = new ChainCursor();
        newCursor.setChainId(chainId);
        newCursor.setLastBlockNumber(startBlockNumber);
        ChainCursor saved = chainCursorRepository.save(newCursor);
        return saved;
    }
}
