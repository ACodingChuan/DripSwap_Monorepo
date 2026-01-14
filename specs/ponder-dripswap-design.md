# DripSwap Ponder 索引设计说明（从 0 到 1）

> **文档目的**：完整描述 Ponder 版索引方案的架构、事件处理流程、数据模型与字段更新逻辑，便于新同学不读代码即可检视逻辑。
>
> **适用范围**：仅覆盖 Ponder 索引层（链上事件 → Postgres）。不包含 BFF/前端适配。
>
> **最后更新**：2025-01-01

---

## 目录

1. [总体概述](#总体概述)
2. [系统组成与文件索引](#系统组成与文件索引)
3. [数据流与事件顺序](#数据流与事件顺序)
4. [全局约定与类型映射](#全局约定与类型映射)
5. [链与合约配置](#链与合约配置)
6. [常量与地址表](#常量与地址表)
7. [运行时状态与缓存](#运行时状态与缓存)
8. [价格与数值计算机制](#价格与数值计算机制)
9. [核心实体 (Core Entities)](#核心实体-core-entities)
10. [事件实体 (Event Entities)](#事件实体-event-entities)
11. [时间聚合实体 (Time Aggregation Entities)](#时间聚合实体-time-aggregation-entities)
12. [初始化与更新流程（按 Handler）](#初始化与更新流程按-handler)
13. [事件-实体更新矩阵](#事件-实体更新矩阵)
14. [相对 v2 Subgraph 的主要修改](#相对-v2-subgraph-的主要修改)

---

## 总体概述

该索引方案使用 **Ponder** 替换 **The Graph Subgraph**，实现 Uniswap V2 核心事件与统计数据的索引。目标是做到：

- 逻辑 1:1 迁移（尽量保持 v2 subgraph 行为）
- 类型映射严格统一（raw bigint、派生 text 普通十进制字符串）
- 多链隔离（所有表含 chainId，复合主键）
- 事件定位字段完整（block/tx/log）

当前索引共 **15 张表**（核心状态 + 事件明细 + 时间聚合 + PairTokenLookup），已移除 Bridge 相关表与 Transaction 表。

---

## 系统组成与文件索引

### 关键文件

- `apps/ponder/dripswap/ponder.config.ts`
  - 链配置（Sepolia / Scroll Sepolia）
  - 合约注册（Factory/Pair），使用 factory pattern

- `apps/ponder/dripswap/ponder.schema.ts`
  - 15 张表定义
  - 关系定义（Token ↔ Pair、Pair ↔ Events、Token ↔ TimeSeries 等）

- `apps/ponder/dripswap/src/index.ts`
  - 所有 handler 的业务逻辑
  - 事件顺序依赖、pending mint/burn 机制

- `apps/ponder/dripswap/src/pricing.ts`
  - ETH/USD Oracle 读取
  - tracked volume / liquidity 规则
  - findEthPerToken（依赖 pair_token_lookup）

- `apps/ponder/dripswap/src/fixedPoint.ts`
  - Decimal 小数运算（不做 1e18 缩放）

- `apps/ponder/dripswap/src/token.ts`
  - Token 元数据读取（ERC20 + bytes fallback）

- `apps/ponder/dripswap/src/constants.ts`
  - 地址、白名单、稳定币、阈值

---

## 数据流与事件顺序

### 1) 事件顺序保证

Ponder 在每条链内按 **EVM 执行顺序**调用 handler：

```
(blockNumber, transactionIndex, logIndex)
```

这保证了：

- 事件 ID 使用 `txHash-logIndex` 可稳定复现
- 不再需要 Transaction 表管理事件数组
- Mint/Burn 可用内存 pending 队列拼接

### 2) 数据流简图

```
链上事件 (Factory/Pair)
    ↓
Ponder handler (index.ts)
    ↓
业务计算 (pricing.ts / fixedPoint.ts)
    ↓
写入 Postgres (ponder schema)
```

---

## 全局约定与类型映射

### 1) 类型映射（强制）

| 类型类别 | Ponder 存储类型 | 说明 |
|---|---|---|
| 链上 raw 整数 | `bigint` | reserve/amount/liquidity/totalSupply |
| 派生小数值 | `text` | 统一保存为 **普通十进制** 字符串 |
| 地址 / bytes | `hex` | 统一小写 |

### 2) 多链主键

所有表包含 `chainId` 字段，主键为 `(chainId, id)`。

### 3) 事件定位字段

Mint/Burn/Swap 必填：
- `blockNumber`
- `blockTimestamp`

- `transactionHash`
- `transactionIndex`
- `logIndex`

事件 ID：

```
${transactionHash}-${logIndex}
```

---

## 链与合约配置

### 1) 支持链

- Sepolia: `chainId = 11155111`
- Scroll Sepolia: `chainId = 534351`

### 2) RPC 环境变量

- `PONDER_SEPOLIA_RPC_URL`
- `PONDER_SCROLL_SEPOLIA_RPC_URL`

### 3) 合约注册

| 合约 | 作用 | 监听事件 |
|---|---|---|
| Factory | Pair 工厂 | PairCreated |
| Pair | 动态实例 | Transfer / Sync / Mint / Burn / Swap |

---

## 常量与地址表

常量定义来源：`apps/ponder/dripswap/src/constants.ts`。

关键常量：

- `FACTORY_ADDRESS`：工厂合约地址
- `REFERENCE_TOKEN`：vETH
- `ORACLE_ETH_USD_SEPOLIA` / `ORACLE_ETH_USD_SCROLL`
- `WHITELIST`：白名单 token
- `STABLECOINS`：稳定币列表
- `MINIMUM_USD_THRESHOLD_NEW_PAIRS = 1000`
- `MINIMUM_LIQUIDITY_THRESHOLD_ETH = 0.001`

这些常量会影响：

- `findEthPerToken` 路径选择
- `getTrackedVolumeUSD` 的过滤门槛
- `getTrackedLiquidityUSD` 的统计范围

---

## 运行时状态与缓存

### 1) pendingMints / pendingBurns

用于替代 Transaction 表拼接 mint/burn。

结构：

- `pendingMints: Map<chainId:txHash, PendingMint[]>`
- `pendingBurns: Map<chainId:txHash, PendingBurn[]>`

行为：

- `Transfer(from=0x0)`：视为 LP mint，入队 pendingMints
- `Transfer(to=pair)`：视为 burn 前置 transfer，入队 pendingBurns
- `Mint`：消费 pendingMints
- `Burn`：消费 pendingBurns，并检查 pendingMints 作为 fee mint

### 2) latestEthPrice

- `Map<chainId, { price, roundId }>`
- Sync 事件强制更新并写 bundle
- Swap/Mint/Burn 使用 cache（若存在）

---

## 价格与数值计算机制

### 1) Decimal 字符串约定

- 不做 1e18 放大/缩放，直接用 Decimal 做小数运算
- 所有派生数值写入 `text`，内容为**普通十进制字符串**
- `formatWad/parseWad` 名称保留，但语义是“格式化/解析十进制字符串”

常用函数：

- `scaleToWad(raw, decimals)`
- `mulWad(a,b)`
- `divWad(a,b)`
- `formatWad(value)`
- `parseWad(text)`

### 2) Oracle 读取

读取 Chainlink Aggregator：

- `latestRoundData` + `decimals`
- answer <= 0 → 0
- 按 oracle decimals 转换为正常十进制

### 3) derivedAmountEth

```
amount0Wad = scaleToWad(amount0, token0.decimals)
amount1Wad = scaleToWad(amount1, token1.decimals)

derived0 = amount0Wad * token0.derivedETH
derived1 = amount1Wad * token1.derivedETH

if derived0 <= ALMOST_ZERO or derived1 <= ALMOST_ZERO:
  derivedAmountEth = derived0 + derived1
else:
  derivedAmountEth = (derived0 + derived1) / 2
```

### 4) tracked 逻辑

- `getTrackedVolumeUSD`：仅对白名单 token 的交易计入 tracked
- 新 pair (liquidityProviderCount < 5) 需满足最小 USD 阈值
- `getTrackedLiquidityUSD`：与 subgraph 逻辑一致

### 5) findEthPerToken

逻辑顺序：

1. `REFERENCE_TOKEN` → 1 ETH
2. `STABLECOINS` → 1 / ethPrice
3. 遍历 `WHITELIST`，从 `pair_token_lookup` 找 pair
4. `reserveETH` 超阈值才返回派生价格

---

## 核心实体 (Core Entities)

### 1. UniswapFactory

**描述**：工厂合约全局统计。

**字段与更新逻辑**：

| 字段 | 类型 | 含义 | 初始化 | 更新时机 | 规则 |
|---|---|---|---|---|---|
| chainId | integer | 链 ID | 创建时 | - | 固定 |
| id | hex | Factory 地址 | 创建时 | - | 固定 |
| pairCount | bigint | Pair 数量 | 0 | PairCreated | +1 |
| totalVolumeUSD | text | tracked USD 交易量 | "0" | Swap | += trackedAmountUsd |
| totalVolumeETH | text | tracked ETH 交易量 | "0" | Swap | += trackedAmountEth |
| untrackedVolumeUSD | text | untracked USD | "0" | Swap | += derivedAmountUsd |
| totalLiquidityUSD | text | tracked USD 流动性 | "0" | Sync | totalLiquidityETH * ethPrice |
| totalLiquidityETH | text | tracked ETH 流动性 | "0" | Sync | oldTotal - oldPairTracked + newPairTracked |
| txCount | bigint | 交易数 | 0 | Mint/Burn/Swap | +1 |

**初始化**：首次 PairCreated 时创建并赋初始值 0。

---

### 2. Bundle

**描述**：Oracle 价格快照，Ponder 采用追加式写入。

| 字段 | 类型 | 含义 | 更新时机 | 规则 |
|---|---|---|---|---|
| chainId | integer | 链 ID | Oracle 读取时 | 固定 |
| id | text | 唯一 id | Oracle 读取时 | `${blockNumber}-${logIndex}` |
| ethPrice | text | ETH/USD（普通十进制字符串） | Oracle 读取时 | Oracle 返回 |
| oracleRoundId | bigint | Oracle roundId | Oracle 读取时 | Oracle 返回 |

---

### 3. Token

**描述**：代币元数据与全局统计。

| 字段 | 类型 | 含义 | 初始化 | 更新时机 | 规则 |
|---|---|---|---|---|---|
| chainId | integer | 链 ID | PairCreated | - | 固定 |
| id | hex | Token 地址 | PairCreated | - | 固定 |
| symbol | text | 符号 | ERC20 | - | 固定 |
| name | text | 名称 | ERC20 | - | 固定 |
| decimals | bigint | 精度 | ERC20 | - | 固定 |
| totalSupply | bigint | 总发行量 | ERC20 | 当前不更新 | 固定 |
| tradeVolume | bigint | raw 交易量 | 0 | Swap | += amountIn+amountOut |
| tradeVolumeUSD | text | tracked USD | "0" | Swap | += trackedAmountUsd |
| untrackedVolumeUSD | text | untracked USD | "0" | Swap | += derivedAmountUsd |
| txCount | bigint | 交易次数 | 0 | Mint/Burn/Swap | +1 |
| totalLiquidity | bigint | raw 流动性 | 0 | Sync | old - prevReserve + newReserve |
| derivedETH | text | 兑换 ETH | "0" | Sync | findEthPerToken |

---

### 4. Pair

**描述**：交易对状态与统计。

| 字段 | 类型 | 含义 | 初始化 | 更新时机 | 规则 |
|---|---|---|---|---|---|
| chainId | integer | 链 ID | PairCreated | - | 固定 |
| id | hex | Pair 地址 | PairCreated | - | 固定 |
| token0 | hex | token0 | PairCreated | - | 固定 |
| token1 | hex | token1 | PairCreated | - | 固定 |
| reserve0 | bigint | reserve0 raw | 0 | Sync | event.reserve0 |
| reserve1 | bigint | reserve1 raw | 0 | Sync | event.reserve1 |
| totalSupply | bigint | LP 总量 | 0 | Transfer | +value / -value |
| reserveETH | text | reserveETH | "0" | Sync | reserve0*derivedETH + reserve1*derivedETH |
| reserveUSD | text | reserveUSD | "0" | Sync | reserveETH * ethPrice |
| trackedReserveETH | text | trackedReserveETH | "0" | Sync | getTrackedLiquidityUSD / ethPrice |
| token0Price | text | token0/token1 | "0" | Sync | reserve0Wad / reserve1Wad |
| token1Price | text | token1/token0 | "0" | Sync | reserve1Wad / reserve0Wad |
| volumeToken0 | bigint | token0 volume | 0 | Swap | += amount0Total |
| volumeToken1 | bigint | token1 volume | 0 | Swap | += amount1Total |
| volumeUSD | text | tracked volume | "0" | Swap | += trackedAmountUsd |
| untrackedVolumeUSD | text | untracked volume | "0" | Swap | += derivedAmountUsd |
| txCount | bigint | 交易次数 | 0 | Mint/Burn/Swap | +1 |
| createdAtTimestamp | bigint | 创建时间 | PairCreated | - | 固定 |
| createdAtBlockNumber | bigint | 创建区块 | PairCreated | - | 固定 |
| liquidityProviderCount | bigint | LP 数量 | 0 | 当前未实现 | 固定 |

---

### 5. PairTokenLookup

**描述**：tokenA-tokenB → pair 的索引表，用于 findEthPerToken。

| 字段 | 类型 | 含义 | 初始化 | 更新时机 | 规则 |
|---|---|---|---|---|---|
| chainId | integer | 链 ID | PairCreated | - | 固定 |
| id | text | tokenA-tokenB | PairCreated | - | 固定 |
| pair | hex | Pair 地址 | PairCreated | - | 固定 |

---

### 6. User

**描述**：用户地址集合（存在即记录）。

| 字段 | 类型 | 含义 | 更新时机 | 规则 |
|---|---|---|---|---|
| chainId | integer | 链 ID | Transfer | 固定 |
| id | hex | 用户地址 | Transfer from/to | 保证存在 |

---

## 事件实体 (Event Entities)

### 1. Mint

**描述**：添加流动性事件明细。

| 字段 | 类型 | 含义 | 更新逻辑 |
|---|---|---|---|
| chainId | integer | 链 ID | ctx.chain.id |
| id | text | txHash-logIndex | 事件生成 |
| timestamp | bigint | block timestamp | event.block.timestamp |
| blockNumber | bigint | block number | event.block.number |
| blockTimestamp | bigint | block timestamp | event.block.timestamp |
| transactionHash | hex | tx hash | event.transaction.hash |
| transactionIndex | integer | tx index | event.log.transactionIndex |
| logIndex | integer | log index | event.log.logIndex |
| pair | hex | pair address | event.log.address |
| to | hex | LP 接收方 | pendingMint.to 或 tx.from |
| liquidity | bigint | LP 数量 | pendingMint.liquidity |
| sender | hex | sender | event.args.sender |
| amount0 | bigint | amount0 raw | event.args.amount0 |
| amount1 | bigint | amount1 raw | event.args.amount1 |
| amountUSD | text | USD 价值 | (amount0*derivedETH + amount1*derivedETH) * ethPrice |
| feeTo | hex | fee 接收方 | null |
| feeLiquidity | bigint | fee LP | null |

---

### 2. Burn

**描述**：移除流动性事件明细（包含 fee mint 信息）。

| 字段 | 类型 | 含义 | 更新逻辑 |
|---|---|---|---|
| chainId | integer | 链 ID | ctx.chain.id |
| id | text | txHash-logIndex | 事件生成 |
| timestamp | bigint | block timestamp | event.block.timestamp |
| blockNumber | bigint | block number | event.block.number |
| blockTimestamp | bigint | block timestamp | event.block.timestamp |
| transactionHash | hex | tx hash | event.transaction.hash |
| transactionIndex | integer | tx index | event.log.transactionIndex |
| logIndex | integer | log index | event.log.logIndex |
| pair | hex | pair address | event.log.address |
| liquidity | bigint | LP 数量 | pendingBurn.liquidity |
| sender | hex | sender | event.args.sender |
| amount0 | bigint | amount0 raw | event.args.amount0 |
| amount1 | bigint | amount1 raw | event.args.amount1 |
| to | hex | 接收方 | event.args.to |
| amountUSD | text | USD 价值 | (amount0*derivedETH + amount1*derivedETH) * ethPrice |
| feeTo | hex | fee 接收方 | pendingMint.to |
| feeLiquidity | bigint | fee LP | pendingMint.liquidity |

---

### 3. Swap

**描述**：交易事件明细。

| 字段 | 类型 | 含义 | 更新逻辑 |
|---|---|---|---|
| chainId | integer | 链 ID | ctx.chain.id |
| id | text | txHash-logIndex | 事件生成 |
| timestamp | bigint | block timestamp | event.block.timestamp |
| blockNumber | bigint | block number | event.block.number |
| blockTimestamp | bigint | block timestamp | event.block.timestamp |
| transactionHash | hex | tx hash | event.transaction.hash |
| transactionIndex | integer | tx index | event.log.transactionIndex |
| logIndex | integer | log index | event.log.logIndex |
| pair | hex | pair address | event.log.address |
| sender | hex | sender | event.args.sender |
| from | hex | tx.from | event.transaction.from |
| to | hex | to | event.args.to |
| amount0In | bigint | amount0In | event.args.amount0In |
| amount1In | bigint | amount1In | event.args.amount1In |
| amount0Out | bigint | amount0Out | event.args.amount0Out |
| amount1Out | bigint | amount1Out | event.args.amount1Out |
| amountUSD | text | USD 价值 | trackedAmountUsd 或 derivedAmountUsd |

---

## 时间聚合实体 (Time Aggregation Entities)

### 1. UniswapDayData

| 字段 | 类型 | 含义 | 更新逻辑 |
|---|---|---|---|
| chainId | integer | 链 ID | ctx.chain.id |
| id | text | dayId | dayId.toString() |
| date | integer | dayStart | dayStart |
| dailyVolumeETH | text | 当日 ETH 交易量 | Swap 累加 |
| dailyVolumeUSD | text | 当日 USD 交易量 | Swap 累加 |
| dailyVolumeUntracked | text | 当日 untracked | Swap 累加 |
| totalVolumeETH | text | 总 ETH 交易量 | 当前实现不更新 |
| totalVolumeUSD | text | 总 USD 交易量 | 当前实现不更新 |
| totalLiquidityETH | text | 全局流动性 | 从 factory 同步 |
| totalLiquidityUSD | text | 全局流动性 | 从 factory 同步 |
| txCount | bigint | 全局交易数 | 从 factory 同步 |

### 2. PairDayData

| 字段 | 类型 | 含义 | 更新逻辑 |
|---|---|---|---|
| chainId | integer | 链 ID | ctx.chain.id |
| id | text | pairId-dayId | `${pair}-${dayId}` |
| date | integer | dayStart | dayStart |
| pairAddress | hex | pair address | pair.id |
| token0 | hex | token0 | pair.token0 |
| token1 | hex | token1 | pair.token1 |
| reserve0 | bigint | reserve0 | pair.reserve0 |
| reserve1 | bigint | reserve1 | pair.reserve1 |
| totalSupply | bigint | totalSupply | pair.totalSupply |
| reserveUSD | text | reserveUSD | pair.reserveUSD |
| dailyVolumeToken0 | bigint | token0 volume | Swap 累加 |
| dailyVolumeToken1 | bigint | token1 volume | Swap 累加 |
| dailyVolumeUSD | text | tracked USD | Swap 累加 |
| dailyTxns | bigint | tx count | Mint/Burn/Swap +1 |

### 3. PairHourData

| 字段 | 类型 | 含义 | 更新逻辑 |
|---|---|---|---|
| chainId | integer | 链 ID | ctx.chain.id |
| id | text | pairId-hourIndex | `${pair}-${hourIndex}` |
| hourStartUnix | integer | hourStart | hourStart |
| pair | hex | pair | pair.id |
| reserve0 | bigint | reserve0 | pair.reserve0 |
| reserve1 | bigint | reserve1 | pair.reserve1 |
| totalSupply | bigint | totalSupply | pair.totalSupply |
| reserveUSD | text | reserveUSD | pair.reserveUSD |
| hourlyVolumeToken0 | bigint | token0 volume | Swap 累加 |
| hourlyVolumeToken1 | bigint | token1 volume | Swap 累加 |
| hourlyVolumeUSD | text | tracked USD | Swap 累加 |
| hourlyTxns | bigint | tx count | Mint/Burn/Swap +1 |

### 4. TokenDayData

| 字段 | 类型 | 含义 | 更新逻辑 |
|---|---|---|---|
| chainId | integer | 链 ID | ctx.chain.id |
| id | text | tokenId-dayId | `${token}-${dayId}` |
| date | integer | dayStart | dayStart |
| token | hex | token | token.id |
| dailyVolumeToken | bigint | token volume | Swap 累加 |
| dailyVolumeETH | text | ETH volume | Swap 累加 |
| dailyVolumeUSD | text | USD volume | Swap 累加 |
| dailyTxns | bigint | tx count | Mint/Burn/Swap +1 |
| totalLiquidityToken | bigint | token liquidity | token.totalLiquidity |
| totalLiquidityETH | text | ETH liquidity | totalLiquidityToken * derivedETH |
| totalLiquidityUSD | text | USD liquidity | totalLiquidityETH * ethPrice |
| priceUSD | text | token price | derivedETH * ethPrice |

### 5. TokenHourData

| 字段 | 类型 | 含义 | 更新逻辑 |
|---|---|---|---|
| chainId | integer | 链 ID | ctx.chain.id |
| id | text | tokenId-hourIndex | `${token}-${hourIndex}` |
| periodStartUnix | integer | hourStart | hourStart |
| token | hex | token | token.id |
| volume | bigint | 交易量 | 覆盖 = token.tradeVolume |
| volumeUSD | text | tracked USD | 覆盖 = token.tradeVolumeUSD |
| untrackedVolumeUSD | text | untracked USD | 覆盖 = token.untrackedVolumeUSD |
| totalValueLocked | bigint | TVL | 固定 0 |
| totalValueLockedUSD | text | TVL USD | 固定 0 |
| priceUSD | text | token price | derivedETH * ethPrice |
| feesUSD | text | fees | 固定 0 |
| open | text | 开盘价 | 首次 = priceUSD |
| high | text | 最高价 | max(old, priceUSD) |
| low | text | 最低价 | min(old, priceUSD) |
| close | text | 收盘价 | priceUSD |

### 6. TokenMinuteData

逻辑与 TokenHourData 相同，仅时间粒度为 minute。

---

## 初始化与更新流程（按 Handler）

以下流程按 Ponder handler 顺序展开，包含触发条件、核心步骤、写入表与关键派生逻辑。

### 1) `Factory:PairCreated`

**触发条件**：Factory 合约 `PairCreated` 事件。

**输入**：

- `token0`, `token1`, `pair`
- `blockNumber`, `timestamp`

**步骤**：

1. `getOrCreateFactory()`
   - 若不存在，初始化 `uniswap_factory`，各统计字段设 0。

2. 更新 `uniswap_factory.pairCount += 1`

3. `getOrCreateToken(token0)`
   - 读取 ERC20 symbol/name/decimals/totalSupply

4. `getOrCreateToken(token1)`

5. 初始化 `pair`：
   - reserve/price/volume = 0
   - createdAtTimestamp/BlockNumber = event block
   - liquidityProviderCount = 0

6. 写入 `pair_token_lookup` 双向记录：
   - `${token0}-${token1}`
   - `${token1}-${token0}`

**写入表**：

- `uniswap_factory`
- `tokens`
- `pairs`
- `pair_token_lookup`

---

### 2) `Pair:Transfer`

**触发条件**：Pair ERC20 `Transfer` 事件。

**输入**：

- `from`, `to`, `value`

**步骤**：

1. 如果 `to == ADDRESS_ZERO && value == 1000` → 忽略（初始锁仓）。

2. 确保 `users` 记录：
   - `from`
   - `to`

3. 如果 `from == ADDRESS_ZERO`：
   - `pair.totalSupply += value`
   - push pendingMint { to, liquidity }

4. 如果 `to == pair`：
   - push pendingBurn { sender, liquidity }

5. 如果 `to == ADDRESS_ZERO && from == pair`：
   - `pair.totalSupply -= value`

**写入表**：

- `pairs`（totalSupply）
- `users`

---

### 3) `Pair:Sync`

**触发条件**：Pair `Sync` 事件。

**输入**：

- `reserve0`, `reserve1`

**核心逻辑**：

1. 读取 pair/token0/token1

2. `oldTracked = factory.totalLiquidityETH`（十进制字符串）
   `pairTracked = pair.trackedReserveETH`（十进制字符串）

3. 更新 reserves：
   - `pair.reserve0 = event.reserve0`
   - `pair.reserve1 = event.reserve1`

4. 计算价格：
   - `token0Price = reserve0Wad / reserve1Wad`
   - `token1Price = reserve1Wad / reserve0Wad`

5. 强制获取 Oracle 价格并写 bundle

6. 计算 derivedETH：
   - `token0.derivedETH = findEthPerToken(token0)`
   - `token1.derivedETH = findEthPerToken(token1)`

7. 计算 reserveETH / reserveUSD：
   - `reserveETH = reserve0Wad*token0.derivedETH + reserve1Wad*token1.derivedETH`
   - `reserveUSD = reserveETH * ethPrice`

8. 计算 trackedReserveETH：
   - `trackedLiquidityUSD = getTrackedLiquidityUSD(...)`
   - `trackedReserveETH = trackedLiquidityUSD / ethPrice`

9. 更新 factory totalLiquidity：
   - `nextTotal = oldTracked - pairTracked + trackedReserveETH`
   - `totalLiquidityUSD = nextTotal * ethPrice`

10. 更新 token.totalLiquidity：
    - `token0.totalLiquidity = token0.totalLiquidity - oldReserve0 + newReserve0`
    - `token1.totalLiquidity = token1.totalLiquidity - oldReserve1 + newReserve1`

11. 更新 TokenHour/TokenMinute OHLC

**写入表**：

- `pairs`
- `tokens`
- `uniswap_factory`
- `token_hour_data`
- `token_minute_data`
- `bundle`

---

### 4) `Pair:Mint`

**触发条件**：Pair `Mint` 事件。

**输入**：

- `sender`, `amount0`, `amount1`

**步骤**：

1. 从 `pendingMints` 取出 `liquidity` / `to`

2. 计算 amountUSD：
   - `(amount0*token0.derivedETH + amount1*token1.derivedETH) * ethPrice`

3. 更新 txCount：
   - `token0.txCount += 1`
   - `token1.txCount += 1`
   - `pair.txCount += 1`
   - `factory.txCount += 1`

4. 写入 `mints` 表

5. 更新日/小时聚合：
   - `pair_day_data`
   - `pair_hour_data`
   - `uniswap_day_data`
   - `token_day_data`

---

### 5) `Pair:Burn`

**触发条件**：Pair `Burn` 事件。

**输入**：

- `sender`, `amount0`, `amount1`, `to`

**步骤**：

1. 从 `pendingBurns` 取出 `liquidity`

2. 检查 `pendingMints` 是否残留：
   - 若有 → 视为 fee mint，记录 `feeTo` 与 `feeLiquidity`

3. 计算 amountUSD（与 Mint 相同）

4. 更新 txCount：
   - `token0/1.txCount += 1`
   - `pair.txCount += 1`
   - `factory.txCount += 1`

5. 写入 `burns` 表

6. 更新日/小时聚合：
   - `pair_day_data`
   - `pair_hour_data`
   - `uniswap_day_data`
   - `token_day_data`

---

### 6) `Pair:Swap`

**触发条件**：Pair `Swap` 事件。

**输入**：

- `amount0In/amount1In/amount0Out/amount1Out`

**步骤**：

1. 计算总量：
   - `amount0Total = amount0In + amount0Out`
   - `amount1Total = amount1In + amount1Out`

2. 计算 derivedAmountEth / derivedAmountUsd

3. 计算 trackedAmountUsd / trackedAmountEth

4. 更新 token 全局统计：
   - `tradeVolume += amountIn+amountOut`
   - `tradeVolumeUSD += trackedAmountUsd`
   - `untrackedVolumeUSD += derivedAmountUsd`

5. 更新 pair 全局统计：
   - `volumeToken0/1 += amountTotal`
   - `volumeUSD += trackedAmountUsd`
   - `untrackedVolumeUSD += derivedAmountUsd`

6. 更新 factory 全局统计：
   - `totalVolumeUSD += trackedAmountUsd`
   - `totalVolumeETH += trackedAmountEth`
   - `untrackedVolumeUSD += derivedAmountUsd`

7. 写入 `swaps` 表

8. 更新日/小时聚合：
   - `uniswap_day_data`
   - `pair_day_data`
   - `pair_hour_data`
   - `token_day_data`

9. 更新 TokenHour/TokenMinute OHLC

---

## 事件-实体更新矩阵

| 事件 | 主要更新实体 |
|---|---|
| PairCreated | uniswap_factory, tokens, pairs, pair_token_lookup |
| Transfer | pairs.totalSupply, users, pendingMints/Burns |
| Sync | pairs, tokens, uniswap_factory, token_hour_data, token_minute_data, bundle |
| Mint | mints, tokens.txCount, pairs.txCount, uniswap_factory.txCount, pair_day_data, pair_hour_data, token_day_data |
| Burn | burns, tokens.txCount, pairs.txCount, uniswap_factory.txCount, pair_day_data, pair_hour_data, token_day_data |
| Swap | swaps, tokens volumes, pairs volumes, uniswap_factory volumes, uniswap_day_data, pair_day_data, pair_hour_data, token_day_data, token_hour_data, token_minute_data |

---

## 附录 A：Handler 详细解析（输入 / 输出 / 边界 / 校验）

### Factory:PairCreated

触发条件: Factory 合约触发 PairCreated 事件

输入字段:
- token0 (address)
- token1 (address)
- pair (address)
- blockNumber / timestamp

读取依赖:
- uniswap_factory (getOrCreate)
- tokens (getOrCreateToken, ERC20 读取)

写入结果:
- uniswap_factory.pairCount
- tokens (token0/token1)
- pairs (初始化)
- pair_token_lookup (双向记录)

关键步骤:
- 1) 初始化或读取 factory
- 2) pairCount += 1
- 3) 读取 token0/token1 元数据（symbol/name/decimals/totalSupply）
- 4) 初始化 pair，reserve/price/volume = 0
- 5) 写入 pair_token_lookup：token0-token1 与 token1-token0
- 6) 返回，等待后续 Pair 事件填充状态

边界情况:
- ERC20 元数据读取失败：回退为 "unknown" 或默认 decimals=18
- 重复 PairCreated：依赖主键冲突避免重复写入
- token0/token1 地址大小写差异：统一 lowercase

一致性校验:
- pair.token0/token1 必须与事件入参一致
- pair_token_lookup 必须双向写入
- factory.pairCount 累加与实际 pair 数一致

示例:
- 示例链: 11155111
- 示例 token0: 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
- 示例 token1: 0x46a906fca4487c87f0d89d2d0824ec57bdaa947d
- 示例 pair: 0x1111111111111111111111111111111111111111
- 写入 lookup: 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d-0x46a906fca4487c87f0d89d2d0824ec57bdaa947d -> 0x1111111111111111111111111111111111111111
- 写入 lookup: 0x46a906fca4487c87f0d89d2d0824ec57bdaa947d-0xe91d02e66a9152fee1bc79c1830121f6507a4f6d -> 0x1111111111111111111111111111111111111111

### Pair:Transfer

触发条件: Pair ERC20 Transfer 事件

输入字段:
- from
- to
- value
- transactionHash / logIndex

读取依赖:
- pairs (根据 event.log.address)
- users (确保存在)

写入结果:
- pairs.totalSupply
- users
- pendingMints / pendingBurns (内存)

关键步骤:
- 1) 忽略 to=0x0 且 value=1000 的初始锁仓
- 2) 确保 users 中包含 from/to
- 3) from=0x0 → totalSupply += value，pendingMints push
- 4) to=pair → pendingBurns push
- 5) to=0x0 且 from=pair → totalSupply -= value

边界情况:
- 单笔 tx 内多次 Transfer：pending 队列顺序按 logIndex
- 异常 Transfer 顺序可能导致 pendingMints/Burns 为空
- value=0 的 Transfer：应忽略但当前实现仍会记录 user

一致性校验:
- pairs.totalSupply 不应为负
- pendingMints/Burns 必须与 Mint/Burn 事件配对消费
- from/to 地址应保持小写

示例:
- from=0x2222222222222222222222222222222222222222, to=0x1111111111111111111111111111111111111111, value=50000000000000000000
- pendingBurns += { sender: from, liquidity: value }
- from=0x0, to=0x3333333333333333333333333333333333333333, value=100000000000000000000
- pendingMints += { to, liquidity }

### Pair:Sync

触发条件: Pair Sync 事件

输入字段:
- reserve0
- reserve1

读取依赖:
- pairs
- tokens
- uniswap_factory
- bundle (cache)

写入结果:
- pairs.reserve0/reserve1
- pairs.token0Price/token1Price
- pairs.reserveETH/reserveUSD
- pairs.trackedReserveETH
- tokens.totalLiquidity
- tokens.derivedETH
- uniswap_factory.totalLiquidityETH/USD
- token_hour_data / token_minute_data
- bundle (新行)

关键步骤:
- 1) 读取 pair + token0/token1
- 2) 计算 token0Price/token1Price
- 3) 强制获取 Oracle 价格并写 bundle
- 4) findEthPerToken 更新 token0/1.derivedETH
- 5) 计算 reserveETH/reserveUSD
- 6) 计算 trackedReserveETH (白名单逻辑)
- 7) 更新 factory.totalLiquidityETH/USD
- 8) 更新 token.totalLiquidity
- 9) 更新 tokenHour/Minute OHLC

边界情况:
- reserve0 或 reserve1 为 0：价格置为 0
- Oracle 读取失败：ethPrice=0，派生值为 0
- token metadata 缺失：直接 return
- decimals > 18：scaleToWad 可能截断

一致性校验:
- reserveETH >= trackedReserveETH
- factory.totalLiquidityETH 不应为负
- token.totalLiquidity 与 reserves 对齐

示例:
- reserve0Raw=5000000000000000000 (5 vETH)
- reserve1Raw=10000000000 (10000 vUSDC)
- token0Price=0.0005 (vETH/vUSDC)
- token1Price=2000 (vUSDC/vETH)
- reserveETH=10 ETH
- reserveUSD=20000 USD
- trackedReserveETH=10 ETH

### Pair:Mint

触发条件: Pair Mint 事件

输入字段:
- sender
- amount0
- amount1

读取依赖:
- pendingMints
- tokens
- pairs
- uniswap_factory
- bundle (cache)

写入结果:
- mints (事件行)
- tokens.txCount
- pairs.txCount
- uniswap_factory.txCount
- pair_day_data / pair_hour_data
- uniswap_day_data
- token_day_data

关键步骤:
- 1) 从 pendingMints 取出 liquidity/to
- 2) 计算 amountUSD
- 3) 递增 txCount
- 4) 写入 Mint 表
- 5) 更新 day/hour 聚合

边界情况:
- pendingMints 为空：to 回退为 tx.from
- token.derivedETH 为 0：amountUSD=0
- amount0/amount1 为 0：仍写入事件行

一致性校验:
- mint.liquidity 与 Transfer 中 pendingMint 一致
- mint.amountUSD 与公式一致
- day/hour 记录的 txns +1

示例:
- amount0=2000000000000000000 (2 vETH)
- amount1=4000000000 (4000 vUSDC)
- amountUSD=8000 USD
- liquidity=100000000000000000000

### Pair:Burn

触发条件: Pair Burn 事件

输入字段:
- sender
- amount0
- amount1
- to

读取依赖:
- pendingBurns
- pendingMints
- tokens
- pairs
- uniswap_factory

写入结果:
- burns (事件行)
- tokens.txCount
- pairs.txCount
- uniswap_factory.txCount
- pair_day_data / pair_hour_data
- uniswap_day_data
- token_day_data

关键步骤:
- 1) 从 pendingBurns 取出 liquidity
- 2) 检查 pendingMints 是否存在 fee mint
- 3) 计算 amountUSD
- 4) 递增 txCount
- 5) 写入 Burn 表（含 feeTo/feeLiquidity）
- 6) 更新 day/hour 聚合

边界情况:
- pendingBurns 为空：liquidity=0
- fee mint 存在：写入 feeTo/feeLiquidity
- amount0/amount1 为 0：仍写入事件行

一致性校验:
- burn.liquidity 与 Transfer pendingBurn 一致
- fee mint 应与 mint 列表尾部对应
- day/hour 记录 txns +1

示例:
- amount0=1000000000000000000 (1 vETH)
- amount1=2000000000 (2000 vUSDC)
- amountUSD=4000 USD
- feeLiquidity=1000000000000000000

### Pair:Swap

触发条件: Pair Swap 事件

输入字段:
- amount0In
- amount1In
- amount0Out
- amount1Out

读取依赖:
- tokens
- pairs
- uniswap_factory
- bundle (cache)

写入结果:
- swaps (事件行)
- tokens.tradeVolume/tradeVolumeUSD/untrackedVolumeUSD
- pairs.volumeToken0/volumeToken1/volumeUSD/untrackedVolumeUSD
- uniswap_factory.totalVolumeUSD/ETH/untrackedVolumeUSD
- uniswap_day_data / pair_day_data / pair_hour_data / token_day_data
- token_hour_data / token_minute_data

关键步骤:
- 1) 计算 amount0Total/amount1Total
- 2) 计算 derivedAmountEth/derivedAmountUsd
- 3) 计算 trackedAmountUsd/ trackedAmountEth
- 4) 更新 token/pair/factory volume
- 5) 写入 Swap 表
- 6) 更新日/小时聚合
- 7) 更新 token OHLC

边界情况:
- 白名单未命中：trackedAmountUsd=0
- 一侧金额为 0：derivedAmountEth 退化为直接相加
- token.derivedETH 为 0：amountUSD=0

一致性校验:
- pair.volumeToken0/1 与 amount0Total/1Total 累加一致
- factory.totalVolumeUSD 与 trackedAmountUsd 累加一致
- swap.amountUSD 逻辑：tracked=0 时使用 derived

示例:
- amount0In=1000000000000000000
- amount1Out=2000000000
- derivedAmountUsd=2000 USD
- trackedAmountUsd=2000 USD

## 附录 B：表字段更新示例（数值场景）

以下示例基于统一场景：
- chainId=11155111
- token0(vETH)=0xe91d02e66a9152fee1bc79c1830121f6507a4f6d, decimals=18
- token1(vUSDC)=0x46a906fca4487c87f0d89d2d0824ec57bdaa947d, decimals=6
- pair=0x1111111111111111111111111111111111111111
- ethPrice=2000 USD
- reserve0=5 vETH, reserve1=10000 vUSDC

### uniswap_factory 示例

id=0x6c9258026a9272368e49bbb7d0a78c17bbe284bf
pairCount=1
totalVolumeUSD=2000 (一次 swap 后)
totalVolumeETH=1
untrackedVolumeUSD=2000
totalLiquidityETH=10
totalLiquidityUSD=20000
txCount=1

### bundle 示例

id=1000-12
ethPrice=2000
oracleRoundId=12345

### tokens 示例 (token0)

id=0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
symbol=vETH, name=DripSwap vETH
decimals=18, totalSupply=示例保持初始值
tradeVolume=1000000000000000000
tradeVolumeUSD=2000
untrackedVolumeUSD=2000
txCount=1
totalLiquidity=5000000000000000000
derivedETH=1

### tokens 示例 (token1)

id=0x46a906fca4487c87f0d89d2d0824ec57bdaa947d
symbol=vUSDC, name=DripSwap vUSDC
decimals=6, totalSupply=示例保持初始值
tradeVolume=2000000000
tradeVolumeUSD=2000
untrackedVolumeUSD=2000
txCount=1
totalLiquidity=10000000000
derivedETH=0.0005

### pairs 示例

id=0x1111111111111111111111111111111111111111
token0=0xe91d02e66a9152fee1bc79c1830121f6507a4f6d, token1=0x46a906fca4487c87f0d89d2d0824ec57bdaa947d
reserve0=5000000000000000000, reserve1=10000000000
token0Price=0.0005, token1Price=2000
reserveETH=10, reserveUSD=20000
trackedReserveETH=10
totalSupply=100000000000000000000
volumeToken0=1000000000000000000, volumeToken1=2000000000
volumeUSD=2000, untrackedVolumeUSD=2000
txCount=1
createdAtTimestamp=1700000000, createdAtBlockNumber=1000

### pair_token_lookup 示例

id=0xe91d02e66a9152fee1bc79c1830121f6507a4f6d-0x46a906fca4487c87f0d89d2d0824ec57bdaa947d, pair=0x1111111111111111111111111111111111111111
id=0x46a906fca4487c87f0d89d2d0824ec57bdaa947d-0xe91d02e66a9152fee1bc79c1830121f6507a4f6d, pair=0x1111111111111111111111111111111111111111

### users 示例

id=0x2222222222222222222222222222222222222222
id=0x3333333333333333333333333333333333333333

### mints 示例

id=0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-20
pair=0x1111111111111111111111111111111111111111
liquidity=100000000000000000000
amount0=2000000000000000000, amount1=4000000000
amountUSD=8000
sender=0x2222222222222222222222222222222222222222, to=0x3333333333333333333333333333333333333333
blockNumber=1000, txIndex=3, logIndex=20

### burns 示例

id=0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-25
pair=0x1111111111111111111111111111111111111111
liquidity=50000000000000000000
amount0=1000000000000000000, amount1=2000000000
amountUSD=4000
feeTo=0x3333333333333333333333333333333333333333, feeLiquidity=1000000000000000000

### swaps 示例

id=0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-12
pair=0x1111111111111111111111111111111111111111
amount0In=1000000000000000000, amount1Out=2000000000
amountUSD=2000
from=0x2222222222222222222222222222222222222222, to=0x3333333333333333333333333333333333333333

### uniswap_day_data 示例

id=19675 (示例 dayId)
dailyVolumeETH=1
dailyVolumeUSD=2000
dailyVolumeUntracked=2000
totalLiquidityETH=10
totalLiquidityUSD=20000
txCount=1

### pair_day_data 示例

pair=0x1111111111111111111111111111111111111111
reserve0=5000000000000000000, reserve1=10000000000
dailyVolumeToken0=1000000000000000000, dailyVolumeToken1=2000000000
dailyVolumeUSD=2000
dailyTxns=1

### pair_hour_data 示例

pair=0x1111111111111111111111111111111111111111
hourlyVolumeToken0=1000000000000000000, hourlyVolumeToken1=2000000000
hourlyVolumeUSD=2000
hourlyTxns=1

### token_day_data 示例 (token0)

token=0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
dailyVolumeToken=1000000000000000000
dailyVolumeETH=1
dailyVolumeUSD=2000
totalLiquidityToken=5000000000000000000
totalLiquidityETH=5
totalLiquidityUSD=10000
priceUSD=2000

### token_day_data 示例 (token1)

token=0x46a906fca4487c87f0d89d2d0824ec57bdaa947d
dailyVolumeToken=2000000000
dailyVolumeETH=1
dailyVolumeUSD=2000
totalLiquidityToken=10000000000
totalLiquidityETH=5
totalLiquidityUSD=10000
priceUSD=1

### token_hour_data 示例 (token0)

token=0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
open/high/low/close=2000
volume=1000000000000000000, volumeUSD=2000
untrackedVolumeUSD=2000
totalValueLocked=0, totalValueLockedUSD=0, feesUSD=0

### token_hour_data 示例 (token1)

token=0x46a906fca4487c87f0d89d2d0824ec57bdaa947d
open/high/low/close=1
volume=2000000000, volumeUSD=2000
untrackedVolumeUSD=2000
totalValueLocked=0, totalValueLockedUSD=0, feesUSD=0

### token_minute_data 示例 (token0)

token=0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
open/high/low/close=2000
volume=1000000000000000000, volumeUSD=2000

### token_minute_data 示例 (token1)

token=0x46a906fca4487c87f0d89d2d0824ec57bdaa947d
open/high/low/close=1
volume=2000000000, volumeUSD=2000

## 附录 C：事件演算示例（Swap / Mint / Burn）

### Swap 示例演算

输入: amount0In=1000000000000000000, amount1Out=2000000000
token0DerivedETH=1, token1DerivedETH=0.0005
ethPrice=2000 USD

步骤:
1) amount0Total=1000000000000000000, amount1Total=2000000000
2) amount0Wad=1, amount1Wad=2000
3) derivedAmountEth=1 ETH
4) derivedAmountUsd=2000 USD
5) trackedAmountUsd=2000 USD (白名单成立)
6) trackedAmountEth=1 ETH

写入结果:
- swaps.amountUSD=2000
- tokens.tradeVolume += amountIn+amountOut
- pairs.volumeToken0 += 1000000000000000000
- pairs.volumeToken1 += 2000000000
- uniswap_factory.totalVolumeUSD += 2000
- uniswap_day_data.dailyVolumeUSD += 2000

### Mint 示例演算

输入: amount0=2000000000000000000, amount1=4000000000
liquidity=100000000000000000000

步骤:
1) 从 pendingMints 获取 liquidity/to
2) amount0Wad=2, amount1Wad=4000
3) amountUSD=(2*1 + 4000*0.0005)*2000 = 8000 USD
4) txCount +1（token/pair/factory）
5) 写入 mints 表
6) 更新 PairDay/PairHour/UniswapDay/TokenDay

### Burn 示例演算

输入: amount0=1000000000000000000, amount1=2000000000
liquidity=50000000000000000000
feeLiquidity=1000000000000000000

步骤:
1) 从 pendingBurns 获取 liquidity
2) 检查 pendingMints 作为 fee mint
3) amountUSD=(1*1 + 2000*0.0005)*2000 = 4000 USD
4) txCount +1（token/pair/factory）
5) 写入 burns 表（feeTo/feeLiquidity）
6) 更新 PairDay/PairHour/UniswapDay/TokenDay

## 附录 D：字段级详解（逐字段公式与示例）

本附录对所有表字段给出：更新时机、计算来源、公式与示例值。

### 表: uniswap_factory

字段详解:
字段: uniswap_factory.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: uniswap_factory.id
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 主键直接取地址或 txHash-logIndex
- 示例值(场景A): 0x6c9258026a9272368e49bbb7d0a78c17bbe284bf
- 是否可空: 否
- 注意事项: 地址统一小写

字段: uniswap_factory.pairCount
- 类型: bigint
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 值应为非负

字段: uniswap_factory.totalVolumeUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_factory.totalVolumeETH
- 类型: text
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_factory.untrackedVolumeUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: derivedAmountUsd 累加
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_factory.totalLiquidityUSD
- 类型: text
- 更新时机: Sync
- 计算/来源: totalLiquidityETH * ethPrice
- 示例值(场景A): 20000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_factory.totalLiquidityETH
- 类型: text
- 更新时机: Sync
- 计算/来源: oldTotal - oldPairTracked + newPairTracked
- 示例值(场景A): 10
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_factory.txCount
- 类型: bigint
- 更新时机: Mint/Burn/Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 值应为非负

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: bundle

字段详解:
字段: bundle.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: bundle.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: `${blockNumber}-${logIndex}`
- 示例值(场景A): 1000-12
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: bundle.ethPrice
- 类型: text
- 更新时机: Oracle 读取
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: bundle.oracleRoundId
- 类型: bigint
- 更新时机: Oracle 读取
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 12345
- 是否可空: 否
- 注意事项: 值应为非负

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: tokens

字段详解:
字段: tokens.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: tokens.id
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 主键直接取地址或 txHash-logIndex
- 示例值(场景A): 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
- 是否可空: 否
- 注意事项: 地址统一小写

字段: tokens.symbol
- 类型: text
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): vETH
- 是否可空: 否
- 注意事项: 普通文本

字段: tokens.name
- 类型: text
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): DripSwap vETH
- 是否可空: 否
- 注意事项: 普通文本

字段: tokens.decimals
- 类型: bigint
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 18
- 是否可空: 否
- 注意事项: 值应为非负

字段: tokens.totalSupply
- 类型: bigint
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: tokens.tradeVolume
- 类型: bigint
- 更新时机: Swap
- 计算/来源: amountIn + amountOut 累加
- 示例值(场景A): 1000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: tokens.tradeVolumeUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: trackedAmountUsd 累加
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: tokens.untrackedVolumeUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: derivedAmountUsd 累加
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: tokens.txCount
- 类型: bigint
- 更新时机: Mint/Burn/Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 值应为非负

字段: tokens.totalLiquidity
- 类型: bigint
- 更新时机: Sync
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 5000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: tokens.derivedETH
- 类型: text
- 更新时机: Sync
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 普通十进制字符串

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: pairs

字段详解:
字段: pairs.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: pairs.id
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 主键直接取地址或 txHash-logIndex
- 示例值(场景A): 0x1111111111111111111111111111111111111111
- 是否可空: 否
- 注意事项: 地址统一小写

字段: pairs.token0
- 类型: hex
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
- 是否可空: 否
- 注意事项: 地址统一小写

字段: pairs.token1
- 类型: hex
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x46a906fca4487c87f0d89d2d0824ec57bdaa947d
- 是否可空: 否
- 注意事项: 地址统一小写

字段: pairs.reserve0
- 类型: bigint
- 更新时机: Sync
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 5000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pairs.reserve1
- 类型: bigint
- 更新时机: Sync
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 10000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pairs.totalSupply
- 类型: bigint
- 更新时机: Transfer
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 100000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pairs.reserveETH
- 类型: text
- 更新时机: Sync
- 计算/来源: reserve0Wad*token0.derivedETH + reserve1Wad*token1.derivedETH
- 示例值(场景A): 10
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pairs.reserveUSD
- 类型: text
- 更新时机: Sync
- 计算/来源: reserveETH * ethPrice
- 示例值(场景A): 20000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pairs.trackedReserveETH
- 类型: text
- 更新时机: Sync
- 计算/来源: getTrackedLiquidityUSD(...) / ethPrice
- 示例值(场景A): 10
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pairs.token0Price
- 类型: text
- 更新时机: Sync
- 计算/来源: reserve0Wad / reserve1Wad
- 示例值(场景A): 0.0005
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pairs.token1Price
- 类型: text
- 更新时机: Sync
- 计算/来源: reserve1Wad / reserve0Wad
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pairs.volumeToken0
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pairs.volumeToken1
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pairs.volumeUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: trackedAmountUsd 累加
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pairs.untrackedVolumeUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: derivedAmountUsd 累加
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pairs.txCount
- 类型: bigint
- 更新时机: Mint/Burn/Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 值应为非负

字段: pairs.createdAtTimestamp
- 类型: bigint
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1700000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pairs.createdAtBlockNumber
- 类型: bigint
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pairs.liquidityProviderCount
- 类型: bigint
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0
- 是否可空: 否
- 注意事项: 值应为非负

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: pair_token_lookup

字段详解:
字段: pair_token_lookup.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_token_lookup.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: `${token0}-${token1}`
- 示例值(场景A): 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d-0x46a906fca4487c87f0d89d2d0824ec57bdaa947d
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pair_token_lookup.pair
- 类型: hex
- 更新时机: PairCreated
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x1111111111111111111111111111111111111111
- 是否可空: 否
- 注意事项: 地址统一小写

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: users

字段详解:
字段: users.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: users.id
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 主键直接取地址或 txHash-logIndex
- 示例值(场景A): 0x2222222222222222222222222222222222222222
- 是否可空: 否
- 注意事项: 地址统一小写

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: mints

字段详解:
字段: mints.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: mints.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: 主键直接取地址或 txHash-logIndex
- 示例值(场景A): 0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-20
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: mints.timestamp
- 类型: bigint
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1700000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: mints.blockNumber
- 类型: bigint
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000
- 是否可空: 否
- 注意事项: 值应为非负

字段: mints.blockTimestamp
- 类型: bigint
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1700000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: mints.transactionHash
- 类型: hex
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
- 是否可空: 否
- 注意事项: 地址统一小写

字段: mints.transactionIndex
- 类型: integer
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 3
- 是否可空: 否
- 注意事项: 值应为非负

字段: mints.logIndex
- 类型: integer
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 12
- 是否可空: 否
- 注意事项: 值应为非负

字段: mints.pair
- 类型: hex
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x1111111111111111111111111111111111111111
- 是否可空: 否
- 注意事项: 地址统一小写

字段: mints.to
- 类型: hex
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x3333333333333333333333333333333333333333
- 是否可空: 否
- 注意事项: 地址统一小写

字段: mints.liquidity
- 类型: bigint
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 100000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: mints.sender
- 类型: hex
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x2222222222222222222222222222222222222222
- 是否可空: 是
- 注意事项: 地址统一小写

字段: mints.amount0
- 类型: bigint
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000000000000000000
- 是否可空: 是
- 注意事项: 值应为非负

字段: mints.amount1
- 类型: bigint
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 4000000000
- 是否可空: 是
- 注意事项: 值应为非负

字段: mints.amountUSD
- 类型: text
- 更新时机: Mint
- 计算/来源: (amount0*derivedETH + amount1*derivedETH) * ethPrice
- 示例值(场景A): 8000
- 是否可空: 是
- 注意事项: 普通十进制字符串

字段: mints.feeTo
- 类型: hex
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x3333333333333333333333333333333333333333
- 是否可空: 是
- 注意事项: 地址统一小写

字段: mints.feeLiquidity
- 类型: bigint
- 更新时机: Mint
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000
- 是否可空: 是
- 注意事项: 值应为非负

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: burns

字段详解:
字段: burns.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: burns.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: 主键直接取地址或 txHash-logIndex
- 示例值(场景A): 0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-25
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: burns.timestamp
- 类型: bigint
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1700000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: burns.blockNumber
- 类型: bigint
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000
- 是否可空: 否
- 注意事项: 值应为非负

字段: burns.blockTimestamp
- 类型: bigint
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1700000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: burns.transactionHash
- 类型: hex
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
- 是否可空: 否
- 注意事项: 地址统一小写

字段: burns.transactionIndex
- 类型: integer
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 3
- 是否可空: 否
- 注意事项: 值应为非负

字段: burns.logIndex
- 类型: integer
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 12
- 是否可空: 否
- 注意事项: 值应为非负

字段: burns.pair
- 类型: hex
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x1111111111111111111111111111111111111111
- 是否可空: 否
- 注意事项: 地址统一小写

字段: burns.liquidity
- 类型: bigint
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 100000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: burns.sender
- 类型: hex
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x2222222222222222222222222222222222222222
- 是否可空: 是
- 注意事项: 地址统一小写

字段: burns.amount0
- 类型: bigint
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000000000000000000
- 是否可空: 是
- 注意事项: 值应为非负

字段: burns.amount1
- 类型: bigint
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 4000000000
- 是否可空: 是
- 注意事项: 值应为非负

字段: burns.to
- 类型: hex
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x3333333333333333333333333333333333333333
- 是否可空: 是
- 注意事项: 地址统一小写

字段: burns.amountUSD
- 类型: text
- 更新时机: Burn
- 计算/来源: (amount0*derivedETH + amount1*derivedETH) * ethPrice
- 示例值(场景A): 8000
- 是否可空: 是
- 注意事项: 普通十进制字符串

字段: burns.feeTo
- 类型: hex
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x3333333333333333333333333333333333333333
- 是否可空: 是
- 注意事项: 地址统一小写

字段: burns.feeLiquidity
- 类型: bigint
- 更新时机: Burn
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000
- 是否可空: 是
- 注意事项: 值应为非负

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: swaps

字段详解:
字段: swaps.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: swaps.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: 主键直接取地址或 txHash-logIndex
- 示例值(场景A): 0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-12
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: swaps.timestamp
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1700000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: swaps.blockNumber
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000
- 是否可空: 否
- 注意事项: 值应为非负

字段: swaps.blockTimestamp
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1700000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: swaps.transactionHash
- 类型: hex
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
- 是否可空: 否
- 注意事项: 地址统一小写

字段: swaps.transactionIndex
- 类型: integer
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 3
- 是否可空: 否
- 注意事项: 值应为非负

字段: swaps.logIndex
- 类型: integer
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 12
- 是否可空: 否
- 注意事项: 值应为非负

字段: swaps.pair
- 类型: hex
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x1111111111111111111111111111111111111111
- 是否可空: 否
- 注意事项: 地址统一小写

字段: swaps.sender
- 类型: hex
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x2222222222222222222222222222222222222222
- 是否可空: 否
- 注意事项: 地址统一小写

字段: swaps.from
- 类型: hex
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x2222222222222222222222222222222222222222
- 是否可空: 否
- 注意事项: 地址统一小写

字段: swaps.amount0In
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: swaps.amount1In
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0
- 是否可空: 否
- 注意事项: 值应为非负

字段: swaps.amount0Out
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0
- 是否可空: 否
- 注意事项: 值应为非负

字段: swaps.amount1Out
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: swaps.to
- 类型: hex
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x3333333333333333333333333333333333333333
- 是否可空: 否
- 注意事项: 地址统一小写

字段: swaps.amountUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: trackedAmountUsd == 0 ? derivedAmountUsd : trackedAmountUsd
- 示例值(场景A): 8000
- 是否可空: 否
- 注意事项: 普通十进制字符串

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: uniswap_day_data

字段详解:
字段: uniswap_day_data.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: uniswap_day_data.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: `${entityId}-${dayId}` 或 dayId
- 示例值(场景A): 19675
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_day_data.date
- 类型: integer
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1699920000
- 是否可空: 否
- 注意事项: 值应为非负

字段: uniswap_day_data.dailyVolumeETH
- 类型: text
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_day_data.dailyVolumeUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_day_data.dailyVolumeUntracked
- 类型: text
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_day_data.totalVolumeETH
- 类型: text
- 更新时机: 当前实现不更新
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_day_data.totalLiquidityETH
- 类型: text
- 更新时机: 从 factory 同步
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 10
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_day_data.totalVolumeUSD
- 类型: text
- 更新时机: 当前实现不更新
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_day_data.totalLiquidityUSD
- 类型: text
- 更新时机: 从 factory 同步
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 20000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: uniswap_day_data.txCount
- 类型: bigint
- 更新时机: 从 factory 同步
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 值应为非负

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: pair_hour_data

字段详解:
字段: pair_hour_data.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_hour_data.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: `${entityId}-${hourIndex}`
- 示例值(场景A): 0x1111111111111111111111111111111111111111-472222
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pair_hour_data.hourStartUnix
- 类型: integer
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1699999200
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_hour_data.pair
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x1111111111111111111111111111111111111111
- 是否可空: 否
- 注意事项: 地址统一小写

字段: pair_hour_data.reserve0
- 类型: bigint
- 更新时机: 覆盖更新
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 5000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_hour_data.reserve1
- 类型: bigint
- 更新时机: 覆盖更新
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 10000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_hour_data.totalSupply
- 类型: bigint
- 更新时机: 覆盖更新
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000000000
- 是否可空: 是
- 注意事项: 值应为非负

字段: pair_hour_data.reserveUSD
- 类型: text
- 更新时机: 覆盖更新
- 计算/来源: reserveETH * ethPrice
- 示例值(场景A): 20000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pair_hour_data.hourlyVolumeToken0
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_hour_data.hourlyVolumeToken1
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_hour_data.hourlyVolumeUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pair_hour_data.hourlyTxns
- 类型: bigint
- 更新时机: Mint/Burn/Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 值应为非负

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: pair_day_data

字段详解:
字段: pair_day_data.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_day_data.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: `${entityId}-${dayId}` 或 dayId
- 示例值(场景A): 0x1111111111111111111111111111111111111111-19675
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pair_day_data.date
- 类型: integer
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1699920000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_day_data.pairAddress
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x1111111111111111111111111111111111111111
- 是否可空: 否
- 注意事项: 地址统一小写

字段: pair_day_data.token0
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
- 是否可空: 否
- 注意事项: 地址统一小写

字段: pair_day_data.token1
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0x46a906fca4487c87f0d89d2d0824ec57bdaa947d
- 是否可空: 否
- 注意事项: 地址统一小写

字段: pair_day_data.reserve0
- 类型: bigint
- 更新时机: 覆盖更新
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 5000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_day_data.reserve1
- 类型: bigint
- 更新时机: 覆盖更新
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 10000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_day_data.totalSupply
- 类型: bigint
- 更新时机: 覆盖更新
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000000000
- 是否可空: 是
- 注意事项: 值应为非负

字段: pair_day_data.reserveUSD
- 类型: text
- 更新时机: 覆盖更新
- 计算/来源: reserveETH * ethPrice
- 示例值(场景A): 20000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pair_day_data.dailyVolumeToken0
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_day_data.dailyVolumeToken1
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: pair_day_data.dailyVolumeUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: pair_day_data.dailyTxns
- 类型: bigint
- 更新时机: Mint/Burn/Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 值应为非负

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: token_day_data

字段详解:
字段: token_day_data.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_day_data.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: `${entityId}-${dayId}` 或 dayId
- 示例值(场景A): 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d-19675
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_day_data.date
- 类型: integer
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1699920000
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_day_data.token
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
- 是否可空: 否
- 注意事项: 地址统一小写

字段: token_day_data.dailyVolumeToken
- 类型: bigint
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_day_data.dailyVolumeETH
- 类型: text
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_day_data.dailyVolumeUSD
- 类型: text
- 更新时机: Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_day_data.dailyTxns
- 类型: bigint
- 更新时机: Mint/Burn/Swap
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_day_data.totalLiquidityToken
- 类型: bigint
- 更新时机: Sync
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 5000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_day_data.totalLiquidityETH
- 类型: text
- 更新时机: Sync
- 计算/来源: totalLiquidityToken * derivedETH
- 示例值(场景A): 10
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_day_data.totalLiquidityUSD
- 类型: text
- 更新时机: Sync
- 计算/来源: totalLiquidityETH * ethPrice
- 示例值(场景A): 20000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_day_data.priceUSD
- 类型: text
- 更新时机: Sync
- 计算/来源: token.derivedETH * ethPrice
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: token_hour_data

字段详解:
字段: token_hour_data.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_hour_data.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: `${entityId}-${hourIndex}`
- 示例值(场景A): 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d-472222
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_hour_data.periodStartUnix
- 类型: integer
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1699999200
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_hour_data.token
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
- 是否可空: 否
- 注意事项: 地址统一小写

字段: token_hour_data.volume
- 类型: bigint
- 更新时机: Sync/Swap(覆盖)
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_hour_data.volumeUSD
- 类型: text
- 更新时机: Sync/Swap(覆盖)
- 计算/来源: trackedAmountUsd 累加
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_hour_data.untrackedVolumeUSD
- 类型: text
- 更新时机: Sync/Swap(覆盖)
- 计算/来源: derivedAmountUsd 累加
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_hour_data.totalValueLocked
- 类型: bigint
- 更新时机: 固定 0
- 计算/来源: 固定为 0
- 示例值(场景A): 0
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_hour_data.totalValueLockedUSD
- 类型: text
- 更新时机: 固定 0
- 计算/来源: 固定为 0
- 示例值(场景A): 0
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_hour_data.priceUSD
- 类型: text
- 更新时机: Sync/Swap
- 计算/来源: token.derivedETH * ethPrice
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_hour_data.feesUSD
- 类型: text
- 更新时机: 固定 0
- 计算/来源: 固定为 0
- 示例值(场景A): 0
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_hour_data.open
- 类型: text
- 更新时机: Sync/Swap
- 计算/来源: OHLC 更新规则
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_hour_data.high
- 类型: text
- 更新时机: Sync/Swap
- 计算/来源: OHLC 更新规则
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_hour_data.low
- 类型: text
- 更新时机: Sync/Swap
- 计算/来源: OHLC 更新规则
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_hour_data.close
- 类型: text
- 更新时机: Sync/Swap
- 计算/来源: OHLC 更新规则
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

### 表: token_minute_data

字段详解:
字段: token_minute_data.chainId
- 类型: integer
- 更新时机: 创建时
- 计算/来源: context.chain.id
- 示例值(场景A): 11155111
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_minute_data.id
- 类型: text
- 更新时机: 创建时
- 计算/来源: `${entityId}-${minuteIndex}`
- 示例值(场景A): 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d-28333333
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_minute_data.periodStartUnix
- 类型: integer
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1699999200
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_minute_data.token
- 类型: hex
- 更新时机: 创建时
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 0xe91d02e66a9152fee1bc79c1830121f6507a4f6d
- 是否可空: 否
- 注意事项: 地址统一小写

字段: token_minute_data.volume
- 类型: bigint
- 更新时机: Sync/Swap(覆盖)
- 计算/来源: 直接来自事件或上一层实体
- 示例值(场景A): 1000000000000000000
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_minute_data.volumeUSD
- 类型: text
- 更新时机: Sync/Swap(覆盖)
- 计算/来源: trackedAmountUsd 累加
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_minute_data.untrackedVolumeUSD
- 类型: text
- 更新时机: Sync/Swap(覆盖)
- 计算/来源: derivedAmountUsd 累加
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_minute_data.totalValueLocked
- 类型: bigint
- 更新时机: 固定 0
- 计算/来源: 固定为 0
- 示例值(场景A): 0
- 是否可空: 否
- 注意事项: 值应为非负

字段: token_minute_data.totalValueLockedUSD
- 类型: text
- 更新时机: 固定 0
- 计算/来源: 固定为 0
- 示例值(场景A): 0
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_minute_data.priceUSD
- 类型: text
- 更新时机: Sync/Swap
- 计算/来源: token.derivedETH * ethPrice
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_minute_data.feesUSD
- 类型: text
- 更新时机: 固定 0
- 计算/来源: 固定为 0
- 示例值(场景A): 0
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_minute_data.open
- 类型: text
- 更新时机: Sync/Swap
- 计算/来源: OHLC 更新规则
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_minute_data.high
- 类型: text
- 更新时机: Sync/Swap
- 计算/来源: OHLC 更新规则
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_minute_data.low
- 类型: text
- 更新时机: Sync/Swap
- 计算/来源: OHLC 更新规则
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

字段: token_minute_data.close
- 类型: text
- 更新时机: Sync/Swap
- 计算/来源: OHLC 更新规则
- 示例值(场景A): 2000
- 是否可空: 否
- 注意事项: 普通十进制字符串

一致性检查要点:
- 主键唯一且 chainId 匹配
- 派生字段需通过 formatWad 写入（普通十进制字符串）
- raw bigint 字段应与链上单位一致

## 附录 E：常见对账与排查清单

1) Sync 后检查 pairs.reserve0/1 与链上同步事件一致
2) Swap 后检查 token.tradeVolume 与 amount0/1Total 累加一致
3) Mint/Burn 后检查 txCount 是否 +1
4) token_day_data.priceUSD 是否等于 token.derivedETH * ethPrice
5) uniswap_day_data.totalVolumeUSD 当前实现应保持 0
6) bundle 表应按事件追加，而非覆盖
7) pair_token_lookup 是否存在双向记录
8) trackedAmountUsd 为 0 的情况是否符合白名单规则
9) totalLiquidityETH 不应出现负数
10) OHLC 更新是否正确反映当期价格


## 相对 v2 Subgraph 的主要修改

1. **类型体系不同**
  - BigDecimal → `text`（普通十进制字符串）
   - raw bigint 保留链上原值

2. **主键加入 chainId**
   - 全表复合主键 `(chainId, id)`

3. **移除 Transaction 表**
   - 事件 ID 改为 `txHash-logIndex`
   - Mint/Burn 通过 pending 队列拼接

4. **Bundle 改为追加式**
   - 不固定 `id=1`
   - 仅在 Oracle fetch 时写入新行

5. **PairTokenLookup 保留**
   - 用于 `findEthPerToken` 的白名单 pair 快速查找

6. **去掉 v2-tokens 的分钟/小时归档数组**
   - 不再维护 `minuteArray/hourArray/lastMinuteArchived`
   - 历史保留交给数据库层

7. **Bridge / Transaction 等表移除**
