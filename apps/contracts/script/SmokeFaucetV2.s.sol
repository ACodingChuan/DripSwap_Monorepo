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

/// @notice 真实链 Smoke 脚本：部署/配置/入金/签名/代付发起一次 claimWithSig()
/// @dev 依赖 RPC：运行时请使用 `FOUNDRY_PROFILE=rpc` 并提供 `RPC_URL`
///      这不是“生产部署脚本”，只是为了 P0-1 快速验证闭环。
contract SmokeFaucetV2 is DeployBase {
    using stdJson for string;

    // P0-0 口径（单一真相源）：name/version/singleAmount/pair rule/relayer min balance
    // - JSON 里金额用 string 表示，避免 JS number 精度问题；脚本里用 parseUint 转回 uint256
    string internal constant CALIB_PATH = "configs/faucetv2-calibration-v1.json";
    // EIP-712: Claim(address user,address token,uint256 amount,uint64 day,uint256 nonce,uint256 deadline,bool pass)
    bytes32 private constant CLAIM_TYPEHASH = keccak256(
        "Claim(address user,address token,uint256 amount,uint64 day,uint256 nonce,uint256 deadline,bool pass)"
    );

    // EIP-712 Domain: EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)
    bytes32 private constant DOMAIN_TYPEHASH = keccak256(
        "EIP712Domain(string name,string version,uint256 chainId,address verifyingContract)"
    );

    function _calibJson() internal returns (string memory json) {
        if (!vm.exists(CALIB_PATH)) revert("missing calib json");
        json = vm.readFile(CALIB_PATH);
    }

    function _calibSingleAmountRaw(string memory json, string memory symbol) internal returns (uint256) {
        string memory p = string.concat(".chains.", vm.toString(block.chainid), ".tokens.", symbol, ".singleAmountRaw");
        if (!json.keyExists(p)) return 0;
        return vm.parseUint(json.readString(p));
    }

    function _envPkOr(string memory key, uint256 fallbackPk) internal returns (uint256) {
        // 兼容两种写法：
        // - 带 0x 前缀：0xabc...
        // - 不带 0x：abc...（你现在的 .env 里就是这种）
        string memory raw = vm.envOr(key, string(""));
        if (bytes(raw).length == 0) return fallbackPk;
        if (bytes(raw).length >= 2 && bytes(raw)[0] == "0" && bytes(raw)[1] == "x") {
            return vm.parseUint(raw);
        }
        return vm.parseUint(string.concat("0x", raw));
    }

    function run() external {
        string memory calib = _calibJson();

        // ---- env ----
        // 约定：优先读 FAUCET_*；没填就回退到项目里已有的 DEPLOYER_PK（方便你只改 .env 文件，不手动 export 一堆变量）
        uint256 deployerPk = _envPkOr("DEPLOYER_PK", 0);

        uint256 adminPk = _envPkOr("FAUCET_ADMIN_PK", deployerPk);
        uint256 signerPk = _envPkOr("FAUCET_SIGNER_PK", deployerPk);
        uint256 relayerPk = _envPkOr("FAUCET_RELAYER_PK", deployerPk); // smoke 里可以先跟 admin 一样

        address admin = vm.addr(adminPk);
        address signer = vm.addr(signerPk);
        address treasury = vm.envOr("FAUCET_TREASURY", admin);

        // 口径：跟 specs/dripswap-mvp5-prd-v1.md 的 P0-0 保持一致
        string memory name_ = vm.envOr("FAUCET_EIP712_NAME", calib.readString(".eip712.name"));
        string memory version_ = vm.envOr("FAUCET_EIP712_VERSION", calib.readString(".eip712.version"));

        address faucetAddr = vm.envOr("FAUCET_ADDRESS", address(0));
        string memory tokenSymbol = vm.envOr("FAUCET_TOKEN_SYMBOL", string(""));
        address token = vm.envOr("FAUCET_TOKEN", address(0));
        if (bytes(tokenSymbol).length > 0) {
            token = _tokenAddress(tokenSymbol);
        }
        if (token == address(0)) revert("FAUCET_TOKEN(_SYMBOL) missing/invalid");

        // 入金与发放参数（raw）
        uint256 fundAmount = vm.envUint("FAUCET_FUND_AMOUNT_RAW");
        address user = vm.envOr("FAUCET_USER", admin);
        uint256 amount = vm.envOr("FAUCET_CLAIM_AMOUNT_RAW", uint256(0));
        if (amount == 0 && bytes(tokenSymbol).length > 0) {
            amount = _calibSingleAmountRaw(calib, tokenSymbol);
        }
        if (amount == 0) revert("FAUCET_CLAIM_AMOUNT_RAW missing (or provide FAUCET_TOKEN_SYMBOL)");
        uint256 nonce = vm.envUint("FAUCET_CLAIM_NONCE");
        uint256 deadlineSeconds = vm.envOr("FAUCET_CLAIM_DEADLINE_SECONDS", uint256(600));

        console2.log("admin:", admin);
        console2.log("signer:", signer);
        address relayer = vm.addr(relayerPk);
        console2.log("relayer:", relayer);
        console2.log("treasury:", treasury);
        console2.log("user:", user);
        console2.log("token:", token);
        if (bytes(tokenSymbol).length > 0) console2.log("tokenSymbol:", tokenSymbol);

        // P0-0：relayer ETH 余额低于阈值时“停签名/停发”
        uint256 relayerMinWei = vm.parseUint(calib.readString(".relayerMinNativeBalanceWei"));
        console2.log("relayerMinNativeBalanceWei:", relayerMinWei);
        if (relayer.balance < relayerMinWei) revert("RELAYER_ETH_LOW");

        // ---- deploy (optional) ----
        FaucetV2 faucet;
        if (faucetAddr == address(0)) {
            vm.startBroadcast(adminPk);
            faucet = new FaucetV2(admin, treasury, signer, name_, version_);
            vm.stopBroadcast();
            console2.log("Deployed FaucetV2:", address(faucet));
        } else {
            faucet = FaucetV2(payable(faucetAddr));
            console2.log("Using existing FaucetV2:", address(faucet));
        }

        // ---- config + fund ----
        vm.startBroadcast(adminPk);
        faucet.setTokenWhitelist(token, true);
        // smoke：给一个很大的 cap，避免误命中 OVER_DAILY_CAP
        faucet.setTokenDailyCap(token, type(uint256).max);
        vm.stopBroadcast();

        // fund：admin 把 token 转进 faucet（需要 admin 先持有 token）
        vm.startBroadcast(adminPk);
        require(IERC20(token).approve(address(faucet), fundAmount), "approve failed");
        faucet.fund(token, fundAmount);
        vm.stopBroadcast();
        console2.log("Funded token:", token, "amountRaw:", fundAmount);
        console2.log("Vault balanceRaw:", faucet.balanceOfToken(token));

        // ---- sign (offchain simulated in script) ----
        uint64 day = faucet.currentDay();
        uint256 deadline = block.timestamp + deadlineSeconds;

        FaucetV2.ClaimReq memory req = FaucetV2.ClaimReq({
            user: user,
            token: token,
            amount: amount,
            day: day,
            nonce: nonce,
            deadline: deadline,
            pass: false
        });

        bytes32 structHash = keccak256(
            abi.encode(CLAIM_TYPEHASH, req.user, req.token, req.amount, req.day, req.nonce, req.deadline, req.pass)
        );

        bytes32 domainSeparator = keccak256(
            abi.encode(
                DOMAIN_TYPEHASH, keccak256(bytes(name_)), keccak256(bytes(version_)), block.chainid, address(faucet)
            )
        );

        bytes32 digest = keccak256(abi.encodePacked("\x19\x01", domainSeparator, structHash));
        (uint8 v, bytes32 r, bytes32 s) = vm.sign(signerPk, digest);
        bytes memory sig = abi.encodePacked(r, s, v);

        // ---- relay claim ----
        vm.startBroadcast(relayerPk);
        faucet.claimWithSig(req, sig);
        vm.stopBroadcast();

        console2.log("Claimed to user:", user);
        console2.log("Remaining vault balanceRaw:", faucet.balanceOfToken(token));
    }
}
