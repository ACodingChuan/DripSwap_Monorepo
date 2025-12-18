# Uniswap V2 前端查询功能完整分析

> **文档目的**：分析 Uniswap interface 项目中针对 V2 的所有查询功能，为 DripSwap 前端设计提供参考  
> **分析来源**：`interface/` 项目源码  
> **最后更新**：2025-12-16

---

## 📋 目录

1. [核心查询页面](#核心查询页面)
2. [V2 GraphQL 查询清单](#v2-graphql-查询清单)
3. [页面级查询分析](#页面级查询分析)
4. [数据流与状态管理](#数据流与状态管理)
5. [DripSwap 实现建议](#dripswap-实现建议)

---

## 核心查询页面

Uniswap 前端针对 V2 的查询功能主要分布在以下页面：

| 页面 | 路由 | 核心功能 | V2 相关查询 |
|-----|------|---------|-----------|
| **Explore - Pools** | `/explore/pools` | 展示热门池子列表 | `TopV2Pairs` |
| **Explore - Transactions** | `/explore/transactions` | 展示全链交易流 | `V2Transactions` |
| **Pool Details** | `/explore/pools/:chain/:address` | 池子详情页 | `V2Pair`, `PoolPriceHistory`, `PoolVolumeHistory`, `V2PairTransactions` |
| **Token Details** | `/explore/tokens/:chain/:address` | Token 详情页 | `TokenPrice`, `TokenHistoricalVolumes`, `TokenHistoricalTvls`, `V2TokenTransactions` |
| **Positions** | `/positions` | 用户 LP 持仓列表 | REST API (PoolsService) |
| **V2 Position Details** | `/positions/v2/:address` | V2 持仓详情 | 链上查询 + REST API |

---

## V2 GraphQL 查询清单

### 1. 池子相关查询

#### 1.1 TopV2Pairs - 热门池子列表

**文件位置**：`interface/packages/uniswap/src/data/graphql/uniswap-data-api/web/topPools.graphql`

```graphql
query TopV2Pairs($chain: Chain!, $first: Int!, $cursor: Float, $tokenAddress: String) {
  topV2Pairs(first: $first, chain: $chain, tokenFilter: $tokenAddress, tvlCursor: $cursor) {
    id
    protocolVersion
    address
    totalLiquidity {
      value
    }
    token0 {
      ...SimpleTokenDetails
    }
    token1 {
      ...SimpleTokenDetails
    }
    txCount
    volume24h: cumulativeVolume(duration: DAY) {
      value
    }
    volume30d: cumulativeVolume(duration: MONTH) {
      value
    }
  }
}
```

**查询参数**：
- `chain`: 链 ID (如 `ETHEREUM`)
- `first`: 返回数量 (默认 100)
- `cursor`: TVL 游标，用于分页
- `tokenAddress`: 可选，按 Token 过滤

**返回字段**：
- `id`: 池子唯一标识
- `address`: 池子合约地址
- `totalLiquidity.value`: TVL (USD)
- `token0/token1`: Token 详情（地址、符号、Logo 等）
- `txCount`: 交易计数
- `volume24h.value`: 24小时交易量
- `volume30d.value`: 30天交易量

**使用场景**：
- Explore 页面的 Pools Tab
- 按 TVL 排序展示热门池子
- 支持按 Token 地址过滤

**分页方式**：
- 使用 `tvlCursor` 游标分页
- 每次取最后一条的 TVL 值作为下次查询的 cursor

---

#### 1.2 V2Pair - 池子详情

**文件位置**：`interface/packages/uniswap/src/data/graphql/uniswap-data-api/web/pool.graphql`

```graphql
query V2Pair($chain: Chain!, $address: String!) {
  v2Pair(chain: $chain, address: $address) {
    id
    protocolVersion
    address
    token0 {
      ...SimpleTokenDetails
      ...TokenPrice
    }
    token0Supply
    token1 {
      ...SimpleTokenDetails
      ...TokenPrice
    }
    token1Supply
    txCount
    volume24h: cumulativeVolume(duration: DAY) {
      value
    }
    historicalVolume(duration: WEEK) {
      value
      timestamp
    }
    totalLiquidity {
      value
    }
    totalLiquidityPercentChange24h {
      value
    }
  }
}
```

**查询参数**：
- `chain`: 链 ID
- `address`: 池子地址

**返回字段**：
- `token0Supply/token1Supply`: 池子中的 Token 储备量
- `historicalVolume`: 历史交易量数组（用于绘制图表）
- `totalLiquidityPercentChange24h`: TVL 24小时变化百分比

**使用场景**：
- Pool 详情页顶部信息卡片
- 显示池子基本信息、TVL、交易量等

---

#### 1.3 PoolPriceHistory - 池子价格历史

```graphql
query PoolPriceHistory($chain: Chain!, $addressOrId: String!, $duration: HistoryDuration!, $isV2: Boolean!) {
  v2Pair(chain: $chain, address: $addressOrId) @include (if: $isV2) {
    id
    priceHistory(duration: $duration) {
      id
      token0Price
      token1Price
      timestamp
    }
  }
}
```

**查询参数**：
- `duration`: 时间范围 (`HOUR`, `DAY`, `WEEK`, `MONTH`, `YEAR`)
- `isV2`: 布尔值，控制是否查询 V2

**返回字段**：
- `priceHistory[]`: 价格历史数组
  - `token0Price`: token0 相对 token1 的价格
  - `token1Price`: token1 相对 token0 的价格
  - `timestamp`: 时间戳

**使用场景**：
- Pool 详情页的价格图表
- 支持多种时间范围切换

---

#### 1.4 PoolVolumeHistory - 池子交易量历史

```graphql
query PoolVolumeHistory($chain: Chain!, $addressOrId: String!, $duration: HistoryDuration!, $isV2: Boolean!) {
  v2Pair(chain: $chain, address: $addressOrId) @include (if: $isV2) {
    id
    historicalVolume(duration: $duration) {
      id
      value
      timestamp
    }
  }
}
```

**使用场景**：
- Pool 详情页的交易量图表
- 柱状图展示历史交易量

---

#### 1.5 V2PairTransactions - 池子交易历史

**文件位置**：`interface/packages/uniswap/src/data/graphql/uniswap-data-api/web/poolTransactions.graphql`

```graphql
query V2PairTransactions($chain: Chain!, $address: String!, $first: Int!, $cursor: Int) {
  v2Pair(chain: $chain, address: $address) {
    id
    transactions(first: $first, timestampCursor: $cursor) {
      timestamp
      hash
      account
      token0 {
        ...PoolTransactionToken
      }
      token0Quantity
      token1 {
        ...PoolTransactionToken
      }
      token1Quantity
      usdValue {
        value
      }
      type
    }
  }
}
```

**查询参数**：
- `address`: 池子地址
- `first`: 返回数量
- `cursor`: 时间戳游标

**返回字段**：
- `type`: 交易类型 (`SWAP`, `MINT`, `BURN`)
- `account`: 交易发起者地址
- `token0Quantity/token1Quantity`: Token 数量
- `usdValue.value`: USD 价值

**使用场景**：
- Pool 详情页的交易历史表格
- 支持分页加载

**分页方式**：
- 使用 `timestampCursor` 游标
- 取最后一条的 timestamp 作为下次查询的 cursor

---

### 2. 交易相关查询

#### 2.1 V2Transactions - 全链交易流

**文件位置**：`interface/packages/uniswap/src/data/graphql/uniswap-data-api/web/transactions.graphql`

```graphql
query V2Transactions($chain: Chain!, $first: Int!, $cursor: Int) {
  v2Transactions(chain: $chain, first: $first, timestampCursor: $cursor) {
    id
    chain
    protocolVersion
    timestamp
    hash
    account
    token0 {
      ...TransactionToken
    }
    token0Quantity
    token1 {
      ...TransactionToken
    }
    token1Quantity
    usdValue {
      id
      value
    }
    type
  }
}
```

**查询参数**：
- `chain`: 链 ID
- `first`: 返回数量 (默认 25)
- `cursor`: 时间戳游标

**返回字段**：
- 包含完整的交易信息
- Token 信息包含 project.logo 用于展示

**使用场景**：
- Explore 页面的 Transactions Tab
- 展示全链最新交易流

---

### 3. Token 相关查询

#### 3.1 TokenWeb - Token 基本信息

**文件位置**：`interface/packages/uniswap/src/data/graphql/uniswap-data-api/web/token.graphql`

```graphql
query TokenWeb($chain: Chain!, $address: String = null) {
  token(chain: $chain, address: $address) {
    id
    decimals
    name
    chain
    address
    symbol
    standard
    market(currency: USD) {
      id
      totalValueLocked {
        id
        value
        currency
      }
      price {
        id
        value
        currency
      }
      volume24H: volume(duration: DAY) {
        id
        value
        currency
      }
      priceHigh52W: priceHighLow(duration: YEAR, highLow: HIGH) {
        id
        value
      }
      priceLow52W: priceHighLow(duration: YEAR, highLow: LOW) {
        id
        value
      }
    }
    project {
      id
      name
      description
      homepageUrl
      twitterName
      logoUrl
      tokens {
        id
        chain
        address
      }
      markets(currencies: [USD]) {
        id
        fullyDilutedValuation {
          id
          value
          currency
        }
        marketCap {
          id
          value
          currency
        }
      }
    }
  }
}
```

**使用场景**：
- Token 详情页顶部信息卡片
- 显示价格、TVL、交易量、市值等

---

#### 3.2 TokenPrice - Token 价格图表数据

**文件位置**：`interface/packages/uniswap/src/data/graphql/uniswap-data-api/web/tokenCharts.graphql`

```graphql
query TokenPrice($chain: Chain!, $address: String = null, $duration: HistoryDuration!, $fallback: Boolean = false) {
  token(chain: $chain, address: $address) {
    id
    address
    chain
    market(currency: USD) {
      id
      price {
        id
        value
      }
      ohlc(duration: $duration) @skip(if: $fallback) {
        id
        timestamp
        open { id value }
        high { id value }
        low { id value }
        close { id value }
      }
      priceHistory(duration: $duration) @include(if: $fallback) {
        id
        value
        timestamp
      }
    }
  }
}
```

**查询参数**：
- `duration`: 时间范围
- `fallback`: 是否使用 fallback 模式（priceHistory 而非 OHLC）

**返回字段**：
- `ohlc[]`: OHLC 蜡烛图数据（开高低收）
- `priceHistory[]`: 简单价格历史（fallback）

**使用场景**：
- Token 详情页的价格图表
- 支持 K 线图和折线图

---

#### 3.3 TokenHistoricalVolumes - Token 交易量历史

```graphql
query TokenHistoricalVolumes($chain: Chain!, $address: String = null, $duration: HistoryDuration!) {
  token(chain: $chain, address: $address) {
    id
    address
    chain
    market(currency: USD) {
      id
      historicalVolume(duration: $duration) {
        id
        timestamp
        value
      }
    }
  }
}
```

**使用场景**：
- Token 详情页的交易量图表

---

#### 3.4 TokenHistoricalTvls - Token TVL 历史

```graphql
query TokenHistoricalTvls($chain: Chain!, $address: String = null, $duration: HistoryDuration!) {
  token(chain: $chain, address: $address) {
    id
    address
    chain
    market(currency: USD) {
      id
      historicalTvl(duration: $duration) {
        id
        timestamp
        value
      }
      totalValueLocked {
        id
        value
        currency
      }
    }
  }
}
```

**使用场景**：
- Token 详情页的 TVL 图表

---

#### 3.5 V2TokenTransactions - Token 交易历史

**文件位置**：`interface/packages/uniswap/src/data/graphql/uniswap-data-api/web/tokenTransactions.graphql`

```graphql
query V2TokenTransactions($chain: Chain!, $address: String!, $first: Int!, $cursor: Int) {
  token(chain: $chain, address: $address) {
    ...TransactionToken
    v2Transactions(first: $first, timestampCursor: $cursor) {
      ...PoolTx
    }
  }
}
```

**使用场景**：
- Token 详情页的交易历史表格
- 展示该 Token 在所有 V2 池子中的交易

---

## 页面级查询分析

### 1. Explore - Pools Tab

**页面路径**：`/explore/pools`

**核心组件**：`ExploreTopPoolTable`

**数据流**：

```
ExploreContext (提供 chainId)
    ↓
useExploreContextTopPools (state/explore/topPools.ts)
    ↓
ExploreStatsResponse (来自 client-explore gRPC)
    ↓
getPoolDataByProtocol (根据 protocol 过滤)
    ↓
convertPoolStatsToPoolStat (计算 APR、volOverTvl)
    ↓
sortPools (按 sortBy 排序)
    ↓
useFilteredPools (按搜索词过滤)
    ↓
PoolsTable (渲染表格)
```

**关键功能**：
1. **协议过滤**：通过 `exploreProtocolVersionFilterAtom` 选择 V2/V3/V4/All
2. **排序**：支持按 TVL、APR、Volume24h、Volume30d、VolOverTvl 排序
3. **搜索**：支持按 Token 符号、地址、池子地址搜索
4. **分页**：使用 `useSimplePagination` 实现无限滚动

**表格列**：
- `#`: 排名
- `Pool`: Token0/Token1 符号 + Logo
- `Protocol`: v2/v3/v4
- `Fee Tier`: 手续费率
- `TVL`: 总锁仓量
- `APR`: 年化收益率
- `Volume 1D`: 24小时交易量
- `Volume 30D`: 30天交易量
- `Vol/TVL`: 交易量/TVL 比率

**APR 计算公式**：
```typescript
// 来自 appGraphql/data/pools/useTopPools.ts
export function calculateApr({
  volume24h,
  tvl,
  feeTier,
}: {
  volume24h: number
  tvl: number
  feeTier: number
}): Percent {
  if (!volume24h || !tvl || !feeTier) {
    return new Percent(0)
  }
  
  // APR = (24h交易量 * 手续费率 * 365) / TVL
  const dailyFees = volume24h * (feeTier / BIPS_BASE)
  const annualFees = dailyFees * 365
  const apr = annualFees / tvl
  
  return new Percent(Math.floor(apr * 10000), 10000)
}
```

---

### 2. Explore - Transactions Tab

**页面路径**：`/explore/transactions`

**核心组件**：`RecentTransactions`

**查询**：`V2Transactions` + `V3Transactions` + `V4Transactions`

**数据流**：
```
useExploreContext
    ↓
根据 protocol 过滤选择查询
    ↓
合并 V2/V3/V4 交易
    ↓
按 timestamp 排序
    ↓
TransactionTable (渲染表格)
```

**表格列**：
- `Time`: 相对时间（如 "2 mins ago"）
- `Type`: Swap/Add/Remove + 图标
- `Token Amount`: Token0 数量 + 符号
- `Token Amount`: Token1 数量 + 符号
- `Account`: 交易发起者地址（缩写）
- `USD Value`: 交易 USD 价值

**分页方式**：
- 使用 `timestampCursor` 游标
- 每次加载 25 条
- 支持无限滚动

---

### 3. Pool Details 页面

**页面路径**：`/explore/pools/:chain/:address`

**核心组件**：`PoolDetails`

**查询组合**：
1. `V2Pair` - 池子基本信息
2. `PoolPriceHistory` - 价格图表数据
3. `PoolVolumeHistory` - 交易量图表数据
4. `V2PairTransactions` - 交易历史

**页面布局**：
```
┌─────────────────────────────────────────┐
│  Pool Header                            │
│  - Token0/Token1 Logo + Symbol          │
│  - TVL, Volume24h, Fees24h              │
│  - Add Liquidity 按钮                   │
└─────────────────────────────────────────┘
┌─────────────────────────────────────────┐
│  Chart Section (Tab 切换)               │
│  - Price Chart (token0Price/token1Price)│
│  - Volume Chart (historicalVolume)      │
│  - TVL Chart (historicalTvl)            │
└─────────────────────────────────────────┘
┌─────────────────────────────────────────┐
│  Pool Stats                             │
│  - TVL, Volume, Fees                    │
│  - Token0/Token1 Locked                 │
└─────────────────────────────────────────┘
┌─────────────────────────────────────────┐
│  Transactions Table                     │
│  - Recent swaps/adds/removes            │
└─────────────────────────────────────────┘
```

**图表时间范围选项**：
- `HOUR`: 1小时
- `DAY`: 1天
- `WEEK`: 1周
- `MONTH`: 1月
- `YEAR`: 1年

---

### 4. Token Details 页面

**页面路径**：`/explore/tokens/:chain/:address`

**核心组件**：`TokenDetails`

**查询组合**：
1. `TokenWeb` - Token 基本信息
2. `TokenPrice` - 价格图表（OHLC 或 priceHistory）
3. `TokenHistoricalVolumes` - 交易量图表
4. `TokenHistoricalTvls` - TVL 图表
5. `V2TokenTransactions` - 交易历史
6. `usePoolsFromTokenAddress` - 该 Token 的所有池子

**页面布局**：
```
┌─────────────────────────────────────────┐
│  Token Header                           │
│  - Logo + Name + Symbol                 │
│  - Price, 24h Change                    │
│  - Market Cap, FDV, TVL                 │
└─────────────────────────────────────────┘
┌─────────────────────────────────────────┐
│  Chart Section (Tab 切换)               │
│  - Price Chart (OHLC K线图)             │
│  - Volume Chart                         │
│  - TVL Chart                            │
└─────────────────────────────────────────┘
┌─────────────────────────────────────────┐
│  Token Stats                            │
│  - 52W High/Low                         │
│  - Volume 24h                           │
└─────────────────────────────────────────┘
┌─────────────────────────────────────────┐
│  Pools Table                            │
│  - 包含该 Token 的所有池子               │
└─────────────────────────────────────────┘
┌─────────────────────────────────────────┐
│  Transactions Table                     │
│  - 该 Token 的所有交易                   │
└─────────────────────────────────────────┘
```

---

### 5. Positions 页面

**页面路径**：`/positions`

**数据来源**：REST API (PoolsService) 而非 GraphQL

**查询方式**：
```typescript
// interface/packages/uniswap/src/data/rest/getPositions.ts
useGetPositionsInfiniteQuery({
  address: account,
  chainIds: [chainId],
  protocolVersions: [ProtocolVersion.V2, ProtocolVersion.V3, ProtocolVersion.V4],
})
```

**V2 Position 数据结构**：
```typescript
interface V2Position {
  chainId: number
  pair: {
    token0: Token
    token1: Token
    reserve0: string
    reserve1: string
  }
  liquidityToken: {
    address: string
  }
  totalSupply: string
  liquidity: string  // owner LP balance
  liquidity0: string // owner token0 amount
  liquidity1: string // owner token1 amount
  apr?: number
  status: 'OPEN' | 'CLOSED'
}
```

**关键点**：
- V2 positions 不依赖 subgraph 的 `LiquidityPosition` 实体
- 通过 REST 服务提供，后端计算 LP 份额
- 前端使用 `erc20.balanceOf(owner)` 验证 ownership

---

## 数据流与状态管理

### 1. 全局状态管理

**ExploreContext**：
```typescript
// state/explore/index.tsx
interface ExploreContextValue {
  chainId?: UniverseChainId
  exploreStats: {
    data?: ExploreStatsResponse
    isLoading: boolean
    error?: Error
  }
}
```

**关键 Atoms**：
- `exploreSearchStringAtom`: 搜索关键词
- `exploreProtocolVersionFilterAtom`: 协议版本过滤 (V2/V3/V4/All)
- `sortMethodAtom`: 排序字段
- `sortAscendingAtom`: 排序方向

---

### 2. 数据获取方式

**方式 A：GraphQL (uniswap-data-api)**
- 用于 Explore 页面的 Pools/Transactions/Tokens
- 用于详情页的图表数据
- 使用 Apollo Client

**方式 B：gRPC (client-explore)**
- 用于 Explore 页面的统计数据
- `ExploreStatsResponse` 包含所有协议的 pool stats
- 通过 ConnectRPC 调用

**方式 C：REST (PoolsService)**
- 用于 Positions 页面
- 提供 V2/V3/V4 positions
- 通过 ConnectRPC 调用

**方式 D：链上查询**
- 用于实时数据验证
- 如 V2 position 的 `balanceOf` 查询
- 使用 Wagmi/Viem

---

## DripSwap 实现建议

### 1. 必须实现的核心查询

#### 优先级 P0（MVP 必需）

1. **TopV2Pairs** - Explore Pools 列表
   - 支持按 TVL 排序
   - 支持搜索过滤
   - 分页加载

2. **V2Pair** - Pool 详情基本信息
   - TVL、Volume、Fees
   - Token0/Token1 信息

3. **V2Transactions** - 全链交易流
   - Explore Transactions Tab
   - 实时展示最新交易

4. **V2PairTransactions** - Pool 交易历史
   - Pool 详情页表格
   - 支持分页

#### 优先级 P1（重要）

5. **PoolPriceHistory** - 价格图表
   - 支持多种时间范围
   - 用于 Pool 详情页

6. **PoolVolumeHistory** - 交易量图表
   - 柱状图展示

7. **TokenWeb** - Token 基本信息
   - Token 详情页顶部

8. **V2TokenTransactions** - Token 交易历史
   - Token 详情页表格

#### 优先级 P2（可选）

9. **TokenPrice** - Token 价格图表
   - OHLC K线图
   - 需要分钟级数据（考虑使用 v2-tokens subgraph）

10. **TokenHistoricalVolumes/Tvls** - Token 图表数据

11. **Positions REST API** - LP 持仓管理
   - 需要后端 REST 服务支持

---

### 2. BFF GraphQL Schema 设计

基于 Uniswap 的查询，DripSwap BFF 应提供以下 GraphQL 接口：

```graphql
type Query {
  # 池子查询
  topV2Pairs(
    chain: Chain!
    first: Int!
    tvlCursor: Float
    tokenFilter: String
  ): [V2Pair!]!
  
  v2Pair(
    chain: Chain!
    address: String!
  ): V2Pair
  
  # 交易查询
  v2Transactions(
    chain: Chain!
    first: Int!
    timestampCursor: Int
  ): [PoolTransaction!]!
  
  v2PairTransactions(
    chain: Chain!
    address: String!
    first: Int!
    timestampCursor: Int
  ): [PoolTransaction!]!
  
  # Token 查询
  token(
    chain: Chain!
    address: String!
  ): Token
  
  v2TokenTransactions(
    chain: Chain!
    address: String!
    first: Int!
    timestampCursor: Int
  ): [PoolTransaction!]!
  
  # 图表数据
  poolPriceHistory(
    chain: Chain!
    address: String!
    duration: HistoryDuration!
  ): [PricePoint!]!
  
  poolVolumeHistory(
    chain: Chain!
    address: String!
    duration: HistoryDuration!
  ): [VolumePoint!]!
}

type V2Pair {
  id: ID!
  address: String!
  protocolVersion: String!
  token0: Token!
  token1: Token!
  token0Supply: String!
  token1Supply: String!
  totalLiquidity: Amount!
  totalLiquidityPercentChange24h: Amount
  volume24h: Amount!
  volume30d: Amount
  txCount: Int!
  feeTier: Int!
}

type Token {
  id: ID!
  address: String!
  symbol: String!
  name: String!
  decimals: Int!
  chain: Chain!
  market: TokenMarket
  project: TokenProject
}

type TokenMarket {
  price: Amount
  totalValueLocked: Amount
  volume24h: Amount
  priceHigh52W: Amount
  priceLow52W: Amount
}

type PoolTransaction {
  id: ID!
  hash: String!
  timestamp: Int!
  type: TransactionType!
  account: String!
  token0: Token!
  token1: Token!
  token0Quantity: String!
  token1Quantity: String!
  usdValue: Amount
}

enum TransactionType {
  SWAP
  MINT
  BURN
}

enum HistoryDuration {
  HOUR
  DAY
  WEEK
  MONTH
  YEAR
}

type PricePoint {
  timestamp: Int!
  token0Price: Float!
  token1Price: Float!
}

type VolumePoint {
  timestamp: Int!
  value: Float!
}
```

---

### 3. 前端实现建议

#### 3.1 目录结构

```
src/
├── pages/
│   ├── Explore/
│   │   ├── index.tsx              # Explore 主页
│   │   ├── PoolsTab.tsx           # Pools 列表
│   │   ├── TransactionsTab.tsx    # 交易流
│   │   └── ProtocolFilter.tsx     # V2 过滤器
│   ├── PoolDetails/
│   │   ├── index.tsx              # Pool 详情页
│   │   ├── ChartSection.tsx       # 图表区域
│   │   └── TransactionsTable.tsx  # 交易表格
│   └── TokenDetails/
│       ├── index.tsx              # Token 详情页
│       └── ...
├── components/
│   ├── Pools/
│   │   └── PoolTable/
│   │       └── PoolTable.tsx      # 池子表格组件
│   └── Transactions/
│       └── TransactionTable.tsx   # 交易表格组件
├── graphql/
│   ├── queries/
│   │   ├── pools.graphql          # 池子查询
│   │   ├── transactions.graphql   # 交易查询
│   │   └── tokens.graphql         # Token 查询
│   └── generated/
│       └── types.ts               # 自动生成的类型
└── state/
    └── explore/
        ├── index.tsx              # ExploreContext
        └── topPools.ts            # Pools 状态管理
```

#### 3.2 关键 Hooks

```typescript
// hooks/useTopV2Pairs.ts
export function useTopV2Pairs({
  chainId,
  first = 100,
  tokenFilter,
}: {
  chainId: number
  first?: number
  tokenFilter?: string
}) {
  const { data, loading, error, fetchMore } = useTopV2PairsQuery({
    variables: {
      chain: chainIdToGraphQLChain(chainId),
      first,
      tokenFilter,
    },
  })
  
  return {
    pairs: data?.topV2Pairs,
    loading,
    error,
    loadMore: () => {
      const lastPair = data?.topV2Pairs[data.topV2Pairs.length - 1]
      if (lastPair) {
        fetchMore({
          variables: {
            cursor: lastPair.totalLiquidity.value,
          },
        })
      }
    },
  }
}

// hooks/useV2PairDetails.ts
export function useV2PairDetails(chainId: number, address: string) {
  const { data, loading, error } = useV2PairQuery({
    variables: {
      chain: chainIdToGraphQLChain(chainId),
      address,
    },
  })
  
  return {
    pair: data?.v2Pair,
    loading,
    error,
  }
}

// hooks/usePoolPriceChart.ts
export function usePoolPriceChart({
  chainId,
  address,
  duration,
}: {
  chainId: number
  address: string
  duration: HistoryDuration
}) {
  const { data, loading } = usePoolPriceHistoryQuery({
    variables: {
      chain: chainIdToGraphQLChain(chainId),
      addressOrId: address,
      duration,
      isV2: true,
      isV3: false,
      isV4: false,
    },
  })
  
  return {
    priceHistory: data?.v2Pair?.priceHistory,
    loading,
  }
}
```

#### 3.3 表格组件复用

参考 Uniswap 的 `PoolsTable` 组件设计：
- 使用 `@tanstack/react-table` 构建表格
- 支持排序、过滤、分页
- 响应式设计（移动端隐藏部分列）
- Loading skeleton 状态
- 错误处理

---

### 4. 性能优化建议

1. **分页策略**：
   - Pools 列表：使用 TVL cursor 分页
   - 交易列表：使用 timestamp cursor 分页
   - 每页 25-100 条数据

2. **缓存策略**：
   - Apollo Client 自动缓存
   - 设置合理的 `fetchPolicy`
   - 对静态数据使用 `cache-first`
   - 对实时数据使用 `cache-and-network`

3. **数据预加载**：
   - 在 Explore 页面预加载 Pool 详情
   - 使用 `prefetchQuery` 提前加载

4. **图表优化**：
   - 使用 `useMemo` 缓存图表数据处理
   - 限制图表数据点数量（如最多 1000 个点）
   - 使用 Web Worker 处理大量数据

---

### 5. 与 Subgraph 同步策略

**数据流**：
```
Subgraph (The Graph)
    ↓ (定期同步 1-5 分钟)
BFF Postgres (权威数据源)
    ↓ (GraphQL 查询)
前端 (Apollo Client 缓存)
```

**同步模块**（BFF 端）：
- `SubgraphSyncService`: 定时拉取 Subgraph 数据
- `SubgraphClient`: GraphQL 客户端
- 数据落库：`pair_cache`, `token_meta`, `swap_tx` 等表

**实时更新**（可选）：
- WebSocket 监听链上事件
- 触发缓存失效
- 前端自动重新查询

---

## 总结

### Uniswap V2 查询功能核心特点

1. **查询分离**：
   - 列表查询（TopV2Pairs, V2Transactions）
   - 详情查询（V2Pair, Token）
   - 图表查询（PriceHistory, VolumeHistory）
   - 交易历史查询（V2PairTransactions, V2TokenTransactions）

2. **分页方式**：
   - 列表：游标分页（tvlCursor, timestampCursor）
   - 图表：时间范围参数（duration）

3. **数据聚合**：
   - 前端计算 APR、volOverTvl
   - 后端提供原始数据（volume, tvl, feeTier）

4. **协议版本过滤**：
   - 支持 V2/V3/V4 混合查询
   - 使用 `@include` 指令条件查询

### DripSwap 实现路径

**Phase 1 - MVP（仅 V2）**：
1. 实现 TopV2Pairs 查询（Explore Pools）
2. 实现 V2Transactions 查询（Explore Transactions）
3. 实现 V2Pair 详情查询
4. 实现基本的交易历史表格

**Phase 2 - 图表增强**：
1. 添加 PoolPriceHistory 图表
2. 添加 PoolVolumeHistory 图表
3. 优化图表交互（时间范围切换）

**Phase 3 - Token 详情**：
1. 实现 TokenWeb 查询
2. 实现 Token 图表数据
3. 实现 Token 交易历史

**Phase 4 - Positions**：
1. 实现 REST API 提供 positions
2. 前端 Positions 页面
3. V2 Position 详情页

---

**文档结束**

> **下一步**：根据本文档设计 DripSwap 的 BFF GraphQL Schema 和前端查询 Hooks
