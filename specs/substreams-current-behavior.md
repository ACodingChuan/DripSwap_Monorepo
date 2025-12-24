# Substreams 当前行为说明（DripSwap V2 实现）

## 1. 目的与范围

本文档描述 **apps/substream** 当前实现的“实际行为”，用于与 `specs/v2-subgraph-data-structure.md` 等设计文档对照。仅陈述现状，不评估优劣。

覆盖范围：
- Substreams 模块（map/store/graph_out）
- 事件提取与状态补齐
- 价格、TVL、成交量、费用计算
- Bridge 事件输出
- 多链与常量配置

---

## 2. 数据源与配置

**核心地址/常量**：
- Factory：`0x6c9258026a9272368e49bbb7d0a78c17bbe284bf`
- Oracle（ETH/USD）：Sepolia `0x694aa1769357215de4fac081bf1f309adc325306`，Scroll Sepolia `0x59f1ec1f10bd7ed9b938431086bc1d9e233ecf41`
- Bridge：`SEPOLIA_BRIDGE_ADDRESS` / `SCROLL_SEPOLIA_BRIDGE_ADDRESS`

**配置来源**：`apps/substream/src/constants.rs`

---

## 3. 模块总览与数据流

Substreams 的主要链路：

1. **map_pools_created**  
   读取 Factory 的 `PairCreated` 事件 → 生成 Pool(Pair) 元数据

2. **store_pools_created / store_pool_count / store_tokens**  
   将 Pool 写入 Store，计数 factory/pool/token

3. **map_tokens_whitelist_pools / store_tokens_whitelist_pools**  
   基于白名单和 Pool token 关系生成 `token:{addr}` → `whitelist_pools` 列表

4. **map_extract_data_types**  
   从区块日志提取 Swap/Mint/Burn/Sync/Transaction

5. **store_pool_sqrt_price / store_prices / store_pool_liquidities**  
   用 Sync 的 reserve0/1 计算价格与流动性

6. **store_native_amounts / store_token_tvl / store_derived_tvl**  
   用 reserves 推导 token/pool 的 TVL

7. **store_eth_prices / store_swaps_volume / store_total_tx_counts**  
   生成 ETH/USD、token 派生价、成交量/费用与 tx 计数

8. **graph_out（db.rs）**  
   把 Store 与 Events 写入实体表（18 张表）

---

## 4. 事件提取与状态补齐

### 4.1 map_pools_created

- 监听 Factory 的 `PairCreated` 事件。
- 生成 Pool：
  - `fee_tier=3000`（固定 0.3%）
  - `tick_spacing=0`（V2 无 tick）
  - token0/token1 元数据通过 RPC 查询补齐  
参考：`apps/substream/src/lib.rs:46`

### 4.2 map_extract_data_types

核心行为：
- 对每笔交易先扫描所有 Transfer，构建 **TransferContext**。
- 再逐条处理 log，按 pool 过滤：
  - `Sync` → 写入 PoolSqrtPrice（用 reserve0/1 填充）
  - `Swap/Mint/Burn` → 转换为 PoolEvent
  - 交易日志 → Transaction 记录  
参考：`apps/substream/src/lib.rs:164`

### 4.3 TransferContext 补齐逻辑

TransferContext 用于把 Mint/Burn 的 LP token 数量与接收方补齐：
- Mint：选择 `Transfer(from=0x0)` 且 `log_index <= Mint log_index` 的最后一笔
- Burn：优先匹配 `Transfer(pool→0x0)` 且 `log_index >= Burn log_index`，否则匹配 `Transfer(*→pool)`  
参考：`apps/substream/src/filtering.rs:20`

### 4.4 PoolEvent 生成规则

Swap：
- 使用 V2 的 `amount0_out - amount0_in` / `amount1_out - amount1_in` 得到净值
Mint/Burn：
- 直接读取事件 amount0/1
- LP token 的 `amount` 与 `owner` 来自 TransferContext  
参考：`apps/substream/src/filtering.rs:127`

### 4.5 Transaction 记录

仅在 Swap/Mint/Burn 时创建 Transaction 记录。  
参考：`apps/substream/src/filtering.rs:289`

---

## 5. 价格与 TVL

### 5.1 Sync → reserves → price

`Sync` 事件写入 `PoolSqrtPrice`：
- `sqrt_price = reserve0`
- `tick = reserve1`  
参考：`apps/substream/src/filtering.rs:268`

`store_prices` 读取 reserve0/1 计算：
```
token0Price = reserve0 / reserve1
token1Price = reserve1 / reserve0
```
并写入：
- `pool:{pool}:token0/token1`
- `pair:{token0}:{token1}`（供派生价）
- `PoolDayData/PoolHourData` 的 OHLC 基础  
参考：`apps/substream/src/lib.rs:238`

### 5.2 ETH/USD 与派生价

`store_eth_prices`：
- 优先读 Chainlink Oracle（latestRoundData + decimals）
- 失败回退 vETH/vUSDT 池价
- 写入：
  - `bundle`（ETH/USD）
  - `bundle:roundId`
  - `token:{addr}:dprice:eth`
  - `TokenDay/Hour/MinuteData` 的价格  
参考：`apps/substream/src/lib.rs:657`，`apps/substream/src/price.rs:303`

### 5.3 TVL

`store_native_amounts`：使用 Sync reserves 将 token 原生数量写入  
`store_token_tvl`：从 native reserves 增量累加  
`store_derived_tvl`：用派生价与 ETH/USD 计算 `totalValueLockedUSD/ETH`  
参考：`apps/substream/src/lib.rs:585`、`apps/substream/src/lib.rs:760`

---

## 6. 交易量与费用

### 6.1 txCount

`store_total_tx_counts` 对 Swap/Mint/Burn 事件统一累加：
```
pool / token / factory / day / hour / minute
```
参考：`apps/substream/src/lib.rs:369`

### 6.2 成交量与费用

`store_swaps_volume`：
- 取派生价与 ETH/USD，计算 tracked volume
- 按 fee_tier（0.3%）计算 fees  
参考：`apps/substream/src/lib.rs:417`，`apps/substream/src/utils.rs:132`

### 6.3 LiquidityProviderCount

当前逻辑：每次 Mint 都会把 `pool:{pool}:liquidityProviderCount` +1  
参考：`apps/substream/src/lib.rs:440`

---

## 7. EntityChanges 输出（graph_out）

### 7.1 核心实体

- Factory：使用 V2 Factory 地址作为 ID  
  参考：`apps/substream/src/db.rs:57`
- Bundle：写入 `ethPriceUSD` 与 `oracleRoundId`  
  参考：`apps/substream/src/db.rs:26`
- Pool/Token/Transaction/User：按 Store/Events 更新

### 7.2 Swap/Mint/Burn

Swap：写入 amount0/1、amountUSD、sqrtPriceX96/tick(占位)  
Mint：写入 amount0/1、amountUSD、owner/sender/origin、LP amount  
Burn：同上，额外写入 fee mint（若识别到）  
参考：`apps/substream/src/db.rs:1120`

### 7.3 Day/Hour/Minute 数据

基于 Store 的 min/max/close 等窗口写入 `TokenDay/Hour/MinuteData`、`PoolDay/HourData`、`UniswapDayData`。  
参考：`apps/substream/src/db.rs:1680`

### 7.4 Bridge

直接解析 Bridge 合约事件输出：
`TransferInitiated` → BridgeTransfer  
`TokenPoolRegistered/Removed`、`LimitsUpdated`、`PayMethodUpdated`、`ServiceFeeUpdated` → BridgeConfigEvent  
参考：`apps/substream/src/db.rs:1877`

---

## 8. 当前未实现/占位行为（现状）

- TokenMinuteData 归档（v2-tokens 的 minuteArray 机制）未实现
- Swap/Pool 的 `sqrtPriceX96/tick/feeGrowth` 在 V2 场景使用占位值

---

## 9. 对照阅读建议

建议与以下文档配合阅读：
- `specs/v2-subgraph-data-structure.md`
- `specs/v2-subgraph-data/substreams-migration-status.md`

---

## 10. 模块依赖图（Module Graph）

简化依赖关系（按主链路）：

```
map_pools_created
  -> store_pools_created
  -> store_pool_count
  -> store_tokens
  -> map_tokens_whitelist_pools -> store_tokens_whitelist_pools

map_extract_data_types (uses store_pools_created)
  -> store_pool_sqrt_price
  -> store_prices (uses store_pools_created)
  -> store_pool_liquidities (uses store_pools_created)
  -> store_native_amounts (uses store_pools_created)
  -> store_total_tx_counts
  -> store_eth_prices (uses store_pools_created, store_prices, store_tokens_whitelist_pools,
                        store_native_amounts, store_pool_liquidities)
  -> store_swaps_volume (uses store_pools_created, store_total_tx_counts, store_eth_prices)
  -> store_token_tvl (uses store_native_amounts, store_pools_created)
  -> store_derived_tvl (uses store_token_tvl, store_eth_prices, store_native_amounts, store_pools_created)
  -> store_derived_factory_tvl (uses store_derived_tvl)
  -> store_min_windows / store_max_windows (uses store_prices, store_eth_prices)

graph_out
  <- map_pools_created, map_extract_data_types
  <- store_* (deltas + last values)
```

完整输入依赖以 `substreams.yaml` 为准。

---

## 11. Store Key 字典（Key Patterns）

### 11.1 基础 Store

- `store_pools_created`
  - `pair:{pool}` -> Pool proto
- `store_tokens`
  - `token:{token}` -> Int64 计数
- `store_pool_count`
  - `factory:pairCount` -> BigInt
- `store_tokens_whitelist_pools`
  - `token:{token}` -> whitelist pools (append)

### 11.2 价格/储备/流动性

- `store_pool_sqrt_price`
  - `pool:{pool}` -> PoolSqrtPrice(reserve0/reserve1)
- `store_prices`
  - `pool:{pool}:{token0}:token0`
  - `pool:{pool}:{token1}:token1`
  - `pair:{token0}:{token1}` / `pair:{token1}:{token0}`
  - `PoolDayData:{day}:{pool}:token0|token1`
  - `PoolHourData:{hour}:{pool}:token0|token1`
- `store_pool_liquidities`
  - `pool:{pool}`
  - `pair:{token0}:{token1}` / `pair:{token1}:{token0}`
  - `PoolDayData:{day}:{pool}`
  - `PoolHourData:{hour}:{pool}`
- `store_native_amounts`
  - `pool:{pool}:{token}:native`

### 11.3 ETH/USD 与派生价

- `store_eth_prices`
  - `bundle` (ETH/USD)
  - `bundle:roundId`
  - `token:{token}:dprice:eth`
  - `TokenDayData:{day}:{token}` (priceUSD)
  - `TokenHourData:{hour}:{token}` (priceUSD)
  - `TokenMinuteData:{minute}:{token}` (priceUSD)

### 11.4 交易计数

- `store_total_tx_counts`
  - `pool:{pool}`
  - `token:{token}`
  - `factory:{factory}`
  - `UniswapDayData:{day}`
  - `PoolDayData:{day}:{pool}`
  - `PoolHourData:{hour}:{pool}`
  - `TokenDayData:{day}:{token}`
  - `TokenHourData:{hour}:{token}`
  - `TokenMinuteData:{minute}:{token}`

### 11.5 成交量与费用

- `store_swaps_volume`（按指标写入多维 keys）
  - pool 级：`pool:{pool}:volumeToken0|volumeToken1|volumeUSD|volumeUntrackedUSD|feesUSD`
  - token 级：`token:{token}:volume|volume:usd|volume:untrackedUSD|feesUSD`
  - factory 级：`factory:totalVolumeUSD|totalVolumeETH|untrackedVolumeUSD|totalFeesUSD|totalFeesETH`
  - day/hour/minute：
    - `UniswapDayData:{day}:volumeUSD|volumeETH|feesUSD`
    - `PoolDayData:{day}:{pool}:volumeUSD|feesUSD`
    - `PoolHourData:{hour}:{pool}:volumeUSD|feesUSD`
    - `TokenDayData:{day}:{token}:volume|volumeUSD|volume:untrackedUSD|feesUSD`
    - `TokenHourData:{hour}:{token}:volume|volumeUSD|volume:untrackedUSD|feesUSD`
    - `TokenMinuteData:{minute}:{token}:volume|volumeUSD|volume:untrackedUSD|feesUSD`

### 11.6 TVL

- `store_token_tvl`
  - `pool:{pool}:{token}:{token0|token1}`
  - `token:{token}`
- `store_derived_tvl`
  - token 级：
    - `token:{token}:totalValueLockedUSD`
    - `TokenDayData:{day}:{token}:totalValueLockedUSD`
    - `TokenHourData:{hour}:{token}:totalValueLockedUSD`
    - `TokenMinuteData:{minute}:{token}:totalValueLockedUSD`
  - pool 级：
    - `pool:{pool}:totalValueLockedETH`
    - `pool:{pool}:totalValueLockedUSD`
    - `pool:{pool}:totalValueLockedETHUntracked`
    - `pool:{pool}:totalValueLockedUSDUntracked`
    - `PoolDayData:{day}:{pool}:totalValueLockedUSD`
    - `PoolHourData:{hour}:{pool}:totalValueLockedUSD`
- `store_derived_factory_tvl`
  - `factory:totalValueLockedUSD`
  - `factory:totalValueLockedETH`
  - `factory:totalValueLockedUSDUntracked`
  - `factory:totalValueLockedETHUntracked`
  - `UniswapDayData:{day}:totalValueLockedUSD`

### 11.7 OHLC 窗口

- `store_min_windows` / `store_max_windows`  
  使用 `store_prices` 与 `store_eth_prices` 的 delta，生成 open/low/high/close：
  - `PoolDayData:{day}:{pool}:token0|token1` (open/low/high/close)
  - `PoolHourData:{hour}:{pool}:token0|token1` (open/low/high/close)
  - `TokenDayData:{day}:{token}` (open/low/high/close)
  - `TokenHourData:{hour}:{token}` (open/low/high/close)
  - `TokenMinuteData:{minute}:{token}` (open/low/high/close)
