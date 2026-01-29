// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {console2} from "forge-std/console2.sol";
import {stdJson} from "forge-std/StdJson.sol";
import {DeployBase} from "script/lib/DeployBase.s.sol";
import {FaucetV2} from "src/faucet/FaucetV2.sol";

interface IERC20 {
    function balanceOf(address) external view returns (uint256);
    function approve(address spender, uint256 amount) external returns (bool);
}

/// @notice P0-3：把各 token 库存打进 FaucetV2（approve + fund）
/// @dev 使用 env 控制每个 token 的入金数量（raw）：
/// - FAUCET_FUND_RAW__vUSDC=...
/// - FAUCET_FUND_RAW__vETH=...
/// 若不提供某 token，则跳过该 token（不会误转）。
contract FundFaucetV2Inventory is DeployBase {
    using stdJson for string;

    string internal constant CALIB_PATH = "configs/faucetv2-calibration-v1.json";

    function run() external {
        uint256 adminPk = vm.envUint("FAUCET_ADMIN_PK");
        address faucetAddr = vm.envOr("FAUCET_ADDRESS", _bookGetAddress("faucetv2.address"));
        if (faucetAddr == address(0)) revert("missing faucet address");

        FaucetV2 faucet = FaucetV2(payable(faucetAddr));
        console2.log("faucet:", faucetAddr);
        console2.log("chainId:", block.chainid);

        string memory calib = vm.readFile(CALIB_PATH);
        string memory chainKey = vm.toString(block.chainid);
        string[] memory symbols = vm.parseJsonKeys(calib, string.concat(".chains.", chainKey, ".tokens"));

        vm.startBroadcast(adminPk);

        for (uint256 i = 0; i < symbols.length; i++) {
            string memory sym = symbols[i];
            address token = _tokenAddress(sym);
            if (token == address(0)) revert(string.concat("missing token in address_book: ", sym));

            string memory envKey = string.concat("FAUCET_FUND_RAW__", sym);
            uint256 amount = vm.envOr(envKey, uint256(0));
            if (amount == 0) continue;

            uint256 bal = IERC20(token).balanceOf(vm.addr(adminPk));
            console2.log("fund tokenSymbol:", sym);
            console2.log("token:", token);
            console2.log("amountRaw:", amount);
            console2.log("adminBalRaw:", bal);
            require(IERC20(token).approve(faucetAddr, amount), "approve failed");
            faucet.fund(token, amount);
            console2.log("vaultBalRaw:", faucet.balanceOfToken(token));
        }

        vm.stopBroadcast();
    }
}
