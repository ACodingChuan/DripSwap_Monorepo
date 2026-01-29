package com.dripswap.bff.service;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.dripswap.bff.config.FaucetV2Calibration;
import com.dripswap.bff.config.FaucetV2Properties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

@Service
public class FaucetV2CalibrationService {
    private final FaucetV2Properties props;
    private final ObjectMapper objectMapper;

    private volatile FaucetV2Calibration cached;

    public FaucetV2CalibrationService(FaucetV2Properties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    public FaucetV2Calibration get() {
        FaucetV2Calibration v = cached;
        if (v != null) return v;
        synchronized (this) {
            if (cached == null) cached = load();
            return cached;
        }
    }

    public FaucetV2Calibration load() {
        String pathStr = props.getCalibrationPath();
        if (pathStr == null || pathStr.isBlank()) {
            throw new IllegalStateException("faucetv2.calibration-path is not set");
        }
        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            throw new IllegalStateException("calibration file not found: " + path.toAbsolutePath());
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(path));

            JsonNode eip712 = root.path("eip712");
            String name = eip712.path("name").asText();
            String version = eip712.path("version").asText();

            BigInteger relayerMinWei = new BigInteger(root.path("relayerMinNativeBalanceWei").asText());

            Map<Long, FaucetV2Calibration.ChainCalibration> chains = new HashMap<>();
            JsonNode chainsNode = root.path("chains");
            Iterator<String> chainIds = chainsNode.fieldNames();
            while (chainIds.hasNext()) {
                String chainIdStr = chainIds.next();
                long chainId = Long.parseLong(chainIdStr);
                JsonNode chainNode = chainsNode.path(chainIdStr);
                JsonNode tokensNode = chainNode.path("tokens");

                Map<String, FaucetV2Calibration.TokenCalibration> tokens = new HashMap<>();
                Iterator<String> tokenSyms = tokensNode.fieldNames();
                while (tokenSyms.hasNext()) {
                    String sym = tokenSyms.next();
                    JsonNode tok = tokensNode.path(sym);
                    int decimals = tok.path("decimals").asInt();
                    String human = tok.path("singleAmountHuman").asText();
                    BigInteger raw = new BigInteger(tok.path("singleAmountRaw").asText());
                    tokens.put(sym, new FaucetV2Calibration.TokenCalibration(decimals, human, raw));
                }

                chains.put(chainId, new FaucetV2Calibration.ChainCalibration(tokens));
            }

            return new FaucetV2Calibration(name, version, relayerMinWei, chains);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read calibration file: " + path.toAbsolutePath(), e);
        }
    }
}
