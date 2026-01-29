# FaucetV2 P0-0 口径（v1）

本文件用于把 FaucetV2 的“硬口径”定死，避免前端 / 后端 / 部署脚本各写各的导致对不上。

权威配置（机器可读）：`apps/contracts/configs/faucetv2-calibration-v1.json`

对应 PRD：`specs/dripswap-mvp5-prd-v1.md` 的 `P0-0`。

## 1) EIP-712 Domain（部署参数）

- `name_ = "DripFaucet"`
- `version_ = "2.0"`

## 2) Single Amount（每次 Single 实际到账）

以 `faucetv2-calibration-v1.json` 为准（同时提供 human + raw）。

- Sepolia（chainId=11155111）：vUSDC / vUSDT / vDAI / vETH / vBTC / vLINK
- Scroll Sepolia（chainId=534351）：同上 + vSCR

## 3) Pair 规则（一次领两种）

- 用户从白名单里任选两种 token（必须不同，`tokenA != tokenB`）
- 每种 token 的数量 = 该 token 的 Single Amount

## 4) 停发/停签名条件（RELAYER_ETH_LOW）

- 当 relayer 的原生币余额 `< 0.5 ETH` 时：直接停签名（后端返回 `RELAYER_ETH_LOW`）

> 注意：链上 FaucetV2 合约本身不感知 relayer 余额；这是后端/签名服务的门禁规则。

