package com.dripswap.bff.modules.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {
    
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    // In-memory storage for T0. In production, use Redis.
    private final Map<String, String> nonceStore = new ConcurrentHashMap<>();
    private final Map<String, String> sessionStore = new ConcurrentHashMap<>();
    
    private final SecureRandom secureRandom = new SecureRandom();

    public String generateNonce(String address) {
        // Simple random nonce
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String nonce = Numeric.toHexString(bytes);
        nonceStore.put(address.toLowerCase(), nonce);
        return nonce;
    }

    public String createSession(String address, String nonce, String signature) {
        String storedNonce = nonceStore.get(address.toLowerCase());
        
        if (storedNonce == null || !storedNonce.equals(nonce)) {
            throw new IllegalArgumentException("Invalid or expired nonce");
        }

        if (!verifySignature(address, nonce, signature)) {
            throw new IllegalArgumentException("Invalid signature");
        }

        // Verification successful
        String sessionId = UUID.randomUUID().toString();
        sessionStore.put(sessionId, address.toLowerCase());
        
        // Clean up nonce
        nonceStore.remove(address.toLowerCase());
        
        log.info("Session created for address: {}, sessionId: {}", address, sessionId);
        return sessionId;
    }

    public String getAddressBySessionId(String sessionId) {
        return sessionStore.get(sessionId);
    }

    private boolean verifySignature(String address, String nonce, String signature) {
        try {
            // This reconstructs the message that was signed.
            // Standard Ethereum "Sign Message" prefixes the message with specific header.
            // Web3j handles the recovery logic.
            
            // NOTE: The frontend will sign the raw nonce string. 
            // Web3j's Sign.signedMessageToKey expects the message to be the standard Ethereum message format?
            // Actually, we need to be careful about how the frontend signs. 
            // If using 'personal_sign', it adds the prefix.
            // Web3j has utilities for this.

            byte[] signatureBytes = Numeric.hexStringToByteArray(signature);
            
            byte v = signatureBytes[64];
            if (v < 27) { 
                v += 27; 
            }
            
            byte[] r = Arrays.copyOfRange(signatureBytes, 0, 32);
            byte[] s = Arrays.copyOfRange(signatureBytes, 32, 64);

            Sign.SignatureData signatureData = new Sign.SignatureData(v, r, s);
            
            // Iterate over possible public keys (recId 0-3) to recover the key
            // But simpler: use the pre-hash message
            // The message signed is likely the nonce string directly.
            
            // Let's use the standard prefix approach as 'personal_sign' does.
            String message = nonce; 
            // Using Web3j to recover public key from signature and message
            
            BigInteger publicKey = Sign.signedPrefixedMessageToKey(message.getBytes(), signatureData);
            String recoveredAddress = "0x" + Keys.getAddress(publicKey);
            
            log.info("Recovered address: {}, Expected: {}", recoveredAddress, address);
            
            return recoveredAddress.equalsIgnoreCase(address);
            
        } catch (Exception e) {
            log.error("Error verifying signature", e);
            return false;
        }
    }
}
