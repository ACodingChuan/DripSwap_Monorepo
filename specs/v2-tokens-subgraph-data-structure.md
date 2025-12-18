# Uniswap V2-Tokens 子图详细数据结构文档

> **文档目的**: 详细描述 v2-tokens-subgraph 项目的数据结构，重点说明与 v2 子图的差异  
> **参考项目**: https://github.com/graphprotocol/uniswap-v2-subgraph  
> **最后更新**: 2025-12-16  
> **关联文档**: [v2-subgraph-data-structure.md](./v2-subgraph-data-structure.md)

---

## 目录
1. [v2-tokens 与 v2 的核心差异](#核心差异)
2. [新增实体: TokenMinuteData](#tokenminutedata)
3. [Token 实体的增强字段](#token-增强字段)
4. [事件处理流程对比](#事件处理流程对比)
5. [存档机制详解](#存档机制详解)
6. [使用场景与选择建议](#使用场景与选择建议)

---

## 核心差异

### 设计目标对比

| 维度 | v2 子图 | v2-tokens 子图 |
|-----|--------|---------------|
| **主要目标** | 完整的 DEX 数据索引 | 高频价格数据与 K 线图 |
| **事件覆盖** | 全部事件 (PairCreated, Transfer, Mint, Burn, Sync, Swap) | 仅价格相关事件 (PairCreated, Sync, Swap) |
| **时间粒度** | 小时/天 | **分钟/小时/天** |
| **数据保留** | 长期保留 | 短期高频 (28小时分钟数据) |
| **存储成本** | 中等 | 较低 (自动存档删除) |
| **查询场景** | 流动性操作、交易历史、统计分析 | **实时价格、K 线图、高频交易分析** |

### 实体对比表

| 实体类型 | v2 子图 | v2-tokens 子图 | 差异说明 |
|---------|--------|----------------|---------|
| **核心实体** |
| UniswapFactory | ✅ | ✅ | 完全相同 |
| Token | ✅ | ✅ | v2-tokens 新增 8 个存档字段 |
| Pair | ✅ | ✅ | 完全相同 |
| Bundle | ✅ | ✅ | 完全相同 |
| PairTokenLookup | ✅ | ✅ | 完全相同 |
| User | ✅ | ✅ | 完全相同 |
| **事件实体** |
| Transaction | ✅ | ❌ | v2-tokens 不索引 |
| Mint | ✅ | ❌ | v2-tokens 不索引 |
| Burn | ✅ | ❌ | v2-tokens 不索引 |
| Swap | ✅ | ❌ | v2-tokens 不索引 |
| **聚合实体** |
| UniswapDayData | ✅ | ✅ | 完全相同 |
| PairDayData | ✅ | ✅ | 完全相同 |
| PairHourData | ✅ | ✅ | 完全相同 |
| TokenDayData | ✅ | ✅ | 完全相同 |
| TokenHourData | ✅ | ✅ | 完全相同 |
| **v2-tokens 独有** |
| TokenMinuteData | ❌ | ✅ | **新增分钟级数据** |

---

## TokenMinuteData

### 用途

记录每个代币每分钟的统计数据，包含 K 线图所需的 OHLC (开高低收) 价格数据，用于超短期统计和实时价格展示。

### Schema 定义

```graphql
type TokenMinuteData @entity {
  # token address concatendated with date
  id: ID!
  # unix timestamp for start of minute
  periodStartUnix: Int!
  # pointer to token
  token: Token!
  # volume in token units
  volume: BigDecimal!
  # volume in derived USD
  volumeUSD: BigDecimal!
  # volume in USD even on pools with less reliable USD values
  untrackedVolumeUSD: BigDecimal!
  # liquidity across all pools in token units
  totalValueLocked: BigDecimal!
  # liquidity across all pools in derived USD
  totalValueLockedUSD: BigDecimal!
  # price at end of period in USD
  priceUSD: BigDecimal!
  # fees in USD
  feesUSD: BigDecimal!
  # opening price USD
  open: BigDecimal!
  # high price USD
  high: BigDecimal!
  # low price USD
  low: BigDecimal!
  # close price USD
  close: BigDecimal!
}
```

### 字段详解

| 字段名 | 类型 | 业务含义 | ID 生成 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|--------|---------|---------|----------|
| **id** | ID! | `{tokenAddress}-{minuteIndex}` | `token.id + '-' + minuteIndex` | - | - | - |
| **periodStartUnix** | Int! | 分钟开始时间戳 | - | `minuteIndex * 60` | 不变 | - |
| **token** | Token! | 关联的代币 | - | `token.id` | 不变 | - |
| **volume** | BigDecimal! | 累计交易量(代币) | - | `ZERO_BD` | Sync/Swap | `token.tradeVolume` (快照) |
| **volumeUSD** | BigDecimal! | 累计交易量(USD,tracked) | - | `ZERO_BD` | Sync/Swap | `token.tradeVolumeUSD` (快照) |
| **untrackedVolumeUSD** | BigDecimal! | 累计交易量(USD,untracked) | - | `ZERO_BD` | Sync/Swap | `token.untrackedVolumeUSD` (快照) |
| **totalValueLocked** | BigDecimal! | 分钟末锁仓量(代币) | - | `ZERO_BD` | Sync/Swap | 当前为 `ZERO_BD` (未实现) |
| **totalValueLockedUSD** | BigDecimal! | 分钟末锁仓量(USD) | - | `ZERO_BD` | Sync/Swap | 当前为 `ZERO_BD` (未实现) |
| **priceUSD** | BigDecimal! | 分钟末价格(USD) | - | 当前价格 | Sync/Swap | `derivedETH * ethPrice` |
| **feesUSD** | BigDecimal! | 累计手续费(USD) | - | `ZERO_BD` | Swap | `volumeUSD * 0.003` (未在代码中实现) |
| **open** | BigDecimal! | 开盘价(USD) | - | 当前价格 | 分钟开始 | 第一次更新时设置 |
| **high** | BigDecimal! | 最高价(USD) | - | 当前价格 | 每次 Sync/Swap | `max(high, priceUSD)` |
| **low** | BigDecimal! | 最低价(USD) | - | 当前价格 | 每次 Sync/Swap | `min(low, priceUSD)` |
| **close** | BigDecimal! | 收盘价(USD) | - | 当前价格 | 每次 Sync/Swap | `priceUSD` (最后一次价格) |

### 初始化逻辑

```typescript
// src/v2-tokens/mappings/minuteUpdates.ts - updateTokenMinuteData()
export function updateTokenMinuteData(token: Token, event: ethereum.Event): TokenMinuteData {
  const bundle = Bundle.load('1')!;
  const timestamp = event.block.timestamp.toI32();
  const minuteIndex = timestamp / 60;  // 分钟索引 (Unix 时间戳除以 60)
  const minuteStartUnix = minuteIndex * 60;  // 分钟开始时间戳
  const tokenMinuteID = token.id.concat('-').concat(minuteIndex.toString());
  
  let tokenMinuteData = TokenMinuteData.load(tokenMinuteID);
  const tokenPrice = token.derivedETH.times(bundle.ethPrice);
  let isNew = false;
  
  if (!tokenMinuteData) {
    tokenMinuteData = new TokenMinuteData(tokenMinuteID);
    tokenMinuteData.periodStartUnix = minuteStartUnix;
    tokenMinuteData.token = token.id;
    tokenMinuteData.volume = ZERO_BD;
    tokenMinuteData.volumeUSD = ZERO_BD;
    tokenMinuteData.untrackedVolumeUSD = ZERO_BD;
    tokenMinuteData.feesUSD = ZERO_BD;
    
    // 初始化 OHLC 为当前价格
    tokenMinuteData.open = tokenPrice;
    tokenMinuteData.high = tokenPrice;
    tokenMinuteData.low = tokenPrice;
    tokenMinuteData.close = tokenPrice;
    
    // 将分钟索引添加到 Token 的 minuteArray (用于存档)
    const tokenMinuteArray = token.minuteArray;
    tokenMinuteArray.push(minuteIndex);
    token.minuteArray = tokenMinuteArray;
    token.save();
    
    isNew = true;
  }
  
  // 更新 OHLC
  if (tokenPrice.gt(tokenMinuteData.high)) {
    tokenMinuteData.high = tokenPrice;
  }
  if (tokenPrice.lt(tokenMinuteData.low)) {
    tokenMinuteData.low = tokenPrice;
  }
  tokenMinuteData.close = tokenPrice;  // 收盘价始终是最新价格
  tokenMinuteData.priceUSD = tokenPrice;
  
  // 注意: totalValueLocked 当前未实现,直接设为 0
  tokenMinuteData.totalValueLocked = ZERO_BD;
  tokenMinuteData.totalValueLockedUSD = ZERO_BD;
  
  tokenMinuteData.save();
  
  // 首次初始化存档相关字段
  if (token.lastMinuteArchived.equals(ZERO_BI) && token.lastMinuteRecorded.equals(ZERO_BI)) {
    token.lastMinuteRecorded = BigInt.fromI32(minuteIndex);
    token.lastMinuteArchived = BigInt.fromI32(minuteIndex - 1);
  }
  
  // 存档逻辑: 删除 1680 分钟 (28 小时) 之前的数据
  if (isNew) {
    const lastMinuteArchived = token.lastMinuteArchived.toI32();
    const stop = minuteIndex - 1680;  // 28 小时前的分钟索引
    if (stop > lastMinuteArchived) {
      archiveMinuteData(token, stop);
    }
    
    token.lastMinuteRecorded = BigInt.fromI32(minuteIndex);
    token.save();
  }
  
  return tokenMinuteData as TokenMinuteData;
}
```

### 存档逻辑 (自动删除旧数据)

```typescript
// src/v2-tokens/mappings/minuteUpdates.ts - archiveMinuteData()
function archiveMinuteData(token: Token, end: i32): void {
  const length = token.minuteArray.length;
  const array = token.minuteArray;
  const modArray = token.minuteArray;
  let last = token.lastMinuteArchived.toI32();
  
  // 遍历 minuteArray,删除 <= end 的所有分钟数据
  for (let i = 0; i < length; i++) {
    if (array[i] > end) {
      break;  // 遇到第一个大于 end 的索引,停止删除
    }
    
    const tokenMinuteID = token.id.concat('-').concat(array[i].toString());
    store.remove('TokenMinuteData', tokenMinuteID);  // 删除实体
    modArray.shift();  // 从数组中移除
    last = array[i];
    
    // 安全限制: 一次最多删除 1000 条记录
    if (BigInt.fromI32(i + 1).equals(BigInt.fromI32(1000))) {
      break;
    }
  }
  
  // 更新 Token 的 minuteArray
  if (modArray) {
    token.minuteArray = modArray;
  } else {
    token.minuteArray = [];
  }
  
  token.lastMinuteArchived = BigInt.fromI32(last - 1);
  token.save();
}
```

**存档机制关键点**:
- ⏰ **保留时长**: 1680 分钟 = 28 小时
- 🔄 **触发时机**: 每次创建新的 TokenMinuteData 时检查
- 🗑️ **删除方式**: 通过 `store.remove()` 物理删除旧实体
- 📊 **辅助数组**: `token.minuteArray` 记录所有分钟索引,便于批量删除
- 🛡️ **安全限制**: 单次最多删除 1000 条记录,防止 gas 消耗过高

### 更新逻辑

#### Sync 事件中的更新

```typescript
// src/v2-tokens/mappings/core.ts - handleSync()
export function handleSync(event: Sync): void {
  // ... 更新 Pair, Token, Bundle 逻辑 (与 v2 相同)
  
  // 更新 TokenHourData (与 v2 相同)
  let token0HourData = updateTokenHourData(token0 as Token, event);
  let token1HourData = updateTokenHourData(token1 as Token, event);
  
  // v2-tokens 新增: 更新 TokenMinuteData
  let token0MinuteData = updateTokenMinuteData(token0 as Token, event);
  let token1MinuteData = updateTokenMinuteData(token1 as Token, event);
  
  // 注意: 这里使用的是 token 的累计交易量快照,而非增量
  token0HourData.volume = token0.tradeVolume;
  token0HourData.volumeUSD = token0.tradeVolumeUSD;
  token0HourData.untrackedVolumeUSD = token0.untrackedVolumeUSD;
  
  token0MinuteData.volume = token0.tradeVolume;
  token0MinuteData.volumeUSD = token0.tradeVolumeUSD;
  token0MinuteData.untrackedVolumeUSD = token0.untrackedVolumeUSD;
  
  // token1 同样的逻辑...
  
  token0HourData.save();
  token1HourData.save();
  token0MinuteData.save();
  token1MinuteData.save();
}
```

#### Swap 事件中的更新

```typescript
// src/v2-tokens/mappings/core.ts - handleSwap()
export function handleSwap(event: Swap): void {
  // ... 更新交易量逻辑 (与 v2 相同)
  
  // 更新 Day/Hour 聚合数据 (与 v2 相同)
  let pairDayData = updatePairDayData(pair, event);
  let pairHourData = updatePairHourData(pair, event);
  let uniswapDayData = updateUniswapDayData(event);
  let token0DayData = updateTokenDayData(token0 as Token, event);
  let token1DayData = updateTokenDayData(token1 as Token, event);
  
  // 更新 Hour 聚合数据 (与 v2 相同)
  let token0HourData = updateTokenHourData(token0 as Token, event);
  let token1HourData = updateTokenHourData(token1 as Token, event);
  
  // v2-tokens 新增: 更新 Minute 聚合数据
  let token0MinuteData = updateTokenMinuteData(token0 as Token, event);
  let token1MinuteData = updateTokenMinuteData(token1 as Token, event);
  
  // 注意: 这里同样使用累计交易量快照
  token0HourData.volume = token0.tradeVolume;
  token0HourData.volumeUSD = token0.tradeVolumeUSD;
  token0HourData.untrackedVolumeUSD = token0.untrackedVolumeUSD;
  
  token0MinuteData.volume = token0.tradeVolume;
  token0MinuteData.volumeUSD = token0.tradeVolumeUSD;
  token0MinuteData.untrackedVolumeUSD = token0.untrackedVolumeUSD;
  
  // token1 同样的逻辑...
  
  token0HourData.save();
  token1HourData.save();
  token0MinuteData.save();
  token1MinuteData.save();
}
```

**注意事项**:
- ⚠️ **volume 是快照值**: 与 TokenHourData 不同,这里的 volume/volumeUSD 不是分钟内的增量,而是 Token 累计交易量的快照
- ⚠️ **totalValueLocked 未实现**: 当前代码中直接设为 `ZERO_BD`,未来可能需要实现
- ⚠️ **feesUSD 未实现**: Schema 中有定义,但代码中未计算

---

## Token 增强字段

### 新增字段说明

v2-tokens 的 Token 实体在 v2 基础上新增了 **8 个字段**,用于支持分钟/小时数据的存档机制:

```graphql
type Token @entity {
  # ... v2 的所有字段 (id, symbol, name, decimals, totalSupply, etc.)
  
  # ========== v2-tokens 新增字段 ==========
  
  # 关联分钟级数据
  tokenMinuteData: [TokenMinuteData!]! @derivedFrom(field: "token")
  
  # 存档控制字段
  lastMinuteArchived: BigInt!   # 上次存档删除到的分钟索引
  lastHourArchived: BigInt!     # 上次存档删除到的小时索引
  
  # 索引数组 (用于批量删除)
  minuteArray: [Int!]!          # 记录所有存在的分钟索引
  hourArray: [Int!]!            # 记录所有存在的小时索引
  
  # 记录控制字段
  lastMinuteRecorded: BigInt!   # 最后记录的分钟索引
  lastHourRecorded: BigInt!     # 最后记录的小时索引
}
```

### 字段详解

| 字段名 | 类型 | 业务含义 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|---------|---------|----------|
| **tokenMinuteData** | [TokenMinuteData!]! | 反向关联所有分钟数据 | - | GraphQL 自动 | @derivedFrom |
| **lastMinuteArchived** | BigInt! | 上次存档删除到的分钟索引 | `minuteIndex - 1` | 存档时 | 记录最后删除的索引 |
| **lastHourArchived** | BigInt! | 上次存档删除到的小时索引 | `hourIndex - 1` | 存档时 | 记录最后删除的索引 |
| **minuteArray** | [Int!]! | 所有分钟索引的数组 | `[]` | 创建 MinuteData 时 | `push(minuteIndex)` |
| **hourArray** | [Int!]! | 所有小时索引的数组 | `[]` | 创建 HourData 时 | `push(hourIndex)` |
| **lastMinuteRecorded** | BigInt! | 最后记录的分钟索引 | `minuteIndex` | 创建 MinuteData 时 | `minuteIndex` |
| **lastHourRecorded** | BigInt! | 最后记录的小时索引 | `hourIndex` | 创建 HourData 时 | `hourIndex` |

### 初始化逻辑

```typescript
// src/v2-tokens/mappings/factory.ts - handleNewPair()
export function handleNewPair(event: PairCreated): void {
  // ... 创建 Token 逻辑 (与 v2 相同)
  
  if (token0 === null) {
    token0 = new Token(event.params.token0.toHexString());
    token0.symbol = fetchTokenSymbol(event.params.token0);
    token0.name = fetchTokenName(event.params.token0);
    // ... v2 的所有字段初始化
    
    // v2-tokens 新增字段初始化
    token0.lastMinuteArchived = ZERO_BI;
    token0.lastHourArchived = ZERO_BI;
    token0.minuteArray = [];
    token0.hourArray = [];
    token0.lastMinuteRecorded = ZERO_BI;
    token0.lastHourRecorded = ZERO_BI;
  }
  
  token0.save();
}
```

### 使用场景

#### 1. minuteArray 的作用

```typescript
// 创建新的 TokenMinuteData 时
const tokenMinuteArray = token.minuteArray;
tokenMinuteArray.push(minuteIndex);  // 记录这个分钟索引
token.minuteArray = tokenMinuteArray;

// 存档删除时
for (let i = 0; i < token.minuteArray.length; i++) {
  if (token.minuteArray[i] > end) break;
  
  // 构造 ID 并删除
  const tokenMinuteID = token.id.concat('-').concat(token.minuteArray[i].toString());
  store.remove('TokenMinuteData', tokenMinuteID);
}
```

**优势**:
- ✅ **高效删除**: 不需要遍历所有可能的分钟索引,只需遍历实际存在的
- ✅ **节省 gas**: 减少不必要的 load 操作

#### 2. lastMinuteArchived 的作用

```typescript
// 避免重复删除
const stop = minuteIndex - 1680;  // 28 小时前
if (stop > token.lastMinuteArchived.toI32()) {
  archiveMinuteData(token, stop);  // 只删除新的过期数据
}
```

**优势**:
- ✅ **避免重复**: 不会重复删除已经删除过的数据
- ✅ **性能优化**: 减少不必要的存档操作

---

## 事件处理流程对比

### v2 子图事件处理流程

```
┌──────────────────────────────────────────────────────────┐
│  1. PairCreated 事件 (Factory 合约)                       │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │  初始化 Factory      │
         │  创建 Bundle('1')    │
         │  创建 Token 实体     │
         │  创建 Pair 实体      │
         │  创建 PairTokenLookup│
         │  启动 Pair 模板监听  │
         └──────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  2. Transfer 事件 (Pair 合约)                             │
└───────────────────┬──────────────────────────────────────┘
                    │
              ┌─────┴─────┐
              │  判断      │
              └─┬────────┬─┘
    from=0x0?  │        │ to=0x0?
    ┌──────────┘        └──────────┐
    ▼                              ▼
┌─────────┐                    ┌─────────┐
│ Mint    │                    │ Burn    │
│ 第一阶段 │                    │ 第一阶段 │
└────┬────┘                    └────┬────┘
     │ 创建 Mint 实体               │ 创建 Burn 实体
     │ mint.liquidity               │ burn.liquidity
     │ 更新 pair.totalSupply        │ 更新 pair.totalSupply
     │                              │
     ▼                              ▼
  等待 Mint 事件                 等待 Burn 事件

┌──────────────────────────────────────────────────────────┐
│  3. Mint 事件 (Pair 合约) - 第二阶段                       │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │  补全 Mint 实体      │
         │  - sender, amount0/1 │
         │  - amountUSD         │
         │  协议费用检测        │
         │  更新统计 (txCount)  │
         │  创建 User 实体      │
         └──────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  4. Burn 事件 (Pair 合约) - 第二阶段                       │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │  补全 Burn 实体      │
         │  - sender, amount0/1 │
         │  - to, amountUSD     │
         │  更新统计 (txCount)  │
         │  创建 User 实体      │
         └──────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  5. Sync 事件 (Pair 合约) - 价格更新                       │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │  1. 减去旧流动性     │
         │  2. 更新 Pair 储备   │
         │  3. 更新 ETH 价格    │
         │  4. 更新 Token derivedETH │
         │  5. 计算 Pair USD 价值    │
         │  6. 加回新流动性     │
         │  7. 更新聚合数据     │
         │     - UniswapDayData │
         │     - PairDayData    │
         │     - PairHourData   │
         │     - TokenDayData   │
         └──────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  6. Swap 事件 (Pair 合约)                                 │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │  1. 创建 Swap 实体   │
         │  2. 计算交易量       │
         │  3. 更新 Pair        │
         │  4. 更新 Token       │
         │  5. 更新 Factory     │
         │  6. 更新聚合数据     │
         │     - UniswapDayData │
         │     - PairDayData    │
         │     - PairHourData   │
         │     - TokenDayData   │
         │     - TokenHourData  │
         └──────────────────────┘
```

### v2-tokens 子图事件处理流程

```
┌──────────────────────────────────────────────────────────┐
│  1. PairCreated 事件 (Factory 合约)                       │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │  初始化 Factory      │
         │  创建 Bundle('1')    │
         │  创建 Token 实体     │  ← 包含新增的 8 个存档字段
         │  创建 Pair 实体      │
         │  创建 PairTokenLookup│
         │  启动 Pair 模板监听  │
         └──────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  ❌ 不处理 Transfer/Mint/Burn 事件                        │
└──────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  2. Sync 事件 (Pair 合约) - 价格更新                       │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │  1. 减去旧流动性     │
         │  2. 更新 Pair 储备   │
         │  3. 更新 ETH 价格    │
         │  4. 更新 Token derivedETH │
         │  5. 计算 Pair USD 价值    │
         │  6. 加回新流动性     │
         │  7. 更新聚合数据     │
         │     - TokenHourData  │  ← v2 相同
         │     - TokenMinuteData│  ← v2-tokens 新增
         └──────────────────────┘

┌──────────────────────────────────────────────────────────┐
│  3. Swap 事件 (Pair 合约)                                 │
└───────────────────┬──────────────────────────────────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │  1. ❌ 不创建 Swap 实体│
         │  2. 计算交易量       │
         │  3. 更新 Pair        │
         │  4. 更新 Token       │
         │  5. 更新 Factory     │
         │  6. 更新聚合数据     │
         │     - UniswapDayData │
         │     - PairDayData    │
         │     - PairHourData   │
         │     - TokenDayData   │
         │     - TokenHourData  │  ← v2 相同
         │     - TokenMinuteData│  ← v2-tokens 新增
         │  7. 触发存档逻辑     │  ← v2-tokens 新增
         │     (删除 28 小时前数据)│
         └──────────────────────┘
```

### 关键差异总结

| 事件 | v2 子图 | v2-tokens 子图 | 差异说明 |
|-----|--------|----------------|---------|
| **PairCreated** | ✅ 完整处理 | ✅ 完整处理 | Token 新增 8 个存档字段 |
| **Transfer** | ✅ 识别 Mint/Burn | ❌ 不处理 | v2-tokens 不索引流动性操作 |
| **Mint** | ✅ 创建 Mint 实体 | ❌ 不处理 | v2-tokens 不索引流动性操作 |
| **Burn** | ✅ 创建 Burn 实体 | ❌ 不处理 | v2-tokens 不索引流动性操作 |
| **Sync** | ✅ 更新价格+流动性 | ✅ 更新价格+流动性 | v2-tokens 额外更新 TokenMinuteData |
| **Swap** | ✅ 创建 Swap 实体 + 更新聚合数据 | ✅ 更新聚合数据(不创建 Swap) | v2-tokens 额外更新 TokenMinuteData + 触发存档 |

---

## 存档机制详解

### 设计目标

- 🎯 **控制存储成本**: 分钟级数据量极大,全量保留会导致存储爆炸
- 🎯 **满足查询需求**: 保留 28 小时足够支持日内交易和短期 K 线图
- 🎯 **自动化管理**: 无需手动清理,随着新数据创建自动删除旧数据

### 存档参数对比

| 实体 | 数据粒度 | 保留时长 | 保留条数(单 Token) | 存档触发 | 删除机制 |
|------|---------|---------|-------------------|---------|---------|
| **TokenMinuteData** | 1 分钟 | 1680 分钟 (28 小时) | ~1680 条 | 创建新分钟数据时 | 物理删除 |
| **TokenHourData** | 1 小时 | 768 小时 (32 天) | ~768 条 | 创建新小时数据时 | 物理删除 |
| **TokenDayData** | 1 天 | 永久保留 | 无限 | - | 不删除 |

### 存档实现原理

#### 1. 索引数组机制

```typescript
// Token 实体维护两个数组
type Token @entity {
  minuteArray: [Int!]!  // 示例: [100, 101, 102, 105, 106] (缺少 103, 104)
  hourArray: [Int!]!    // 示例: [1, 2, 3, 5] (缺少 4)
}
```

**为什么需要数组?**
- ❌ **不能**遍历所有可能的索引 (0 到当前索引),因为大部分索引对应的实体不存在 (没有交易发生)
- ✅ **只遍历实际存在的索引**,通过数组记录

**数组操作**:
```typescript
// 创建新数据时 push
const tokenMinuteArray = token.minuteArray;
tokenMinuteArray.push(minuteIndex);  // 添加新索引
token.minuteArray = tokenMinuteArray;

// 删除旧数据时 shift
modArray.shift();  // 移除第一个元素 (最旧的索引)
```

#### 2. 存档边界控制

```typescript
// Token 实体维护存档边界
type Token @entity {
  lastMinuteArchived: BigInt!   // 上次存档删除到的分钟索引
  lastMinuteRecorded: BigInt!   // 最后记录的分钟索引
}
```

**边界判断**:
```typescript
const minuteIndex = timestamp / 60;  // 当前分钟索引
const stop = minuteIndex - 1680;    // 28 小时前的索引

// 只有当 stop 大于上次存档位置时,才执行存档
if (stop > token.lastMinuteArchived.toI32()) {
  archiveMinuteData(token, stop);
}
```

**示例**:
```
当前分钟索引: 10000
stop = 10000 - 1680 = 8320

lastMinuteArchived = 8300
→ stop (8320) > lastMinuteArchived (8300)
→ 执行存档,删除索引 8301 到 8320 的数据
→ 更新 lastMinuteArchived = 8320
```

#### 3. 物理删除机制

```typescript
function archiveMinuteData(token: Token, end: i32): void {
  const array = token.minuteArray;
  const modArray = token.minuteArray;
  
  for (let i = 0; i < array.length; i++) {
    if (array[i] > end) {
      break;  // 遇到第一个大于 end 的索引,停止
    }
    
    // 构造实体 ID
    const tokenMinuteID = token.id.concat('-').concat(array[i].toString());
    
    // 物理删除实体
    store.remove('TokenMinuteData', tokenMinuteID);
    
    // 从数组中移除索引
    modArray.shift();
    
    // 安全限制: 单次最多删除 1000 条
    if (i + 1 >= 1000) {
      break;
    }
  }
  
  // 更新 Token 的 minuteArray
  token.minuteArray = modArray;
  token.lastMinuteArchived = BigInt.fromI32(last - 1);
  token.save();
}
```

**删除流程**:
1. 遍历 `minuteArray`,找到所有 `<= end` 的索引
2. 对每个索引,调用 `store.remove()` 删除对应的 TokenMinuteData 实体
3. 从 `minuteArray` 中移除这些索引 (使用 `shift()`)
4. 更新 `lastMinuteArchived` 为最后删除的索引
5. 保存 Token 实体

**安全限制**:
- 🛡️ 单次最多删除 1000 条记录
- 🛡️ 如果超过 1000 条,下次再继续删除
- 🛡️ 防止单次操作消耗过多 gas

### 存档时机示例

假设当前时间为 **2025-01-01 10:00:00**:

```
Unix 时间戳: 1704103200
分钟索引: 1704103200 / 60 = 28401720

保留边界: 28401720 - 1680 = 28400040
→ 删除分钟索引 <= 28400040 的所有数据
→ 保留分钟索引 > 28400040 的数据 (最近 28 小时)

时间范围:
- 删除: 2025-01-01 10:00:00 之前 28 小时的数据
- 保留: 2025-01-01 10:00:00 前 28 小时 ~ 当前的数据
```

### 存档机制优缺点

**优点**:
- ✅ **自动化**: 无需手动清理,随着新数据创建自动触发
- ✅ **节省存储**: 控制数据量在合理范围内 (单 Token ~1680 条分钟数据)
- ✅ **高效删除**: 通过索引数组只删除实际存在的数据
- ✅ **防止爆炸**: 避免因忘记清理导致存储爆炸

**缺点**:
- ❌ **数据丢失**: 超过 28 小时的分钟数据永久丢失,无法查询历史
- ❌ **Gas 消耗**: 每次创建新数据时可能触发存档,增加 gas 消耗
- ❌ **数组维护成本**: `minuteArray` 可能变得很大 (1680 个元素)
- ❌ **不适合长期分析**: 无法做超过 28 小时的分钟级历史分析

**适用场景**:
- ✅ 实时价格展示
- ✅ 日内 K 线图 (1 分钟线)
- ✅ 短期交易分析 (最近 1 天)
- ❌ 长期历史分析 (需要使用 TokenHourData 或 TokenDayData)

---

## 使用场景与选择建议

### 场景对比表

| 使用场景 | 推荐子图 | 数据粒度 | 查询示例 |
|---------|---------|---------|---------|
| **实时价格监控** | v2-tokens | 分钟 | 最近 1 分钟的价格波动 |
| **1 分钟 K 线图** | v2-tokens | 分钟 | 最近 24 小时的分钟 OHLC |
| **5 分钟 K 线图** | v2-tokens | 分钟 | 聚合 5 个 TokenMinuteData |
| **15 分钟 K 线图** | v2-tokens | 分钟 | 聚合 15 个 TokenMinuteData |
| **1 小时 K 线图** | v2 或 v2-tokens | 小时 | TokenHourData (两者相同) |
| **1 天 K 线图** | v2 或 v2-tokens | 天 | TokenDayData (两者相同) |
| **流动性操作历史** | v2 | 事件 | 查询 Mint/Burn 实体 |
| **交易历史记录** | v2 | 事件 | 查询 Swap 实体 |
| **用户持仓追踪** | v2 | - | User + LiquidityPosition |
| **长期价格分析** | v2 | 天 | TokenDayData (永久保留) |
| **协议统计分析** | v2 | 天 | UniswapDayData (永久保留) |

### 选择建议

#### 使用 v2 子图,当您需要:

1. **完整的历史记录**
   - ✅ 查询所有 Mint/Burn/Swap 事件
   - ✅ 用户流动性操作追踪
   - ✅ 交易对手方分析

2. **长期数据分析**
   - ✅ 超过 28 小时的分钟级数据 (但 v2 没有分钟级)
   - ✅ 超过 32 天的小时级数据 (TokenHourData 会被删除)
   - ✅ 所有历史日级数据 (TokenDayData 永久保留)

3. **业务逻辑开发**
   - ✅ 计算用户 LP 收益
   - ✅ 手续费分成统计
   - ✅ 交易对手方分析

#### 使用 v2-tokens 子图,当您需要:

1. **高频价格数据**
   - ✅ 1 分钟 K 线图 (最近 28 小时)
   - ✅ 实时价格监控 (分钟级更新)
   - ✅ 短期交易信号 (日内交易)

2. **降低查询成本**
   - ✅ 不需要事件实体 (Transaction, Mint, Burn, Swap)
   - ✅ 自动存档控制数据量
   - ✅ 查询更快 (数据量更小)

3. **纯价格分析场景**
   - ✅ 只关心价格波动
   - ✅ 不关心流动性操作
   - ✅ 不关心交易明细

#### 同时使用两个子图

**推荐架构**:
```
前端应用
  │
  ├─ v2-tokens 子图 (实时价格 API)
  │   └─ TokenMinuteData: 1 分钟 K 线图
  │   └─ TokenHourData: 1 小时 K 线图
  │
  └─ v2 子图 (历史数据 API)
      └─ Swap: 交易历史
      └─ Mint/Burn: 流动性操作历史
      └─ TokenDayData: 长期趋势分析
```

**查询示例**:

1. **获取最近 24 小时的 1 分钟 K 线** (v2-tokens):
```graphql
query Get1MinuteCandles($token: String!, $startTime: Int!) {
  tokenMinuteDatas(
    where: {
      token: $token
      periodStartUnix_gte: $startTime
    }
    orderBy: periodStartUnix
    orderDirection: asc
  ) {
    periodStartUnix
    open
    high
    low
    close
    volumeUSD
  }
}
```

2. **获取交易历史** (v2):
```graphql
query GetSwapHistory($pair: String!, $limit: Int!) {
  swaps(
    where: { pair: $pair }
    orderBy: timestamp
    orderDirection: desc
    first: $limit
  ) {
    id
    timestamp
    sender
    amount0In
    amount0Out
    amount1In
    amount1Out
    amountUSD
  }
}
```

3. **获取流动性操作历史** (v2):
```graphql
query GetLiquidityHistory($pair: String!) {
  mints(where: { pair: $pair }, orderBy: timestamp, orderDirection: desc) {
    id
    timestamp
    to
    amount0
    amount1
    amountUSD
  }
  burns(where: { pair: $pair }, orderBy: timestamp, orderDirection: desc) {
    id
    timestamp
    sender
    amount0
    amount1
    amountUSD
  }
}
```

---

## 总结

### v2-tokens 核心特点

1. **专注价格**: 移除流动性操作事件 (Mint/Burn),只保留价格相关事件 (Sync/Swap)
2. **高频数据**: 新增 TokenMinuteData,提供分钟级 OHLC 数据
3. **自动存档**: 分钟数据保留 28 小时,自动删除过期数据
4. **存储优化**: 通过索引数组高效管理数据删除
5. **适用场景**: K 线图、实时价格、日内交易分析

### 技术亮点

- ✅ **分钟级 OHLC**: 支持 1/5/15 分钟 K 线图
- ✅ **存档机制**: 自动删除 28 小时前的分钟数据,控制存储成本
- ✅ **索引数组**: 通过 minuteArray/hourArray 高效管理数据删除
- ✅ **边界控制**: lastMinuteArchived 避免重复删除
- ✅ **安全限制**: 单次最多删除 1000 条,防止 gas 爆炸

### 注意事项

- ⚠️ **volume 是快照**: TokenMinuteData 的 volume 字段是累计值快照,而非分钟内增量
- ⚠️ **totalValueLocked 未实现**: 当前代码中直接设为 0
- ⚠️ **feesUSD 未实现**: Schema 中有定义但未计算
- ⚠️ **数据丢失**: 超过 28 小时的分钟数据会被永久删除
- ⚠️ **不适合长期分析**: 无法查询超过 28 小时的分钟级历史数据

### 设计权衡

| 维度 | v2 子图 | v2-tokens 子图 |
|-----|--------|----------------|
| **数据完整性** | ⭐⭐⭐⭐⭐ 完整保留所有事件 | ⭐⭐⭐ 仅保留价格数据 |
| **时间粒度** | ⭐⭐⭐⭐ 小时/天 | ⭐⭐⭐⭐⭐ 分钟/小时/天 |
| **存储成本** | ⭐⭐⭐ 中等 | ⭐⭐⭐⭐⭐ 低 (自动存档) |
| **查询性能** | ⭐⭐⭐ 数据量较大 | ⭐⭐⭐⭐⭐ 数据量小,查询快 |
| **实时性** | ⭐⭐⭐⭐ 小时级最快 | ⭐⭐⭐⭐⭐ 分钟级更新 |
| **历史分析** | ⭐⭐⭐⭐⭐ 支持长期分析 | ⭐⭐⭐ 仅 28 小时分钟数据 |

---

**文档结束**

> **推荐阅读顺序**:
> 1. 先阅读 [v2-subgraph-data-structure.md](./v2-subgraph-data-structure.md) 了解 v2 子图的完整结构
> 2. 再阅读本文档了解 v2-tokens 的差异和 TokenMinuteData 的详细实现
> 3. 根据实际使用场景选择合适的子图或同时使用两者

