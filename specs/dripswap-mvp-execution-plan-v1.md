# DripSwap 可执行 MVP 交付计划（v1）

> **目的**：把“要做什么”拆成可连续交付、可验收的 MVP 步骤，并明确 pipeline/webhook 何时做、做多大。
>
> 关联母文档：`../specs/dripswap-dex-functional-spec-v1.md`
>
> **前提**：Goldsky 支持 entity 增量推送 + webhook/流式导出 + sink（Postgres/队列/对象存储）。
>
> **默认目标**：先把 **Explore 全量可用 + Pool/Token Details 可用 + V2 Add/Remove Liquidity 可用 + Faucet 可用** 做成闭环；pipeline 先做“最小可用”来服务缓存与实时性，不一上来做大数据仓库。

---

## 0. 决策：先做 pipeline 还是后做？

结论：**先做“最小 pipeline（用于缓存失效/增量更新）”，把“大 sink（落库/队列/对象存储）延后**。

原因：
- 你的读路径会严重依赖 Redis 缓存；没有增量信号只能靠 TTL，Explore/Transactions 的“新鲜度”与成本会不可控。
- 最小 pipeline 的范围很小（只需要实体变更信号 + 失效部分 key），但会影响缓存 key 设计、BFF 数据层结构与前端实时策略，所以应当早定。
- 大 sink（落库/对象存储）属于 P2 优化：等你确认“哪些 query 成本最高/需要长历史/需要复杂聚合”再做，避免过度建设。

---

## 1. MVP-1：契约冻结 + Goldsky 读路径（合并原 MVP-1 + MVP-2）

**目标**：在同一个阶段同时完成：
- 冻结 P0 的 BFF GraphQL 契约（query/type/口径/边界/错误策略）
- 落地 BFF 的 Goldsky 读路径（含 Redis 缓存），让现有前端页面可跑

### 1.1 契约（PRD）
- `Explore`：
  - `exploreStats(chainId, days)`
  - `exploreTokens(chainId, limit, search, sort?)`
  - `explorePools(chainId, limit, search, sort?)`（新增，支撑 Explore Pools）
  - `recentTransactions(chainId, limit, types?)`（升级：Swap/Mint/Burn 过滤）
- `Token`：
  - `tokenDetails(chainId, tokenAddress)`
  - `tokenPriceCandles(chainId, tokenAddress, interval, from, to)`
  - `tokenPools(chainId, tokenAddress, limit)`
  - `tokenTransactions(chainId, tokenAddress, limit)`
- `Pool`（新增，支撑 Pool Details 页面）：
  - `poolDetails(chainId, pairAddress)`
  - `poolPriceCandles(chainId, pairAddress, interval, from, to)`
  - `poolTransactions(chainId, pairAddress, limit)`

### 1.2 工程（BFF：Goldsky + Redis）
读路径统一为：**Goldsky →（派生/聚合）→ Redis 缓存 → 前端**，并保留降级/兜底策略（如 list 返回 `[]`）。

工程交付建议拆分：
- `GoldskyEndpointRouter`：`chainId -> endpoint`
- `GoldskyGraphqlClient`：统一超时、重试、错误归一化
- `SubgraphQueries`：集中维护查询语句（避免散落在 resolver）
- `SubgraphMappers`：派生字段（priceUsd、fees24hUsd、change1h/1d、apr…）

实现优先级（先跑起来，再补齐新增页面）：
- 先完成“已被前端使用的” query 的 Goldsky 实现：
  - `exploreStats`、`exploreTokens`、`recentTransactions`
  - `tokenDetails`、`tokenPriceCandles`、`tokenPools`、`tokenTransactions`
- `explorePools/poolDetails/pool*` 的实现放到后续（见 MVP-3）

### 1.3 验收标准
- GraphQL schema/字段/语义与 PRD 一致，前端不再需要临时 mock 字段/硬编码
- 本地起 `apps/bff` + `apps/frontend`：
  - Explore（Stats/Tokens/Transactions）可从 Goldsky 拉数据并展示
  - Token Details（Details/Candles/Pools/Tx）可从 Goldsky 拉数据并展示

**范围边界（与 PRD 6.x 对齐）**
- MVP-1 必须实现：`exploreStats/exploreTokens/recentTransactions/tokenDetails/tokenPriceCandles/tokenPools/tokenTransactions`
- MVP-1 仅冻结契约，允许占位返回：`explorePools/poolDetails/poolPriceCandles/poolTransactions`（完整实现放到 MVP-3）

---

## 2. MVP-2：最小 Pipeline（Entity 增量 → Webhook → 失效缓存）

**目标**：让 Explore/Details 的缓存不用“纯 TTL 碰运气”，并为后续 SSE/实时榜单打基础。

**要做的设计与实现**
- Goldsky Pipeline（最小范围）：
  - 监听实体：`Swap`、`Mint`、`Burn`、（可选）`Pair`、`Token`、`UniswapDayData`
  - 推送粒度：entity 变更（create/update）+ 关键字段（chainId/pair/token/timestamp/txHash）
  - Sink：Webhook 到 BFF（优先）或队列（如果你已有 MQ）
- BFF 接口（建议）：
  - `POST /api/webhook/goldsky`（签名校验 + 幂等处理）
  - 处理逻辑：仅做 **缓存 key 失效**（必要时异步预热），不要做复杂落库

**缓存失效最小规则（例）**
- 收到 Swap：
  - 删：`ds:v2:{chain}:explore:tx:*`
  - 删：`ds:v2:{chain}:token:{token0}:tx:*`、`ds:v2:{chain}:token:{token1}:tx:*`
  - 删：`ds:v2:{chain}:pool:{pair}:tx:*`
  - 删：`ds:v2:{chain}:explore:tokens:*`（可选：如果列表含 price/change）
  - 删：`ds:v2:{chain}:explore:pools:*`（可选：如果列表含 volume/tvl/apr）

**验收标准**
- 在链上发生新 Swap 后：
  - Explore Transactions 在 1 个刷新周期内能看到新数据（无需等到 TTL 到期）。
  - Goldsky 查询次数显著下降（缓存命中率提升）。

---

## 3. MVP-3：Explore Pools + Pool Details（读功能闭环）

**目标**：对齐成品 DEX 的 Explore 体验（tokens/pools/tx 三个 tab 都可用），并补齐池子详情页。

**要做的交付**
- 前端：
  - `apps/frontend/src/app/routes/explore-pools.tsx` 接入 `explorePools`
  - `apps/frontend/src/app/routes/pool-details.tsx` 对齐真实数据（当前是占位结构）
- BFF：
  - 实现 `explorePools`、`poolDetails`、`poolTransactions`、`poolPriceCandles`
  - 统一 sort/search（至少：tvl、volume24h、tx24h、apr）

**验收标准**
- Explore Pools 列表可搜索、可排序、可跳转池子详情。
- 池子详情页能展示：TVL/Volume/Fees（含图表或至少时间序列）、最近交易。

---

## 4. MVP-4：V2 Add/Remove Liquidity（写功能闭环）

**目标**：完成“提供/移除流动性”的链上交易闭环（Uniswap V2 Router）。

**要做的交付（前端为主）**
- Add Liquidity：
  - 选择 token0/token1，输入 amount，提示比例/预估 LP
  - allowance 检测 + approve
  - Router `addLiquidity` / `addLiquidityETH`（视你的 native 策略）
  - slippage + deadline
- Remove Liquidity：
  - 读取用户 LP balance（链上）+ 可用份额
  - percent slider + min received
  - approve LP token + Router remove

**验收标准**
- 真实链上操作成功后：
  - Explore Transactions / Pool Details 在 pipeline 失效后能体现新增 Mint/Burn/Sync 的结果。

---

## 5. MVP-5：Faucet（最短路径可用）

**目标**：让新用户能完成“领币 → Swap/LP”的测试网闭环。

**推荐落地顺序**
1) **合约 Faucet**（前端直连 claim，最快）
2) BFF 仅做：
   - 领取记录（可选）
   - 风控（IP/地址限流、冷却时间展示）

**验收标准**
- 用户可在 Sepolia/Scroll 领取指定 token，余额变化可见（链上）。

---

## 6. 何时做“大 sink”（Postgres/队列/对象存储）？

触发条件（满足任意 1 条即可启动 P2 设计）：
- Goldsky QPS/费用不可接受（Top N query 高峰打爆）
- 需要长历史（> TokenHourData 32 天 / TokenMinuteData 28 小时）
- 需要复杂聚合（排行榜、跨链聚合、按用户/按池收益分析）
- 需要强一致的“近实时数据面板”（SSE/实时榜单）

到那时再设计：
- sink 目标（Postgres vs ClickHouse vs 对象存储）
- 事件模型（规范化事实表 + 维表）
- 回填与重放机制
