package com.dripswap.bff.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import com.dripswap.bff.config.FaucetV2Properties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class FaucetV2CalibrationServiceTest {
    @Test
    void loadsCalibrationJson() throws Exception {
        Path p = Files.createTempFile("calib", ".json");
        Files.writeString(p, """
            {
              "eip712": { "name": "DripFaucet", "version": "2.0" },
              "relayerMinNativeBalanceWei": "500000000000000000",
              "chains": {
                "11155111": { "tokens": { "vUSDC": { "decimals": 6, "singleAmountHuman": "200", "singleAmountRaw": "200000000" } } }
              }
            }
            """);

        FaucetV2Properties props = new FaucetV2Properties();
        props.setCalibrationPath(p.toString());
        FaucetV2CalibrationService svc = new FaucetV2CalibrationService(props, new ObjectMapper());
        var c = svc.load();

        assertEquals("DripFaucet", c.eip712Name());
        assertEquals("2.0", c.eip712Version());
        assertEquals("200", c.chains().get(11155111L).tokens().get("vUSDC").singleAmountHuman());
    }
}
