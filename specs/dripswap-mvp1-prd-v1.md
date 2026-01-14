# PRD：DripSwap MVP-1（数据契约冻结 / Explore & Details 可开发）

> **版本**：v1（可直接指导开发）
>
> **范围定位**：MVP-1 只做“可执行的产品定义 + 数据契约冻结”，不要求本阶段完成后端实现；但文档必须让 MVP-2/MVP-4 的工程实现可以按此直接落地。
>
> **参考实现**：Uniswap `interface` Explore、`sushiswap` Explore & V2 pool details/management、以及当前 DripSwap 代码现状（`apps/frontend` / `apps/bff` / `apps/subgraphgoldsky`）。
>
> **Goldsky 能力前提**：支持 entity 增量推送 + webhook/流式导出 + sink（但 MVP-1 仅规定契约，不强制落地 pipeline）。
>
> **最后更新**：2026-01-11

---

## 1. 背景与问题

DripSwap 目标是做一个基于自建 Uniswap V2 Factory 的测试网 DEX，核心闭环为：

`Faucet → Swap → Add/Remove Liquidity → Explore → Token/Pool Details`

当前代码进展（以仓库为准）：

- 前端 `apps/frontend`：
  - Explore Tokens / Explore Transactions / Token Details 已接入 BFF GraphQL（`/graphql`）。
  - Explore Pools 为占位页（Coming soon）。
  - Pool Details 页面结构存在但仍为占位/未对齐真实数据。
  - Add/Remove Liquidity 页面 UI 存在但未接入链上 Router。
  - Faucet UI 存在但未对接合约/后端。
- BFF `apps/bff`：
  - 已有 GraphQL schema 与部分 resolver（但实现仍依赖“旧的同步入库模式”）。
- Goldsky Subgraph `apps/subgraphgoldsky`：
  - 已有 V2 核心实体与时间聚合（Swap/Mint/Burn/TokenDayData/TokenHourData/TokenMinuteData/PairDayData/PairHourData/UniswapDayData/Bundle…），可作为真相源。

当前最大阻塞：**缺少一套完整、稳定、可演进的“前端需要什么数据”契约**，导致后续 BFF（改读 Goldsky + Redis）与前端（补齐 Pools/Details/LP）无法并行推进。

---

## 2. MVP-1 目标与非目标

### 2.1 目标（必须达成）

1) 冻结 P0 “读查询”数据契约：Explore（Tokens/Pools/Transactions）+ Token Details + Pool Details  
2) 明确每个字段的数据来源（Goldsky entity/计算口径）与精度/单位/空值规则  
3) 明确搜索、排序、过滤、limit 的语义与边界  
4) 明确错误处理契约（哪些场景返回空数组/哪些抛 GraphQL error）以保证前端可稳定渲染  

### 2.2 非目标（MVP-1 不做）

- 不实现任何后端逻辑（实现属于 MVP-2/MVP-4）
- 不实现 Add/Remove Liquidity 的链上交易（属于 MVP-5）
- 不建设“大 sink”（Postgres/对象存储/队列全量落地，属于 P2）
- 不做完整 UI 视觉稿（但会规定最小交互与页面结构）

---

## 3. 参考产品抽象（用于对齐体验）

### 3.1 Uniswap Interface（`interface`）对 DripSwap 的可借鉴点

- Explore 三 Tab：Tokens / Pools / Transactions（见 `interface/apps/web/src/pages/Explore/index.tsx`）
- 统一的：
  - network filter（按链）
  - search（全局模糊）
  - tokens 的时间窗（volume/price history 的切换）
  - pools 的派生指标（APR、Vol/TVL）
- transactions 支持类型过滤：Swap/Add/Remove（见 `interface/.../RecentTransactions.tsx`）

### 3.2 SushiSwap（`sushiswap`）对 DripSwap 的可落地实现点

- Tokens table：Name/Price/1d/FDV/Sparkline（见 `sushiswap/.../explore/tokens/_ui/columns.tsx`）
- Pools table：TVL(含1d)/Vol(24h,1w)/Tx(24h)/APR（见 `sushiswap/.../_ui/columns.tsx`）
- Pool V2 details：图表 + 组成 + stats + tx 列表（见 `sushiswap/.../pool-page-v2.tsx`）

---

## 4. 用户与使用场景（User Stories）

### 4.1 Explore Tokens

- 作为用户，我能在指定链上看到 Token 列表，支持搜索与排序，查看价格、涨跌幅、FDV、24h 量。
- 点击 Token 行进入 Token Details。

### 4.2 Explore Pools

- 作为用户，我能在指定链上看到 Pool（Pair）列表，支持搜索与排序，查看 TVL、24h 量、24h fees、24h tx、APR。
- 点击 Pool 行进入 Pool Details。

### 4.3 Explore Transactions

- 作为用户，我能看到最近的链上事件（Swap/Mint/Burn），支持按类型过滤，并能跳转区块浏览器查看交易。

### 4.4 Token Details

- 作为用户，我能看到 token 的价格/TVL/成交量等头部信息。
- 我能看到价格图（OHLC）并切换区间（1D/1W/1M/1Y，由前端做 bucket 采样）。
- 我能看到与该 token 相关的 top pools 与最近 swaps。

### 4.5 Pool Details

- 作为用户，我能看到 pool 的 TVL、24h volume、24h fees、24h tx、APR 等。
- 我能看到 pool 的交易列表（Swap/Mint/Burn）。
- 我能看到 pool 的 TVL/Volume 时间序列图。

---

## 5. 产品范围（MVP-1 要冻结的页面与数据点）

> **注意**：MVP-1 的交付物是“契约 + 口径 + 验收条件”，后端实现与前端改造在后续 MVP 执行。

### 5.1 页面清单（前端现状 vs MVP-1 契约）

- 已接入且必须保持兼容：
  - Explore Tokens：`apps/frontend/src/app/routes/explore-tokens.tsx`
  - Explore Transactions：`apps/frontend/src/app/routes/explore-transactions.tsx`
  - Token Details：`apps/frontend/src/app/routes/token-details.tsx`
- 需要补齐（本阶段冻结契约）：
  - Explore Pools：`apps/frontend/src/app/routes/explore-pools.tsx`（从占位改成可开发）
  - Pool Details：`apps/frontend/src/app/routes/pool-details.tsx`（从占位改成可开发）

---

## 6. 数据契约（GraphQL v1）

### 6.1 通用约定

**链参数**
- `chainId` 入参类型：`String!`
- 允许值（当前）：`"11155111"`（Sepolia）、`"534351"`（Scroll Sepolia）
- BFF 对非法 `chainId`：返回 GraphQL error（`BAD_USER_INPUT`）

**地址参数**
- `tokenAddress` / `pairAddress` 入参必须允许大小写混用，但服务端内部一律 normalize：
  - `trim` + `toLowerCase`
- 非法地址：返回 GraphQL error（`BAD_USER_INPUT`）

**数值与单位**
- `BigDecimal`：GraphQL 层按字符串序列化（前端按 number 解析展示）
- `Long`：用于 timestamp/blockNumber（前端用 number 处理即可）
- `date` / `timestamp` 统一使用 **Unix seconds**（前端需要时自行 *1000）

**limit 边界**
- 所有 list query 都必须支持 `limit`：
  - 默认值：见各 query
  - 最大值：`200`（超出按 200 截断）

**错误处理（与当前前端渲染兼容）**
- list query：在上游失败/空数据时，优先返回 `[]`（避免前端白屏）
- 非 list 的 payload（如 `exploreStats`）：允许返回 GraphQL error（前端显示 “Failed to load”）
- 该策略与当前 `ExploreTokensPage`、`ExploreTransactionsPage` 的 `react-query` error UI 兼容

---

## 6.2 Query：Explore Stats（协议统计）

### GraphQL

```
exploreStats(chainId: String!, days: Int): ExploreStatsPayload!
```

### 参数语义
- `days`：返回 `tvlSeries/volumeSeries` 的窗口天数
  - 默认：30
  - 允许：1 ~ 90（服务端 clamp）

### 返回字段（必须）
- `tvlUsd`：当前协议 TVL（USD）
- `volume24hUsd`：最近 24h volume（USD）
- `fees24hUsd`：最近 24h fees（USD），口径：`volume24hUsd * 0.003`
- `tvlSeries[]`：按天的 TVL 序列（`date`=dayStartUnixSeconds，`valueUsd`=TVL）
- `volumeSeries[]`：按天的 volume 序列（`date`=dayStartUnixSeconds，`valueUsd`=dailyVolumeUSD）

### Goldsky 数据来源建议（实现参考）
- `UniswapFactory.totalLiquidityUSD` → `tvlUsd`
- `UniswapDayData` 最新一条 `dailyVolumeUSD` → `volume24hUsd`
- `UniswapDayData` 最近 N 天：
  - `totalLiquidityUSD` → `tvlSeries.valueUsd`
  - `dailyVolumeUSD` → `volumeSeries.valueUsd`

### 验收标准
- Explore 页头部（`ExploreProtocolStats`）可渲染两张图（TVL area + Volume bar）且 hover 交互正常。

---

## 6.3 Query：Explore Tokens（Tokens 列表）

### GraphQL

```
exploreTokens(
  chainId: String!
  limit: Int
  search: String
  sort: ExploreTokenSort
): [ExploreTokenRow!]!
```

> 当前前端已在 `ExploreHttpAdapter` 使用 `exploreTokens(chainId, limit, search)`；新增 `sort` 必须为可选，且不影响旧调用。

### sort 枚举（v1 必须冻结）

```
enum ExploreTokenSort {
  VOLUME_24H_DESC
  TVL_DESC
  PRICE_DESC
  FDV_DESC
  CHANGE_1H_DESC
  CHANGE_1D_DESC
}
```

### search 语义
- `search` 支持前缀/包含匹配（建议包含匹配，以对齐 interface/sushi 的体验）：
  - token address（hex string）
  - symbol
  - name
- `search` 为空/空白：视为无过滤

### 返回字段（对齐前端 + 参考实现）
前端最小展示字段（MVP-1 必须保证可用）：
- `id`（token address）
- `symbol` / `name`
- `priceUsd`
- `change1h` / `change1d`
- `fdvUsd`
- `volume24hUsd`

基础字段（可用于 debug 与未来扩展，建议保留）：
- `decimals`、`totalSupply`、`derivedETH`

### 派生字段口径
- `priceUsd` = `token.derivedETH * bundle.ethPrice`
- `fdvUsd` = `priceUsd * totalSupply / (10 ^ decimals)`
- `change1h`：
  - 推荐：使用 `TokenHourData` 最近两条的 `close`（或 `priceUSD`）计算
- `change1d`：
  - 推荐：使用 `TokenDayData` 最近两天的 `priceUSD` 计算
- `volume24hUsd`：
  - 推荐：`TokenDayData` 最新 `dailyVolumeUSD`（简单稳定）
  - 或：聚合最近 24h `TokenHourData.volumeUSD`（更准确但成本更高）

### 验收标准
- `apps/frontend/src/app/routes/explore-tokens.tsx`：
  - 搜索 token（symbol/address/name）能得到结果
  - 字段显示不为 NaN；空值显示 `—`

---

## 6.4 Query：Explore Pools（Pools 列表，新增）

### GraphQL

```
explorePools(
  chainId: String!
  limit: Int
  search: String
  sort: ExplorePoolSort
): [ExplorePoolRow!]!
```

### sort 枚举（v1 必须冻结）

```
enum ExplorePoolSort {
  TVL_DESC
  VOLUME_24H_DESC
  TX_24H_DESC
  FEES_24H_DESC
  APR_DESC
}
```

### search 语义（对齐 interface/sushi）
- 支持匹配：
  - pair address（hex）
  - token0/token1 的 symbol/name/address
  - “TOKEN0/TOKEN1” 拼接名（不区分顺序可选）

### 返回类型（v1 必须冻结）

```
type ExplorePoolRow {
  pairAddress: String!
  chainId: String!
  token0: TokenLite!
  token1: TokenLite!

  tvlUsd: BigDecimal!
  tvlChange1d: BigDecimal

  volume24hUsd: BigDecimal!
  volume1wUsd: BigDecimal

  fees24hUsd: BigDecimal!
  tx24hCount: Int

  apr: BigDecimal
}
```

### 字段口径（实现提示）
- `tvlUsd`：`Pair.reserveUSD`
- `volume24hUsd`：优先 `PairDayData` 最新 `dailyVolumeUSD`
- `fees24hUsd`：`volume24hUsd * 0.003`
- `tx24hCount`：优先 `PairDayData.dailyTxns` 或 `PairHourData.hourlyTxns` 聚合
- `tvlChange1d`：
  - 取昨天 `PairDayData.reserveUSD` 与今天对比（或近两天 `reserveUSD`）
- `apr`（简化展示版）：
  - `(fees24hUsd * 365) / tvlUsd`，`tvlUsd==0` 时为 null

### 前端验收标准（可直接指导开发 Explore Pools 页）
- `apps/frontend/src/app/routes/explore-pools.tsx` 从占位改成真实列表后：
  - 每行可点击跳转到 `/explore/pools/$chain/$poolAddress`（你现有路由已定义）
  - 展示 TVL/Volume/Fee/Tx/APR（空值按 `—`）

---

## 6.5 Query：Explore Transactions（统一 recent transactions，升级）

### GraphQL（升级现有 query，保持兼容）

```
recentTransactions(
  chainId: String!
  limit: Int
  types: [ExploreTxType!]
): [TransactionPayload!]!
```

> 当前前端 `ExploreHttpAdapter` 使用 `recentTransactions(chainId, limit)`；新增 `types` 必须可选且不影响旧调用。

### 类型枚举（v1 必须冻结）

```
enum ExploreTxType { SWAP MINT BURN }
```

### 返回结构（沿用现有 TransactionPayload，但冻结 decodedData 的 JSON 结构）

```
type TransactionPayload {
  id: ID!
  chainId: String!
  blockNumber: Long!
  txHash: String!
  decodedName: String
  decodedData: String
  status: String!
  createdAt: String!
}
```

#### decodedName 约定
- `Swap` / `Mint` / `Burn`（首字母大写，与当前前端展示一致）

#### decodedData（JSON string）约定（v1 必须冻结）

> 前端当前在 `apps/frontend/src/app/routes/explore-transactions.tsx` 里会 `JSON.parse(decodedData)` 并渲染；因此必须冻结字段名与类型。

**通用字段（所有类型都必须包含）**
```
{
  "type": "SWAP" | "MINT" | "BURN",
  "timestamp": 1700000000,
  "pair": "0x...",
  "account": "0x..."  // 主要展示的钱包（swap 用 from；mint/burn 用 to/from 或 sender）
}
```

**SWAP 专有字段**
```
{
  "tokenIn":  { "id": "0x..", "symbol": "USDC", "name": "USD Coin", "decimals": 6 },
  "tokenOut": { "id": "0x..", "symbol": "WETH", "name": "Wrapped Ether", "decimals": 18 },
  "amountIn": "123.45",
  "amountOut": "0.067",
  "amountUsd": "201.23",

  "sender": "0x..",
  "from": "0x..",
  "to": "0x.."
}
```

**MINT 专有字段**
```
{
  "token0": { "id": "0x..", "symbol": "...", "name": "...", "decimals": 18 },
  "token1": { "id": "0x..", "symbol": "...", "name": "...", "decimals": 6 },
  "amount0": "1.23",
  "amount1": "456.78",
  "amountUsd": "999.99",

  "sender": "0x..",
  "to": "0x.."
}
```

**BURN 专有字段**
```
{
  "token0": { "id": "0x..", "symbol": "...", "name": "...", "decimals": 18 },
  "token1": { "id": "0x..", "symbol": "...", "name": "...", "decimals": 6 },
  "amount0": "1.23",
  "amount1": "456.78",
  "amountUsd": "999.99",

  "sender": "0x..",
  "to": "0x.."
}
```

### 验收标准
- Explore Transactions 页面能在同一列表中展示 Swap/Mint/Burn，并可按类型过滤（UI 可后续做，但后端契约必须支持）。

---

## 6.6 Query：Token Details（已存在，冻结口径）

### GraphQL

```
tokenDetails(chainId: String!, tokenAddress: String!): TokenDetails
```

### 返回字段（必须）
- `priceUsd`、`change24hPct`、`tvlUsd`、`volume24hUsd`、`fdvUsd`

### 口径建议
- `priceUsd`：同上（derivedETH * ethPrice）
- `change24hPct`：
  - 推荐：`TokenHourData` 近 24h 的 open/close 计算（更贴近 24h）
  - 或：`TokenDayData` 昨天 vs 今天
- `tvlUsd`：
  - 推荐：`TokenHourData.totalValueLockedUSD`（最新）
  - 或从 `Token.totalLiquidity` * `priceUsd`
- `volume24hUsd`：`TokenDayData.dailyVolumeUSD`（最新）

### 验收标准
- `apps/frontend/src/app/routes/token-details.tsx` 顶部 header 数据可正确展示，不需要 mock。

---

## 6.7 Query：Token Price Candles（已存在，冻结）

### GraphQL

```
tokenPriceCandles(
  chainId: String!
  tokenAddress: String!
  interval: TokenChartInterval!
  from: Int!
  to: Int!
): [TokenOhlc!]!
```

### interval
- `MINUTE` / `HOUR` / `DAY`（与 `apps/frontend/src/domain/ports/token-port.ts` 一致）

### from/to 语义
- `from` / `to` 为 Unix seconds
- 服务端返回的 `timestamp` 为该 bucket 的 periodStart（seconds）
- 返回必须按 timestamp 升序

### 注意：Goldsky subgraph 的窗口限制（实现必须考虑）
- Minute 数据只保留 ~28h
- Hour 数据只保留 ~32d
- Day 数据可长期

### 验收标准
- Token Details 的 1D/1W/1M/1Y 区间都能有合理数据（缺数据时返回空数组，前端显示空态或 fallback）。

---

## 6.8 Query：Token Pools / Token Transactions（已存在，冻结）

### GraphQL

```
tokenPools(chainId: String!, tokenAddress: String!, limit: Int): [TokenPoolRow!]!
tokenTransactions(chainId: String!, tokenAddress: String!, limit: Int): [TokenTransactionRow!]!
```

### 口径建议
- `tokenPools`：按 `Pair.reserveUSD` 排序取 top N（对齐 sushi “token page 下方 pools”）
- `tokenTransactions`：按 timestamp desc，取与 token 相关的 swaps（必要时限定 top pairs 范围）

### 验收标准
- Token Details 页面：
  - Pools 表可展示 top pools
  - Transactions 表可展示 swaps

---

## 6.9 Query：Pool Details（新增，冻结）

### GraphQL

```
poolDetails(chainId: String!, pairAddress: String!): PoolDetails
```

### 返回类型（v1 必须冻结）

```
type PoolDetails {
  chainId: String!
  pairAddress: String!
  token0: TokenLite!
  token1: TokenLite!

  tvlUsd: BigDecimal!
  volume24hUsd: BigDecimal!
  fees24hUsd: BigDecimal!
  tx24hCount: Int

  apr: BigDecimal
  reserve0: BigDecimal
  reserve1: BigDecimal
  token0Price: BigDecimal
  token1Price: BigDecimal
}
```

### 口径建议
- `reserve0/reserve1/token0Price/token1Price`：来自 `Pair` 实体（subgraph）
- 其余与 ExplorePools 同口径

### 验收标准
- `apps/frontend/src/app/routes/pool-details.tsx` 可改造成真实数据渲染（不需要临时 mock）。

---

## 6.10 Query：Pool Candles / Pool Transactions（新增，冻结）

### GraphQL

```
poolPriceCandles(
  chainId: String!
  pairAddress: String!
  interval: PoolChartInterval!
  from: Int!
  to: Int!
): [PoolOhlc!]!

poolTransactions(
  chainId: String!
  pairAddress: String!
  limit: Int
  types: [ExploreTxType!]
): [PoolTransactionRow!]!
```

### 类型枚举（v1 必须冻结）

```
enum PoolChartInterval { HOUR DAY }
```

### 返回类型（v1 必须冻结）

```
type PoolOhlc {
  timestamp: Int!
  tvlUsd: BigDecimal!
  volumeUsd: BigDecimal!
  feesUsd: BigDecimal!
}

type PoolTransactionRow {
  type: ExploreTxType!
  timestamp: Long!
  txHash: String!
  amountUsd: BigDecimal
  token0Amount: BigDecimal
  token1Amount: BigDecimal
  account: String
}
```

> 说明：Pool 交易列表不必复用 `decodedData JSON`，这里建议给 PoolDetails 页面一个“类型化的 row”，减少前端解析成本；Explore 交易列表为兼容现状保留 JSON 方案。

### 口径建议
- `poolPriceCandles`：
  - 从 `PairDayData` / `PairHourData` 读取 `reserveUSD/dailyVolumeUSD/hourlyVolumeUSD` 并计算 fees
- `poolTransactions`：
  - 从 `Swap/Mint/Burn` 实体按 timestamp desc 拉取

---

## 7. 前端交互与页面需求（按页面列出开发要点）

> MVP-1 冻结“页面需要什么数据”，以便前端与后端并行开发。

### 7.1 Explore Layout（全局）

参考：
- 当前实现：`apps/frontend/src/app/components/explore-layout.tsx`
- Interface：Explore 顶部 stats + tab + filters

要求：
- Explore 头部 stats（TVL/Volume 图）依赖 `exploreStats`
- Tabs：Tokens/Pools/Transactions 必须保持一致导航与路由结构（你已实现）

### 7.2 Explore Tokens

参考：
- 当前实现：`apps/frontend/src/app/routes/explore-tokens.tsx`
- Sushi tokens columns

要求（数据层）：
- `exploreTokens` 返回必须覆盖前端渲染字段：name/symbol/priceUsd/change1h/change1d/fdvUsd
- 推荐补齐 `volume24hUsd` 以对齐产品（可先不展示但契约要有）

### 7.3 Explore Pools

参考：
- Sushi pools columns

要求（数据层）：
- `explorePools` 提供 TVL、24h volume、24h fees、24h tx、APR

### 7.4 Explore Transactions

参考：
- Interface recent transactions filter（Swap/Add/Remove）

要求（数据层）：
- `recentTransactions` 支持 `types` 过滤（可选）
- `decodedData` JSON 结构必须稳定（见上）

### 7.5 Token Details

参考：
- Sushi token page：chart + swap widget + info + pools table
- 当前实现：`apps/frontend/src/app/routes/token-details.tsx`

要求（数据层）：
- `tokenDetails`、`tokenPriceCandles`、`tokenPools`、`tokenTransactions` 均需稳定返回
- chart 范围由前端做 bucket（你已实现 bucketOhlcSeries），后端只需按 interval 返回 base candles

### 7.6 Pool Details

参考：
- Sushi V2 pool page：chart + composition + stats + tx

要求（数据层）：
- `poolDetails`、`poolPriceCandles`、`poolTransactions` 必须齐全

---

## 8. 非功能需求（NFR）

- 性能目标（P0 testnet）：
  - Explore Tokens/Pools：P95 < 800ms（含缓存）
  - Details：P95 < 1200ms
- 可靠性：
  - Goldsky 失败时，list query 返回空数组；payload query 可 error
- 可观测：
  - BFF 对每个 query 打点：cacheHit、goldskyLatency、errorCount

---

## 9. MVP-1 验收清单（可直接用于开发验收）

### 9.1 契约验收

- GraphQL schema 满足本 PRD 中所有 query/type/enum/input（字段名、nullability、参数默认值一致）
- 对现有前端调用兼容：
  - `exploreStats(chainId, days)`
  - `exploreTokens(chainId, limit, search)`
  - `recentTransactions(chainId, limit)`
  - `tokenDetails/tokenPriceCandles/tokenPools/tokenTransactions`

### 9.2 行为验收（语义）

- `chainId` 非法：GraphQL error
- `limit` 越界：clamp 到 200
- `search` 空：无过滤
- `decodedData` JSON：字段齐全且能被前端 parse

---

## 10. 后续文档拆分建议（从 MVP-1 派生）

- MVP-2（BFF 读 Goldsky + Redis）工程设计：模块划分、错误策略、缓存 key、重试/超时
- MVP-3（最小 pipeline）PRD：webhook payload、幂等键、失效 key 规则、可选预热
- MVP-4（Explore Pools + Pool Details）前端页面 PRD：表格列、排序、空态、跳转

