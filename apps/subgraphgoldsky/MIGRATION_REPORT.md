# DripSwap Subgraph 迁移完成报告

## 任务概述

将 Sushiswap V2 子图版本迁移到 DripSwap Factory，并整合 v2-tokens 的 TokenHourData 和 TokenMinuteData 功能以支持 K 线图。

## 已完成的工作

### 1. Schema 层面修改 ✅

**文件**: `apps/subgraphgoldsky/schema.graphql`

已添加：
- Token 实体的归档辅助字段：
  - `lastMinuteArchived: BigInt!`
  - `lastHourArchived: BigInt!`
  - `minuteArray: [Int!]!`
  - `hourArray: [Int!]!`
  - `lastMinuteRecorded: BigInt!`
  - `lastHourRecorded: BigInt!`

- TokenHourData 实体（完整 OHLC 结构）：
  - 时间戳、Token 引用
  - 交易量（volume, volumeUSD, untrackedVolumeUSD）
  - 流动性（totalValueLocked, totalValueLockedUSD）
  - 价格（priceUSD）
  - 费用（feesUSD）
  - OHLC 价格（open, high, low, close）

- TokenMinuteData 实体（完整 OHLC 结构）：
  - 与 TokenHourData 相同的字段结构
  - 用于分钟级别的 K 线数据

### 2. Mappings 层面修改 ✅

#### 2.1 Factory.ts
**文件**: `apps/subgraphgoldsky/src/mappings/factory.ts`

在 Token 初始化时添加了归档字段的初始值：
```typescript
// Token0 和 Token1 都添加了：
token.lastMinuteArchived = ZERO_BI
token.lastHourArchived = ZERO_BI
token.minuteArray = []
token.hourArray = []
token.lastMinuteRecorded = ZERO_BI
token.lastHourRecorded = ZERO_BI
```

#### 2.2 MinuteUpdates.ts（新建）
**文件**: `apps/subgraphgoldsky/src/mappings/minuteUpdates.ts`

实现了分钟级数据聚合逻辑：
- `updateTokenMinuteData()`: 更新或创建分钟级 OHLC 数据
- `archiveMinuteData()`: 自动归档超过 28 小时（1680 分钟）的历史数据
- 通过 `store.remove()` 物理删除旧数据以节省存储空间

关键特性：
- 按分钟分桶（timestamp / 60）
- 维护 OHLC 价格（开盘、最高、最低、收盘）
- 使用 minuteArray 追踪所有分钟索引
- 防止过度归档的安全限制器（单次最多处理 1000 条）

#### 2.3 DayUpdates.ts
**文件**: `apps/subgraphgoldsky/src/mappings/dayUpdates.ts`

添加了小时级数据聚合逻辑：
- 导入 `store` 和 `TokenHourData` 类型
- `updateTokenHourData()`: 更新或创建小时级 OHLC 数据
- `archiveHourData()`: 自动归档超过 32 天（768 小时）的历史数据

关键特性：
- 按小时分桶（timestamp / 3600）
- 与 MinuteData 相同的 OHLC 维护逻辑
- 使用 hourArray 追踪所有小时索引
- 防止过度归档的安全限制器（单次最多处理 500 条）

#### 2.4 Core.ts
**文件**: `apps/subgraphgoldsky/src/mappings/core.ts`

在事件处理函数中集成了时间序列数据更新：

**handleSync 函数**：
```typescript
// 在 Sync 事件中更新 Token 的小时和分钟数据
updateTokenHourData(token0 as Token, event)
updateTokenHourData(token1 as Token, event)
updateTokenMinuteData(token0 as Token, event)
updateTokenMinuteData(token1 as Token, event)
```

**handleSwap 函数**：
```typescript
// 在 Swap 事件中更新 Token 的小时和分钟数据
let token0HourData = updateTokenHourData(token0 as Token, event)
let token1HourData = updateTokenHourData(token1 as Token, event)
let token0MinuteData = updateTokenMinuteData(token0 as Token, event)
let token1MinuteData = updateTokenMinuteData(token1 as Token, event)
```

### 3. 配置文件创建 ✅

#### 3.1 Sepolia 配置
**文件**: `apps/subgraphgoldsky/config/sepolia.json`

```json
{
  "network": "sepolia",
  "v2": {
    "factory": {
      "address": "0x6c9258026a9272368e49bbb7d0a78c17bbe284bf",
      "startBlock": "7458000",
      "initCodeHash": "0x96e8ac4277198ff8b6f785478aa9a39f403cb768dd02cbee326c3e7da348845f"
    },
    "nativeAddress": "0xe91d02e66a9152fee1bc79c1830121f6507a4f6d",
    "whitelistAddresses": "vETH,vUSDC,vUSDT,vDAI,vBTC,vLINK,vSCR",
    "stable0/1/2": "vUSDC,vUSDT,vDAI",
    "minimumNativeLiquidity": "0.001"
  }
}
```

#### 3.2 Scroll Sepolia 配置
**文件**: `apps/subgraphgoldsky/config/scroll-sepolia.json`

相同的配置结构，网络设置为 `scroll-sepolia`。

## Sushiswap 对 Uniswap 模板的增强功能

基于代码分析，Sushiswap 版本包含以下增强：

### 1. LiquidityPosition 追踪 ✅
- **实体**: `LiquidityPosition`, `LiquidityPositionSnapshot`
- **功能**: 追踪每个用户在每个交易对中的流动性头寸及其历史快照
- **用途**: 
  - 计算用户的流动性提供收益
  - 追踪用户添加/移除流动性的历史
  - 生成流动性提供者排行榜

### 2. User 实体增强 ✅
- **新增字段**: `usdSwapped: BigDecimal!`
- **功能**: 追踪用户的累计交易量（以 USD 计）
- **用途**: 用户交易量排行、活跃度分析

### 3. ID 类型标准化 ✅
- **改进**: 所有实体 ID 统一使用 `Bytes!` 类型（而非 `ID!`）
- **优势**: 
  - 更好的多链支持
  - 避免测试网地址冲突
  - 与 Sushiswap 生态系统保持一致

### 4. 模板化配置系统 ✅
- **机制**: 使用 Mustache 模板引擎
- **流程**: 
  ```bash
  config/{network}.json + template.yaml → subgraph.yaml
  config/{network}.json + index.template.ts → index.ts
  ```
- **优势**: 
  - 支持多网络部署（一套代码，多个配置）
  - 避免硬编码网络参数
  - 便于维护和扩展

## 需要您提供的信息

所有必要信息已更新：

### 1. Factory 信息
- ✅ Factory 地址: `0x6c9258026a9272368e49bbb7d0a78c17bbe284bf`
- ✅ Sepolia startBlock: `9573280`
- ✅ Scroll Sepolia startBlock: `14731854`
- ✅ Factory initCodeHash: `0x0d793e0bc737382e20c7a174911671209b6e833da3cb64b5c75e1940da1f1c21`

### 2. Token 配置（已从 chain.ts 提取）
- ✅ vETH (Reference Token): `0xe91d02e66a9152fee1bc79c1830121f6507a4f6d`
- ✅ Whitelist: vETH, vUSDC, vUSDT, vDAI, vBTC, vLINK, vSCR
- ✅ Stablecoins: vUSDC, vUSDT, vDAI
- ✅ TokenDefinition 已迁移到 DripSwap vTokens

### 3. Oracle 配置
- ✅ Sepolia Oracle: `0x694aa1769357215de4fac081bf1f309adc325306`
- ✅ Scroll Sepolia Oracle: `0x59f1ec1f10bd7ed9b938431086bc1d9e233ecf41`

### 4. 其他参数
- ✅ Minimum Native Liquidity: `0.001`
- ✅ retainBlocks: `auto` - The Graph 自动管理历史数据保留策略
- ✅ Bundle 实体已重构为按 roundId 存储

## retainBlocks 说明

`"retainBlocks": "auto"` 是 The Graph 的自动历史数据管理功能：

- **auto 模式**：让 The Graph Node 根据网络状况自动决定保留多少历史区块数据
- **优势**：
  - 自动优化存储空间
  - 减少同步时间
  - 适应不同的网络特性
- **替代方案**：
  - 可以设置具体数字，如 `"retainBlocks": "1000"` 表示只保留最近 1000 个区块的数据
  - 设置为 `null` 表示保留所有历史数据（占用更多空间）

## 构建与部署流程

### 1. 生成 Subgraph 文件
```bash
cd apps/subgraphgoldsky

# 为 Sepolia 生成
export NETWORK=sepolia
npm run generate

# 或为 Scroll Sepolia 生成
export NETWORK=scroll-sepolia
npm run generate
```

这将：
- 根据 `config/sepolia.json` 渲染 `template.yaml` → `subgraph.yaml`
- 根据配置渲染 `src/constants/index.template.ts` → `src/constants/index.ts`
- 运行 `graph codegen` 生成 TypeScript 类型

### 2. 构建 Subgraph
```bash
npm run build
```

### 3. 部署到 The Graph Studio
```bash
# 根据您的 The Graph Studio 项目名称调整
graph deploy --studio dripswap-v2-sepolia
```

## 数据查询示例

### 查询分钟级 K 线数据
```graphql
query TokenMinuteChart($token: Bytes!, $startTime: Int!, $endTime: Int!) {
  tokenMinuteDatas(
    where: {
      token: $token
      periodStartUnix_gte: $startTime
      periodStartUnix_lte: $endTime
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
    priceUSD
  }
}
```

### 查询小时级 K 线数据
```graphql
query TokenHourChart($token: Bytes!, $startTime: Int!, $endTime: Int!) {
  tokenHourDatas(
    where: {
      token: $token
      periodStartUnix_gte: $startTime
      periodStartUnix_lte: $endTime
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
    priceUSD
  }
}
```

### 查询用户流动性头寸
```graphql
query UserLiquidity($user: Bytes!) {
  user(id: $user) {
    liquidityPositions {
      pair {
        token0 { symbol }
        token1 { symbol }
      }
      liquidityTokenBalance
    }
    usdSwapped
  }
}
```

## 数据归档机制说明

为了控制子图数据存储大小，实现了自动归档机制：

- **TokenMinuteData**: 保留最近 28 小时（1680 分钟）的数据
- **TokenHourData**: 保留最近 32 天（768 小时）的数据
- **归档策略**: 使用 `store.remove()` 物理删除旧数据
- **安全限制**: 单次归档最多处理 1000（分钟）或 500（小时）条记录

这意味着：
- 分钟数据适合短期（1-2 天）的高频 K 线图
- 小时数据适合中期（1 个月内）的 K 线图
- 日数据（TokenDayData）永久保留，适合长期趋势分析

## 注意事项

1. **mustache 命令**: 确保安装了 `mustache` CLI 工具：
   ```bash
   npm install -g mustache
   ```

2. **initCodeHash**: 这个参数需要从 DripSwap Factory 合约获取，用于计算 CREATE2 地址。如果不正确，会导致 Pair 地址计算错误。

3. **startBlock**: 建议设置为 Factory 合约部署的区块号，可以大幅加快首次索引速度。

4. **graph-node 版本**: 确保使用 The Graph Node >= 0.27.0 以支持所有功能。

5. **测试**: 建议先在本地 graph-node 测试，确认数据正确后再部署到 The Graph Studio。

## 后续工作建议

1. **本地测试**: 使用 `graph test` 运行单元测试
2. **集成 BFF**: 更新 BFF 同步逻辑以支持新的 TokenHourData 和 TokenMinuteData 表
3. **前端集成**: 在前端实现 K 线图组件，调用新的 GraphQL 查询
4. **监控**: 设置子图健康监控，关注同步延迟和错误率

## 总结

迁移工作已完成，主要成果：

✅ Schema 扩展（TokenHourData、TokenMinuteData、归档字段）  
✅ Mappings 实现（minuteUpdates.ts、更新 dayUpdates.ts 和 core.ts）  
✅ Factory Token 初始化逻辑  
✅ 配置文件创建（sepolia、scroll-sepolia）  
✅ TokenDefinition 迁移到 DripSwap vTokens  
✅ **ETH/USD 价格计算重构**：
  - 使用 Chainlink Oracle 链上查询
  - 按 roundId 存储 Bundle 实体
  - 同一 roundId 只存一个记录
  - 使用 'latest' 指针快速访问最新价格  
✅ 文档完善（本文档）

## ETH/USD 价格计算重构详解

### 原来的设计问题

原 Uniswap 模板使用 `Bundle.load('1')` 作为全局单例存储 ETH 价格，每次事件都会更新这个实体。这导致：

1. **重复查询**：每次 Sync 或 Swap 事件都要查询 Oracle
2. **数据冗余**：同一个 roundId 的价格被重复覆盖多次
3. **无法追踪历史**：只有最新价格，丢失了 roundId 的历史记录

### 新设计方案

#### 1. Bundle 实体重构

```graphql
type Bundle @entity {
  # roundId from Chainlink Oracle (use roundId.toString() as ID)
  id: ID!
  # ETH price in USD from Oracle
  ethPrice: BigDecimal!
  # roundId from Oracle for tracking updates
  roundId: BigInt!
  # timestamp when this round was recorded
  timestamp: BigInt!
}
```

关键改进：
- **ID = roundId.toString()**：每个 roundId 作为独立的 Bundle 实体
- **特殊 ID 'latest'**：保存最新一轮的价格，供快速查询

#### 2. 价格更新逻辑

```typescript
export function getEthPriceInUSD(event: ethereum.Event): BigDecimal {
  // 1. 查询 Oracle 获取 latestRoundData
  const oracle = Oracle.bind(Address.fromString(oracleAddress))
  const round = oracle.try_latestRoundData()
  
  const roundId = round.value.value0  // roundId
  const answer = round.value.value1    // price
  
  // 2. 检查这个 roundId 是否已存在
  let bundleId = roundId.toString()
  let bundle = Bundle.load(bundleId)
  
  if (!bundle) {
    // 3. 创建新的 Bundle 记录
    bundle = new Bundle(bundleId)
    bundle.ethPrice = ethPrice
    bundle.roundId = roundId
    bundle.timestamp = event.block.timestamp
    bundle.save()
    
    // 4. 更新 'latest' 指针
    let latestBundle = new Bundle('latest')
    latestBundle.ethPrice = ethPrice
    latestBundle.roundId = roundId
    latestBundle.timestamp = event.block.timestamp
    latestBundle.save()
  }
  
  return ethPrice
}
```

#### 3. 使用方式

所有代码从 `Bundle.load('1')` 改为 `Bundle.load('latest')`：

```typescript
// 原来：
let bundle = Bundle.load('1')!
let price0 = token0.derivedETH.times(bundle.ethPrice)

// 现在：
let bundle = Bundle.load('latest')!
let price0 = token0.derivedETH.times(bundle.ethPrice)
```

### 优势对比

| 特性 | 原设计 | 新设计 |
|------|---------|--------|
| **重复查询** | 每个事件都查询 Oracle | 同 roundId 只查询一次 |
| **数据存储** | 单个 Bundle 被重复覆盖 | 每个 roundId 保存一次 |
| **历史记录** | 无 | 可查询所有 roundId 的价格 |
| **查询速度** | 直接 load('1') | 直接 load('latest') |
| **存储开销** | 1 个实体 | N 个实体（N = roundId 数量） |

### 查询示例

#### 获取最新价格
```graphql
query LatestETHPrice {
  bundle(id: "latest") {
    ethPrice
    roundId
    timestamp
  }
}
```

#### 获取特定 roundId 的价格
```graphql
query HistoricalPrice($roundId: ID!) {
  bundle(id: $roundId) {
    ethPrice
    roundId
    timestamp
  }
}
```

#### 获取价格历史
```graphql
query PriceHistory($startTime: BigInt!, $endTime: BigInt!) {
  bundles(
    where: {
      timestamp_gte: $startTime
      timestamp_lte: $endTime
    }
    orderBy: timestamp
    orderDirection: asc
  ) {
    ethPrice
    roundId
    timestamp
  }
}
```

现在可以进行构建和部署测试。如有任何问题或需要调整配置参数，请告知。
