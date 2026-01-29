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

/// @notice 一键把「100 天库存」打进 FaucetV2（两条链分别跑一次）。
/// @dev 口径来自 specs/dripswap-mvp5-prd-v1.md（tokenDailyCap * 100days）。
///      - Sepolia: vUSDC/vUSDT/vDAI/vETH/vBTC/vLINK
///      - Scroll Sepolia: 额外 vSCR
///      若想改天数：FAUCET_FUND_DAYS=...（默认 100）。
contract FundFaucetV2Inventory100Days is DeployBase {
    using stdJson for string;

    string internal constant CALIB_PATH = "configs/faucetv2-calibration-v1.json";

    function run() external {
        uint256 adminPk = vm.envUint("FAUCET_ADMIN_PK");
        address faucetAddr = vm.envOr("FAUCET_ADDRESS", _bookGetAddress("faucetv2.address"));
        if (faucetAddr == address(0)) revert("missing faucet address");

        uint256 daysCount = vm.envOr("FAUCET_FUND_DAYS", uint256(100));
        console2.log("chainId:", block.chainid);
        console2.log("faucet:", faucetAddr);
        console2.log("fundDays:", daysCount);

        FaucetV2 faucet = FaucetV2(payable(faucetAddr));

        string memory calib = vm.readFile(CALIB_PATH);
        string memory chainKey = vm.toString(block.chainid);
        string[] memory symbols = vm.parseJsonKeys(calib, string.concat(".chains.", chainKey, ".tokens"));

        vm.startBroadcast(adminPk);

        for (uint256 i = 0; i < symbols.length; i++) {
            string memory sym = symbols[i];
            address token = _tokenAddress(sym);
            if (token == address(0)) revert(string.concat("missing token in address_book: ", sym));

            uint256 dailyCapRaw = _dailyCapRaw(sym);
            if (dailyCapRaw == 0) {
                console2.log("skip tokenSymbol(no plan):", sym);
                continue;
            }

            uint256 amount = dailyCapRaw * daysCount;
            uint256 bal = IERC20(token).balanceOf(vm.addr(adminPk));
            console2.log("fund tokenSymbol:", sym);
            console2.log("token:", token);
            console2.log("dailyCapRaw:", dailyCapRaw);
            console2.log("amountRaw:", amount);
            console2.log("adminBalRaw:", bal);
            require(bal >= amount, "insufficient admin token balance");

            require(IERC20(token).approve(faucetAddr, amount), "approve failed");
            faucet.fund(token, amount);
            console2.log("vaultBalRaw:", faucet.balanceOfToken(token));
        }

        vm.stopBroadcast();
    }

    /// @dev tokenDailyCap(raw) values:
    /// - vUSDC/vUSDT: 72,000/day * 1e6
    /// - vDAI: 72,000/day * 1e18
    /// - vETH: 18/day * 1e18
    /// - vBTC: 2.88/day * 1e8 = 288,000,000
    /// - vLINK: 7,200/day * 1e18
    /// - vSCR (Scroll only): 360/day * 1e18
    function _dailyCapRaw(string memory sym) internal pure returns (uint256) {
        bytes32 h = keccak256(bytes(sym));
        if (h == keccak256("vUSDC")) return 72_000 * 1e6;
        if (h == keccak256("vUSDT")) return 72_000 * 1e6;
        if (h == keccak256("vDAI")) return 72_000 * 1e18;
        if (h == keccak256("vETH")) return 18 * 1e18;
        if (h == keccak256("vBTC")) return 288_000_000; // 2.88 * 1e8
        if (h == keccak256("vLINK")) return 7_200 * 1e18;
        if (h == keccak256("vSCR")) return 360 * 1e18;
        return 0;
    }
}

