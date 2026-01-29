// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {console2} from "forge-std/console2.sol";
import {stdJson} from "forge-std/StdJson.sol";
import {DeployBase} from "script/lib/DeployBase.s.sol";
import {FaucetV2} from "src/faucet/FaucetV2.sol";

/// @notice P0-2：部署后对 FaucetV2 做基础配置（白名单 / cap / passPriceWei）
/// @dev 读取：
/// - FaucetV2 地址：address_book.md 的 faucetv2.address（或 env 覆盖）
/// - token 列表：configs/faucetv2-calibration-v1.json（按 chainId）
/// - token 地址：address_book.md 的 tokens.<SYM>.address
///
/// 默认行为（安全）：
/// - 只 set whitelist=true
/// - caps 只有显式给了 env 才会写（避免误覆盖已有 cap）
/// - passPriceWei 默认写 0（免费期）；可用 env 覆盖
contract ConfigureFaucetV2 is DeployBase {
    using stdJson for string;

    string internal constant CALIB_PATH = "configs/faucetv2-calibration-v1.json";

    function run() external {
        uint256 adminPk = vm.envUint("FAUCET_ADMIN_PK");
        address faucetAddr = vm.envOr("FAUCET_ADDRESS", _bookGetAddress("faucetv2.address"));
        if (faucetAddr == address(0)) revert("missing faucet address");

        string memory calib = vm.readFile(CALIB_PATH);
        string memory chainKey = vm.toString(block.chainid);

        // passPriceWei：默认 0（免费期）
        uint256 passPriceWei = vm.envOr("FAUCET_PASS_PRICE_WEI", uint256(0));

        // 可选：是否写 caps（默认 false）
        bool setCaps = vm.envOr("FAUCET_SET_CAPS", false);

        FaucetV2 faucet = FaucetV2(payable(faucetAddr));
        console2.log("faucet:", faucetAddr);
        console2.log("chainId:", block.chainid);
        console2.log("setCaps:", setCaps);
        console2.log("passPriceWei:", passPriceWei);

        // token symbols: calib.chains.<chainId>.tokens keys
        string[] memory symbols = _jsonKeys(string.concat(".chains.", chainKey, ".tokens"), calib);
        console2.log("tokenCount:", symbols.length);

        vm.startBroadcast(adminPk);

        for (uint256 i = 0; i < symbols.length; i++) {
            string memory sym = symbols[i];
            address token = _tokenAddress(sym);
            if (token == address(0)) revert(string.concat("missing token in address_book: ", sym));

            faucet.setTokenWhitelist(token, true);

            if (setCaps) {
                // cap raw from env, per token:
                // - FAUCET_TOKEN_DAILY_CAP_RAW__vUSDC=...
                // - FAUCET_TOKEN_DAILY_CAP_RAW__vETH=...
                string memory envKey = string.concat("FAUCET_TOKEN_DAILY_CAP_RAW__", sym);
                uint256 cap = vm.envOr(envKey, uint256(0));
                faucet.setTokenDailyCap(token, cap);
            }
        }

        // 免费期：强制设置为 0；如要开启买票期，再用 env 覆盖
        faucet.setPassPriceWei(passPriceWei);

        vm.stopBroadcast();
    }

    /// @dev StdJson 没有直接返回 object keys 的接口，这里用 "vm.parseJsonKeys"（Foundry cheatcode）
    function _jsonKeys(string memory path, string memory json) internal returns (string[] memory) {
        // forge-std: vm.parseJsonKeys exists on recent Foundry; fallback will revert if not supported.
        return vm.parseJsonKeys(json, path);
    }
}

