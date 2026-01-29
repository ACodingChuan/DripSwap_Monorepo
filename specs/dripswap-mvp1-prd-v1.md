# PRD：DripSwap MVP-1（契约冻结 + Goldsky 读路径落地）

> **版本**：v1（可直接指导开发）
>
> **范围定位**：MVP-1 同时完成“可执行的产品定义 + 数据契约冻结 + 第一版工程落地（BFF 读 Goldsky + Redis）”。（原计划）新增页面（Explore Pools / Pool Details）的完整落地在后续 MVP-3；但当前仓库已提前落地基础版，详见 6.1.2。
>
> **参考实现**：Uniswap `interface` Explore、`sushiswap` Explore & V2 pool details/management、以及当前 DripSwap 代码现状（`apps/frontend` / `apps/bff` / `apps/subgraphgoldsky`）。
>
> **Goldsky 能力前提**：支持 entity 增量推送 + webhook/流式导出 + sink（MVP-1 不强制落地 pipeline/webhook 或大 sink）。
>
> **最后更新**：2026-01-20

---

## 1. 背景与问题

DripSwap 目标是做一个基于自建 Uniswap V2 Factory 的测试网 DEX，核心闭环为：

`Faucet → Swap → Add/Remove Liquidity → Explore → Token/Pool Details`

当前代码进展（以仓库为准）：

- 前端 `apps/frontend`：
  - Explore Tokens / Explore Transactions / Token Details 已接入 BFF GraphQL（`/graphql`）。
  - Explore Pools 已落地（可查/可排序/可跳转 Pool Details）。
  - Pool Details 已落地基础信息 + 图表 + 交易列表（对齐 Sushi V2 pool page 的布局骨架）。
  - Add/Remove Liquidity 页面 UI 存在但未接入链上 Router。
  - Faucet UI 存在但未对接合约/后端。
- BFF `apps/bff`：
  - 已有 GraphQL schema 与部分 resolver（但实现仍依赖“旧的同步入库模式”）。
- Goldsky Subgraph `apps/subgraphgoldsky`：
  - 已有 V2 核心实体与时间聚合（Swap/Mint/Burn/TokenDayData/TokenHourData/TokenMinuteData/PairDayData/PairHourData/UniswapDayData/Bundle…），可作为真相源。

当前最大阻塞：**缺少一套完整、稳定、可演进的“前端需要什么数据”契约**，导致后续 BFF（改读 Goldsky + Redis）与前端（补齐 Pools/Details/LP）无法并行推进。

---

## 2. MVP-1 目标与非目标（合并原 MVP-1 + MVP-2）

### 2.1 目标（必须达成）

1) 冻结 P0 “读查询”数据契约：Explore（Tokens/Pools/Transactions）+ Token Details + Pool Details  
2) 落地第一版实现：BFF 读 Goldsky + Redis 缓存，至少保证现有前端页面可跑（Explore Stats/Tokens/Transactions + Token Details）  
3) 明确每个字段的数据来源（Goldsky entity/计算口径）与精度/单位/空值规则  
4) 明确搜索、排序、过滤、limit 的语义与边界  
5) 明确错误处理契约（哪些场景返回空数组/哪些抛 GraphQL error）以保证前端可稳定渲染  

### 2.2 非目标（MVP-1 不做，实际实现见 6.1.2）

- 不要求本阶段落地最小 pipeline/webhook（属于 MVP-2）
- （原计划）不要求本阶段完成 Explore Pools / Pool Details 的前端页面落地与新增 query 的工程实现（属于 MVP-3）
- 不实现 Add/Remove Liquidity 的链上交易（属于 MVP-4）
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

## 5. 产品范围（MVP-1 要冻结/实现的页面与数据点）

> **注意**：MVP-1 的交付物包含：
> - “契约 + 口径 + 验收条件”（本 PRD）
> - “第一版工程落地”（BFF 读 Goldsky + Redis），优先保证现有前端页面可运行；新增页面完整落地见后续 MVP

### 5.1 页面清单（前端现状 vs MVP-1 契约）

- 已接入且必须保持兼容：
  - Explore Tokens：`apps/frontend/src/app/routes/explore-tokens.tsx`
  - Explore Transactions：`apps/frontend/src/app/routes/explore-transactions.tsx`
  - Token Details：`apps/frontend/src/app/routes/token-details.tsx`
- 已补齐（与契约一起落地）：
  - Explore Pools：`apps/frontend/src/app/routes/explore-pools.tsx`
  - Pool Details：`apps/frontend/src/app/routes/pool-details.tsx`

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

### 6.1.1 MVP-1 vs MVP-3 的实现边界（本 PRD 的落地口径）

> 本 PRD 在 MVP-1 阶段要求同时做到“契约冻结 + 最小可运行实现（Goldsky + Redis）”。
> 但考虑到前端当前页面现状，6.x 的 query 会按“现有页面必需 vs 新增页面”分阶段落地：

**MVP-1 必须实现（用于支撑现有前端页面可跑）**
- 6.2 `exploreStats`（Explore 顶部图表）
- 6.3 `exploreTokens`（Explore Tokens 列表）
- 6.5 `recentTransactions`（Explore Transactions 列表，保持兼容；types 过滤可后置）
- 6.6 `tokenDetails`（Token Details header）
- 6.7 `tokenPriceCandles`（Token 图表）
- 6.8 `tokenPools` / `tokenTransactions`（Token Details pools/tx 列表）

**MVP-1 仅冻结契约（原计划）**
- （原计划）6.4 `explorePools`
- （原计划）6.9 `poolDetails`
- （原计划）6.10 `poolPriceCandles` / `poolTransactions`

**数据源与依赖约束（MVP-1 默认）**
- 权威数据源：Goldsky Subgraph
- BFF：`Redis（read-through） -> Goldsky`（不要求启用 sink/全量落库）

### 6.1.2 Repo 实际完成情况（2026-01-20，供后续 AI 接手）

> 本节为“实际落地 vs PRD 原计划”的差异记录（以仓库代码为准），用于后续继续开发时快速理解当前状态。

**完成范围（整体结论）**
- ✅ MVP-1 读路径契约（6.2~6.10）已全部落地可用：BFF（Goldsky + Redis read-through）+ 前端对应页面（Explore Tokens/Pools/Transactions、Token Details、Pool Details）
- ✅ 6.4/6.9/6.10 原本标注为 “MVP-3 才落地”，已提前在本次实现中完成（页面与后端一起完成）

**按 PRD 目标核对（2.1 必须达成项）**
| 目标 | 状态 | 备注 |
|---|---|---|
| 1) 冻结 P0 “读查询”数据契约（6.2~6.10） | ✅ 已达成 | schema 已补齐并与前端调用对齐 |
| 2) 落地第一版实现：BFF 读 Goldsky + Redis | ✅ 已达成 | 采用 read-through + batch DataLoader；本地 DB 为空也可跑（读 Goldsky） |
| 3) 字段数据来源/口径/空值规则 | ✅ 已补充 | PRD 各节 + “契约与实现差异（重要）” |
| 4) search/sort/filter/limit 语义与边界 | ✅ 基本达成 | 关键 query 已实现 clamp；少量过滤语义（如 recentTransactions.types）仍由前端兜底 |
| 5) 错误处理契约（空数组 vs error） | ✅ 已对齐 | list query 优先 `[]`，payload 允许 error |

**仍需注意的差异/欠缺（不阻塞 MVP-1，但会影响后续优化）**
- `recentTransactions(types)`：schema 支持但后端未按入参过滤（当前前端用 UI 侧过滤兜底）
- 可观测性（NFR）：PRD 提到的 `cacheHit/goldskyLatency/errorCount` 目前未统一埋点
- 统计口径：部分 “24h” 数据在 testnet 长时间无交易时会为空/为 0（前端已做展示兜底：如 volume=0 渲染为 `—`）
- Subgraph 约束：Goldsky 对 `first` 有上限（<=1000），实现已做 clamp；若后续加大窗口需改为分页/游标

**后端架构落地（BFF）**
- 统一 Query Resolver：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`
- 统一 DataLoader 注册入口（替代多个 Registrar）：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/UnifiedDataLoaderRegistrar.java`
- Field Resolver（SchemaMapping）目录：`apps/bff/src/main/java/com/dripswap/bff/gql/resolver/field/`
- Loader（Redis read-through + Subgraph batch）目录：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/`
- Redis key 规范：`apps/bff/src/main/java/com/dripswap/bff/util/redis/RedisKeys.java`

**前端页面落地（Frontend）**
- Explore Tokens：`apps/frontend/src/app/routes/explore-tokens.tsx`
- Explore Pools：`apps/frontend/src/app/routes/explore-pools.tsx`
- Explore Transactions：`apps/frontend/src/app/routes/explore-transactions.tsx`
- Token Details：`apps/frontend/src/app/routes/token-details.tsx`
- Pool Details（对齐 Sushi V2 Pool Page 布局：图表 + 组成 + 统计 + 交易表）：`apps/frontend/src/app/routes/pool-details.tsx`

**Query 落地清单（按 6.x 对应，便于快速定位代码）**
- 6.2 `exploreStats`
  - BFF Query：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`
  - Field Resolver：`apps/bff/src/main/java/com/dripswap/bff/gql/resolver/field/ExploreStatsFieldResolver.java`
  - DataLoader：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/stats/`
  - 前端：`apps/frontend/src/app/services/explore-service.ts`
- 6.3 `exploreTokens`
  - BFF Query：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`
  - Field Resolver：`apps/bff/src/main/java/com/dripswap/bff/gql/resolver/field/ExploreTokenRowFieldResolver.java`
  - DataLoader：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/token/`（ethPrice/tokenDayStats/tokenHourStats）
  - 前端：`apps/frontend/src/app/routes/explore-tokens.tsx`
- 6.4 `explorePools`
  - BFF Query：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`
  - Field Resolver：`apps/bff/src/main/java/com/dripswap/bff/gql/resolver/field/ExplorePoolRowFieldResolver.java`
  - DataLoader：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/pool/PoolDayWindowStatsLoader.java`
  - 前端：`apps/frontend/src/app/routes/explore-pools.tsx`
- 6.5 `recentTransactions`
  - BFF Query：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`
  - 前端：`apps/frontend/src/app/routes/explore-transactions.tsx`
- 6.6 `tokenDetails`
  - BFF Query：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`（只查 token base）
  - Field Resolver：`apps/bff/src/main/java/com/dripswap/bff/gql/resolver/field/TokenDetailsFieldResolver.java`
  - DataLoader：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/token/TokenLatestTvlLoader.java`（tvl 快照）
  - 前端：`apps/frontend/src/app/routes/token-details.tsx`（header）
- 6.7 `tokenPriceCandles`
  - BFF Query：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`
  - 前端：`apps/frontend/src/app/routes/token-details.tsx`（Price/Volume/TVL 图表切换；Price 支持 Line/K-line）
- 6.8 `tokenPools` / `tokenTransactions`
  - BFF Query：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`（Query 内调用 DataLoader）
  - DataLoader：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/token/TokenPoolsLoader.java`
  - DataLoader：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/token/TokenTransactionsLoader.java`
  - 前端：`apps/frontend/src/app/routes/token-details.tsx`（Pools/Transactions 列表）
- 6.9 `poolDetails`
  - BFF Query：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`
  - DataLoader：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/pool/PoolDetailsLoader.java`
  - Field Resolver：`apps/bff/src/main/java/com/dripswap/bff/gql/resolver/field/PoolDetailsFieldResolver.java`（复用 6.4 的日窗口统计口径）
  - 前端：`apps/frontend/src/app/routes/pool-details.tsx`（右侧 cards）
- 6.10 `poolPriceCandles` / `poolTransactions`
  - BFF Query：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`
  - DataLoader（candles）：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/pool/PoolCandleWindowLoader.java`
  - DataLoader（tx）：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/pool/PoolTransactionsLoader.java`
  - 前端：`apps/frontend/src/app/routes/pool-details.tsx`（图表 + tx 表）

**契约与实现差异（重要）**
- `recentTransactions(types)`：schema 支持，但当前实现为“返回混合列表”，前端自行过滤（types 入参不影响返回）
- `TokenDetails.change24hPct`：复用 ExploreTokens 的 `change1d` 逻辑；缺数据时后端返回 0（前端按展示规则兜底）
- `TokenDetails.volume24hUsd`：缺数据时后端返回 0；前端为了避免“长时间无交易时显示 $0.00 的误导”，把 0 渲染为 `—`
- `poolPriceCandles`：依赖 `PairHourData/PairDayData`；当 hour/day 窗口不足时允许返回空数组（前端显示空态）

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

### MVP-1 实现要求（必须）
- 必须可在“本地 DB 为空”的情况下正常返回（即读 Goldsky + Redis 缓存）
- `days` 支持默认与 clamp（1~90）
- `tvlSeries/volumeSeries` 必须按时间升序返回，且点数不足时补齐到 >=2

### 当前实现状态（repo）
- ✅ 已完成（满足 MVP-1 的“Goldsky + Redis”最小可运行实现与前端交互验收）
- 主查询（只返回 chainId/days seed）：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`
- 字段级 resolver（SchemaMapping）：`apps/bff/src/main/java/com/dripswap/bff/gql/resolver/field/ExploreStatsFieldResolver.java`
- DataLoader（Redis 二级缓存 + Goldsky 批量取数）：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/stats/`
- 前端使用与渲染：`apps/frontend/src/app/components/explore-protocol-stats.tsx:45`

### Redis 缓存（当前实现）
- Full（含 series）
  - Key：`ds:v2:{chainId}:explore:stats:{days}`
  - TTL：60s
  - 说明：由 DataLoader 维护（read-through）
- Summary（不含 series，用于只查顶部数字时避免拉 series）
  - Key：`ds:v2:{chainId}:explore:stats:summary`
  - TTL：60s
  - 说明：由 DataLoader 维护（read-through）

### BFF GraphQL 验证查询（用于验收 6.2）

```graphql
query ExploreStats($chainId: String!, $days: Int) {
  exploreStats(chainId: $chainId, days: $days) {
    chainId
    tvlUsd
    volume24hUsd
    fees24hUsd
    tvlSeries { date valueUsd }
    volumeSeries { date valueUsd }
  }
}
```

Variables (Sepolia example):
```json
{ "chainId": "11155111", "days": 30 }
```

### Goldsky 实体查询（用于核对 6.2 口径）

> 注意：以下查询是 **直查 Goldsky subgraph 的 entity**，不是查 BFF 的 `exploreStats`。
> 用于核对：
> - `tvlUsd` ≈ `uniswapFactories[0].totalLiquidityUSD`
> - `volume24hUsd` ≈ `uniswapDayDatas[0].dailyVolumeUSD`（最新日）
> - `tvlSeries/volumeSeries` 来自 `uniswapDayDatas(first:$days)`，按 `date` 升序后映射

```graphql
query ExploreStatsEntities($days: Int!) {
  factories: uniswapFactories(first: 1) {
    totalLiquidityUSD
  }
  latestDay: uniswapDayDatas(first: 1, orderBy: date, orderDirection: desc) {
    date
    dailyVolumeUSD
  }
  series: uniswapDayDatas(first: $days, orderBy: date, orderDirection: desc) {
    date
    totalLiquidityUSD
    dailyVolumeUSD
  }
}
```

Variables:
```json
{ "days": 30 }
```

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

### 空值规则（MVP-1 实现约束）
- 若最近 1h / 1d 没有 swap 交易活动（hour/day 的 `volumeUSD` / `dailyVolumeUSD` 为 0），`change1h/change1d` 必须返回 `null`（前端显示 `—`）。

### 验收标准
- `apps/frontend/src/app/routes/explore-tokens.tsx`：
  - 搜索 token（symbol/address/name）能得到结果
  - 字段显示不为 NaN；空值显示 `—`

### MVP-1 实现要求（必须）
- 必须返回前端最小展示字段：`id/symbol/name/priceUsd/change1h/change1d/fdvUsd/volume24hUsd`
- `sort` 必须保持可选且不影响旧调用（MVP-1 可不实现排序逻辑，但 schema 需允许传入）
- list query：上游失败/空数据时返回 `[]`

### 当前实现状态（repo）
- ✅ 已完成（Goldsky 直查 + Redis 缓存；计算字段采用 GraphQL Field Resolver + DataLoader 批量取数，不依赖本地 DB/sink）
- 主查询（只取基础 token 列表）：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`
- 计算字段 Field Resolver：`apps/bff/src/main/java/com/dripswap/bff/gql/resolver/field/ExploreTokenRowFieldResolver.java`
- DataLoader 批量实现：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/token/`
- GraphQL schema（可选 sort）：`apps/bff/src/main/resources/graphql/schema.graphqls:46`

### Redis 缓存（当前实现）
- Key：`ds:v2:{chainId}:tokens:list:{limit}:{searchHash}`
- TTL：60s
- 说明：只缓存基础 token 列表（id/symbol/name/decimals/totalSupply/derivedETH）；`priceUsd/change1h/change1d/fdvUsd/volume24hUsd` 为按需计算字段（GraphQL field resolver），跨请求不缓存，单次请求内用 DataLoader 批量合并外部查询。

### Redis 二级缓存（DataLoader read-through，用于减少 Goldsky 请求）

> 这些 key 由后端 DataLoader 维护：先 `MGET` 命中则不再请求 Goldsky；miss 才会批量请求并写回 Redis。

- ETH 价格
  - Key：`ds:v2:{chainId}:bundle:ethPrice`
  - TTL：60s
- Token Day stats（用于 `change1d` / `volume24hUsd`）
  - Key：`ds:v2:{chainId}:token:{tokenId}:dayStats`
  - TTL：60s
- Token Hour stats（用于 `change1h`）
  - Key：`ds:v2:{chainId}:token:{tokenId}:hourStats`
  - TTL：60s

### BFF GraphQL 验证查询（用于验收 6.3）

```graphql
query ExploreTokens($chainId: String!, $limit: Int, $search: String) {
  exploreTokens(chainId: $chainId, limit: $limit, search: $search) {
    id
    chainId
    symbol
    name
    decimals
    totalSupply
    derivedETH
    priceUsd
    change1h
    change1d
    fdvUsd
    volume24hUsd
  }
}
```

Variables (Sepolia example):
```json
{ "chainId": "11155111", "limit": 50, "search": "" }
```

### Goldsky 实体查询（用于核对 6.3 口径）

> 注意：以下查询是 **直查 Goldsky subgraph 的 entity**，不是查 BFF 的 `exploreTokens`。
> 你可以在 Goldsky 的 GraphQL 页面直接执行，用来核对：
> - `priceUsd` = `token.derivedETH * bundle.ethPrice`
> - `change1h` 口径（TokenHourData open/close）
> - `change1d` 口径（TokenDayData priceUSD 昨天 vs 今天）
> - `volume24hUsd` 口径（TokenDayData 最新 dailyVolumeUSD）
>
> 如果最近 1 小时/1 天没有交易，BFF 的 `change1h/change1d` 会按“无交易则不展示”的规则返回 `null`，
> 前端会显示为 `—`（即你不会看到任何百分比数值）。

#### 1) 取 ETH 价格（Bundle）+ Top tokens 基础字段

```graphql
query ExploreTokensEntities($first: Int!) {
  latestBundle: bundles(first: 1, orderBy: timestamp, orderDirection: desc) {
    ethPrice
    timestamp
  }
  tokens: tokens(first: $first, orderBy: tradeVolumeUSD, orderDirection: desc) {
    id
    symbol
    name
    decimals
    totalSupply
    derivedETH
  }
}
```

Variables:
```json
{ "first": 50 }
```

#### 2) 取某个 token 的 hour/day 数据（用于验证 change/volume）

```graphql
query TokenStatsEntities($token: Bytes!, $dayFirst: Int!, $hourFirst: Int!) {
  token(id: $token) {
    id
    symbol
    name
    decimals
    totalSupply
    derivedETH
  }
  latestBundle: bundles(first: 1, orderBy: timestamp, orderDirection: desc) {
    ethPrice
  }
  day: tokenDayDatas(
    first: $dayFirst
    orderBy: date
    orderDirection: desc
    where: { token: $token }
  ) {
    date
    dailyVolumeUSD
    dailyTxns
    priceUSD
  }
  hour: tokenHourDatas(
    first: $hourFirst
    orderBy: periodStartUnix
    orderDirection: desc
    where: { token: $token }
  ) {
    periodStartUnix
    open
    close
    volumeUSD
  }
}
```

Variables (example):
```json
{ "token": "0x0000000000000000000000000000000000000000", "dayFirst": 2, "hourFirst": 2 }
```

#### 2.1) 批量取 day/hour（对应 BFF DataLoader 的 `token_in: [...]` 查询）

> 这个版本等价于 BFF 的 batch loader（一次查多个 tokenId），用于你在 Goldsky Playground 里验证“批量过滤器”是否可用。

```graphql
query TokenDayStatsBatch($tokenIds: [Bytes!]!, $first: Int!) {
  rows: tokenDayDatas(
    first: $first
    orderBy: date
    orderDirection: desc
    where: { token_in: $tokenIds }
  ) {
    date
    dailyVolumeUSD
    dailyTxns
    priceUSD
    token { id }
  }
}
```

```graphql
query TokenHourStatsBatch($tokenIds: [Bytes!]!, $first: Int!) {
  rows: tokenHourDatas(
    first: $first
    orderBy: periodStartUnix
    orderDirection: desc
    where: { token_in: $tokenIds }
  ) {
    periodStartUnix
    open
    close
    volumeUSD
    token { id }
  }
}
```

#### 3) 验证“最近 1h / 1d 是否有交易”（用于解释为什么 change 必须为 null）

> 用这个查询直接判断窗口内是否有数据/是否有交易：
> - 最近 1h：`recentHour[0].volumeUSD > 0` 才认为“有交易”
> - 最近 1d：`recentDay[0].dailyVolumeUSD > 0` 才认为“有交易”
>
> `hourFrom/dayFrom` 需要你在本地按当前时间计算：
> - `hourFrom` = 当前小时起始 `currentHourStart - 3600`
> - `dayFrom` = 当天起始 `todayStart - 86400`

```graphql
query TokenActivityWindow($token: Bytes!, $hourFrom: Int!, $dayFrom: Int!) {
  recentHour: tokenHourDatas(
    first: 2
    orderBy: periodStartUnix
    orderDirection: desc
    where: { token: $token, periodStartUnix_gte: $hourFrom }
  ) {
    periodStartUnix
    open
    close
    volumeUSD
  }
  recentDay: tokenDayDatas(
    first: 2
    orderBy: date
    orderDirection: desc
    where: { token: $token, date_gte: $dayFrom }
  ) {
    date
    dailyTxns
    dailyVolumeUSD
    priceUSD
  }
}
```

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

### MVP-1 实现要求（契约冻结即可；实现留到 MVP-3）
- （原计划）MVP-1 只要求 schema/type/enum 与本节一致，resolver 允许暂时返回 `[]`
- ✅（实际落地）已完成：BFF 实现 `explorePools` 并驱动前端 Explore Pools 页面可用
  - BFF：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`（explorePools base list）
  - Field Resolver：`apps/bff/src/main/java/com/dripswap/bff/gql/resolver/field/ExplorePoolRowFieldResolver.java`（volume/fees/tx/apr 等派生）
  - DataLoader：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/pool/PoolDayWindowStatsLoader.java`（PairDayData 窗口统计）
  - 前端：`apps/frontend/src/app/routes/explore-pools.tsx`

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

### MVP-1 实现要求（必须，但允许分步）
- 必须保持兼容：`recentTransactions(chainId, limit)` 能返回数据（list 失败返回 `[]`）
- `types` 必须保持可选且不影响旧调用（MVP-1 可不实现过滤逻辑，但 schema 需允许传入）
- `decodedData` JSON 结构必须稳定（字段名/类型对齐本节），避免前端 parse 失败

---

## 6.6 Query：Token Details

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

### MVP-1 实现要求（必须）
- 必须可在“本地 DB 为空”的情况下正常返回（即读 Goldsky + Redis 缓存）
- 缺数据时允许字段为 null，但页面不应出现 NaN/崩溃

---

## 6.7 Query：Token Price Candles

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

### MVP-1 实现要求（必须）
- `timestamp` 必须为 bucket 的 periodStart（seconds），并按 timestamp 升序返回
- 当 minute/hour 数据窗口不足时，允许返回 `[]`（由前端做空态/降级）

---

## 6.8 Query：Token Pools / Token Transactions（已实现）

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

### MVP-1 实现要求（必须）
- `limit` 支持默认与 clamp；list 失败返回 `[]`
- `tokenPools/tokenTransactions` 返回结构应满足前端渲染，不需要 mock 字段

---

## 6.9 Query：Pool Details（已实现）

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

### MVP-1 实现要求
- ✅ 已完成（BFF + 前端页面已落地）
  - BFF：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`（poolDetails）
  - DataLoader：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/pool/PoolDetailsLoader.java`（Pair 基础信息 + reserves/price）
  - Field Resolver：`apps/bff/src/main/java/com/dripswap/bff/gql/resolver/field/PoolDetailsFieldResolver.java`（复用 ExplorePools 统计口径）
  - 前端：`apps/frontend/src/app/routes/pool-details.tsx`

---

## 6.10 Query：Pool Candles / Pool Transactions（已实现）

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

### MVP-1 实现要求
- ✅ 已完成（BFF + 前端页面已落地）
  - BFF：`apps/bff/src/main/java/com/dripswap/bff/gql/api/QueryResolver.java`（poolPriceCandles/poolTransactions）
  - DataLoader（candles）：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/pool/PoolCandleWindowLoader.java`
  - DataLoader（tx）：`apps/bff/src/main/java/com/dripswap/bff/gql/dataloader/loader/pool/PoolTransactionsLoader.java`
  - 前端：`apps/frontend/src/app/routes/pool-details.tsx`

---

## 7. 前端交互与页面需求（按页面列出开发要点）

> （原计划）MVP-1 冻结“页面需要什么数据”，并落地“现有页面可跑”的最小实现；新增页面在后续 MVP 完整落地。
> （实际落地）Explore Pools + Pool Details 已提前完成基础版（详见 6.1.2），后续 MVP 可在此基础上继续做细节与性能优化。

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
  - `explorePools(chainId, limit, search, sort)`
  - `recentTransactions(chainId, limit)`
  - `tokenDetails/tokenPriceCandles/tokenPools/tokenTransactions`
  - `poolDetails/poolPriceCandles/poolTransactions`

### 9.2 行为验收（语义）

- `chainId` 非法：GraphQL error
- `limit` 越界：clamp 到 200
- `search` 空：无过滤
- `decodedData` JSON：字段齐全且能被前端 parse

### 9.3 工程验收（可运行）

**数据源验收（不依赖 sink/全量落库）**
- 在本地 DB 为空/未启用 sink 的情况下：
  - `exploreStats` / `exploreTokens` / `recentTransactions` / `tokenDetails` / `tokenPriceCandles` / `tokenPools` / `tokenTransactions` 仍可返回并驱动前端渲染
- BFF 读路径：`Redis（read-through） -> Goldsky`，缓存 key 与 TTL 生效（至少 `exploreStats`）

**页面验收（以现有前端页面为准）**
- 本地起 `apps/bff` + `apps/frontend`：
  - Explore：Stats/Tokens/Transactions 可渲染
  - Explore Pools：列表可渲染、可跳转 Pool Details
  - Token Details：header + candles + pools + tx 列表可渲染（无 mock）
  - Pool Details：图表（Volume/TVL/Fees）+ Pool Liquidity + Statistics + Transactions 列表可渲染

**接口验收（MVP-1 已实现）**
- BFF 实现并稳定返回：
  - `exploreStats(chainId, days)`
  - `exploreTokens(chainId, limit, search)`
  - `explorePools(chainId, limit, search, sort)`
  - `recentTransactions(chainId, limit)`
  - `tokenDetails/tokenPriceCandles/tokenPools/tokenTransactions`
  - `poolDetails/poolPriceCandles/poolTransactions`

---

## 10. 后续文档拆分建议（从 MVP-1 派生）

- MVP-2（最小 pipeline）PRD：webhook payload、幂等键、失效 key 规则、可选预热
- MVP-3（Explore Pools + Pool Details）前端页面 PRD：表格列、排序、空态、跳转
- MVP-4（V2 Add/Remove Liquidity）前端交易 PRD：approve/slippage/deadline、错误处理、状态追踪
- MVP-5（Faucet）PRD：领取路径（二选一）、风控与历史记录
