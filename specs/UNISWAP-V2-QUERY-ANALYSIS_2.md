# Uniswap Interface 查询架构分析

**版本**: v1.0  
**状态**: 分析完成  
**最后更新**: 2025-12-16  
**目的**: 为 DripSwap BFF 设计提供参考

---

## 1. 核心发现总结

### 1.1 架构模式

**Uniswap 使用的是 "统一 GraphQL 后端" 模式,而非直接查询 Subgraph!**

```
前端 (interface)
    ↓ GraphQL Query
Uniswap Backend API (Cloudflare + AWS)
    ↓ 聚合多个数据源
    ├─ Subgraph V2 (The Graph)
    ├─ Subgraph V3 (The Graph)
    ├─ Subgraph V4 (The Graph)
    ├─ Redis/缓存层
    ├─ PostgreSQL (聚合数据)
    └─ 第三方 API (价格、NFT 等)
```

### 1.2 关键结论

| 维度 | Uniswap 方案 | 对你的影响 |
|------|-------------|-----------|
| **数据来源** | 统一后端 API,封装所有数据源 | ✅ 你的 BFF 设计正确,避免前端直连 Subgraph |
| **Subgraph 角色** | 仅作为后端的一个数据源 | ✅ Subgraph 不对外暴露,只被 BFF 消费 |
| **缓存策略** | 后端实现多层缓存(Redis + DB) | ⚠️ 你需要在 BFF 中实现缓存层 |
| **数据聚合** | 后端预处理 + 实时查询混合 | ⚠️ 你需要决定哪些数据预聚合,哪些实时查询 |
| **API 协议** | GraphQL (统一 Schema) | ✅ 你的 BFF 使用 GraphQL 是正确的 |

---

## 2. Uniswap Backend API 架构

### 2.1 API 端点配置

**核心 API 基础 URL**:
```typescript
// packages/uniswap/src/constants/urls.ts

const uniswapUrls = {
  // 主 API 端点(Cloudflare Workers)
  apiBaseUrl: 'https://interface.gateway.uniswap.org',
  
  // GraphQL 端点
  graphQLUrl: 'https://interface.gateway.uniswap.org/v1/graphql',
  
  // V2 API(Connect RPC)
  apiBaseUrlV2: 'https://interface.gateway.uniswap.org/v2',
  
  // 数据服务端点
  dataApiServiceUrl: 'https://interface.gateway.uniswap.org/v2/data.v1.DataApiService',
  
  // 交易 API
  tradingApiUrl: 'https://interface.gateway.uniswap.org',
};
```

**环境特定前缀**:
```typescript
// Mobile: android.wallet.gateway.uniswap.org / ios.wallet.gateway.uniswap.org
// Extension: extension.gateway.uniswap.org
// Web: interface.gateway.uniswap.org
// Beta: beta.gateway.uniswap.org
```

### 2.2 数据流向

```
┌────────────────────────────────────────────────────────────────────┐
│                       Frontend (React)                              │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐                   │
│  │ Apollo     │  │ TanStack   │  │ REST API   │                   │
│  │ Client     │  │ Query      │  │ Client     │                   │
│  └─────┬──────┘  └─────┬──────┘  └─────┬──────┘                   │
└────────┼───────────────┼───────────────┼─────────────────────────┘
         │               │               │
         │ GraphQL       │ REST          │ REST
         │               │               │
┌────────┼───────────────┼───────────────┼─────────────────────────┐
│        ↓               ↓               ↓                          │
│   Cloudflare Workers Gateway (uniswap.org)                        │
│   ┌────────────────────────────────────────────────┐              │
│   │  API Gateway (路由 + 鉴权 + 限流)              │              │
│   └────────┬─────────────────────┬─────────────────┘              │
│            │                     │                                │
│            ↓                     ↓                                │
│   ┌────────────────┐    ┌────────────────┐                       │
│   │  GraphQL       │    │  REST API      │                       │
│   │  Resolver      │    │  Handlers      │                       │
│   └────────┬───────┘    └────────┬───────┘                       │
└────────────┼──────────────────────┼────────────────────────────┘
             │                      │
             ↓                      ↓
┌────────────────────────────────────────────────────────────────────┐
│                    Backend Services (AWS)                           │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐            │
│  │   Redis      │  │  PostgreSQL  │  │  DynamoDB    │            │
│  │  (缓存层)     │  │ (聚合数据)    │  │ (用户数据)    │            │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘            │
│         │                  │                  │                    │
│         └──────────────────┼──────────────────┘                    │
│                            │                                       │
│                ┌───────────┼──────────────┐                        │
│                │           │              │                        │
│                ↓           ↓              ↓                        │
│   ┌───────────────┐ ┌──────────────┐ ┌──────────────┐            │
│   │  Subgraph V2  │ │  Subgraph V3 │ │  Subgraph V4 │            │
│   │ (The Graph)   │ │ (The Graph)  │ │ (The Graph)  │            │
│   └───────────────┘ └──────────────┘ └──────────────┘            │
│                                                                    │
│   ┌───────────────┐ ┌──────────────┐ ┌──────────────┐            │
│   │ 3rd Party API │ │  NFT API     │ │  Price Feed  │            │
│   └───────────────┘ └──────────────┘ └──────────────┘            │
└────────────────────────────────────────────────────────────────────┘
```

---

## 3. 前端查询分类

### 3.1 V2 相关查询

#### 3.1.1 Top Pairs 查询

**文件**: `web/topPools.graphql`

```graphql
query TopV2Pairs($chain: Chain!, $first: Int!, $cursor: Float, $tokenAddress: String) {
  topV2Pairs(first: $first, chain: $chain, tokenFilter: $tokenAddress, tvlCursor: $cursor) {
    id
    protocolVersion
    address
    totalLiquidity { value }  # ← 预聚合数据
    token0 { ...SimpleTokenDetails }
    token1 { ...SimpleTokenDetails }
    txCount                   # ← 预聚合数据
    volume24h: cumulativeVolume(duration: DAY) { value }  # ← 预聚合数据
    volume30d: cumulativeVolume(duration: MONTH) { value }
  }
}
```

**数据来源推断**:
- ✅ `totalLiquidity`: **后端聚合** (Subgraph Pair.reserveUSD)
- ✅ `txCount`: **后端聚合** (Subgraph Pair.txCount)
- ✅ `volume24h/volume30d`: **后端预计算** (从 PairDayData 聚合)
- ⚠️ `token0/token1`: **后端缓存** (Token 基础信息)

**对应 Subgraph 表**:
| 后端字段 | Subgraph 表 | Subgraph 字段 |
|---------|------------|--------------|
| `totalLiquidity.value` | `Pair` | `reserveUSD` |
| `txCount` | `Pair` | `txCount` |
| `volume24h` | `PairDayData` | `dailyVolumeUSD` (聚合) |
| `volume30d` | `PairDayData` | `dailyVolumeUSD` (聚合 30 天) |
| `token0.symbol` | `Token` | `symbol` |
| `token0.decimals` | `Token` | `decimals` |

#### 3.1.2 Pair 详情查询

**文件**: `web/pool.graphql`

```graphql
query V2Pair($chain: Chain!, $address: String!) {
  v2Pair(chain: $chain, address: $address) {
    id
    protocolVersion
    address
    token0 { ...SimpleTokenDetails, ...TokenPrice }
    token0Supply          # ← Pair.reserve0
    token1 { ...SimpleTokenDetails, ...TokenPrice }
    token1Supply          # ← Pair.reserve1
    txCount
    volume24h: cumulativeVolume(duration: DAY) { value }
    historicalVolume(duration: WEEK) {  # ← 历史数据
      value
      timestamp
    }
    totalLiquidity { value }
    totalLiquidityPercentChange24h { value }  # ← 后端计算
  }
}
```

**数据来源推断**:
- ✅ `token0Supply/token1Supply`: **实时 Subgraph** (Pair.reserve0/reserve1)
- ✅ `historicalVolume`: **后端预聚合** (PairHourData/PairDayData)
- ✅ `totalLiquidityPercentChange24h`: **后端计算** (对比 24h 前 reserveUSD)

**对应 Subgraph 表**:
| 后端字段 | Subgraph 表 | Subgraph 字段 |
|---------|------------|--------------|
| `token0Supply` | `Pair` | `reserve0` |
| `token1Supply` | `Pair` | `reserve1` |
| `historicalVolume[]` | `PairHourData` 或 `PairDayData` | `hourlyVolumeUSD` 或 `dailyVolumeUSD` |
| `totalLiquidity.value` | `Pair` | `reserveUSD` |
| `totalLiquidityPercentChange24h` | `Pair` + `PairDayData` | 计算: (当前 - 24h前) / 24h前 |

#### 3.1.3 价格历史查询 (K 线数据)

**文件**: `web/pool.graphql`

```graphql
query PoolPriceHistory(
  $chain: Chain!, 
  $addressOrId: String!, 
  $duration: HistoryDuration!,
  $isV2: Boolean!
) {
  v2Pair(chain: $chain, address: $addressOrId) @include(if: $isV2) {
    id
    priceHistory(duration: $duration) {  # ← K 线数据
      id
      token0Price
      token1Price
      timestamp
    }
  }
}
```

**数据来源推断**:
- ✅ `priceHistory`: **后端预聚合** 
  - `duration: HOUR` → 从 `PairHourData` 聚合
  - `duration: DAY` → 从 `PairDayData` 聚合
  - `duration: WEEK/MONTH/YEAR` → 从 `PairDayData` 聚合

**对应 Subgraph 表**:
| 后端字段 | Subgraph 表 | Subgraph 字段 |
|---------|------------|--------------|
| `priceHistory[].token0Price` | `PairHourData` 或 `PairDayData` | `reserve0` / `reserve1` (计算) |
| `priceHistory[].token1Price` | `PairHourData` 或 `PairDayData` | `reserve1` / `reserve0` (计算) |
| `priceHistory[].timestamp` | `PairHourData` 或 `PairDayData` | `hourStartUnix` 或 `date` |

⚠️ **注意**: Subgraph 的 PairHourData/PairDayData 没有直接存储 `token0Price`!  
后端需要根据 `reserve0` 和 `reserve1` 计算:
```sql
token0Price = reserve1 / reserve0
token1Price = reserve0 / reserve1
```

#### 3.1.4 交易历史查询

**文件**: `web/poolTransactions.graphql` 和 `web/tokenTransactions.graphql`

```graphql
query V2PairTransactions($chain: Chain!, $address: String!, $first: Int!, $cursor: Int) {
  v2Pair(chain: $chain, address: $address) {
    id
    transactions(first: $first, timestampCursor: $cursor) {
      timestamp
      hash
      account
      token0 { ...PoolTransactionToken }
      token0Quantity
      token1 { ...PoolTransactionToken }
      token1Quantity
      usdValue { value }
      type  # SWAP | MINT | BURN
    }
  }
}

query V2TokenTransactions($chain: Chain!, $address: String!, $first: Int!, $cursor: Int) {
  token(chain: $chain, address: $address) {
    id
    v2Transactions(first: $first, timestampCursor: $cursor) {
      # 同上
    }
  }
}
```

**数据来源推断**:
- ✅ `transactions`: **后端聚合** (Swap + Mint + Burn 合并)
- ✅ `usdValue`: **后端计算** (实时价格 × 数量)

**对应 Subgraph 表**:
| 后端字段 | Subgraph 表 | Subgraph 字段 |
|---------|------------|--------------|
| `transactions[].timestamp` | `Swap` / `Mint` / `Burn` | `timestamp` |
| `transactions[].hash` | `Transaction` | `id` (交易哈希) |
| `transactions[].account` | `Swap` / `Mint` / `Burn` | `from` / `to` / `sender` |
| `transactions[].token0Quantity` | `Swap` / `Mint` / `Burn` | `amount0In/Out` / `amount0` |
| `transactions[].token1Quantity` | `Swap` / `Mint` / `Burn` | `amount1In/Out` / `amount1` |
| `transactions[].type` | `Swap` / `Mint` / `Burn` | 实体类型 |
| `transactions[].usdValue` | - | **后端计算**: amount × token.derivedETH × bundle.ethPrice |

⚠️ **重要**: Subgraph 中 Swap/Mint/Burn 是独立的表,后端需要:
1. 合并三个表的数据
2. 按 timestamp 排序
3. 实时计算 USD 价值

#### 3.1.5 Token 详情查询

**文件**: `web/token.graphql`

```graphql
query TokenWeb($chain: Chain!, $address: String = null) {
  token(chain: $chain, address: $address) {
    id
    decimals
    name
    symbol
    market(currency: USD) {
      totalValueLocked { value }      # ← TVL
      price { value }                 # ← 当前价格
      volume24H: volume(duration: DAY) { value }  # ← 24h 交易量
      priceHigh52W: priceHighLow(duration: YEAR, highLow: HIGH) { value }
      priceLow52W: priceHighLow(duration: YEAR, highLow: LOW) { value }
    }
    project {
      name
      description
      homepageUrl
      twitterName
      markets(currencies: [USD]) {
        fullyDilutedValuation { value }  # ← FDV
        marketCap { value }              # ← 市值
      }
    }
  }
}
```

**数据来源推断**:
- ✅ `totalValueLocked`: **后端聚合** (所有 Pair 中该 Token 的流动性总和)
- ✅ `price`: **后端实时计算** (Token.derivedETH × ETH/USD)
- ✅ `volume24H`: **后端聚合** (TokenDayData)
- ✅ `priceHigh52W/priceLow52W`: **后端聚合** (TokenDayData 历史数据)
- ❌ `project.*`: **外部数据源** (不在 Subgraph 中!)

**对应 Subgraph 表**:
| 后端字段 | Subgraph 表 | Subgraph 字段 |
|---------|------------|--------------|
| `decimals` | `Token` | `decimals` |
| `name` | `Token` | `name` |
| `symbol` | `Token` | `symbol` |
| `market.totalValueLocked` | `Token` | `totalLiquidity` × `derivedETH` × `ethPrice` |
| `market.price` | `Token` + `Bundle` | `derivedETH` × `ethPrice` |
| `market.volume24H` | `TokenDayData` | 最近一天的 `dailyVolumeUSD` |
| `priceHigh52W` | `TokenDayData` | 近 365 天 `priceUSD` 最高值 |
| `priceLow52W` | `TokenDayData` | 近 365 天 `priceUSD` 最低值 |

#### 3.1.6 Token 价格历史 (K 线)

**文件**: `web/tokenCharts.graphql`

```graphql
query TokenPrice($chain: Chain!, $address: String!, $duration: HistoryDuration!) {
  token(chain: $chain, address: $address) {
    id
    market(currency: USD) {
      ohlc(duration: $duration) {  # ← OHLC K 线数据
        timestamp
        open { value }
        high { value }
        low { value }
        close { value }
      }
    }
  }
}
```

**数据来源推断**:
- ✅ `ohlc`: **后端预聚合**
  - `duration: HOUR` → 从 `TokenHourData` 聚合
  - `duration: DAY` → 从 `TokenDayData` 聚合

**对应 Subgraph 表**:
| 后端字段 | Subgraph 表 | Subgraph 字段 |
|---------|------------|--------------|
| `ohlc[].open` | `TokenHourData` | `open` (v2-tokens 有此字段) |
| `ohlc[].high` | `TokenHourData` | `high` |
| `ohlc[].low` | `TokenHourData` | `low` |
| `ohlc[].close` | `TokenHourData` | `close` |
| `ohlc[].timestamp` | `TokenHourData` | `periodStartUnix` |

⚠️ **重要发现**: 
- v2 子图的 `TokenHourData` **没有** OHLC 字段!
- v2-tokens 子图的 `TokenHourData` **有** OHLC 字段!
- **结论**: Uniswap 使用 v2-tokens 子图提供 K 线数据

---

### 3.2 全局查询

#### 3.2.1 多 Token 价格查询

**文件**: `web/UniswapPrices.graphql`

```graphql
query UniswapPrices($contracts: [ContractInput!]!) {
  tokens(contracts: $contracts) {
    id
    address
    chain
    project {
      markets(currencies: [USD]) {
        price { value }
      }
    }
  }
}
```

**数据来源推断**:
- ✅ **批量查询优化**: 后端一次查询多个 Token
- ✅ **缓存友好**: 高频访问的价格数据可能有 Redis 缓存

---

## 4. 数据处理策略分析

### 4.1 实时查询 vs 预聚合

| 数据类型 | 查询频率 | Uniswap 策略 | 数据源 |
|---------|---------|-------------|--------|
| **Pair 当前储备量** | 高 | **实时 Subgraph** | `Pair.reserve0/reserve1` |
| **Token 当前价格** | 极高 | **Redis 缓存** + Subgraph | `Token.derivedETH` × `Bundle.ethPrice` |
| **24h 交易量** | 高 | **预聚合** (DB) | `PairDayData.dailyVolumeUSD` |
| **历史 K 线数据** | 中 | **预聚合** (DB) | `TokenHourData` / `PairHourData` |
| **交易历史** | 中 | **混合** (Subgraph + DB) | `Swap` + `Mint` + `Burn` |
| **Top Pairs 排行** | 低 | **预聚合** (DB + 缓存) | 定时任务聚合 |

### 4.2 缓存策略推断

```
┌─────────────────────────────────────────────────────────────┐
│                    缓存层级                                   │
├─────────────────────────────────────────────────────────────┤
│ L1: CDN 缓存 (Cloudflare)                                   │
│   - 静态资源                                                 │
│   - 低频变化的查询结果 (TTL: 60s)                            │
├─────────────────────────────────────────────────────────────┤
│ L2: Redis 缓存                                               │
│   - Token 价格 (TTL: 5s)                                    │
│   - Top Pairs (TTL: 30s)                                    │
│   - Token 基础信息 (TTL: 1h)                                 │
├─────────────────────────────────────────────────────────────┤
│ L3: PostgreSQL (预聚合数据)                                  │
│   - 历史 K 线数据                                            │
│   - 交易历史                                                 │
│   - 统计数据                                                 │
├─────────────────────────────────────────────────────────────┤
│ L4: Subgraph (实时数据)                                      │
│   - Pair 储备量                                              │
│   - 最新交易                                                 │
│   - Token 价格计算原始数据                                   │
└─────────────────────────────────────────────────────────────┘
```

### 4.3 预聚合任务推断

**推测的后端定时任务**:

```typescript
// 伪代码:Uniswap 后端可能的聚合任务

// 1. 每 1 分钟:更新 Token 价格缓存
async function updateTokenPricesCache() {
  const tokens = await subgraph.query(`
    query {
      tokens(first: 1000, orderBy: totalLiquidity, orderDirection: desc) {
        id
        derivedETH
      }
      bundle(id: "1") {
        ethPrice
      }
    }
  `);
  
  tokens.forEach(token => {
    const priceUSD = token.derivedETH * bundle.ethPrice;
    redis.set(`token:price:${token.id}`, priceUSD, 'EX', 300); // 5分钟过期
  });
}

// 2. 每 5 分钟:更新 Top Pairs 排行
async function updateTopPairs() {
  const pairs = await subgraph.query(`
    query {
      pairs(first: 100, orderBy: reserveUSD, orderDirection: desc) {
        id
        reserveUSD
        txCount
        # ... 其他字段
      }
    }
  `);
  
  // 计算 24h 交易量(从 PairDayData)
  for (const pair of pairs) {
    const dayData = await subgraph.query(`
      query {
        pairDayDatas(
          where: { pair: "${pair.id}" }
          first: 1
          orderBy: date
          orderDirection: desc
        ) {
          dailyVolumeUSD
        }
      }
    `);
    pair.volume24h = dayData[0]?.dailyVolumeUSD || 0;
  }
  
  await db.upsert('top_pairs', pairs);
  await redis.set('top_pairs', JSON.stringify(pairs), 'EX', 1800); // 30分钟
}

// 3. 每小时:聚合历史数据到 DB
async function aggregateHistoricalData() {
  // 从 Subgraph 拉取 TokenHourData/PairHourData
  // 转换为 OHLC 格式
  // 存储到 PostgreSQL
  
  const hourData = await subgraph.query(`
    query {
      tokenHourDatas(
        where: { periodStartUnix_gte: ${lastProcessedTime} }
        first: 1000
      ) {
        id
        token { id }
        periodStartUnix
        open
        high
        low
        close
        volume
      }
    }
  `);
  
  await db.bulkInsert('token_ohlc', hourData);
}

// 4. 每天:聚合每日统计数据
async function aggregateDailyStats() {
  // 聚合交易量、流动性变化等
  // 计算 priceHigh52W/priceLow52W
}
```

---

## 5. 对你的 BFF 设计建议

### 5.1 应该做什么

#### ✅ 1. 实现统一 GraphQL 端点

**原因**: Uniswap 证明这种架构的可行性

**实现方案**:
```java
// BFF GraphQL Schema 设计
type Query {
  # Pair 相关
  topPairs(chain: String!, first: Int!, tvlCursor: Float): [V2Pair!]!
  v2Pair(chain: String!, address: String!): V2Pair
  
  # Token 相关
  token(chain: String!, address: String!): Token
  tokens(contracts: [ContractInput!]!): [Token!]!
  
  # 交易历史
  pairTransactions(chain: String!, address: String!, first: Int!, cursor: Int): [PoolTransaction!]!
  tokenTransactions(chain: String!, address: String!, first: Int!, cursor: Int): [PoolTransaction!]!
  
  # K 线数据
  tokenPriceHistory(chain: String!, address: String!, duration: HistoryDuration!): TokenOHLC!
  pairPriceHistory(chain: String!, address: String!, duration: HistoryDuration!): PairPriceHistory!
}

type V2Pair {
  id: ID!
  address: String!
  token0: Token!
  token0Supply: Float!
  token1: Token!
  token1Supply: Float!
  txCount: Int!
  totalLiquidity: Amount!
  volume24h: Amount!
  historicalVolume(duration: HistoryDuration!): [TimestampedAmount!]!
  priceHistory(duration: HistoryDuration!): [TimestampedPoolPrice!]!
}
```

#### ✅ 2. 实现多层缓存

**推荐架构**:
```java
@Service
public class TokenPriceService {
  @Autowired private RedisTemplate<String, String> redis;
  @Autowired private SubgraphClient subgraphClient;
  
  public BigDecimal getTokenPrice(String tokenAddress) {
    // L1: Redis 缓存(5分钟)
    String cacheKey = "token:price:" + tokenAddress;
    String cached = redis.opsForValue().get(cacheKey);
    if (cached != null) {
      return new BigDecimal(cached);
    }
    
    // L2: 查询 Subgraph
    TokenPriceData data = subgraphClient.query("""
      query {
        token(id: "%s") {
          derivedETH
        }
        bundle(id: "1") {
          ethPrice
        }
      }
    """, tokenAddress);
    
    BigDecimal priceUSD = data.token.derivedETH
                          .multiply(data.bundle.ethPrice);
    
    // 写入缓存
    redis.opsForValue().set(cacheKey, priceUSD.toString(), 
                            Duration.ofMinutes(5));
    
    return priceUSD;
  }
}
```

#### ✅ 3. 实现预聚合定时任务

**核心任务**:

**任务 1: 更新 Top Pairs 缓存**
```java
@Scheduled(fixedRate = 300000) // 每 5 分钟
public void updateTopPairs() {
  List<Pair> pairs = subgraphClient.queryTopPairs();
  
  // 聚合 24h 交易量
  for (Pair pair : pairs) {
    BigDecimal volume24h = subgraphClient.queryPairDayVolume(pair.getId());
    pair.setVolume24h(volume24h);
  }
  
  // 存入 Redis
  redis.opsForValue().set("top_pairs:" + chain, 
                          JSON.toJSONString(pairs),
                          Duration.ofMinutes(30));
}
```

**任务 2: 聚合历史 K 线数据**
```java
@Scheduled(cron = "0 5 * * * *") // 每小时第 5 分钟执行
public void aggregateOHLCData() {
  // 查询 v2-tokens Subgraph 的 TokenHourData
  List<TokenHourData> hourData = subgraphClient.queryTokenHourData(
    lastProcessedTimestamp
  );
  
  // 转换并存储到 PostgreSQL
  tokenOHLCRepository.saveAll(hourData.stream()
    .map(this::convertToOHLC)
    .collect(Collectors.toList()));
  
  updateLastProcessedTimestamp();
}
```

**任务 3: 聚合交易历史**
```java
@Scheduled(fixedRate = 60000) // 每 1 分钟
public void aggregateTransactions() {
  // 合并 Swap + Mint + Burn
  List<PoolTransaction> transactions = new ArrayList<>();
  
  transactions.addAll(subgraphClient.querySwaps(lastBlock));
  transactions.addAll(subgraphClient.queryMints(lastBlock));
  transactions.addAll(subgraphClient.queryBurns(lastBlock));
  
  // 按时间排序
  transactions.sort(Comparator.comparing(PoolTransaction::getTimestamp).reversed());
  
  // 计算 USD 价值
  for (PoolTransaction tx : transactions) {
    BigDecimal usdValue = calculateUsdValue(tx);
    tx.setUsdValue(usdValue);
  }
  
  // 存储到 DB
  poolTransactionRepository.saveAll(transactions);
}
```

#### ✅ 4. Subgraph 字段映射表

**为每个查询创建映射关系**:

```java
// 配置文件:subgraph-mapping.yml
queries:
  topPairs:
    dataSource: aggregated  # 预聚合数据
    cacheStrategy: redis
    cacheTTL: 300
    fields:
      - name: totalLiquidity
        source: subgraph.Pair.reserveUSD
        transformation: none
      - name: volume24h
        source: subgraph.PairDayData.dailyVolumeUSD
        transformation: aggregateLast24h
      - name: txCount
        source: subgraph.Pair.txCount
        transformation: none
        
  pairDetails:
    dataSource: realtime  # 实时查询
    cacheStrategy: shortLived
    cacheTTL: 30
    fields:
      - name: token0Supply
        source: subgraph.Pair.reserve0
        transformation: none
      - name: token1Supply
        source: subgraph.Pair.reserve1
        transformation: none
```

### 5.2 不应该做什么

#### ❌ 1. 不要让前端直接查询 Subgraph

**原因**:
- Subgraph 查询限制(1000 条/次,嵌套深度限制)
- 无法实现缓存和聚合
- 难以处理跨链查询
- 无法统一鉴权和限流

#### ❌ 2. 不要完全依赖实时 Subgraph 查询

**原因**:
- The Graph 查询延迟较高(100-500ms)
- 聚合查询(如 Top Pairs)性能差
- 无法实现复杂的业务逻辑

#### ❌ 3. 不要在 Subgraph 中计算 OHLC

**原因**:
- v2 子图没有 OHLC 数据
- 需要使用 v2-tokens 子图
- 后端需要转换数据格式

#### ❌ 4. 不要忽略价格计算的复杂性

**Subgraph 中的价格**:
- `Token.derivedETH`: 相对 ETH 的价格
- `Bundle.ethPrice`: ETH 相对 USD 的价格
- **USD 价格** = `derivedETH` × `ethPrice`

**K 线价格**:
- `PairHourData` 没有存储 `token0Price`!
- 需要根据 `reserve0` 和 `reserve1` 计算
- 公式: `token0Price = reserve1 / reserve0`

---

## 6. 完整实现路径

### 6.1 Phase 1: MVP (最小可行产品)

**目标**: 实现核心查询功能

**实现内容**:
1. ✅ BFF GraphQL Schema 设计
2. ✅ 直接查询 Subgraph(无缓存)
3. ✅ 实现基础 Resolver
   - `topPairs`
   - `v2Pair`
   - `token`
   - `pairTransactions`

**技术栈**:
- Spring Boot + GraphQL Java
- Apollo Federation(如果需要多服务)
- GraphQL Client(查询 Subgraph)

### 6.2 Phase 2: 缓存优化

**目标**: 提升查询性能

**实现内容**:
1. ✅ Redis 缓存层
   - Token 价格缓存(5min TTL)
   - Top Pairs 缓存(30min TTL)
   - Token 基础信息缓存(1h TTL)
2. ✅ 缓存预热定时任务
3. ✅ 缓存失效策略

### 6.3 Phase 3: 预聚合

**目标**: 支持复杂查询

**实现内容**:
1. ✅ PostgreSQL 聚合表设计
   ```sql
   -- Token OHLC 数据
   CREATE TABLE token_ohlc (
     id BIGSERIAL PRIMARY KEY,
     chain_id INT NOT NULL,
     token_address VARCHAR(42) NOT NULL,
     timestamp BIGINT NOT NULL,
     open NUMERIC(78, 18),
     high NUMERIC(78, 18),
     low NUMERIC(78, 18),
     close NUMERIC(78, 18),
     volume NUMERIC(78, 18),
     INDEX idx_token_time (token_address, timestamp)
   );
   
   -- Pair 交易历史
   CREATE TABLE pool_transactions (
     id BIGSERIAL PRIMARY KEY,
     chain_id INT NOT NULL,
     pair_address VARCHAR(42) NOT NULL,
     tx_hash VARCHAR(66) NOT NULL,
     timestamp BIGINT NOT NULL,
     account VARCHAR(42),
     token0_amount NUMERIC(78, 18),
     token1_amount NUMERIC(78, 18),
     usd_value NUMERIC(78, 18),
     type VARCHAR(20),  -- SWAP | MINT | BURN
     INDEX idx_pair_time (pair_address, timestamp),
     INDEX idx_account (account)
   );
   ```

2. ✅ 定时聚合任务
   - 每小时聚合 TokenHourData → DB
   - 每分钟同步最新交易 → DB
   - 每 5 分钟更新 Top Pairs

### 6.4 Phase 4: 高级功能

**实现内容**:
1. ✅ 跨链查询支持
2. ✅ 用户持仓查询(Portfolio)
3. ✅ 交易搜索和过滤
4. ✅ WebSocket 实时推送

---

## 7. 关键技术决策

### 7.1 Query 1: Top Pairs

**前端需求**:
```graphql
query TopPairs {
  topPairs(chain: "sepolia", first: 20) {
    address
    token0 { symbol, decimals }
    token1 { symbol, decimals }
    totalLiquidity
    volume24h
    txCount
  }
}
```

**BFF 实现选项**:

**选项 A: 完全实时查询 Subgraph** ❌
```java
// 性能差,每次查询 500ms+
List<Pair> pairs = subgraph.queryTopPairs(20);
for (Pair pair : pairs) {
  // 需要额外查询 PairDayData
  pair.volume24h = subgraph.queryPairDayVolume(pair.id);
}
```

**选项 B: Redis 缓存 + 定时更新** ✅ **(推荐)**
```java
@Cacheable(value = "topPairs", key = "#chain", ttl = 300)
public List<Pair> getTopPairs(String chain) {
  // 从 Redis 读取,如果没有则查询 Subgraph
  return redis.get("topPairs:" + chain);
}

@Scheduled(fixedRate = 300000)
public void updateTopPairs() {
  List<Pair> pairs = subgraph.queryAndAggregate();
  redis.set("topPairs:" + chain, pairs, 300);
}
```

**选项 C: PostgreSQL 预聚合** ⚠️ (复杂场景)
```java
public List<Pair> getTopPairs(String chain) {
  // 从 DB 读取预聚合数据
  return pairRepository.findTopByLiquidity(chain, 20);
}
```

**推荐**: **选项 B**(MVP 阶段) → **选项 C**(生产环境)

### 7.2 Query 2: Pair 价格历史 (K 线)

**前端需求**:
```graphql
query PairPriceHistory {
  v2Pair(address: "0x...") {
    priceHistory(duration: DAY) {
      timestamp
      token0Price
      token1Price
    }
  }
}
```

**BFF 实现选项**:

**选项 A: 实时查询 Subgraph PairDayData** ⚠️
```java
// 问题:Subgraph 没有存储 token0Price!
List<PairDayData> dayData = subgraph.query("""
  query {
    pairDayDatas(
      where: { pair: "%s" }
      first: 30
      orderBy: date
      orderDirection: desc
    ) {
      date
      reserve0
      reserve1
    }
  }
""", pairAddress);

// 需要手动计算价格
for (PairDayData data : dayData) {
  data.token0Price = data.reserve1.divide(data.reserve0);
  data.token1Price = data.reserve0.divide(data.reserve1);
}
```

**选项 B: PostgreSQL 预聚合 + 计算** ✅ **(推荐)**
```java
@Scheduled(cron = "0 10 0 * * *") // 每天 00:10
public void aggregatePairDayPrices() {
  List<PairDayData> dayData = subgraph.queryPairDayData();
  
  List<PairPriceHistory> history = dayData.stream()
    .map(data -> PairPriceHistory.builder()
      .pairAddress(data.getPairAddress())
      .timestamp(data.getDate())
      .token0Price(data.getReserve1().divide(data.getReserve0()))
      .token1Price(data.getReserve0().divide(data.getReserve1()))
      .build())
    .collect(Collectors.toList());
  
  pairPriceHistoryRepository.saveAll(history);
}

// 查询时直接读 DB
public List<PairPriceHistory> getPriceHistory(String pairAddress) {
  return pairPriceHistoryRepository.findByPairAddress(pairAddress);
}
```

**推荐**: **选项 B**,原因:
- Subgraph 没有直接存储价格
- 计算逻辑复杂,适合后端处理
- DB 查询性能更好

### 7.3 Query 3: Token 价格 (实时)

**前端需求**:
```graphql
query TokenPrice {
  token(address: "0x...") {
    market {
      price { value }
    }
  }
}
```

**BFF 实现选项**:

**选项 A: 实时 Subgraph + Redis 缓存** ✅ **(推荐)**
```java
@Cacheable(value = "tokenPrice", key = "#address", ttl = 30)
public BigDecimal getTokenPrice(String address) {
  // 查询 Subgraph
  TokenPriceData data = subgraph.query("""
    query {
      token(id: "%s") { derivedETH }
      bundle(id: "1") { ethPrice }
    }
  """, address);
  
  return data.token.derivedETH.multiply(data.bundle.ethPrice);
}
```

**选项 B: 定时更新所有 Token 价格到 Redis** ⚠️
```java
@Scheduled(fixedRate = 60000) // 每分钟
public void updateAllTokenPrices() {
  List<Token> tokens = subgraph.queryAllTokens();
  Bundle bundle = subgraph.queryBundle();
  
  for (Token token : tokens) {
    BigDecimal price = token.getDerivedETH()
                       .multiply(bundle.getEthPrice());
    redis.set("token:price:" + token.getAddress(), price, 300);
  }
}
```

**推荐**: **选项 A**,原因:
- 按需查询,节省资源
- 缓存命中率高
- TTL 较短(30s),价格较新鲜

---

## 8. 完整数据流示例

### 示例:用户查询 UNI/WETH Pair 详情页

**前端查询**:
```graphql
query PairDetails {
  v2Pair(chain: "sepolia", address: "0xabc...") {
    address
    token0 { symbol, decimals }
    token0Supply
    token1 { symbol, decimals }
    token1Supply
    totalLiquidity
    volume24h
    txCount
    priceHistory(duration: WEEK) {
      timestamp
      token0Price
      token1Price
    }
    transactions(first: 20) {
      timestamp
      hash
      type
      token0Quantity
      token1Quantity
      usdValue
    }
  }
}
```

**BFF 处理流程**:

```java
@Component
public class PairResolver {
  
  @QueryMapping
  public V2Pair v2Pair(@Argument String chain, @Argument String address) {
    // 1. 查询 Pair 基础信息(实时 Subgraph)
    Pair pair = subgraphClient.queryPair(address);
    
    // 2. 查询 24h 交易量(Redis 缓存)
    BigDecimal volume24h = getCachedVolume24h(address);
    
    // 3. 查询价格历史(PostgreSQL)
    List<PricePoint> priceHistory = pairPriceHistoryRepo
      .findByPairAndTimeRange(address, Duration.ofDays(7));
    
    // 4. 查询交易历史(PostgreSQL + Subgraph 混合)
    List<PoolTransaction> transactions = getRecentTransactions(address, 20);
    
    // 5. 组装返回
    return V2Pair.builder()
      .address(pair.getAddress())
      .token0(pair.getToken0())
      .token0Supply(pair.getReserve0())
      .token1(pair.getToken1())
      .token1Supply(pair.getReserve1())
      .totalLiquidity(pair.getReserveUSD())
      .volume24h(volume24h)
      .txCount(pair.getTxCount())
      .priceHistory(priceHistory)
      .transactions(transactions)
      .build();
  }
  
  private BigDecimal getCachedVolume24h(String address) {
    String cacheKey = "pair:volume24h:" + address;
    String cached = redis.get(cacheKey);
    
    if (cached != null) {
      return new BigDecimal(cached);
    }
    
    // 从 Subgraph 聚合
    List<PairDayData> dayData = subgraphClient.queryPairDayData(
      address, 1  // 最近 1 天
    );
    
    BigDecimal volume = dayData.isEmpty() ? BigDecimal.ZERO 
                       : dayData.get(0).getDailyVolumeUSD();
    
    redis.set(cacheKey, volume.toString(), 300); // 5分钟过期
    return volume;
  }
  
  private List<PoolTransaction> getRecentTransactions(String address, int limit) {
    // 优先从 DB 读取
    List<PoolTransaction> dbTxs = poolTxRepo
      .findByPairAddress(address, PageRequest.of(0, limit));
    
    if (!dbTxs.isEmpty()) {
      return dbTxs;
    }
    
    // Fallback:查询 Subgraph
    List<Swap> swaps = subgraphClient.querySwaps(address, limit);
    List<Mint> mints = subgraphClient.queryMints(address, limit);
    List<Burn> burns = subgraphClient.queryBurns(address, limit);
    
    // 合并并排序
    List<PoolTransaction> txs = mergeAndSort(swaps, mints, burns);
    
    // 异步写入 DB
    CompletableFuture.runAsync(() -> poolTxRepo.saveAll(txs));
    
    return txs;
  }
}
```

**数据来源总结**:

| 字段 | 数据来源 | 延迟 |
|------|---------|------|
| `token0/token1` | Subgraph Pair | ~100ms |
| `token0Supply/token1Supply` | Subgraph Pair.reserve0/reserve1 | ~100ms |
| `totalLiquidity` | Subgraph Pair.reserveUSD | ~100ms |
| `volume24h` | Redis 缓存(Subgraph PairDayData) | ~5ms |
| `txCount` | Subgraph Pair.txCount | ~100ms |
| `priceHistory` | PostgreSQL(预聚合) | ~20ms |
| `transactions` | PostgreSQL(优先) + Subgraph(Fallback) | ~20-100ms |

**总耗时**: ~200-300ms (带缓存优化)

---

## 9. 总结与行动建议

### 9.1 核心结论

1. ✅ **Uniswap 不直接暴露 Subgraph**: 前端查询统一后端 API
2. ✅ **混合数据源架构**: Subgraph + Redis + PostgreSQL
3. ✅ **预聚合是关键**: 复杂查询必须后端预处理
4. ✅ **缓存分层设计**: CDN + Redis + DB 多级缓存

### 9.2 你的 BFF 设计清单

**Phase 1: MVP (2-3 周)**
- [ ] 设计 GraphQL Schema(参考本文档 Schema)
- [ ] 实现 Subgraph Client(查询 v2 和 v2-tokens)
- [ ] 实现核心 Resolver
  - [ ] `topPairs`
  - [ ] `v2Pair`
  - [ ] `token`
  - [ ] `pairTransactions`
- [ ] 基础单元测试

**Phase 2: 缓存优化 (1-2 周)**
- [ ] 集成 Redis
- [ ] 实现价格缓存(5min TTL)
- [ ] 实现 Top Pairs 缓存(30min TTL)
- [ ] 定时任务:缓存预热

**Phase 3: 预聚合 (2-3 周)**
- [ ] 设计 PostgreSQL 聚合表
  - [ ] `token_ohlc`
  - [ ] `pair_price_history`
  - [ ] `pool_transactions`
- [ ] 实现定时聚合任务
  - [ ] 每小时聚合 K 线数据
  - [ ] 每分钟同步最新交易
  - [ ] 每 5 分钟更新 Top Pairs
- [ ] 修改 Resolver 使用聚合数据

**Phase 4: 性能优化 (1-2 周)**
- [ ] 查询性能监控
- [ ] 慢查询优化
- [ ] 数据库索引优化
- [ ] 缓存命中率分析

### 9.3 Subgraph 字段映射速查表

**Pair 查询映射**:
```yaml
BFF.V2Pair.totalLiquidity → Subgraph.Pair.reserveUSD
BFF.V2Pair.token0Supply → Subgraph.Pair.reserve0
BFF.V2Pair.token1Supply → Subgraph.Pair.reserve1
BFF.V2Pair.txCount → Subgraph.Pair.txCount
BFF.V2Pair.volume24h → Subgraph.PairDayData.dailyVolumeUSD (聚合最近1天)
BFF.V2Pair.priceHistory[].token0Price → 计算: PairDayData.reserve1 / reserve0
BFF.V2Pair.priceHistory[].token1Price → 计算: PairDayData.reserve0 / reserve1
```

**Token 查询映射**:
```yaml
BFF.Token.market.price → 计算: Token.derivedETH × Bundle.ethPrice
BFF.Token.market.totalValueLocked → 计算: Token.totalLiquidity × derivedETH × ethPrice
BFF.Token.market.volume24H → Subgraph.TokenDayData.dailyVolumeUSD (聚合最近1天)
BFF.Token.market.ohlc → Subgraph.TokenHourData (v2-tokens子图)
```

**交易查询映射**:
```yaml
BFF.PoolTransaction.timestamp → Swap/Mint/Burn.timestamp
BFF.PoolTransaction.hash → Transaction.id
BFF.PoolTransaction.token0Quantity → Swap.amount0In/Out | Mint.amount0 | Burn.amount0
BFF.PoolTransaction.token1Quantity → Swap.amount1In/Out | Mint.amount1 | Burn.amount1
BFF.PoolTransaction.usdValue → 后端计算: amount × token.derivedETH × ethPrice
BFF.PoolTransaction.type → 实体类型(SWAP | MINT | BURN)
```

---

## 10. 附录:完整 GraphQL Schema 参考

```graphql
# BFF GraphQL Schema (完整版)

type Query {
  # Pair 查询
  topPairs(chain: Chain!, first: Int!, tvlCursor: Float, tokenAddress: String): [V2Pair!]!
  v2Pair(chain: Chain!, address: String!): V2Pair
  
  # Token 查询
  token(chain: Chain!, address: String): Token
  tokens(contracts: [ContractInput!]!): [Token!]!
  
  # 交易历史
  pairTransactions(chain: Chain!, address: String!, first: Int!, cursor: Int): [PoolTransaction!]!
  tokenTransactions(chain: Chain!, address: String!, first: Int!, cursor: Int): [PoolTransaction!]!
  v2Transactions(chain: Chain!, first: Int!, cursor: Int): [PoolTransaction!]!
  
  # 价格历史
  pairPriceHistory(chain: Chain!, address: String!, duration: HistoryDuration!): [TimestampedPoolPrice!]!
  pairVolumeHistory(chain: Chain!, address: String!, duration: HistoryDuration!): [TimestampedAmount!]!
  
  # Token 图表数据
  tokenPriceOHLC(chain: Chain!, address: String!, duration: HistoryDuration!): TokenOHLC!
  tokenVolumeHistory(chain: Chain!, address: String!, duration: HistoryDuration!): [TimestampedAmount!]!
  tokenTvlHistory(chain: Chain!, address: String!, duration: HistoryDuration!): [TimestampedAmount!]!
}

enum Chain {
  ETHEREUM
  SEPOLIA
  SCROLL_SEPOLIA
}

enum HistoryDuration {
  HOUR
  DAY
  WEEK
  MONTH
  YEAR
}

type V2Pair {
  id: ID!
  protocolVersion: ProtocolVersion!
  chain: Chain!
  address: String!
  createdAtTimestamp: Int
  
  # Token 信息
  token0: Token!
  token0Supply: Float!
  token1: Token!
  token1Supply: Float!
  
  # 统计数据
  txCount: Int!
  totalLiquidity: Amount!
  totalLiquidityPercentChange24h: Amount
  
  # 交易量
  volume24h: Amount!
  volume30d: Amount
  cumulativeVolume(duration: HistoryDuration!): Amount!
  
  # 历史数据
  historicalVolume(duration: HistoryDuration!): [TimestampedAmount!]!
  priceHistory(duration: HistoryDuration!): [TimestampedPoolPrice!]!
  transactions(first: Int!, timestampCursor: Int): [PoolTransaction!]!
}

type Token {
  id: ID!
  chain: Chain!
  address: String
  decimals: Int
  name: String
  symbol: String
  standard: TokenStandard
  
  # 市场数据
  market(currency: Currency): TokenMarket
  
  # 项目信息(可选,外部数据源)
  project: TokenProject
  
  # 交易历史
  v2Transactions(first: Int!, timestampCursor: Int): [PoolTransaction!]!
}

type TokenMarket {
  id: ID!
  
  # 当前数据
  price: Amount
  totalValueLocked: Amount
  
  # 交易量
  volume24H: Amount
  volume(duration: HistoryDuration!): Amount
  
  # 价格范围
  priceHigh52W: Amount
  priceLow52W: Amount
  priceHighLow(duration: HistoryDuration!, highLow: HighLow!): Amount
  
  # 历史数据
  ohlc(duration: HistoryDuration!): [TimestampedOhlc!]!
  priceHistory(duration: HistoryDuration!): [TimestampedAmount!]!
  historicalVolume(duration: HistoryDuration!): [TimestampedAmount!]!
  historicalTvl(duration: HistoryDuration!): [TimestampedAmount!]!
}

type TokenProject {
  id: ID!
  name: String
  description: String
  homepageUrl: String
  twitterName: String
  logoUrl: String
  tokens: [Token!]!
  markets(currencies: [Currency!]!): [ProjectMarket!]!
}

type ProjectMarket {
  id: ID!
  fullyDilutedValuation: Amount
  marketCap: Amount
}

type PoolTransaction {
  id: ID!
  chain: Chain!
  protocolVersion: ProtocolVersion!
  timestamp: Int!
  hash: String!
  account: String!
  
  token0: Token!
  token0Quantity: Float!
  token1: Token!
  token1Quantity: Float!
  
  usdValue: Amount
  type: TransactionType!
}

enum TransactionType {
  SWAP
  MINT
  BURN
}

type TimestampedPoolPrice {
  id: ID!
  timestamp: Int!
  token0Price: Float!
  token1Price: Float!
}

type TimestampedAmount {
  id: ID!
  timestamp: Int!
  value: Float!
  currency: Currency
}

type TimestampedOhlc {
  id: ID!
  timestamp: Int!
  open: Amount!
  high: Amount!
  low: Amount!
  close: Amount!
}

type TokenOHLC {
  token: Token!
  duration: HistoryDuration!
  ohlc: [TimestampedOhlc!]!
}

type Amount {
  id: ID!
  value: Float!
  currency: Currency
}

enum Currency {
  USD
  ETH
}

enum ProtocolVersion {
  V2
  V3
  V4
}

enum TokenStandard {
  ERC20
  NATIVE
}

enum HighLow {
  HIGH
  LOW
}

input ContractInput {
  chain: Chain!
  address: String
}
```

---

**文档结束**

这份文档为你的 BFF 设计提供了完整的参考架构、数据映射关系和实现建议。核心要点是:**不要让前端直接查询 Subgraph,而是通过 BFF 统一封装,并通过缓存和预聚合优化性能**。
