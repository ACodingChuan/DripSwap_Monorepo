package com.dripswap.bff.util;

import org.web3j.crypto.Credentials;
import org.web3j.utils.Numeric;

public final class EvmKeys {
    private EvmKeys() {}

    public static Credentials credentialsFromPrivateKey(String pk) {
        if (pk == null || pk.isBlank()) {
            throw new IllegalArgumentException("private key is empty");
        }
        String normalized = pk.trim();
        if (!Numeric.containsHexPrefix(normalized)) {
            normalized = "0x" + normalized;
        }
        // Numeric.cleanHexPrefix handles 0x and returns lower/upper agnostic.
        String cleaned = Numeric.cleanHexPrefix(normalized);
        return Credentials.create(cleaned);
    }
}
