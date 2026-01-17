# DripSwap DEX 总体功能与改造规格（v1）

> **文档目的**：在现有实现基础上，参考 `interface` 与 `sushiswap` 的成品 DEX 交互与数据面板，设计 DripSwap（Uniswap V2 自建 Factory + Faucet Token）后续的功能补齐、数据获取、缓存与 Goldsky 管线方案，作为后续“按功能拆分规格”的母文档。
>
> **适用范围**：`apps/frontend`、`apps/bff`、`apps/subgraphgoldsky`（Goldsky Subgraph）以及与其交互的数据层（Redis/可选 DB/可选 Pipeline/Webhook）。
>
> **不在本篇范围**：合约审计、主网部署、安全策略细节、完整 UI 设计稿、逐接口字段级的最终 Schema（会在后续子文档完善）。
>
> **最后更新**：2026-01-11

---

## 1. 当前现状（以代码为准）

### 1.1 前端（`apps/frontend`）

- 已有路由（核心）：
  - Swap：`apps/frontend/src/app/routes/swap.tsx`（已接入钱包与链上交易）
  - Bridge：`apps/frontend/src/app/routes/bridge.tsx`（已接入 CCIP 交互，Permit2 等）
  - Explore：
    - Tokens：`apps/frontend/src/app/routes/explore-tokens.tsx`（已接入 GraphQL 查询）
    - Transactions：`apps/frontend/src/app/routes/explore-transactions.tsx`（已接入 GraphQL 查询）
    - Pools：`apps/frontend/src/app/routes/explore-pools.tsx`（当前为占位/Coming soon）
  - Token Details：`apps/frontend/src/app/routes/token-details.tsx`（已接入 details + candles + pools + swaps 查询，ECharts 展示）
  - Pools（流动性管理页）：`apps/frontend/src/app/routes/pools.tsx`（页面 UI 存在，但数据与链上 Add/Remove 仍为占位/未对齐）
  - Pools Add/Remove：`apps/frontend/src/app/routes/pools-add.tsx`、`apps/frontend/src/app/routes/pools-remove.tsx`（目前是“交互样式 + toast”，未接入 Router/Pair）
  - Faucet：`apps/frontend/src/app/routes/faucet.tsx`（UI 存在，未对接后端或合约）

- 数据访问方式（目前代码）：
  - 前端 GraphQL：统一走 BFF：`POST {VITE_API_BASE_URL}/graphql`（见 `apps/frontend/src/infrastructure/graphql/client.ts`）
  - REST：例如 pools summary 走 `GET /api/pools/summary`（见 `apps/frontend/src/infrastructure/adapters/pools.http.ts`），但 BFF 目前未实现该接口。

### 1.2 BFF（`apps/bff`）

- 当前形态仍以“同步到本地 PostgreSQL + JPA 仓储 + Redis 缓存”的旧模式为主（见 `apps/bff/src/main/java/com/dripswap/bff/sync/*`、`apps/bff/src/main/resources/application.yaml`）。
- 已存在 GraphQL Schema 与部分 Explore/Token 相关 Query（见 `apps/bff/src/main/resources/graphql/schema.graphqls`、`apps/bff/src/main/java/com/dripswap/bff/gql/QueryResolver.java`）。
- 现状问题（与“已改用 Goldsky”目标不一致）：
  - `application.yaml` 仍配置 The Graph Studio 端点（非 Goldsky）。
  - QueryResolver 的实现依赖本地数据库表（`TokenRepository`、`PairRepository`、`UniswapDayDataRepository`…），如果弃用“同步入库”，则需要整体重构数据层。
  - REST 侧缺少前端已调用的 `/api/pools/*`、`/api/faucet/*` 等接口实现。

### 1.3 Goldsky Subgraph（`apps/subgraphgoldsky`）

- 已有 V2 Subgraph 的核心实体与时间聚合实体（Token/Pair/Swap/Mint/Burn/UniswapDayData/TokenDayData/TokenHourData/TokenMinuteData/PairDayData/PairHourData…），见：
  - Schema：`apps/subgraphgoldsky/schema.graphql`
  - 映射与聚合：`apps/subgraphgoldsky/src/mappings/core.ts`、`apps/subgraphgoldsky/src/mappings/dayUpdates.ts`、`apps/subgraphgoldsky/src/mappings/minuteUpdates.ts`
- 重要行为约束（会影响前端图表范围与缓存策略）：
  - `TokenMinuteData` 有“归档删除”逻辑，保留窗口约 ~28 小时（见 `minuteUpdates.ts`）。
  - `TokenHourData` 有“归档删除”逻辑，保留窗口约 ~32 天（见 `dayUpdates.ts`）。
  - `TokenDayData`/`UniswapDayData` 作为长期序列来源更可靠（不应假设无限 minute/hour 历史可用）。

- 当前线上 Goldsky GraphQL 端点（用户提供）：
  - Sepolia：`https://api.goldsky.com/api/public/project_cmke483ckgziz01w9gr6cb0we/subgraphs/dripswap-v2-sepolia/1.0.5/gn`
  - Scroll Sepolia：`https://api.goldsky.com/api/public/project_cmjbktp0056ic01yj30ya4t7q/subgraphs/dripswap-v2-scroll-sepolia/1.0.5/gn`

---

## 2. 参考实现要点（用于对齐体验与数据面板）

### 2.1 Uniswap Interface（`interface`）Explore 设计抽象

- Explore 页由三大 Tab 组成：Tokens / Pools / Transactions（见 `interface/apps/web/src/pages/Explore/index.tsx`）。
- 核心交互：
  - Network filter（按链切换）
  - Search（全局模糊搜索）
  - Tokens 的时间窗口切换（Volume/Price history 维度）
  - Pools 的协议版本过滤（V2/V3/V4），并在列表中计算 APR、Vol/TVL 等派生指标（见 `interface/apps/web/src/state/explore/topPools.ts`）
- Transactions 支持类型过滤（Swap/Add/Remove），并提供“时间、类型、金额、钱包地址”等列（见 `interface/apps/web/src/pages/Explore/tables/RecentTransactions.tsx`）。

### 2.2 SushiSwap（`sushiswap`）Explore + V2 管理的可落地模式

- Tokens 列表示例（见 `sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/explore/tokens/_ui/*`）：
  - Name（含 icon）、Price、1d Change、FDV、Sparkline（简化而高效）。
- Pools 列表示例（见 `sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/_ui/columns.tsx`）：
  - TVL（含 1d change）、Volume(24h/1w)、Tx(24h)、APR（含奖励）。
- V2 Pool 管理（Add/Remove）落地细节：
  - 以 Pool 详情页为入口，详情页展示：图表、组成、统计、交易列表（见 `sushiswap/.../pool/v2/[address]/(landing)/_ui/pool-page-v2.tsx`）。
  - Add/Remove 走 Router 交易，包含 slippage/deadline、最小收到、LP 余额等（见 `sushiswap/.../pool/v2/[address]/(manage)/_common/ui/remove-section-legacy.tsx`）。
  - 服务端缓存：使用 Next `unstable_cache` 对 pool 详情进行 15 分钟缓存（见 `sushiswap/.../get-cached-v2-pool.ts`），等价于我们在 BFF/Redis 做 read-through 缓存 + 统一失效。

---

## 3. 目标产品范围（DripSwap v2 Testnet DEX）

> 目标是完成“Faucet → Swap → Add/Remove Liquidity → Explore → Token/Pool Details → Bridge（可选）”的闭环，并保证 Explore 类查询在多链下稳定、可缓存、可扩展。

### 3.1 P0（必须完成的 MVP）

**交易与资金操作**
- Swap：完善路由、多跳、错误提示、交易状态追踪（当前已有基础）。
- Add Liquidity（V2）：支持创建/追加流动性、自动报价与比例提示、滑点/截止时间设置、授权与提交。
- Remove Liquidity（V2）：支持按百分比移除、最小收到、授权与提交。
- Faucet：至少支持“领取测试代币”的可用流程（合约或 BFF 代发二选一），并在前端提供历史（本地/服务端）与失败原因提示。

**数据面板（Explore）**
- Explore Tokens：列表 + 搜索 + 基础指标（Price、1h/1d、FDV、Volume 24h）。
- Explore Pools：列表 + 搜索 + 基础指标（TVL、Volume 24h、Tx 24h、Fees 24h、APR）。
- Explore Transactions：最近 Swap/Mint/Burn 的统一视图（支持类型过滤）。
- Token Details：价格图（OHLC）+ 交易列表 + 相关池子列表。
- Pool Details：TVL/Volume/Fees 图 + 交易列表 + 组成与储备。

**数据源与稳定性**
- 全部查询以 Goldsky Subgraph 为权威来源，BFF 负责聚合、缓存、容错与“前端友好字段”的派生计算。

### 3.2 P1（体验与可用性增强）

- “我的流动性”页：列出用户 LP positions（LiquidityPosition + Snapshot），提供跳转到 add/remove。
- 统一链切换体验（Explore 与 Swap/Pool/Bridge 的链状态一致策略）。
- Bridge 状态追踪：记录 messageId，提供 ccip explorer 跳转，状态更新（需要额外索引能力）。
- 更完善的图表区间与抽样策略（1D/1W/1M/1Y），避免 minute/hour 缺失导致的断图。

### 3.3 P2（性能、成本、观测）

- BFF Redis 缓存命中率、Goldsky 请求量控制、失败降级策略。
- 可选：将高频派生数据写入本地（Postgres/ClickHouse/Timescale），进一步减少对 Goldsky 的依赖。
- 加入 SSE/WebSocket 推送（以缓存失效/最新交易推送为主）。

---

## 4. 数据与缓存总体设计（面向 Goldsky）

### 4.1 总体原则

- **Goldsky Subgraph = 真相源**：不再依赖“BFF 同步入库 + 再查询”的旧路径作为主路径。
- **BFF = 查询聚合 + 缓存 + 派生字段计算**：
  - 对前端暴露稳定的 GraphQL/REST 契约
  - 对 Goldsky 做并发/重试/分页/限流
  - 对热点 query 做 Redis read-through（必要时 stale-while-revalidate）
- **可选本地化**（Pipeline/Sink）：用于“成本/延迟敏感”与“长历史/复杂分析”场景。

### 4.2 BFF 的 Goldsky Gateway（建议新增模块）

建议在 BFF 增加一层“Subgraph Gateway”，将链路分为：

1) `ChainRouter`：按 `chainId` 选择对应 Goldsky endpoint（Sepolia/Scroll）。
2) `GraphQLClient`：统一的 HTTP 客户端（超时、重试、错误归一化、日志 traceId）。
3) `QueryBuilders`：集中维护 subgraph 查询语句与分页策略（优先游标分页，避免 `skip` 大偏移）。
4) `Mappers`：把 Subgraph 返回映射为 BFF GraphQL payload（含派生字段）。

这样前端可以保持 `POST /graphql` 不变，只替换 resolver 的数据来源。

### 4.3 Redis 缓存键与 TTL（建议）

沿用现有约定：`ds:v2:{chainId}:{domain}:{...}`，并将 key 与“参数”一一对应，避免缓存污染。

建议的最小集合：

- Explore
  - `ds:v2:{chainId}:explore:stats:{days}`（TTL=60s）
  - `ds:v2:{chainId}:explore:tokens:{limit}:{search}`（TTL=60s）
  - `ds:v2:{chainId}:explore:pools:{limit}:{search}:{sort}`（TTL=60s）
  - `ds:v2:{chainId}:explore:tx:{limit}:{types}`（TTL=15~60s，取决于实时性需求）

- Token
  - `ds:v2:{chainId}:token:{token}:details`（TTL=60s）
  - `ds:v2:{chainId}:token:{token}:pools:{limit}`（TTL=60s）
  - `ds:v2:{chainId}:token:{token}:tx:{limit}`（TTL=15~60s）
  - `ds:v2:{chainId}:token:{token}:candles:{interval}:{fromBucket}:{toBucket}`（TTL=300s，按 bucket 分片避免超大单 key）

- Pool
  - `ds:v2:{chainId}:pool:{pair}:details`（TTL=60s）
  - `ds:v2:{chainId}:pool:{pair}:candles:{range}`（TTL=300s）
  - `ds:v2:{chainId}:pool:{pair}:tx:{limit}`（TTL=15~60s）

**失效策略（建议）**
- 无 pipeline/webhook 时：纯 TTL（读穿透缓存）即可。
- 有 pipeline/webhook 时：以事件驱动做“精准 key 失效”（例如新 Swap 到来时，失效 recentTransactions、相关 token/pair 的局部 key）。

### 4.4 派生字段计算（最小可用口径）

> 目标是让前端得到“直接可展示”的字段，避免前端重复计算与多次请求。

- `priceUsd(token)`：`token.derivedETH * bundle.ethPrice`
- `fees24hUsd`：`volume24hUsd * 0.003`（V2 固定 0.3%）
- `change1h / change1d`（可选口径，择一实现即可）：
  - 1h：取最近两个 `TokenHourData.close` 或 `priceUSD` 做差
  - 1d：取最近两天 `TokenDayData.priceUSD` 做差（或用 24 个小时聚合）
- `poolApr`（简化版）：`(volume24hUsd * 0.003 * 365) / tvlUsd`（仅做展示，不承诺与真实收益完全一致）

---

## 5. Goldsky Pipeline / Webhook 的建议方案（可选，但推荐）

> 这里不假设具体产品能力已启用：如果 Goldsky 支持“子图数据导出到 Sink/Webhook”，则按以下方案做；若不支持，可用 BFF 定时任务替代（定时预热/定时落库）。

### 5.1 目标

- 降低前端高频查询对 Goldsky GraphQL 的压力（成本与稳定性）。
- 提供“更快的读”与“更稳定的派生指标计算”。
- 为后续 SSE/实时榜单/排行榜提供事件源。

### 5.2 推荐的最小管线拆分

**Pipeline A：事实事件流（适合 Webhook / 消息队列 / 轻量存储）**
- 输入：Swap / Mint / Burn（必要时加 PairCreated）
- 输出：Webhook 到 BFF（或写入队列/Redis Stream）
- 用途：
  - 精准失效缓存（recent tx / token tx / pool tx）
  - 可选：推送 SSE 给前端（Explore Transactions 实时更新）

**Pipeline B：低频统计快照（适合 Sink 到 DB/对象存储）**
- 输入：UniswapDayData、TokenDayData、PairDayData（长历史）
- 输出：Postgres/ClickHouse/对象存储（按日增量）
- 用途：
  - Explore Stats / 图表类 query 本地化（减少 Goldsky 依赖）

**Pipeline C：短窗 OHLC（按需）**
- 输入：TokenHourData（32 天窗）、TokenMinuteData（28 小时窗）
- 输出：本地缓存（Redis/DB）
- 用途：
  - Token 图表的 1D/1W/1M 快速响应
  - 统一采样/补点逻辑在服务端做

### 5.3 本地数据结构（如果落地 Sink）

若选择“落库”，建议表设计统一 `(chain_id, id)` 或 `(chain_id, address, bucket_ts)`，避免多链覆盖：

- `tx_events`：`chain_id, type, pair, token0, token1, amount_usd, tx_hash, timestamp, log_index`
- `token_ohlc_hour`：`chain_id, token, hour_start, open, high, low, close, volume_usd, tvl_usd`
- `token_ohlc_day`：`chain_id, token, day_start, price_usd, volume_usd, tvl_usd`
- `pair_day`：`chain_id, pair, day_start, reserve_usd, volume_usd, tx_count`
- `protocol_day`：`chain_id, day_start, total_liquidity_usd, daily_volume_usd, tx_count`

并在 BFF 中做“Redis → DB → Goldsky”三级回退（可选）。

---

## 6. BFF 对前端的契约建议（保持可演进）

### 6.1 GraphQL（建议继续以 BFF 统一出口）

前端已依赖以下 query（建议保留并以 Goldsky 实现）：

- Explore：
  - `exploreStats(chainId, days)`
  - `exploreTokens(chainId, limit, search)`
  - `recentTransactions(chainId, limit)`（建议升级为支持类型过滤：Swap/Mint/Burn）
- Token：
  - `tokenDetails(chainId, tokenAddress)`
  - `tokenPriceCandles(chainId, tokenAddress, interval, from, to)`
  - `tokenPools(chainId, tokenAddress, limit)`
  - `tokenTransactions(chainId, tokenAddress, limit)`

建议新增（用于补齐 Explore Pools 与 Pool Details）：
- `explorePools(chainId, limit, search, sort)`
- `poolDetails(chainId, pairAddress)`
- `poolPriceCandles(chainId, pairAddress, interval, from, to)`
- `poolTransactions(chainId, pairAddress, limit)`

### 6.2 REST（建议仅用于“写操作/管理操作”）

建议集中在：
- Faucet：`POST /api/faucet/request`（领取、限流、记录）
-（可选）缓存管理：`POST /api/cache/invalidate`（仅管理员/内部）
-（可选）pipeline webhook：`POST /api/webhook/goldsky`（接收事件，失效缓存/推送 SSE）

`/api/pools/summary` 若保留，可作为 Explore Stats 的 REST 镜像，但建议逐步迁移到 GraphQL，减少协议分裂。

---

## 7. 前端改造方向（以“对齐 Explore + 完成 LP”优先）

### 7.1 Explore（对齐 Interface/Sushi 的最小集合）

- Tabs 结构保持：Tokens / Pools / Transactions（已存在）。
- Tokens（已做一部分）：
  - 补齐列：`volume24hUsd`、可选 `sparkline`（可从 `TokenDayData` 派生或后续 pipeline 提供）
  - 补齐排序与筛选（至少支持按 TVL/Volume/FDV 排序）
- Pools（当前占位）：
  - 接入 `explorePools`，展示 TVL、Volume24h、Fees24h、Tx24h、APR
  - 支持搜索（token symbol/address/pair address）
  - 行点击进入 Pool Details
- Transactions（已做一部分）：
  - 类型过滤：Swap/Mint/Burn
  - 统一字段展示：时间、类型、USD、token0/token1 amounts、钱包、hash 链接

### 7.2 Pool 管理（Add/Remove Liquidity）

- 入口设计（建议对齐 Sushi）：
  - Explore Pools → Pool Details → “Add/Remove”
  - “My Liquidity”页列出用户 positions，并提供管理入口
- 交易实现（建议直接链上）：
  - Add：Router `addLiquidity` / `addLiquidityETH`（若存在 native 包装策略）
  - Remove：Router `removeLiquidity` / `removeLiquidityETH`
  - 授权：token0/token1（Add），LP token（Remove）
  - 交易参数：slippage、deadline
- 数据展示（读）：
  - reserves/price：可链上读 Pair `getReserves` + subgraph 校验
  - TVL/Volume：走 BFF（Goldsky + cache）

### 7.3 Faucet

- 两条可选路径（择一实现，后续可并存）：
  1) **合约 Faucet**：前端直接调用 `claim()`，BFF 仅做“历史记录/风控”（推荐）
  2) **BFF 代发**：BFF 持有私钥向 Faucet 合约/Token 合约转账（更像中心化水龙头，需严格风控，不推荐默认）

---

## 8. 迁移与落地顺序（建议）

1) **BFF 改为 Goldsky 读路径**：保持 `POST /graphql` 不变，内部从“DB 仓储”切到“Goldsky Gateway + Redis”。
2) **补齐 Explore Pools + Pool Details**：先做 read-only，可快速对齐成品体验。
3) **完成 Add/Remove Liquidity 链上交易**：对齐 Sushi 的最小交互（slippage/deadline/approve）。
4) **Faucet 落地**：优先合约调用 + 服务端记录/限流。
5) **引入 pipeline/webhook（可选）**：以“失效缓存 + recent tx 实时更新”为第一目标。

---

## 9. 需要确认的开放问题（影响后续子文档）

- Goldsky 是否支持：
  - Subgraph 数据的 webhook sink 或流式导出？
  - 以 entity/event 为粒度的增量推送？
  - 直接 sink 到 Postgres/队列/对象存储的官方能力？
- DripSwap 当前“价格口径”：
  - `Bundle.ethPrice` 来自 Chainlink Oracle（已在 subgraph 映射中使用），但稳定币/whitelist 策略是否与当前测试币一致？
- Bridge 与 Faucet 是否需要纳入同一子图（`apps/subgraphgoldsky`），还是单独建子图/索引器（Ponder/Substreams）更合适？
- 多链 Token/Pairs 地址是否可能冲突（当前通过 endpoint 分链天然隔离；若落库需 `chain_id` 复合主键）。

---

## 10. 可执行 MVP 计划

执行顺序与验收口径见：`../specs/dripswap-mvp-execution-plan-v1.md:1`
