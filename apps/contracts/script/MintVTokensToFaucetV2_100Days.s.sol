// SPDX-License-Identifier: MIT
pragma solidity ^0.8.24;

import {console2} from "forge-std/console2.sol";
import {DeployBase} from "script/lib/DeployBase.s.sol";

/// @notice 直接调用各 VToken.mint() 把「100 天库存」mint 到 FaucetV2 合约地址。
/// @dev VToken.mint 需要 BRIDGE_ROLE；脚本会确保 admin 拥有该 role（必要时临时 grant，并可选择 revoke）。
///      - Sepolia faucet: 0xD9F31b482E843bb06185832ad0Eb8b2A4e7098F9
///      - Scroll Sepolia faucet: 0xafAdf833574c27C6f0f960a7A7EB032b25967cE8
///      Token addresses are hardcoded from your message.
contract MintVTokensToFaucetV2_100Days is DeployBase {
    // chainIds
    uint256 internal constant CHAIN_SEPOLIA = 11155111;
    uint256 internal constant CHAIN_SCROLL_SEPOLIA = 534351;

    // faucets
    address internal constant FAUCET_SEPOLIA = 0xD9F31b482E843bb06185832ad0Eb8b2A4e7098F9;
    address internal constant FAUCET_SCROLL = 0xBd64eD49edf5dAdB4b5931c4190FC005C88F33eE;

    // tokens (shared)
    address internal constant vETH = 0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D; // 18
    address internal constant vUSDT = 0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7; // 6
    address internal constant vUSDC = 0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D; // 6
    address internal constant vDAI = 0x0C156E2F45a812ad743760A88d73fB22879BC299; // 18
    address internal constant vBTC = 0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6; // 8
    address internal constant vLINK = 0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454; // 18
    // Scroll-only
    address internal constant vSCR = 0x4911Fb3923F6DA0cd4920F914991B0A742d88Bfd; // 18

    // 100 days inventory (raw)
    uint256 internal constant USDC_100D = 7_200_000 * 1e6;
    uint256 internal constant USDT_100D = 7_200_000 * 1e6;
    uint256 internal constant DAI_100D = 7_200_000 * 1e18;
    uint256 internal constant ETH_100D = 1_800 * 1e18;
    uint256 internal constant BTC_100D = 288 * 1e8;
    uint256 internal constant LINK_100D = 720_000 * 1e18;
    uint256 internal constant SCR_100D = 36_000 * 1e18;

    function run() external {
        uint256 adminPk = vm.envOr("FAUCET_ADMIN_PK", uint256(0));
        if (adminPk == 0) {
            // Fallback to common deploy key name used in contracts/.env.*
            adminPk = vm.envUint("DEPLOYER_PK");
        }
        address admin = vm.addr(adminPk);
        bool revokeAfter = vm.envOr("FAUCET_REVOKE_MINTER", false);

        address faucet = _faucetForChain(block.chainid);
        console2.log("chainId:", block.chainid);
        console2.log("admin:", admin);
        console2.log("faucet:", faucet);
        console2.log("revokeAfter:", revokeAfter);

        vm.startBroadcast(adminPk);

        _mintWithRole(vUSDC, faucet, USDC_100D, "vUSDC", admin, revokeAfter);
        _mintWithRole(vUSDT, faucet, USDT_100D, "vUSDT", admin, revokeAfter);
        _mintWithRole(vDAI, faucet, DAI_100D, "vDAI", admin, revokeAfter);
        _mintWithRole(vETH, faucet, ETH_100D, "vETH", admin, revokeAfter);
        _mintWithRole(vBTC, faucet, BTC_100D, "vBTC", admin, revokeAfter);
        _mintWithRole(vLINK, faucet, LINK_100D, "vLINK", admin, revokeAfter);
        if (block.chainid == CHAIN_SCROLL_SEPOLIA) {
            _mintWithRole(vSCR, faucet, SCR_100D, "vSCR", admin, revokeAfter);
        }

        vm.stopBroadcast();
    }

    function _faucetForChain(uint256 chainId) internal pure returns (address) {
        if (chainId == CHAIN_SEPOLIA) return FAUCET_SEPOLIA;
        if (chainId == CHAIN_SCROLL_SEPOLIA) return FAUCET_SCROLL;
        revert("unsupported chainId");
    }

    function _mintWithRole(
        address token,
        address to,
        uint256 amountRaw,
        string memory sym,
        address admin,
        bool revokeAfter
    ) internal {
        bytes32 role = IVToken(token).BRIDGE_ROLE();
        bool had = IVToken(token).hasRole(role, admin);
        if (!had) {
            console2.log("grant BRIDGE_ROLE -> admin for", sym);
            IVToken(token).grantRole(role, admin);
        }

        uint256 beforeBal = IVToken(token).balanceOf(to);
        console2.log("mint", sym);
        console2.log("token:", token);
        console2.log("to:", to);
        console2.log("amountRaw:", amountRaw);
        IVToken(token).mint(to, amountRaw);
        uint256 afterBal = IVToken(token).balanceOf(to);
        console2.log("vaultBalBefore:", beforeBal);
        console2.log("vaultBalAfter:", afterBal);

        if (!had && revokeAfter) {
            console2.log("revoke BRIDGE_ROLE from admin for", sym);
            IVToken(token).revokeRole(role, admin);
        }
    }
}

interface IVToken {
    function BRIDGE_ROLE() external view returns (bytes32);
    function hasRole(bytes32 role, address account) external view returns (bool);
    function grantRole(bytes32 role, address account) external;
    function revokeRole(bytes32 role, address account) external;
    function mint(address to, uint256 amount) external;
    function balanceOf(address) external view returns (uint256);
}

