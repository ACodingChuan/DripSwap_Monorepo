# DripSwap MVP-3 PRD（v1）：Explore Pools + Pool Details（体验完善与读功能闭环）

> 关联文档：
> - `specs/dripswap-dex-functional-spec-v1.md`
> - `specs/dripswap-mvp-execution-plan-v1.md`（MVP-3 定义）
> - `specs/dripswap-mvp1-prd-v1.md`（6.4/6.9/6.10 的数据契约与口径）
>
> 最后更新：2026-01-20

---

## 1. 背景与现状

### 1.1 背景（为什么是 MVP-3）

在 `specs/dripswap-mvp-execution-plan-v1.md` 里，MVP-3 的定位是：
- 把 Explore 的 Pools tab 做到“可用、可查、可排序、可跳转详情”
- 把 Pool Details 页面做到“数据齐全、可阅读、可对齐成品 DEX（Sushi/Uniswap Interface）体验”

### 1.2 仓库现状（以代码为准）

`specs/dripswap-mvp1-prd-v1.md` 的 6.1.2 已记录：Explore Pools + Pool Details 在仓库里已提前落地“基础版”。

因此本 MVP-3 文档的目标不是“从 0 到 1”，而是：
- 把**基础版补齐到 PRD 口径**（例如：表格列/排序/跳转细节）
- 把**体验/空态/错误/可用性**做到“可以对外演示”的程度

---

## 2. MVP-3 目标与非目标

### 2.1 目标（必须完成）

结合仓库现状（MVP-1 已把 Explore Pools/Pool Details 的“基础版读功能”做完），MVP-3 本轮只聚焦三条“缺口”：

1) Explore Pools：实现“按 TVL / 24h Volume / 24h Tx / 24h Fees / APR 排序”（前端提供 sort UI，本轮采用前端本地重排；后端 sort 入参保留但非必须）

2) Pool Details：交易列表每一行可打开区块浏览器核对（至少 txHash；建议同时支持 maker address）

3) 全站地址展示规范：前端任何地方不要直接展示 pool/token 等完整地址（默认展示简写，必要时提供 explorer 跳转）

### 2.2 非目标（本阶段不做）

- 不做 Add/Remove Liquidity 的真实链上交易闭环（这是 MVP-4）
- 不做“我的流动性 / 用户 positions”体系（可作为后续 MVP）
- 不做任何“数据仓库/落库/sink”（保持 Goldsky + Redis read-through）
- 不引入 WebSocket/SSE 实时推送（属于 MVP-2/后续增强）

---

## 3. 范围（Scope）

### 3.1 涉及页面（前端）

- Explore Pools：`apps/frontend/src/app/routes/explore-pools.tsx`
- Pool Details：`apps/frontend/src/app/routes/pool-details.tsx`

### 3.2 涉及接口（BFF GraphQL）

契约以 `apps/bff/src/main/resources/graphql/schema.graphqls` 为准：
- `explorePools(chainId, limit, search, sort): [ExplorePoolRow!]!`
- `poolDetails(chainId, pairAddress): PoolDetails`
- `poolPriceCandles(chainId, pairAddress, interval, from, to): [PoolOhlc!]!`
- `poolTransactions(chainId, pairAddress, limit, types): [PoolTransactionRow!]!`

---

## 4. 用户故事（User Stories）

1) 作为用户，我在 Explore Pools 能快速找到池子：
- 我能搜 token symbol / token address / pair address
- 我能按 TVL / 24h Volume / 24h Tx / 24h Fees / APR 排序

2) 作为用户，我点进某个池子能理解“这个池子现在什么情况”：
- 它是哪两个 token
- 现在大概有多少 liquidity（展示口径以 subgraph 为准）
- 最近 24h 有多少交易

3) 作为用户，我能看到这个池子的最近交易，并能点开区块浏览器核对：
- 交易类型（swap/add/remove）
- 发起地址（maker）
- 发生时间

---

## 5. 详细需求（按页面）

> 说明：本节尽量用“前端要怎么展示 + 后端要保证什么”来写，方便直接拆任务。

### 5.1 Explore Pools 页面（/explore/pools）

#### 5.1.0 UI 参考（对齐 Sushi 的“表格 + 表头排序 + 行内菜单”）

本页面建议直接对齐 Sushi 的体验（你给的截图就是这一套）：

Sushi 参考入口（页面级）：
- `sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/explore/pools/page.tsx`：Explore Pools 页面组合（filters + PoolsTable）

Sushi 参考实现（表格级）：
- `sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/_ui/pools-table.tsx`：
  - columns 定义（包含 actions 三点菜单）
  - DataTable 的 sorting state（点击表头箭头切换 asc/desc）
- `sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/_ui/columns.tsx`：
  - Name/TVL/Volume/Tx/APR 每列的展示样式与 sortingFn
  - Name 列的 token icon + protocol badge + fee badge

#### 5.1.1 表格列（必须对齐）

基础列（必须）：
- Pool：`token0.symbol/token1.symbol`（必要时 fallback 到 address 简写）
- TVL：`tvlUsd` + 可选 `tvlChange1d`
- Volume (24h)：`volume24hUsd`
- Tx (24h)：`tx24hCount`
- APR：`apr`（后端返回的是 ratio，前端展示成百分比）

建议补齐的列（如果页面空间允许，建议做）：
- Fees (24h)：`fees24hUsd`（即便你后面不强调金额，这一列对“为什么 APR 会变”很有帮助）
- Volume (1w)：`volume1wUsd`（你当前页面已展示）

#### 5.1.2 排序（必须）

前端需要提供一个 sort 交互（表头箭头点击排序），覆盖枚举语义：
- `TVL_DESC`
- `VOLUME_24H_DESC`
- `TX_24H_DESC`
- `FEES_24H_DESC`
- `APR_DESC`

默认值：
- `TVL_DESC`

行为细节：
- 本轮实现采用“前端本地重排”（不需要每次切换都重新请求后端）
- 搜索 + 排序可以组合（search 不清空 sort）

Sushi 参考实现：
- 表格 sorting state：`sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/_ui/pools-table.tsx`
- 每列的 sortingFn：`sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/_ui/columns.tsx`

#### 5.1.3 搜索（必须）

输入框语义（对齐 `specs/dripswap-mvp1-prd-v1.md` 6.4）：
- 支持包含匹配：
  - pair address
  - token0/token1 的 symbol/name/address
  - “TOKEN0/TOKEN1” 组合（顺序不敏感可选）

交互：
- 输入防抖（300ms 左右）
- 清空输入立即回到全量列表

#### 5.1.4 跳转（必须）

- 行点击跳转：`/explore/pools/$chain/$poolAddress`
- chain 参数来源：
  - 默认使用当前钱包 chainId（与现有实现一致）
  - 当用户未连接钱包：可使用系统默认链（Sepolia）或 UI store 的选中链（按你项目现状定）

#### 5.1.5 空态与错误（必须）

- Loading：skeleton
- Error：展示“Failed to load pools”并提供重试按钮（可选）
- Empty：No pools found（当 search 无匹配时）

#### 5.1.6 行内菜单（新增，必须）：Add / Remove liquidity

目标：对齐你截图里的三点菜单（…），让用户在 Pools 列表里直接对某个池子执行：
- Add liquidity
- Remove liquidity

实现建议（DripSwap）：
- 每行最右侧加一个 `...` 按钮
- 点击打开菜单，菜单项跳转到：
  - `/pools/add?chainId=...&pair=0x...`
  - `/pools/remove?chainId=...&pair=0x...`

Sushi 参考实现：
- actions 菜单与路由跳转：`sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/_ui/pools-table.tsx`

---

### 5.2 Pool Details 页面（/explore/pools/:chain/:poolAddress）

#### 5.2.1 页面头部信息（必须）

展示：
- pair：`token0.symbol/token1.symbol` + `v2`
- chain label（Sepolia / Scroll Sepolia）
- pairAddress（可复制/可简写）

建议增强（可选但很推荐）：
- token0/token1 地址跳转区块浏览器
- pair 地址跳转区块浏览器

#### 5.2.2 统计与组成（必须）

Pool Liquidity 卡：
- token0 reserve、token1 reserve（来自 `poolDetails.reserve0/reserve1`）
- 允许显示 `—`（当 subgraph 数据不足/还没索引到）

Statistics 卡（必须至少 4 个）：
- Liquidity：`poolDetails.tvlUsd`
- Volume (24h)：`poolDetails.volume24hUsd`
- Fees (24h)：`poolDetails.fees24hUsd`
- Transactions (24h)：`poolDetails.tx24hCount`

APR（建议补齐）：
- 如果 UI 有位置展示，可加一项：`poolDetails.apr`

#### 5.2.3 图表（必须）

数据源：
- `poolPriceCandles(interval, from, to)` 返回 `PoolOhlc[]`

交互：
- metric toggle：Volume / TVL / Fees
- range toggle：至少保留当前实现的 1D/1W/1M/1Y/ALL
- 空态：当 candles 为空时显示 “Not enough data yet.”

#### 5.2.4 交易列表（必须）

数据源：
- `poolTransactions(limit, types)` 返回 `PoolTransactionRow[]`

交互：
- 类型切换：Swaps / Add / Remove（对应 SWAP/MINT/BURN）
- 默认类型：SWAP

行展示（最小必须）：
- Maker（account，简写）
- Amount in / Amount out（根据 type 做展示）
- Time（相对时间）

建议增强（强烈推荐）：
- 点击行跳转区块浏览器 tx（用 `txHash`）
- Maker 点击跳转区块浏览器 address（用 `account`）

刷新策略：
- read-only 页面允许 60s 轮询一次（你当前实现已是 60s）

#### 5.2.5 错误与空态（必须）

- poolDetails 拉不到：
  - 显示 “Pool details” + 基础地址信息
  - 统计/交易/图表区域显示 skeleton 或 error 说明
- poolTransactions 为空：No transactions found

---

## 6. 数据契约与口径（冻结）

本 MVP-3 不新增 GraphQL 字段，沿用已冻结契约（见 `specs/dripswap-mvp1-prd-v1.md`）：
- Explore Pools：6.4
- Pool Details：6.9
- Pool Candles/Transactions：6.10

实现上需要保证：
- `limit` clamp（<=200）
- list query 失败返回 `[]`（避免前端白屏）

---

## 7. Redis 缓存与“新鲜度”（本阶段只要求稳定，不强求实时）

本 MVP-3 默认依赖 TTL 缓存即可：
- Explore Pools：60s 左右
- Pool Details：60s 左右
- Pool candles：300s 左右
- Pool transactions：15~60s（按你当前 TTL/实现为准）

如果 MVP-2 webhook pipeline 已完成：
- Swap/Mint/Burn 发生后可以主动失效 `explore:tx` / `pool:{pair}:tx` / `token:{token}:tx`
- 这会显著改善 Pool Details 的交易列表“新鲜度”

---

## 8. 开发任务拆分（Task Breakdown）

### 8.1 前端（必须）

Explore Pools（`apps/frontend/src/app/routes/explore-pools.tsx`）：
- 增加 sort UI（并把 sort 加入 queryKey 与请求参数）
- 允许按 `TVL/Volume24h/Tx24h/Fees24h/APR` 排序（对齐 schema 枚举）

Pool Details（`apps/frontend/src/app/routes/pool-details.tsx`）：
- 给交易行增加区块浏览器跳转（txHash 必须；maker 可选但推荐）

全站地址展示规范（前端通用）：
- 所有页面不要直接展示 pool/token 的完整地址（只展示简写）
- 如果需要核对完整地址，走区块浏览器跳转或 copy（本 MVP 不强制 copy）

### 8.2 BFF（可选，但建议做一次巡检）

- 确认 `explorePools(sort=FEES_24H_DESC/APR_DESC...)` 的排序在前端可见（即字段确实会返回且非全 0）
- 确认 `poolTransactions(types)` 过滤符合预期（类型切换时列表变化）

### 8.3 测试（建议必须）

- Playwright E2E：
  - Explore Pools 能加载、能搜索、能切 sort、能跳转详情
  - Pool Details 能加载、能切换交易类型、交易行能跳转浏览器（可用 stub 或检查 href）

---

## 9. 验收标准（DoD）

Explore Pools：
- sort 可用（至少 5 个枚举都能触发请求并影响排序）
- 点击行能进入 Pool Details

Pool Details：
- 页面可渲染（无 mock）
- 交易列表可切换 type，且行可跳转区块浏览器 tx（maker 跳转可选但推荐）

地址展示规范：
- Token/Pool 页面 header 不直接展示完整地址（使用简写）
- Token Details 的 pools 列表不直接展示完整 pairAddress（使用简写）

---

## 10. 建议排期（按 1 名前端 + 0.5 名后端巡检）

- Day 1：补 Explore Pools sort UI + queryKey；补 Fees 列（如需要）
- Day 2：补 Pool Details 交易跳转与错误 UI；完成视觉/交互细节
- Day 3：E2E 测试补齐 + 回归（两条链至少各测一次）
