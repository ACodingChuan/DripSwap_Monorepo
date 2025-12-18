# DripSwap 查询与同步策略

> **文档目的**：定义所有页面功能的数据来源、查询方式和同步策略  
> **模块范围**：Swap、Pool、Tokens、Explore 页面  
> **最后更新**：2025-12-18

---

## 📋 目录

1. [Swap 页面模块](#swap-页面模块)
   - 2.1.1 [最近交易列表](#功能-211最近交易列表)
   - 2.1.2 [24h 交易统计](#功能-21224h-交易统计)
2. [Pool 页面模块](#pool-页面模块)
   - 3.1.1 [Pool 列表（TVL 排序）](#功能-311pool-列表tvl-排序)
   - 3.1.2 [Pool 详情页](#功能-312pool-详情页)
   - 3.1.3 [用户流动性仓位](#功能-313用户流动性仓位)
3. [Tokens 页面模块](#tokens-页面模块)
   - 4.1.1 [Token 列表（按 TVL 排序）](#功能-411token-列表按-tvl-排序)
   - 4.1.2 [Token 详情页](#功能-412token-详情页)
4. [Explore 页面模块](#explore-页面模块)
   - 5.1.1 [全链交易流](#功能-511全链交易流)
   - 5.1.2 [全局统计数据](#功能-512全局统计数据)
5. [实时推送技术细节](#实时推送技术细节)
6. [边界情况与问题处理](#边界情况与问题处理)
7. [性能指标](#性能指标)
8. [数据来源总结](#数据来源总结)

---

## Swap 页面模块

### 模块概述

#### Swap 页面定位

**核心功能**：用户进行代币交换的主要界面

**用户场景**：
- 用户选择输入/输出代币
- 查看当前价格和汇率（前端直接从链上查询）
- 查看交易路径（前端直接计算）
- 查看最近交易记录
- 查看 24h 交易统计

**页面路由**：`/swap`

**访问频率**：极高（核心功能）

> **说明**：实时价格、Token 信息、交易路径等功能已由前端直接从链上查询实现，不需要后端支持。

---

### 功能 2.1.1：最近交易列表

**功能描述**：显示该交易对最近的 10 笔交易记录

**用户场景**：用户想要了解该交易对的最近交易情况，判断市场活跃度

**数据量级**：10 条记录

**用户感知**：⭐⭐⭐ 中  
**访问频率**：中  
**数据变化频率**：实时（每笔 Swap 后都有新交易）

---

#### 数据需求

| 数据项 | 来源表 | 字段 |
|-------|-------|------|
| 交易哈希 | swaps | transaction_id |
| 交易时间 | swaps | timestamp |
| 交易账户 | swaps | from_address |
| Token0 数量 | swaps | amount0_in, amount0_out |
| Token1 数量 | swaps | amount1_in, amount1_out |
| USD 价值 | swaps | amount_usd |

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：GraphQL

**查询逻辑**：
```
前端发起查询
    ↓
GraphQL Resolver 接收请求（chainId, pairAddress, first）
    ↓
检查 Redis 缓存（Key: recent-swaps:{chainId}:{pairAddress}）
    ├─ 命中 → 直接返回（< 5ms）
    └─ 未命中 → 查询 PostgreSQL swaps 表
              ↓
              WHERE chain_id = ? AND pair_id = ?
              ORDER BY timestamp DESC
              LIMIT 10
              ↓
              写入 Redis 缓存（TTL: 60s）
              ↓
              返回结果（< 50ms）
```

**SQL 查询示例**：
```sql
SELECT 
  id,
  transaction_id,
  timestamp,
  pair_id,
  from_address,
  amount0_in,
  amount0_out,
  amount1_in,
  amount1_out,
  amount_usd
FROM swaps
WHERE chain_id = 'sepolia' 
  AND pair_id = '0xdef...'
ORDER BY timestamp DESC
LIMIT 10;
```

**GraphQL Schema**：
```graphql
type Query {
  pairRecentSwaps(
    chainId: String!
    pairAddress: String!
    first: Int = 10
  ): [Swap!]!
}

type Swap {
  id: ID!
  transactionId: String!
  timestamp: Int!
  pairAddress: String!
  fromAddress: String!
  amount0In: String!
  amount0Out: String!
  amount1In: String!
  amount1Out: String!
  amountUsd: String!
}
```

**查询参数说明**：
- `chainId`：链 ID（如 "11155111" 表示 Sepolia）
- `pairAddress`：Pair 合约地址（用于精确查询特定交易对）
- `first`：返回记录数（默认 10 条）

---

##### 2. WS 监听实时更新流程

**监听事件**：Swap 事件

**处理逻辑**：
```
区块链触发 Swap 事件
    ↓
WS 监听器捕获事件
    ↓
解析事件数据（txHash, pairAddress, amounts, timestamp 等）
    ↓
写入 Redis（LPUSH 到 List 头部）
    ├─ Key: recent-swaps:{chainId}:{pairAddress}
    ├─ 保留最近 10 条（LTRIM 0 9）
    └─ 不写数据库（降低数据库压力）
    ↓
通知前端更新（通过 Server-Sent Events 或轮询）
    ↓
前端重新查询 GraphQL（从 Redis 获取最新数据）
    ↓
用户 B 看到最新交易（无需手动刷新）
```

**关键点**：
- ✅ 只写 Redis，不写数据库
- ✅ 使用 List 结构，LPUSH 新数据到头部
- ✅ LTRIM 保留最近 10 条
- ✅ 通知前端更新（通过 SSE 或轮询）

**实时推送技术方案**：

**方案 1：Server-Sent Events (SSE)** - 推荐
```
前端建立 SSE 连接
    ↓
GET /api/events/swap-updates?pairAddress=0xdef...
    ↓
后端保持连接，WS 监听到新交易时推送事件
    ↓
event: swap-update
data: {"pairAddress": "0xdef...", "txHash": "0xabc..."}
    ↓
前端收到事件，重新查询 GraphQL
    ↓
用户看到最新交易（无需刷新页面）
```

**方案 2：短轮询 (Polling)** - 备选
```
前端每 5 秒查询一次 GraphQL
    ↓
query { pairRecentSwaps(pairAddress: "0xdef...") }
    ↓
如果有新数据，更新 UI
```

**方案 3：WebSocket** - 可选
```
前端建立 WebSocket 连接
    ↓
ws://backend/swap-updates
    ↓
订阅特定 Pair：{"action": "subscribe", "pairAddress": "0xdef..."}
    ↓
后端推送新交易：{"type": "swap", "data": {...}}
    ↓
前端更新 UI
```

**推荐方案**：SSE（简单、单向推送、自动重连）

---

##### 3. 前端触发同步流程

**触发时机**：用户在前端执行 Swap 交易成功后（交易已发送到链上）

**前端发送信息**（REST API）：
```json
POST /api/sync/swap

{
  "txHash": "0xabc...",
  "chainId": 11155111,
  "pairAddress": "0xdef..."
}
```

**说明**：
- 前端可以获取到 `txHash`（交易哈希）、`chainId`（链 ID）、`pairAddress`（Pair 地址）
- `pairAddress` 来自 `quote.pair.address`（前端在执行 Swap 前已经计算过 quote）
- `timestamp` 等其他信息需要后端从 Subgraph 查询
- 前端在 `handleSwap` 成功后等90s后待the graph同步了逻辑后调用此接口

**后端处理逻辑**：
```
接收前端信号（txHash, chainId, pairAddress）
    ↓
查询 Subgraph（根据 txHash + pairAddress 精确查询）
    ↓
query {
  swaps(
    where: { 
      transaction: "0xabc...",
      pair: "0xdef..."
    }
  ) {
    id
    transaction { id timestamp }
    pair { id }
    from
    amount0In
    amount0Out
    amount1In
    amount1Out
    amountUSD
  }
}
    ↓
写入 PostgreSQL swaps 表（INSERT 或 UPDATE）
    ├─ 使用 transaction_id + pair_id 作为唯一标识
    └─ 避免重复插入
    ↓
不删除 Redis 缓存（保持 WS 写入的实时数据）
    ↓
返回成功响应
```

**Subgraph 查询优化**：
- 使用 `transaction` + `pair` 双重过滤，精确定位交易
- 避免全表扫描，提高查询效率
- 如果 Subgraph 返回空，等待 10 秒后重试（最多 3 次）

**同步字段说明**：
- **前端提供**：`txHash`（交易哈希）、`chainId`（链 ID）、`pairAddress`（Pair 地址）
- **后端查询**：根据 `txHash` 和 `pairAddress` 查询 Subgraph，获取完整交易信息
- **同步表**：swaps 表
- **同步字段**：所有字段（id, transaction_id, timestamp, pair_id, from_address, amount0_in, amount0_out, amount1_in, amount1_out, amount_usd 等）
- **唯一标识**：transaction_id（避免重复插入）
- **查询优化**：使用 `pairAddress` 可以精确查询 Subgraph，避免全表扫描

**缓存处理策略**：
- **不删除缓存**：前端触发同步后不删除 Redis 缓存
- **原因**：
  - WS 监听已经实时写入 Redis，用户能立即看到自己的交易
  - 数据库写入是为了持久化，不影响缓存的实时性
  - 等待缓存自然过期（60 秒）后，下次查询会从数据库重新加载
  - 避免不必要的缓存删除操作，减少 Redis 压力
- **数据一致性保障**：
  - Redis 中有 WS 监听写入的最新数据（实时，用户立即可见）
  - PostgreSQL 中有前端触发同步的完整数据（持久化，防止 Redis 重启丢失）
  - 60 秒后缓存自然过期，下次查询会从数据库重新加载，确保最终一致性
  - 即使 Redis 重启，查询会自动从数据库加载并重建缓存

---

##### 4. Redis 缓存结构

**Key 设计**：
```
recent-swaps:{chainId}:{pairAddress}
```

**数据结构**：List

**存储内容**（JSON 字符串）：
```
[
  "{\"transactionId\":\"0xabc...\",\"timestamp\":1702800000,...}",  // 最新
  "{\"transactionId\":\"0xdef...\",\"timestamp\":1702799990,...}",
  ...
  "{\"transactionId\":\"0x123...\",\"timestamp\":1702799900,...}"   // 最旧（第 10 条）
]
```

**操作说明**：
- **WS 监听写入**：`LPUSH` 新数据 + `LTRIM 0 9`（保留最近 10 条）
- **查询读取**：`LRANGE 0 9`（获取最近 10 条）
- **查询写入**：`DEL` + `LPUSH` 多条 + `EXPIRE 60`（重建缓存并设置 TTL）
- **前端触发同步**：不删除缓存（保持 WS 写入的实时数据）

**TTL 策略**：
- **WS 监听写入时**：不设置 TTL（保持原有 TTL 不变）
- **查询时写入**：60 秒 TTL

**TTL 处理逻辑说明**：

Redis List 的 TTL 是针对整个 Key 的，不是针对 List 中的单个元素。

**场景 1：WS 监听写入（LPUSH）**
```
WS 监听到新交易
    ↓
LPUSH 到 Redis List（往 List 头部插入数据）
    ↓
LTRIM 0 9（保留最近 10 条）
    ↓
不执行 EXPIRE 命令
    ↓
结果：
  - 如果 Key 已存在且有 TTL → TTL 不变（继续倒计时）
  - 如果 Key 已存在但无 TTL → 仍然无 TTL
  - 如果 Key 不存在 → 创建 Key，设置TTL 为60s
```

**场景 2：GraphQL 查询时写入**
```
查询 Redis 未命中（Key 不存在或已过期）
    ↓
查询 PostgreSQL
    ↓
写入 Redis（LPUSH 多条数据）
    ↓
设置 TTL 60 秒（EXPIRE 命令）
    ↓
结果：
  - 整个 Key 在 60 秒后过期
  - 60 秒内的所有查询都命中缓存
  - 60 秒后 Key 自动删除
```

**场景 3：前端触发同步后**
```
前端触发同步
    ↓
写入 PostgreSQL
    ↓
不删除 Redis 缓存（保持 WS 写入的实时数据）
    ↓
等待缓存自然过期（60 秒）
```

**为什么 WS 监听不设置 TTL？**
1. **保持原有 TTL**：如果 Key 已有 TTL（如查询时设置的 60 秒），LPUSH 不会影响 TTL，让它自然过期
2. **性能考虑**：WS 监听频繁写入，每次都执行 EXPIRE 会增加 Redis 操作
3. **避免永久存储**：依赖查询时设置的 TTL，确保冷门 Pair 的缓存会自动过期

**实际运行情况**：
- **活跃 Pair**：频繁查询 → 查询时设置 60 秒 TTL → WS 持续写入（不影响 TTL）→ TTL 倒计时 → 60 秒后过期 → 下次查询重新加载
- **冷门 Pair**：偶尔查询 → 查询时设置 60 秒 TTL → 可能有 WS 写入 → 60 秒后过期 → 释放内存
- **从未查询的 Pair**：只有 WS 写入 →  TTL 60s 

---

##### 5. 数据一致性保障

**问题**：WS 监听的数据只在 Redis，如果 Redis 失效或重启，数据会丢失

**解决方案**：前端触发同步写入 PostgreSQL（持久化）

**完整流程示例**：

**场景 1：用户 A 执行交易，用户 B 实时看到**
```
用户 A 执行交易
    ↓
区块链确认交易
    ↓
WS 监听捕获 Swap 事件
    ↓
写入 Redis（LPUSH）
    ↓
用户 A 立即看到自己的交易（< 5 秒）
    ↓
后端通过 SSE 推送事件给所有订阅该 Pair 的用户
    ↓
用户 B 收到 SSE 事件
    ↓
用户 B 前端重新查询 GraphQL
    ↓
GraphQL 从 Redis 读取最新数据
    ↓
用户 B 看到用户 A 的交易（< 5 秒，无需刷新页面）
    ↓
前端触发同步（发送 txHash + pairAddress）
    ↓
后端查询 Subgraph
    ↓
写入 PostgreSQL（持久化）
    ↓
不删除 Redis 缓存（保持 WS 写入的实时数据）
    ↓
60 秒后缓存自然过期
    ↓
下次查询从 PostgreSQL 重新加载
```

**场景 2：用户 C 首次访问页面**
```
用户 C 打开 Swap 页面
    ↓
前端查询 GraphQL
    ↓
Redis 命中（有缓存）
    ↓
返回最近 10 笔交易（< 5ms）
    ↓
前端建立 SSE 连接（订阅该 Pair）
    ↓
后续有新交易时，自动推送更新
```

**关键点**：
- ✅ WS 监听保证实时性（用户立即看到自己的交易，< 5 秒）
- ✅ SSE 推送保证多用户实时性（其他用户无需刷新页面即可看到新交易）
- ✅ 前端触发同步保证持久化（防止 Redis 重启丢失）
- ✅ 不删除缓存保证性能（避免不必要的缓存失效）
- ✅ 自然过期保证一致性（60 秒后自动从数据库重新加载）

---

#### 来源决策理由

- ✅ **Redis 优先**：热点数据，访问频率高，毫秒级响应（< 5ms）
- ✅ **GraphQL 查询**：Redis 未命中时查询 PostgreSQL，自动回填缓存（< 50ms）
- ✅ **WS 监听只写 Redis**：保证实时性（< 5 秒），降低数据库压力
- ✅ **前端触发写 DB**：用户交易后持久化（< 5 秒），保证数据完整性
- ✅ **不删除缓存**：前端触发同步后不删除缓存，保持 WS 写入的实时数据，等待自然过期（60 秒）
- ❌ **不需要定时同步**：前端触发已覆盖，无需额外定时任务

---

### 功能 2.1.2：24h 交易统计

**功能描述**：显示该交易对从昨天结束到现在的交易量、交易笔数、TVL 变化

**用户场景**：用户想要了解该交易对的活跃度和流动性

**数据量级**：单条记录（聚合数据）

**用户感知**：⭐⭐⭐ 中  
**访问频率**：中  
**数据变化频率**：分钟级（今天的数据在累积中）

---

#### 数据需求

| 数据项 | 来源表 | 字段 | 时间维度 |
|-------|-------|------|---------|
| 昨天交易量 | pair_day_data | daily_volume_usd | 昨天（固定值） |
| 昨天交易笔数 | pair_day_data | daily_tx_count | 昨天（固定值） |
| 昨天 为止历史累计TVL | pair_day_data | reserve_usd | 昨天结束时（固定值） |
| 今天交易量 | pair_day_data | daily_volume_usd | 今天（累积中） |
| 今天交易笔数 | pair_day_data | daily_tx_count | 今天（累积中） |
| 今天 为止历史累计TVL | pair_day_data | reserve_usd | 今天当前时刻 |

**说明**：
- 这不是严格的"过去 24 小时"，而是"从昨天结束到现在"的变化
- Uniswap 的标准设计，简单实用
- `reserve_usd` 使用历史价格快照（Subgraph 计算时的 ETH/USD 价格），不是实时价格

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：GraphQL

**查询逻辑**：
```
前端发起查询（chainId, pairAddress）
    ↓
GraphQL Resolver 接收请求
    ↓
检查 Redis 缓存（Key: pair-stats-24h:{chainId}:{pairAddress}）
    ├─ 命中 → 直接返回（< 5ms）
    └─ 未命中 → 查询 PostgreSQL
              ↓
              查询 pair_day_data 表（昨天 + 今天）
              ↓
              计算变化百分比
              ↓
              写入 Redis 缓存（TTL: 180s）
              ↓
              返回结果（< 50ms）
```

**GraphQL Schema**：
```graphql
type Query {
  pairStats24h(
    chainId: String!
    pairAddress: String!
  ): PairStats24h
}

type PairStats24h {
  # 昨天的数据（固定值）
  volumeYesterday: String!
  txCountYesterday: Int!
  tvlYesterday: String!
  
  # 今天的数据（累积中）
  volumeToday: String!
  txCountToday: Int!
  tvlToday: String!
  
  # 变化百分比
  volumeChange: String!      # 例如："+15.3%"
  txCountChange: String!     # 例如："+8.2%"
  tvlChange: String!         # 例如："+5.23%"
}
```

**查询参数说明**：
- `chainId`：链 ID（如 "11155111" 表示 Sepolia）
- `pairAddress`：Pair 合约地址

---

##### 2. 前端触发同步流程

**触发时机**：用户执行 Swap 交易成功后

**前端发送信息**（REST API）：
```json
POST /api/sync/swap

{
  "txHash": "0xabc...",
  "chainId": 11155111,
  "pairAddress": "0xdef..."
}
```

**后端处理逻辑**：
```
接收前端信号（txHash, chainId, pairAddress）
    ↓
查询 Subgraph（根据 pairAddress 查询今天的 pair_day_data）
    ↓
query {
  pairDayDatas(
    where: { 
      pair: "0xdef...",
      date: <今天的 dayId>
    }
  ) {
    id
    date
    dailyVolumeUSD
    dailyTxCount
    reserveUSD
  }
}
    ↓
写入/更新 PostgreSQL pair_day_data 表
    ├─ 使用 pair_id + date 作为唯一标识
    ├─ INSERT ON CONFLICT UPDATE
    └─ 更新今天的累积数据
    ↓
不删除 Redis 缓存（等待自然过期 180 秒）
    ↓
返回成功响应
```

**同步字段说明**：
- **前端提供**：`txHash`、`chainId`、`pairAddress`
- **后端查询**：根据 `pairAddress` + `today` 查询 Subgraph
- **同步表**：pair_day_data（今天的记录）
- **同步字段**：daily_volume_usd, daily_tx_count, reserve_usd
- **唯一标识**：pair_id + date（避免重复插入）

---

##### 3. 定时同步流程

**同步频率**：每天凌晨 1:00 UTC

**同步逻辑**：
```
定时任务触发
    ↓
查询 Subgraph（昨天的 pair_day_data）
    ↓
query {
  pairDayDatas(
    where: { date: <昨天的 dayId> }
    first: 1000
  ) {
    id
    pair { id }
    date
    dailyVolumeUSD
    dailyTxCount
    reserveUSD
  }
}
    ↓
写入 PostgreSQL pair_day_data 表
    ├─ 使用 pair_id + date 作为唯一标识
    └─ INSERT ON CONFLICT UPDATE
    ↓
不更新 Redis（由查询时更新）
```

**关键点**：
- ✅ 只同步昨天的数据（已完整，不再变化）
- ✅ 批量同步所有 Pair（不是单个 Pair）
- ✅ 只写数据库，不写 Redis

---

##### 4. Redis 缓存结构

**Key 设计**：
```
pair-stats-24h:{chainId}:{pairAddress}
```

**数据结构**：Hash

**存储内容**：
```json
{
  "volumeYesterday": "1000000.50",
  "txCountYesterday": "542",
  "tvlYesterday": "5000000.00",
  "volumeToday": "850000.30",
  "txCountToday": "487",
  "tvlToday": "5250000.00",
  "volumeChange": "+15.3%",
  "txCountChange": "+8.2%",
  "tvlChange": "+5.0%",
  "lastUpdate": "1702800000"
}
```

**操作说明**：
- **查询读取**：`HGETALL`
- **查询写入**：`HMSET` + `EXPIRE 180`
- **前端触发同步**：不删除缓存（等待自然过期）

**TTL 策略**：
- **查询时写入**：180 秒（3 分钟）
- **前端触发同步**：不设置 TTL（不删除缓存）

---

#### 来源决策理由

- ✅ **Redis 缓存**：180 秒 TTL，平衡实时性和性能
- ✅ **GraphQL 查询**：Redis 未命中时查询 PostgreSQL，自动回填缓存（< 50ms）
- ✅ **前端触发同步**：用户交易后更新今天的 pair_day_data
- ✅ **定时同步**：每天凌晨同步昨天的数据（固定值）
- ❌ **不使用 WS 监听**：数据变化慢，不需要实时推送
- ❌ **不使用 SSE 推送**：用户可以接受 3 分钟的延迟

---

## Pool 页面模块

### 模块概述

**核心功能**：用户查看和管理流动性池

**用户场景**：
- 浏览所有可用的流动性池
- 查看池子的 TVL、交易量、手续费
- 添加/移除流动性
- 查看自己的流动性仓位

**页面路由**：`/pools`

**访问频率**：高

---

### 功能 3.1.1：Pool 列表（TVL 排序）

**功能描述**：展示所有 Pool 的列表，按 TVL 从高到低排序，支持搜索和分页

**用户场景**：用户想要浏览所有可用的流动性池，找到 TVL 最高的池子进行添加流动性

**数据量级**：Top 100（默认展示前 100 个，支持加载更多）

**用户感知**：⭐⭐⭐ 中  
**访问频率**：高  
**数据变化频率**：分钟级（TVL 随着交易变化，但不需要秒级更新）

---

#### 数据需求

| 数据项 | 来源表 | 字段 | 计算方式 |
|-------|-------|------|---------|
| Pool 地址 | pairs | id, address | - |
| Token0 信息 | tokens | symbol, decimals, name | - |
| Token1 信息 | tokens | symbol, decimals, name | - |
| TVL | pairs | reserve_usd | - |
| 24h 交易量 | pair_day_data | daily_volume_usd | 昨天的数据 |
| 30天交易量 | pair_day_data | daily_volume_usd | SUM(最近30天) |
| 交易计数 | pairs | tx_count | - |
| APR（年化收益率） | - | - | (24h交易量 × 0.003 × 365) / TVL |
| Vol/TVL 比率 | - | - | 24h交易量 / TVL |

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：GraphQL

**查询逻辑**：
```
前端发起查询（chainId, first, orderBy）
    ↓
GraphQL Resolver 接收请求
    ↓
检查 Redis 缓存（Key: pool-list:{chainId}:{orderBy}）
    ├─ 命中 → 直接返回（< 5ms）
    └─ 未命中 → 查询 PostgreSQL
              ↓
              查询 pairs 表
              ↓
              ORDER BY reserve_usd DESC (或其他排序字段)
              LIMIT 100
              ↓
              计算 APR 和 Vol/TVL
              ├─ APR = (volume24h × 0.003 × 365) / reserveUsd
              └─ volOverTvl = volume24h / reserveUsd
              ↓
              写入 Redis 缓存（TTL: 60s）
              ↓
              返回结果（< 100ms）
```

**GraphQL Schema**：
```graphql
type Query {
  pools(
    chainId: String!
    first: Int = 100
    orderBy: PoolOrderBy = TVL_DESC
    cursor: String
  ): PoolConnection!
}

type PoolConnection {
  edges: [PoolEdge!]!
  pageInfo: PageInfo!
}

type PoolEdge {
  node: Pool!
  cursor: String!
}

type Pool {
  id: ID!
  address: String!
  token0: Token!
  token1: Token!
  reserveUsd: String!
  volume24h: String!
  volume30d: String!
  txCount: Int!
  apr: String!              # 年化收益率
  volOverTvl: String!       # Vol/TVL 比率
}

type Token {
  id: ID!
  address: String!
  symbol: String!
  name: String!
  decimals: Int!
}

enum PoolOrderBy {
  TVL_DESC
  VOLUME_DESC
  TX_COUNT_DESC
}
```

**查询参数说明**：
- `chainId`：链 ID
- `first`：返回记录数（默认 100）
- `orderBy`：排序方式（默认按 TVL 降序）
- `cursor`：分页游标

---

##### 2. 前端触发同步流程

**触发时机**：用户执行 Swap / 添加流动性 / 移除流动性 后

**前端发送信息**（REST API）：
```json
POST /api/sync/pool

{
  "txHash": "0xabc...",
  "chainId": 11155111,
  "pairAddress": "0xdef...",
  "type": "swap"  // swap | mint | burn
}
```

**后端处理逻辑**：
```
接收前端信号（txHash, chainId, pairAddress, type）
    ↓
查询 Subgraph（根据 pairAddress 查询 Pair 最新状态）
    ↓
query {
  pair(id: "0xdef...") {
    id
    reserve0
    reserve1
    reserveUSD
    totalSupply
    txCount
    token0 { id symbol name decimals }
    token1 { id symbol name decimals }
  }
  
  # 查询今天的 pair_day_data
  pairDayDatas(
    where: { 
      pair: "0xdef...",
      date: <今天的 dayId>
    }
    first: 1
  ) {
    id
    date
    dailyVolumeUSD
    dailyTxCount
    reserveUSD
  }
}
    ↓
写入 PostgreSQL
    ├─ 更新 pairs 表
    │   ├─ reserve0, reserve1, reserve_usd
    │   ├─ total_supply
    │   ├─ tx_count
    │   └─ INSERT ON CONFLICT UPDATE
    │
    ├─ 更新 tokens 表（token0, token1 基础信息）
    │   └─ INSERT ON CONFLICT UPDATE
    │
    └─ 更新 pair_day_data 表（今天的数据）
        ├─ daily_volume_usd
        ├─ daily_tx_count
        ├─ reserve_usd
        └─ INSERT ON CONFLICT UPDATE
    ↓
删除 Redis 缓存（精确失效）
    ├─ DEL pool-list:{chainId}:*（所有排序方式的缓存）
    ├─ DEL pool-details:{chainId}:{pairAddress}
    └─ DEL pair-stats-24h:{chainId}:{pairAddress}
    ↓
返回成功响应
```

**同步的表和字段**：

| 操作类型 | 同步表 | 同步字段 | 说明 |
|---------|-------|---------|------|
| **Swap** | pairs | reserve0, reserve1, reserve_usd, tx_count | 储备量和交易计数变化 |
| **Swap** | pair_day_data | daily_volume_usd, daily_tx_count, reserve_usd | 今天的交易量和 TVL |
| **Mint** | pairs | reserve0, reserve1, reserve_usd, total_supply, tx_count | 储备量和 LP 供应量增加 |
| **Mint** | pair_day_data | reserve_usd | 今天的 TVL 增加 |
| **Burn** | pairs | reserve0, reserve1, reserve_usd, total_supply, tx_count | 储备量和 LP 供应量减少 |
| **Burn** | pair_day_data | reserve_usd | 今天的 TVL 减少 |
| **所有操作** | tokens | symbol, name

---

### 功能 3.1.2：Pool 详情页

**功能描述**：展示单个 Pool 的详细信息，包括价格图表、交易量图表、交易历史

**用户场景**：用户想要深入了解某个池子的详细数据，决定是否添加流动性

**数据量级**：单条记录 + 图表数据（最近 7 天）

**用户感知**：⭐⭐⭐⭐ 高  
**访问频率**：中  
**数据变化频率**：分钟级

---

#### 数据需求

| 数据项 | 来源表 | 字段 | 时间维度 | 计算方式 |
|-------|-------|------|---------|---------|
| Pool 基本信息 | pairs | address, reserve0, reserve1, reserve_usd | 当前 | - |
| Token0 信息 | tokens | symbol, name, decimals | - | - |
| Token1 信息 | tokens | symbol, name, decimals | - | - |
| 24h 交易量 | pair_day_data | daily_volume_usd | 昨天 | - |
| 24h 手续费 | pair_day_data | daily_volume_usd * 0.003 | 昨天 | 交易量 × 0.3% |
| 24h TVL 变化 | pair_day_data | reserve_usd | 昨天 vs 今天 | (今天 - 昨天) / 昨天 × 100% |
| 价格历史（K线） | pair_hour_data / pair_day_data | reserve0, reserve1 | 可选范围 | token0Price = reserve1 / reserve0 |
| 交易量历史 | pair_hour_data / pair_day_data | hourly_volume_usd / daily_volume_usd | 可选范围 | - |
| TVL 历史 | pair_hour_data / pair_day_data | reserve_usd | 可选范围 | - |
| 最近交易 | swaps | transaction_id, timestamp, amounts | 最近 20 笔 | - |

**时间范围选项**：
- `HOUR`：最近 1 小时（使用 pair_hour_data）
- `DAY`：最近 1 天（使用 pair_hour_data）
- `WEEK`：最近 7 天（使用 pair_day_data）
- `MONTH`：最近 30 天（使用 pair_day_data）
- `YEAR`：最近 365 天（使用 pair_day_data）

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：GraphQL

**查询逻辑**：
```
前端发起查询（chainId, pairAddress）
    ↓
GraphQL Resolver 接收请求
    ↓
查询 PostgreSQL
    ├─ pairs 表（基本信息）
    ├─ pair_day_data 表（24h 数据）
    ├─ pair_hour_data 表（图表数据）
    └─ swaps 表（最近交易）
    ↓
返回结果（< 200ms）
```

**GraphQL Schema**：
```graphql
type Query {
  poolDetails(
    chainId: String!
    pairAddress: String!
  ): PoolDetails
}

type PoolDetails {
  # 基本信息
  id: ID!
  address: String!
  token0: Token!
  token1: Token!
  reserve0: String!
  reserve1: String!
  reserveUsd: String!
  
  # 24h 统计
  volume24h: String!
  fees24h: String!
  tvlChange24h: String!
  
  # 图表数据（支持多种时间范围）
  priceHistory(duration: HistoryDuration!): [PricePoint!]!
  volumeHistory(duration: HistoryDuration!): [VolumePoint!]!
  tvlHistory(duration: HistoryDuration!): [TvlPoint!]!
  
  # 最近交易
  recentSwaps: [Swap!]!
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
  token0Price: String!
  token1Price: String!
}

type VolumePoint {
  timestamp: Int!
  volume: String!
}

type TvlPoint {
  timestamp: Int!
  tvl: String!
}
```

---

##### 2. 定时同步流程

**同步频率**：
- pairs 表：每 5 分钟
- pair_day_data 表：每天凌晨（昨天的数据）
- pair_hour_data 表：每小时

**同步逻辑**：
```
定时任务触发
    ↓
查询 Subgraph
    ↓
query {
  # 每小时同步
  pairHourDatas(
    where: { 
      pair: "0xdef...",
      hourStartUnix_gte: <7天前>
    }
  ) {
    id
    hourStartUnix
    reserve0
    reserve1
    hourlyVolumeUSD
  }
}
    ↓
写入 PostgreSQL pair_hour_data 表
    ├─ 使用 id 作为唯一标识
    └─ INSERT ON CONFLICT UPDATE
```

---

#### 来源决策理由

- ✅ **GraphQL 查询**：一次请求获取所有数据，减少网络往返
- ✅ **定时同步**：分层同步（5分钟/1小时/1天），平衡实时性和性能
- ✅ **图表数据**：使用 pair_hour_data，提供足够的粒度
- ❌ **不使用 Redis**：数据量大，查询复杂，不适合缓存
- ❌ **不使用 WS 监听**：图表数据不需要实时更新

---

### 功能 3.1.3：用户流动性仓位

**功能描述**：展示用户在所有 Pool 中的流动性仓位

**用户场景**：用户想要查看自己提供了多少流动性，当前价值多少

**数据量级**：用户的所有仓位（通常 < 10 个）

**用户感知**：⭐⭐⭐⭐ 高  
**访问频率**：中  
**数据变化频率**：分钟级

---

#### 数据需求

| 数据项 | 来源 | 说明 |
|-------|------|------|
| LP Token 余额 | 链上查询 | 前端直接调用 ERC20.balanceOf |
| Pool 总供应量 | pairs | total_supply |
| Pool 储备量 | pairs | reserve0, reserve1 |
| Token 价格 | tokens | price_usd |

**说明**：
- 前端直接从链上查询用户的 LP Token 余额
- 后端提供 Pool 信息（总供应量、储备量）
- 前端计算用户的份额和价值

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：混合（链上 + GraphQL）

**查询逻辑**：
```
前端获取用户地址
    ↓
查询链上所有 LP Token 余额
    ↓
for each LP Token with balance > 0:
    ↓
    查询 GraphQL 获取 Pool 信息
    ↓
    计算用户份额 = balance / totalSupply
    ↓
    计算用户资产 = 份额 * (reserve0 + reserve1 * price)
    ↓
返回用户仓位列表
```

**GraphQL Schema**：
```graphql
type Query {
  userPositions(
    chainId: String!
    userAddress: String!
  ): [UserPosition!]!
}

type UserPosition {
  pool: Pool!
  lpTokenBalance: String!
  lpTokenTotalSupply: String!
  share: String!              # 用户份额百分比
  token0Amount: String!       # 用户拥有的 token0 数量
  token1Amount: String!       # 用户拥有的 token1 数量
  valueUsd: String!           # 总价值（USD）
}
```

---

##### 2. 定时同步流程

**同步频率**：每 5 分钟（随 pairs 表同步）

**同步逻辑**：
```
定时任务触发
    ↓
查询 Subgraph（pairs 表）
    ↓
更新 total_supply, reserve0, reserve1
    ↓
前端查询时自动获取最新数据
```

---

#### 来源决策理由

- ✅ **链上查询**：LP Token 余额必须从链上查询（最准确）
- ✅ **GraphQL 查询**：Pool 信息从后端查询（性能更好）
- ✅ **前端计算**：用户份额和价值由前端计算（灵活）
- ❌ **不存储用户数据**：不在后端存储用户仓位（隐私考虑）

---

## Tokens 页面模块

### 模块概述

**核心功能**：展示所有 Token 的列表和详情

**用户场景**：
- 浏览所有可用的 Token
- 查看 Token 的价格、TVL、交易量
- 查看 Token 的价格图表和交易历史
- 搜索和筛选 Token

**页面路由**：`/tokens`

**访问频率**：高

---

### 功能 4.1.1：Token 列表（按 TVL 排序）

**功能描述**：展示所有 Token 的列表，按 TVL 从高到低排序，支持搜索和分页

**用户场景**：用户想要浏览所有可用的 Token，找到 TVL 最高或交易量最大的 Token

**数据量级**：Top 100（默认展示前 100 个，支持加载更多）

**用户感知**：⭐⭐⭐ 中  
**访问频率**：高  
**数据变化频率**：分钟级

---

#### 数据需求

| 数据项 | 来源表 | 字段 |
|-------|-------|------|
| Token 地址 | tokens | id, address |
| Token 信息 | tokens | symbol, name, decimals |
| 当前价格 | tokens | price_usd |
| TVL | tokens | tvl_usd |
| 24h 交易量 | token_day_data | daily_volume_usd |
| 24h 价格变化 | token_day_data | price_change_24h |

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：GraphQL

**查询逻辑**：
```
前端发起查询（chainId, first, orderBy）
    ↓
GraphQL Resolver 接收请求
    ↓
查询 PostgreSQL tokens 表
    ↓
ORDER BY tvl_usd DESC
LIMIT 100
    ↓
返回结果（< 100ms）
```

**GraphQL Schema**：
```graphql
type Query {
  tokens(
    chainId: String!
    first: Int = 100
    orderBy: TokenOrderBy = TVL_DESC
    cursor: String
  ): TokenConnection!
}

type TokenConnection {
  edges: [TokenEdge!]!
  pageInfo: PageInfo!
}

type TokenEdge {
  node: Token!
  cursor: String!
}

type Token {
  id: ID!
  address: String!
  symbol: String!
  name: String!
  decimals: Int!
  priceUsd: String!
  tvlUsd: String!
  volume24h: String!
  priceChange24h: String!
}

enum TokenOrderBy {
  TVL_DESC
  VOLUME_DESC
  PRICE_DESC
}
```

---

##### 2. 定时同步流程

**同步频率**：每 5 分钟

**同步逻辑**：
```
定时任务触发
    ↓
查询 Subgraph（tokens 表）
    ↓
query {
  tokens(
    first: 1000
    orderBy: totalLiquidity
    orderDirection: desc
  ) {
    id
    symbol
    name
    decimals
    derivedETH
    totalLiquidity
    tradeVolume
    tradeVolumeUSD
    txCount
  }
}
    ↓
写入 PostgreSQL tokens 表
    ├─ 使用 id 作为唯一标识
    └─ INSERT ON CONFLICT UPDATE
```

---

#### 来源决策理由

- ✅ **不使用 Redis**：数据量大（100+ 条），排序复杂，不适合 Redis
- ✅ **GraphQL 查询**：支持灵活的排序、分页、字段选择
- ✅ **定时同步**：5 分钟同步一次，平衡实时性和性能
- ❌ **不使用 WS 监听**：Token 数据变化慢，不需要实时推送

---

### 功能 4.1.2：Token 详情页

**功能描述**：展示单个 Token 的详细信息，包括价格图表、交易量图表、交易历史

**用户场景**：用户想要深入了解某个 Token 的详细数据，决定是否交易

**数据量级**：单条记录 + 图表数据（最近 7 天）

**用户感知**：⭐⭐⭐⭐ 高  
**访问频率**：中  
**数据变化频率**：分钟级

---

#### 数据需求

| 数据项 | 来源表 | 字段 | 时间维度 | 计算方式 |
|-------|-------|------|---------|---------|
| Token 基本信息 | tokens | address, symbol, name, decimals | - | - |
| 当前价格 | tokens | price_usd | 当前 | derivedETH × ethPrice |
| TVL | tokens | tvl_usd | 当前 | totalLiquidity × derivedETH × ethPrice |
| 24h 交易量 | token_day_data | daily_volume_usd | 昨天 | - |
| 24h 价格变化 | token_day_data | price_change_24h | 昨天 | (今天价格 - 昨天价格) / 昨天价格 × 100% |
| 52周最高价 | token_day_data | price_usd | 最近 365 天 | MAX(price_usd) |
| 52周最低价 | token_day_data | price_usd | 最近 365 天 | MIN(price_usd) |
| 价格历史（K线/OHLC） | token_hour_data / token_day_data | open, high, low, close | 可选范围 | - |
| 交易量历史 | token_hour_data / token_day_data | hourly_volume_usd / daily_volume_usd | 可选范围 | - |
| TVL 历史 | token_hour_data / token_day_data | tvl_usd | 可选范围 | - |
| 该 Token 的所有 Pool | pairs | - | - | WHERE token0 = ? OR token1 = ? |
| 最近交易 | swaps | transaction_id, timestamp, amounts | 最近 20 笔 | WHERE token0 = ? OR token1 = ? |

**时间范围选项**：
- `HOUR`：最近 1 小时（使用 token_hour_data）
- `DAY`：最近 1 天（使用 token_hour_data）
- `WEEK`：最近 7 天（使用 token_day_data）
- `MONTH`：最近 30 天（使用 token_day_data）
- `YEAR`：最近 365 天（使用 token_day_data）

**说明**：
- ⚠️ **K线数据（OHLC）**：Uniswap V2 标准 Subgraph 的 `TokenHourData` **没有** OHLC 字段
- ✅ **解决方案**：需要使用 **v2-tokens Subgraph**（专门提供 Token K线数据）或后端计算
- ✅ **后端计算方案**：从 `token_hour_data` 的 `price_usd` 字段计算每小时的 OHLC

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：GraphQL

**查询逻辑**：
```
前端发起查询（chainId, tokenAddress）
    ↓
GraphQL Resolver 接收请求
    ↓
查询 PostgreSQL
    ├─ tokens 表（基本信息）
    ├─ token_day_data 表（24h 数据）
    ├─ token_hour_data 表（图表数据）
    └─ swaps 表（最近交易，WHERE token0 = ? OR token1 = ?）
    ↓
返回结果（< 200ms）
```

**GraphQL Schema**：
```graphql
type Query {
  tokenDetails(
    chainId: String!
    tokenAddress: String!
  ): TokenDetails
}

type TokenDetails {
  # 基本信息
  id: ID!
  address: String!
  symbol: String!
  name: String!
  decimals: Int!
  priceUsd: String!
  tvlUsd: String!
  
  # 24h 统计
  volume24h: String!
  priceChange24h: String!
  
  # 52周价格范围
  priceHigh52w: String!
  priceLow52w: String!
  
  # 图表数据（支持多种时间范围）
  priceHistory(duration: HistoryDuration!): [PricePoint!]!
  priceOHLC(duration: HistoryDuration!): [OHLCPoint!]!    # K线图数据
  volumeHistory(duration: HistoryDuration!): [VolumePoint!]!
  tvlHistory(duration: HistoryDuration!): [TvlPoint!]!
  
  # 该 Token 的所有 Pool
  pools: [Pool!]!
  
  # 最近交易
  recentSwaps: [Swap!]!
}

type OHLCPoint {
  timestamp: Int!
  open: String!
  high: String!
  low: String!
  close: String!
}

type TvlPoint {
  timestamp: Int!
  tvl: String!
}
```

---

##### 2. 定时同步流程

**同步频率**：
- tokens 表：每 5 分钟
- token_day_data 表：每天凌晨（昨天的数据）
- token_hour_data 表：每小时

**同步逻辑**：
```
定时任务触发
    ↓
查询 Subgraph
    ↓
query {
  # 每小时同步
  tokenHourDatas(
    where: { 
      token: "0xabc...",
      hourStartUnix_gte: <7天前>
    }
  ) {
    id
    hourStartUnix
    priceUSD
    totalLiquidityUSD
    hourlyVolumeUSD
  }
}
    ↓
写入 PostgreSQL token_hour_data 表
    ├─ 使用 id 作为唯一标识
    └─ INSERT ON CONFLICT UPDATE
```

---

#### 来源决策理由

- ✅ **GraphQL 查询**：一次请求获取所有数据，减少网络往返
- ✅ **定时同步**：分层同步（5分钟/1小时/1天），平衡实时性和性能
- ✅ **图表数据**：使用 token_hour_data，提供足够的粒度
- ❌ **不使用 Redis**：数据量大，查询复杂，不适合缓存
- ❌ **不使用 WS 监听**：图表数据不需要实时更新

---

### 功能 4.1.3：Token 价格 K 线图

**功能描述**：展示 Token 的价格 K 线图（OHLC：开盘价、最高价、最低价、收盘价）

**用户场景**：用户想要查看 Token 的价格走势，进行技术分析

**数据量级**：根据时间范围不同（最多 365 个数据点）

**用户感知**：⭐⭐⭐⭐ 高  
**访问频率**：中  
**数据变化频率**：小时级/天级

---

#### 数据需求

| 数据项 | 来源表 | 字段 | 说明 |
|-------|-------|------|------|
| OHLC 数据 | token_hour_data / token_day_data | open, high, low, close | ⚠️ V2 标准 Subgraph 没有此字段 |
| 时间戳 | token_hour_data / token_day_data | hour_start_unix / date | - |

**重要说明**：

⚠️ **Uniswap V2 标准 Subgraph 的限制**：
- `TokenHourData` 和 `TokenDayData` **没有** `open`, `high`, `low`, `close` 字段
- 只有 `priceUSD` 字段（某个时刻的价格快照）

✅ **解决方案 1：使用 v2-tokens Subgraph**
- Uniswap 维护了一个专门的 **v2-tokens Subgraph**
- 该 Subgraph 的 `TokenHourData` **有** OHLC 字段
- 端点：需要单独部署或使用 Uniswap 的端点

✅ **解决方案 2：后端计算 OHLC**
- 从标准 V2 Subgraph 查询 `TokenHourData` 的 `priceUSD`
- 后端按小时/天聚合计算 OHLC：
  - `open`：该时间段第一个价格
  - `high`：该时间段最高价格
  - `low`：该时间段最低价格
  - `close`：该时间段最后一个价格

**推荐方案**：方案 2（后端计算），原因：
- 不依赖额外的 Subgraph
- 可以完全控制数据质量
- 可以缓存计算结果

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：GraphQL

**查询逻辑**：
```
前端发起查询（chainId, tokenAddress, duration）
    ↓
GraphQL Resolver 接收请求
    ↓
查询 PostgreSQL token_ohlc 表
    ↓
WHERE token_address = ? AND duration = ?
ORDER BY timestamp ASC
    ↓
返回结果（< 100ms）
```

**GraphQL Schema**：
```graphql
type Query {
  tokenPriceOHLC(
    chainId: String!
    tokenAddress: String!
    duration: HistoryDuration!
  ): [OHLCPoint!]!
}

type OHLCPoint {
  timestamp: Int!
  open: String!
  high: String!
  low: String!
  close: String!
  volume: String!    # 可选：该时间段的交易量
}
```

---

##### 2. 定时同步流程（后端计算 OHLC）

**同步频率**：每小时

**同步逻辑**：
```
定时任务触发
    ↓
查询 Subgraph（token_hour_data 或 token_day_data）
    ↓
query {
  tokenHourDatas(
    where: { 
      token: "0xabc...",
      periodStartUnix_gte: <上次同步时间>
    }
    orderBy: periodStartUnix
    orderDirection: asc
  ) {
    id
    periodStartUnix
    priceUSD
    hourlyVolumeUSD
  }
}
    ↓
按小时聚合计算 OHLC
    ├─ 如果该小时只有一个数据点：open = high = low = close
    ├─ 如果该小时有多个数据点：
    │   ├─ open = 第一个 priceUSD
    │   ├─ high = MAX(priceUSD)
    │   ├─ low = MIN(priceUSD)
    │   └─ close = 最后一个 priceUSD
    └─ volume = SUM(hourlyVolumeUSD)
    ↓
写入 PostgreSQL token_ohlc 表
    ├─ 使用 token_address + timestamp 作为唯一标识
    └─ INSERT ON CONFLICT UPDATE
```

**数据库表结构**：
```sql
CREATE TABLE token_ohlc (
  id BIGSERIAL PRIMARY KEY,
  chain_id VARCHAR(50) NOT NULL,
  token_address VARCHAR(42) NOT NULL,
  timestamp BIGINT NOT NULL,
  duration VARCHAR(20) NOT NULL,  -- HOUR | DAY
  open NUMERIC(78, 18),
  high NUMERIC(78, 18),
  low NUMERIC(78, 18),
  close NUMERIC(78, 18),
  volume NUMERIC(78, 18),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(chain_id, token_address, timestamp, duration)
);

CREATE INDEX idx_token_ohlc_lookup 
ON token_ohlc(chain_id, token_address, duration, timestamp);
```

---

#### 来源决策理由

- ✅ **PostgreSQL 存储**：OHLC 数据适合关系型数据库
- ✅ **定时同步**：每小时同步一次，平衡实时性和性能
- ✅ **后端计算**：不依赖额外的 Subgraph，完全可控
- ❌ **不使用 Redis**：K线数据量大，不适合缓存
- ❌ **不使用 WS 监听**：K线数据不需要实时更新

---

### 功能 4.1.4：Token 的所有 Pool 列表

**功能描述**：展示包含该 Token 的所有流动性池

**用户场景**：用户想要查看该 Token 在哪些池子中有流动性，选择最优的交易池

**数据量级**：通常 < 20 个 Pool

**用户感知**：⭐⭐⭐ 中  
**访问频率**：中  
**数据变化频率**：分钟级

---

#### 数据需求

| 数据项 | 来源表 | 字段 | 查询条件 |
|-------|-------|------|---------|
| Pool 列表 | pairs | - | WHERE token0 = ? OR token1 = ? |
| 配对 Token | tokens | symbol, name | - |
| TVL | pairs | reserve_usd | - |
| 24h 交易量 | pair_day_data | daily_volume_usd | - |
| APR | - | - | 计算 |

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：GraphQL

**查询逻辑**：
```
前端发起查询（chainId, tokenAddress）
    ↓
GraphQL Resolver 接收请求
    ↓
查询 PostgreSQL pairs 表
    ↓
WHERE (token0_address = ? OR token1_address = ?)
  AND reserve_usd > 0
ORDER BY reserve_usd DESC
    ↓
返回结果（< 50ms）
```

**GraphQL Schema**：
```graphql
type Query {
  tokenPools(
    chainId: String!
    tokenAddress: String!
  ): [Pool!]!
}

type Pool {
  id: ID!
  address: String!
  token0: Token!
  token1: Token!
  reserveUsd: String!
  volume24h: String!
  apr: String!
}
```

---

##### 2. 定时同步流程

**同步频率**：每 5 分钟（随 pairs 表同步）

**同步逻辑**：
```
定时任务触发
    ↓
查询 Subgraph（pairs 表）
    ↓
更新 pairs 表
    ↓
前端查询时自动获取最新数据
```

---

#### 来源决策理由

- ✅ **不使用 Redis**：数据量小，直接查询数据库即可
- ✅ **GraphQL 查询**：支持灵活的过滤和排序
- ✅ **定时同步**：5 分钟同步一次，保持数据新鲜
- ❌ **不使用 WS 监听**：数据变化慢，不需要实时推送

---

## Explore 页面模块

### 模块概述

**核心功能**：展示全局的交易流和统计数据

**用户场景**：
- 查看全链最新交易
- 查看热门 Pool 和 Token
- 查看全局统计数据（总 TVL、24h 交易量等）

**页面路由**：`/explore`

**访问频率**：中

---

### 功能 5.1.1：全链交易流

**功能描述**：展示全链最新的交易记录（Swap、Add、Remove）

**用户场景**：用户想要查看整个平台的实时交易活动

**数据量级**：最近 50 笔交易

**用户感知**：⭐⭐⭐ 中  
**访问频率**：中  
**数据变化频率**：实时

---

#### 数据需求

| 数据项 | 来源表 | 字段 |
|-------|-------|------|
| 交易哈希 | swaps / mints / burns | transaction_id |
| 交易时间 | swaps / mints / burns | timestamp |
| 交易类型 | - | 根据表名判断（swap/mint/burn） |
| 交易账户 | swaps / mints / burns | from_address |
| Token0 信息 | tokens | symbol, name |
| Token1 信息 | tokens | symbol, name |
| Token0 数量 | swaps / mints / burns | amount0 |
| Token1 数量 | swaps / mints / burns | amount1 |
| USD 价值 | swaps / mints / burns | amount_usd |

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：GraphQL

**查询逻辑**：
```
前端发起查询（chainId, first）
    ↓
GraphQL Resolver 接收请求
    ↓
查询 PostgreSQL
    ├─ swaps 表（最近 50 笔）
    ├─ mints 表（最近 50 笔）
    └─ burns 表（最近 50 笔）
    ↓
合并三个表的结果
    ↓
按 timestamp 降序排序
    ↓
取前 50 条
    ↓
返回结果（< 100ms）
```

**GraphQL Schema**：
```graphql
type Query {
  recentTransactions(
    chainId: String!
    first: Int = 50
  ): [Transaction!]!
}

type Transaction {
  id: ID!
  transactionId: String!
  timestamp: Int!
  type: TransactionType!
  account: String!
  token0: Token!
  token1: Token!
  amount0: String!
  amount1: String!
  amountUsd: String!
}

enum TransactionType {
  SWAP
  MINT
  BURN
}
```

---

##### 2. WS 监听实时更新流程

**监听事件**：Swap、Mint、Burn 事件

**处理逻辑**：
```
区块链触发事件
    ↓
WS 监听器捕获事件
    ↓
解析事件数据
    ↓
写入 Redis（LPUSH 到 List 头部）
    ├─ Key: recent-transactions:{chainId}
    ├─ 保留最近 50 条（LTRIM 0 49）
    └─ 不写数据库
    ↓
通过 SSE 推送事件给前端
    ↓
前端重新查询 GraphQL
    ↓
用户看到最新交易
```

---

##### 3. 前端触发同步流程

**触发时机**：用户执行交易成功后

**前端发送信息**（REST API）：
```json
POST /api/sync/transaction

{
  "txHash": "0xabc...",
  "chainId": 11155111,
  "type": "swap"  // swap | mint | burn
}
```

**后端处理逻辑**：
```
接收前端信号
    ↓
查询 Subgraph（根据 txHash 查询）
    ↓
写入对应的表（swaps / mints / burns）
    ↓
不删除 Redis 缓存
    ↓
返回成功响应
```

---

#### 来源决策理由

- ✅ **Redis 优先**：热点数据，访问频率高，毫秒级响应
- ✅ **GraphQL 查询**：Redis 未命中时查询 PostgreSQL
- ✅ **WS 监听只写 Redis**：保证实时性，降低数据库压力
- ✅ **前端触发写 DB**：用户交易后持久化，保证数据完整性
- ✅ **SSE 推送**：让所有用户实时看到新交易
- ❌ **不需要定时同步**：前端触发已覆盖

---

### 功能 5.1.2：全局统计数据

**功能描述**：展示全局的统计数据（总 TVL、24h 交易量、24h 手续费等）

**用户场景**：用户想要了解整个平台的规模和活跃度

**数据量级**：单条记录（聚合数据）

**用户感知**：⭐⭐ 低  
**访问频率**：中  
**数据变化频率**：分钟级

---

#### 数据需求

| 数据项 | 来源表 | 字段 | 计算方式 |
|-------|-------|------|---------|
| 总 TVL | pairs | reserve_usd | SUM(reserve_usd) |
| 24h 交易量 | pair_day_data | daily_volume_usd | SUM(daily_volume_usd) WHERE date = 昨天 |
| 24h 手续费 | pair_day_data | daily_volume_usd * 0.003 | 计算 |
| 总交易笔数 | pairs | tx_count | SUM(tx_count) |

---

#### 完整流程说明

##### 1. 前端查询流程

**查询方式**：GraphQL

**查询逻辑**：
```
前端发起查询（chainId）
    ↓
GraphQL Resolver 接收请求
    ↓
检查 Redis 缓存（Key: global-stats:{chainId}）
    ├─ 命中 → 直接返回（< 5ms）
    └─ 未命中 → 查询 PostgreSQL
              ↓
              聚合查询（SUM）
              ↓
              写入 Redis 缓存（TTL: 300s）
              ↓
              返回结果（< 100ms）
```

**GraphQL Schema**：
```graphql
type Query {
  globalStats(chainId: String!): GlobalStats
}

type GlobalStats {
  totalTvl: String!
  volume24h: String!
  fees24h: String!
  totalTxCount: Int!
}
```

---

##### 2. 定时同步流程

**同步频率**：每 5 分钟

**同步逻辑**：
```
定时任务触发
    ↓
查询 PostgreSQL（聚合查询）
    ↓
更新 Redis 缓存
    ↓
设置 TTL 300 秒
```

---

#### 来源决策理由

- ✅ **Redis 缓存**：300 秒 TTL，减少数据库聚合查询压力
- ✅ **GraphQL 查询**：Redis 未命中时查询 PostgreSQL
- ✅ **定时同步**：5 分钟同步一次，保持缓存热度
- ❌ **不使用 WS 监听**：数据变化慢，不需要实时推送

---

## 实时推送技术细节

### 为什么需要实时推送？

**问题**：用户 B 在查看 Swap 页面时，用户 A 执行了一笔交易，WS 监听已经写入 Redis，但用户 B 的页面不会自动更新。

**解决方案**：后端通过 SSE（Server-Sent Events）推送事件，通知前端有新交易，前端重新查询 GraphQL。

---

### SSE 实现方案（推荐）

#### 后端实现逻辑

**SSE 端点**：
```
GET /api/events/swap-updates?pairAddress=0xdef...
```

**连接管理**：
```
用户打开 Swap 页面
    ↓
前端建立 SSE 连接（订阅特定 Pair）
    ↓
后端维护连接池：Map<pairAddress, Set<SSE连接>>
    ↓
WS 监听到新交易
    ↓
查找该 Pair 的所有 SSE 连接
    ↓
推送事件：event: swap-update, data: {"pairAddress": "0xdef...", "txHash": "0xabc..."}
    ↓
前端收到事件，重新查询 GraphQL
    ↓
用户看到最新交易
```

**连接生命周期**：
- **建立连接**：前端打开页面时建立
- **保持连接**：后端定期发送心跳（每 30 秒）
- **断开连接**：用户关闭页面或网络断开
- **自动重连**：SSE 自带重连机制

---

#### 前端实现逻辑

**建立连接**：
```typescript
// 伪代码
const eventSource = new EventSource(
  `/api/events/swap-updates?pairAddress=${pairAddress}`
);

eventSource.addEventListener('swap-update', (event) => {
  const data = JSON.parse(event.data);
  // 重新查询 GraphQL
  refetchSwaps();
});

eventSource.addEventListener('error', () => {
  // 自动重连（SSE 内置）
});
```

**清理连接**：
```typescript
// 用户离开页面时
useEffect(() => {
  return () => {
    eventSource.close();
  };
}, []);
```

---

### 备选方案：短轮询

**适用场景**：SSE 不可用时（如某些代理服务器不支持）

**实现逻辑**：
```
前端每 5 秒查询一次 GraphQL
    ↓
query { pairRecentSwaps(pairAddress: "0xdef...") }
    ↓
比较返回数据与当前数据
    ↓
如果有新交易，更新 UI
```

**优缺点**：
- ✅ 简单，无需维护连接
- ❌ 延迟较高（5 秒）
- ❌ 增加服务器负载（频繁查询）

---

### 性能优化

**连接池管理**：
- 限制单个 Pair 的最大连接数（如 1000）
- 超过限制时，拒绝新连接或关闭最旧的连接

**事件过滤**：
- 只推送用户订阅的 Pair 的事件
- 避免推送无关事件

**批量推送**：
- 如果短时间内有多笔交易（如 1 秒内 10 笔）
- 合并为一个事件推送，减少网络开销

---

## 边界情况与问题处理


---

### 问题 1：Redis 重启后数据丢失

**场景**：
- Redis 重启或故障
- 所有缓存数据丢失
- 用户查询时需要从数据库重新加载

**影响**：
- 短时间内大量查询打到数据库
- 数据库压力激增（缓存雪崩）

**解决方案**：
1. **Redis 持久化**
   - 启用 RDB 或 AOF 持久化
   - 重启后自动恢复数据

2. **缓存预热**
   - Redis 重启后，后台任务预加载热点数据
   - 查询最活跃的 Top 100 Pair，写入缓存

3. **限流保护**
   - 数据库查询加限流（如每秒最多 100 次）
   - 超过限流返回降级数据或错误

**推荐方案**：方案 1（持久化）+ 方案 2（预热）

---

### 问题 3：WS 监听延迟或丢失事件

**场景**：
- WS 连接断开或网络延迟
- 某些 Swap 事件未被监听到
- Redis 中缺少部分交易记录

**影响**：
- 用户看到的交易列表不完整
- 数据不一致

**解决方案**：
1. **前端触发同步兜底**
   - 用户交易后，前端触发同步
   - 从 Subgraph 查询完整数据，写入数据库
   - 60 秒后缓存过期，查询会从数据库加载完整数据

2. **WS 重连机制**
   - WS 断开后自动重连
   - 重连后补扫缺失的区块

3. **定期对账**
   - 定时任务（如每 10 分钟）对比 Redis 和数据库
   - 发现不一致时，从数据库重新加载

**推荐方案**：方案 1（前端触发兜底）+ 方案 2（WS 重连）

---

### 问题 4：前端触发同步失败

**场景**：
- 前端发送同步请求失败（网络错误）
- 后端处理同步失败（Subgraph 故障）
- 数据未写入数据库

**影响**：
- 用户的交易未持久化
- Redis 缓存过期后，数据丢失

**解决方案**：
1. **前端重试机制**
   - 同步失败后，前端自动重试（最多 3 次）
   - 使用指数退避（1s, 2s, 4s）

2. **后端幂等性**
   - 使用 `transaction_id` 作为唯一标识
   - 重复同步不会插入重复数据

3. **降级到定时同步**
   - 如果前端触发同步持续失败
   - 依赖定时任务（如每小时）从 Subgraph 全量同步

**推荐方案**：方案 1（前端重试）+ 方案 2（幂等性）

---

### 问题 5：Subgraph 数据延迟

**场景**：
- Subgraph 索引延迟（如 10 秒）
- 前端触发同步时，Subgraph 还未索引到该交易
- 查询 Subgraph 返回空数据

**影响**：
- 数据未写入数据库
- 用户的交易未持久化

**解决方案**：
1. **延迟重试**
   - 查询 Subgraph 返回空时，等待 10 秒后重试
   - 最多重试 3 次

2. **依赖 WS 数据**
   - WS 监听的数据已在 Redis
   - 用户能立即看到自己的交易
   - 等待 Subgraph 索引完成后再持久化

3. **直接查询链上**
   - 如果 Subgraph 持续失败
   - 后端直接查询链上数据（RPC）
   - 解析交易日志，写入数据库

**推荐方案**：方案 1（延迟重试）+ 方案 2（依赖 WS）

---

### 问题 6：高并发查询压力

**场景**：
- 热门 Pair 被大量用户同时查询
- Redis 缓存过期瞬间，大量请求打到数据库
- 数据库压力激增（缓存击穿）

**影响**：
- 数据库响应变慢
- 用户查询超时

**解决方案**：
1. **分布式锁**
   - 缓存过期时，只允许一个请求查询数据库
   - 其他请求等待，直到缓存重建完成

2. **提前刷新**
   - 缓存 TTL 剩余 10 秒时，异步刷新缓存
   - 用户始终命中缓存

3. **多级缓存**
   - 增加本地缓存（如 Caffeine）
   - Redis 未命中时，先查本地缓存

**推荐方案**：方案 1（分布式锁）+ 方案 2（提前刷新）

---

## 性能指标

### 查询性能目标

| 场景 | 目标响应时间 | 数据来源 |
|-----|-------------|---------|
| Redis 命中 | < 5ms | Redis |
| Redis 未命中 | < 50ms | PostgreSQL + Redis 回填 |
| 数据库查询 | < 100ms | PostgreSQL |

### 实时性目标

| 场景 | 目标延迟 | 说明 |
|-----|---------|------|
| WS 监听写入 | < 5 秒 | 区块确认 + WS 延迟 |
| 前端触发同步 | < 5 秒 | Subgraph 查询 + 数据库写入 |
| 缓存自然过期 | 60 秒 | TTL 过期后自动重新加载 |

### 数据一致性目标

| 场景 | 一致性级别 | 说明 |
|-----|-----------|------|
| 用户自己的交易 | 强一致性 | WS 监听 + 前端触发同步，< 5 秒可见 |
| 其他用户的交易 | 最终一致性 | 60 秒后缓存过期，从数据库加载 |
| 历史数据 | 最终一致性 | 定时同步 + 查询时回填 |

---

## 数据来源总结

### 所有功能的数据来源汇总

| 功能模块 | 功能点 | 主要数据来源 | 缓存策略 | 同步方式 | 实时推送 |
|---------|-------|------------|---------|---------|---------|
| **Swap 页面** | 最近交易列表 | Redis → PostgreSQL | Redis 60s | WS 监听 + 前端触发 | SSE 推送 |
| **Swap 页面** | 24h 交易统计 | PostgreSQL | Redis 180s | 前端触发 + 定时（每天） | 不推送 |
| **Pool 页面** | Pool 列表（含APR/Vol/TVL） | PostgreSQL | 不缓存 | 定时（每 5 分钟） | 不推送 |
| **Pool 页面** | Pool 详情页 | PostgreSQL | 不缓存 | 定时（5分钟/1小时/1天） | 不推送 |
| **Pool 页面** | Pool 价格/交易量/TVL 图表 | PostgreSQL | 不缓存 | 定时（1小时/1天） | 不推送 |
| **Pool 页面** | 用户流动性仓位 | 链上 + PostgreSQL | 不缓存 | 定时（每 5 分钟） | 不推送 |
| **Tokens 页面** | Token 列表 | PostgreSQL | 不缓存 | 定时（每 5 分钟） | 不推送 |
| **Tokens 页面** | Token 详情页（含52周高低） | PostgreSQL | 不缓存 | 定时（5分钟/1小时/1天） | 不推送 |
| **Tokens 页面** | Token K线图（OHLC） | PostgreSQL | 不缓存 | 定时（每小时，后端计算） | 不推送 |
| **Tokens 页面** | Token 的所有 Pool | PostgreSQL | 不缓存 | 定时（每 5 分钟） | 不推送 |
| **Explore 页面** | 全链交易流 | Redis → PostgreSQL | Redis 60s | WS 监听 + 前端触发 | SSE 推送 |
| **Explore 页面** | 全局统计数据 | PostgreSQL | Redis 300s | 定时（每 5 分钟） | 不推送 |

---

### 数据库表同步策略汇总

| 表名 | 同步频率 | 同步方式 | 数据来源 | 触发条件 | 说明 |
|-----|---------|---------|---------|---------|------|
| **swaps** | 实时 + 按需 | WS 监听（Redis）+ 前端触发（DB） | Subgraph | 用户执行 Swap | - |
| **mints** | 实时 + 按需 | WS 监听（Redis）+ 前端触发（DB） | Subgraph | 用户添加流动性 | - |
| **burns** | 实时 + 按需 | WS 监听（Redis）+ 前端触发（DB） | Subgraph | 用户移除流动性 | - |
| **pairs** | 每 5 分钟 | 定时同步 | Subgraph | 定时任务 | 包含 APR、Vol/TVL 计算 |
| **tokens** | 每 5 分钟 | 定时同步 | Subgraph | 定时任务 | 包含 52周高低价计算 |
| **pair_day_data** | 每天凌晨 + 按需 | 定时同步（昨天）+ 前端触发（今天） | Subgraph | 定时任务 + 用户交易 | 用于计算 30天交易量 |
| **pair_hour_data** | 每小时 | 定时同步 | Subgraph | 定时任务 | 用于价格/交易量图表 |
| **token_day_data** | 每天凌晨 | 定时同步 | Subgraph | 定时任务 | 用于计算 52周高低价 |
| **token_hour_data** | 每小时 | 定时同步 | Subgraph | 定时任务 | 用于价格/交易量图表 |
| **token_ohlc** | 每小时 | 定时同步 + 后端计算 | Subgraph（原始数据） | 定时任务 | K线图数据，后端计算 OHLC |

---

### Redis 缓存键设计汇总

| 缓存键 | 数据结构 | TTL | 用途 | 写入时机 | 失效时机 |
|-------|---------|-----|------|---------|---------|
| `recent-swaps:{chainId}:{pairAddress}` | List | 60s | 最近交易列表 | WS 监听 + 查询回填 | 自然过期 |
| `pair-stats-24h:{chainId}:{pairAddress}` | Hash | 180s | 24h 交易统计 | 查询回填 | 自然过期 |
| `recent-transactions:{chainId}` | List | 60s | 全链交易流 | WS 监听 + 查询回填 | 自然过期 |
| `global-stats:{chainId}` | Hash | 300s | 全局统计数据 | 查询回填 + 定时刷新 | 自然过期 |

---

### 前端触发同步接口汇总

| 接口 | 触发时机 | 请求参数 | 同步表 | 响应时间 |
|-----|---------|---------|-------|---------|
| `POST /api/sync/swap` | 用户执行 Swap 后 | txHash, chainId, pairAddress | swaps, pair_day_data | < 5s |
| `POST /api/sync/mint` | 用户添加流动性后 | txHash, chainId, pairAddress | mints, pair_day_data | < 5s |
| `POST /api/sync/burn` | 用户移除流动性后 | txHash, chainId, pairAddress | burns, pair_day_data | < 5s |
| `POST /api/sync/transaction` | 用户执行任意交易后 | txHash, chainId, type | swaps/mints/burns | < 5s |

---

### WS 监听事件汇总

| 事件 | 监听合约 | 写入目标 | 推送方式 | 用途 |
|-----|---------|---------|---------|------|
| **Swap** | UniswapV2Pair | Redis (recent-swaps, recent-transactions) | SSE | 实时交易列表 |
| **Mint** | UniswapV2Pair | Redis (recent-transactions) | SSE | 实时交易列表 |
| **Burn** | UniswapV2Pair | Redis (recent-transactions) | SSE | 实时交易列表 |
| **Sync** | UniswapV2Pair | 不写入（仅用于缓存失效） | 不推送 | 触发缓存失效 |

---

### 定时同步任务汇总

| 任务名称 | 执行频率 | 同步表 | 数据来源 | 说明 |
|---------|---------|-------|---------|------|
| **Pairs 同步** | 每 5 分钟 | pairs, tokens | Subgraph | 同步所有活跃 Pair，计算 APR、Vol/TVL |
| **Pair Hour Data 同步** | 每小时 | pair_hour_data | Subgraph | 同步最近 7 天的小时数据，用于图表 |
| **Pair Day Data 同步** | 每天凌晨 | pair_day_data | Subgraph | 同步昨天的日数据，计算 30天交易量 |
| **Token Hour Data 同步** | 每小时 | token_hour_data | Subgraph | 同步最近 7 天的小时数据，用于图表 |
| **Token Day Data 同步** | 每天凌晨 | token_day_data | Subgraph | 同步昨天的日数据，计算 52周高低价 |
| **Token OHLC 计算** | 每小时 | token_ohlc | Subgraph（原始数据） | 从 token_hour_data 计算 OHLC K线数据 |
| **Global Stats 刷新** | 每 5 分钟 | - | PostgreSQL 聚合 | 刷新全局统计缓存 |

---

### 核心准则总结

#### 查询准则

1. **查询优先级**：Redis（缓存）→ GraphQL（主要查询）→ REST（特殊场景）
2. **缓存策略**：
   - 热点数据（最近交易）：Redis 60s
   - 统计数据（24h 统计）：Redis 180s
   - 全局数据（全局统计）：Redis 300s
   - 列表数据（Pool/Token 列表）：不缓存（直接查 DB）
3. **查询优化**：
   - 使用 GraphQL 关联查询，避免 N+1 问题
   - 使用分页游标，支持无限滚动
   - 使用索引优化排序和过滤

#### 同步准则

1. **WS 监听策略**：只写 Redis，不写数据库（保证实时性，降低数据库压力）
2. **数据库写入**：由前端触发同步或定时任务完成（保证数据完整性）
3. **缓存更新**：
   - 定时任务或前端触发同步只写数据库，不写 Redis
   - GraphQL 查询时从 Redis 找不到，查询数据库后重新更新 Redis
   - 前端触发同步后不删除 Redis 缓存（保持 WS 写入的实时数据，等待自然过期）
4. **同步频率**：
   - 实时数据（交易记录）：WS 监听 + 前端触发
   - 分钟级数据（Pair/Token 信息）：每 5 分钟
   - 小时级数据（图表数据）：每小时
   - 天级数据（历史统计）：每天凌晨
5. **计算字段策略**：
   - APR（年化收益率）：后端计算，公式 `(24h交易量 × 0.003 × 365) / TVL`
   - Vol/TVL 比率：后端计算，公式 `24h交易量 / TVL`
   - 30天交易量：后端聚合，从 `pair_day_data` 表 SUM 最近 30 天
   - 52周高低价：后端聚合，从 `token_day_data` 表 MAX/MIN 最近 365 天
   - Token 价格：后端计算，公式 `derivedETH × ethPrice`
   - Pool 价格：后端计算，公式 `token0Price = reserve1 / reserve0`
6. **K线数据（OHLC）策略**：
   - ⚠️ V2 标准 Subgraph 没有 OHLC 字段
   - ✅ 后端从 `token_hour_data` 的 `priceUSD` 计算 OHLC
   - ✅ 每小时聚合一次，存储到 `token_ohlc` 表
   - ✅ 支持多种时间范围（HOUR, DAY, WEEK, MONTH, YEAR）

#### 实时推送准则

1. **需要推送的场景**：
   - 最近交易列表（Swap 页面）：用户需要实时看到其他人的交易
   - 全链交易流（Explore 页面）：用户需要实时看到全局交易活动
2. **不需要推送的场景**：
   - 统计数据（24h 交易量、TVL 等）：用户可以接受分钟级延迟
   - 列表数据（Pool/Token 列表）：用户可以接受分钟级延迟
   - 图表数据（价格/交易量历史）：用户可以接受分钟级延迟
3. **推送方式**：SSE（Server-Sent Events）推荐，WebSocket 可选，短轮询备选

---

**文档完成**

> **状态**：已完成 Swap、Pool、Tokens、Explore 四个模块的查询与同步策略设计  
> **下一步**：根据此文档进行后端开发和前端对接
