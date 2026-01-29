package com.dripswap.bff.config;

import java.math.BigInteger;
import java.util.Map;

public record FaucetV2Calibration(
        String eip712Name,
        String eip712Version,
        BigInteger relayerMinNativeBalanceWei,
        Map<Long, ChainCalibration> chains
) {
    public record ChainCalibration(
            Map<String, TokenCalibration> tokens
    ) {}

    public record TokenCalibration(
            int decimals,
            String singleAmountHuman,
            BigInteger singleAmountRaw
    ) {}
}
