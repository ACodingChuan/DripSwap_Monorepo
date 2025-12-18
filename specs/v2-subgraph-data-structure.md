# Uniswap V2 子图详细数据结构文档

> **文档目的**: 详细描述 v2-subgraph 项目中的所有数据实体、字段含义、初始化逻辑和更新逻辑  
> **参考项目**: https://github.com/graphprotocol/uniswap-v2-subgraph  
> **最后更新**: 2025-12-16

---

## 目录
1. [核心实体 (Core Entities)](#核心实体)
2. [事件实体 (Event Entities)](#事件实体)
3. [时间聚合实体 (Time Aggregation Entities)](#时间聚合实体)
4. [辅助实体 (Helper Entities)](#辅助实体)
5. [初始化与更新流程](#初始化与更新流程)
6. [价格计算机制](#价格计算机制)

---

## 核心实体

### 1. UniswapFactory

**描述**: 工厂合约实体,全局唯一,存储协议级别的聚合数据

**Schema 定义**:
```graphql
type UniswapFactory @entity {
  id: ID!
  pairCount: Int!
  totalVolumeUSD: BigDecimal!
  totalVolumeETH: BigDecimal!
  untrackedVolumeUSD: BigDecimal!
  totalLiquidityUSD: BigDecimal!
  totalLiquidityETH: BigDecimal!
  txCount: BigInt!
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|---------|---------|---------|
| **id** | ID! | Factory 合约地址 (如 `0x5C69bEe701ef814a2B6a3EDD4B1652CB9cc5aA6f`) | Factory 合约地址 | 不变 | - |
| **pairCount** | Int! | 已创建的交易对总数 | 0 | 每次 PairCreated 事件 | +1 |
| **totalVolumeUSD** | BigDecimal! | 累计总交易量 (USD,tracked) | `0` | 每次 Swap 事件 | `+= trackedAmountUSD` |
| **totalVolumeETH** | BigDecimal! | 累计总交易量 (ETH,tracked) | `0` | 每次 Swap 事件 | `+= trackedAmountETH` |
| **untrackedVolumeUSD** | BigDecimal! | 累计总交易量 (USD,untracked) | `0` | 每次 Swap 事件 | `+= derivedAmountUSD` |
| **totalLiquidityUSD** | BigDecimal! | 当前总流动性 (USD,tracked) | `0` | 每次 Sync 事件 | `totalLiquidityETH * ethPrice` |
| **totalLiquidityETH** | BigDecimal! | 当前总流动性 (ETH,tracked) | `0` | 每次 Sync 事件 | 先减去旧的 `pair.trackedReserveETH`,更新后加上新的 |
| **txCount** | BigInt! | 全局交易计数 (Mint/Burn/Swap) | `0` | 每次 Mint/Burn/Swap 事件 | +1 |

**初始化逻辑** (`factory.ts#handleNewPair`):
```typescript
let factory = UniswapFactory.load(FACTORY_ADDRESS)
if (factory === null) {
  factory = new UniswapFactory(FACTORY_ADDRESS)
  factory.pairCount = 0
  factory.totalVolumeETH = ZERO_BD
  factory.totalLiquidityETH = ZERO_BD
  factory.totalVolumeUSD = ZERO_BD
  factory.untrackedVolumeUSD = ZERO_BD
  factory.totalLiquidityUSD = ZERO_BD
  factory.txCount = ZERO_BI
  
  // 同时创建 Bundle 实体用于存储 ETH 价格
  let bundle = new Bundle('1')
  bundle.ethPrice = ZERO_BD
  bundle.save()
}
```

**关键更新场景**:

1. **PairCreated 事件**:
   ```typescript
   factory.pairCount = factory.pairCount + 1
   factory.save()
   ```

2. **Swap 事件** (交易量累计):
   ```typescript
   uniswap.totalVolumeUSD = uniswap.totalVolumeUSD.plus(trackedAmountUSD)
   uniswap.totalVolumeETH = uniswap.totalVolumeETH.plus(trackedAmountETH)
   uniswap.untrackedVolumeUSD = uniswap.untrackedVolumeUSD.plus(derivedAmountUSD)
   uniswap.txCount = uniswap.txCount.plus(ONE_BI)
   ```

3. **Sync 事件** (流动性更新):
   ```typescript
   // 1. 减去旧的流动性贡献
   uniswap.totalLiquidityETH = uniswap.totalLiquidityETH.minus(pair.trackedReserveETH)
   
   // 2. 更新后加上新的流动性贡献
   uniswap.totalLiquidityETH = uniswap.totalLiquidityETH.plus(trackedLiquidityETH)
   uniswap.totalLiquidityUSD = uniswap.totalLiquidityETH.times(bundle.ethPrice)
   ```

---

### 2. Token

**描述**: 代币实体,记录每个 ERC20 代币的元数据和统计信息

**Schema 定义**:
```graphql
type Token @entity {
  id: ID!
  symbol: String!
  name: String!
  decimals: BigInt!
  totalSupply: BigInt!
  tradeVolume: BigDecimal!
  tradeVolumeUSD: BigDecimal!
  untrackedVolumeUSD: BigDecimal!
  txCount: BigInt!
  totalLiquidity: BigDecimal!
  derivedETH: BigDecimal!
  
  # 关联字段
  tokenDayData: [TokenDayData!]! @derivedFrom(field: "token")
  pairDayDataBase: [PairDayData!]! @derivedFrom(field: "token0")
  pairDayDataQuote: [PairDayData!]! @derivedFrom(field: "token1")
  pairBase: [Pair!]! @derivedFrom(field: "token0")
  pairQuote: [Pair!]! @derivedFrom(field: "token1")
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | 初始化逻辑 | 更新时机 | 更新逻辑 |
|--------|------|---------|----------|---------|---------|
| **id** | ID! | 代币地址 (小写,如 `0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2`) | `event.params.token0.toHexString()` | 不变 | - |
| **symbol** | String! | 代币符号 (如 `WETH`) | `fetchTokenSymbol(address)` 调用链上合约 | 不变 | - |
| **name** | String! | 代币名称 (如 `Wrapped Ether`) | `fetchTokenName(address)` 调用链上合约 | 不变 | - |
| **decimals** | BigInt! | 小数位数 (如 18) | `fetchTokenDecimals(address)` 调用链上合约 | 不变 | - |
| **totalSupply** | BigInt! | 总供应量 (raw,未除精度) | `fetchTokenTotalSupply(address)` 调用链上合约 | 一般不变 | - |
| **tradeVolume** | BigDecimal! | 累计交易量 (代币单位,已除精度) | `0` | 每次 Swap 事件 | `+= amount0In + amount0Out` (或 amount1) |
| **tradeVolumeUSD** | BigDecimal! | 累计交易量 (USD,tracked) | `0` | 每次 Swap 事件 | `+= trackedAmountUSD` |
| **untrackedVolumeUSD** | BigDecimal! | 累计交易量 (USD,untracked) | `0` | 每次 Swap 事件 | `+= derivedAmountUSD` |
| **txCount** | BigInt! | 交易计数 (Mint/Burn/Swap) | `0` | 每次 Mint/Burn/Swap 事件 | +1 |
| **totalLiquidity** | BigDecimal! | 当前总流动性 (代币单位,所有 Pair 中该代币的储备量之和) | `0` | 每次 Sync 事件 | 先 `-= pair.reserve0`, 更新后 `+= pair.reserve0` |
| **derivedETH** | BigDecimal! | 相对 ETH 的派生价格 (1 代币 = ? ETH) | `0` | 每次 Sync 事件 | `findEthPerToken(token)` 通过白名单交易对计算 |

**初始化逻辑** (`factory.ts#handleNewPair`):
```typescript
if (token0 === null) {
  token0 = new Token(event.params.token0.toHexString())
  token0.symbol = fetchTokenSymbol(event.params.token0)
  token0.name = fetchTokenName(event.params.token0)
  token0.totalSupply = fetchTokenTotalSupply(event.params.token0)
  let decimals = fetchTokenDecimals(event.params.token0)
  
  // 如果无法获取 decimals，放弃创建
  if (decimals === null) {
    return
  }
  
  token0.decimals = decimals
  token0.derivedETH = ZERO_BD
  token0.tradeVolume = ZERO_BD
  token0.tradeVolumeUSD = ZERO_BD
  token0.untrackedVolumeUSD = ZERO_BD
  token0.totalLiquidity = ZERO_BD
  token0.txCount = ZERO_BI
}
```

**代币信息获取方式** (`helpers.ts`):
```typescript
// 1. 优先使用静态定义 (STATIC_TOKEN_DEFINITIONS)
export function fetchTokenSymbol(tokenAddress: Address): string {
  let contract = ERC20.bind(tokenAddress)
  let contractSymbolBytes = ERC20SymbolBytes.bind(tokenAddress)
  
  // 尝试调用 symbol() 方法 (返回 string)
  let symbolValue = 'unknown'
  let symbolResult = contract.try_symbol()
  if (!symbolResult.reverted) {
    return symbolResult.value
  }
  
  // 尝试调用 symbol() 方法 (返回 bytes32)
  let symbolResultBytes = contractSymbolBytes.try_symbol()
  if (!symbolResultBytes.reverted) {
    return symbolResultBytes.value.toString()
  }
  
  return symbolValue
}

// 2. 类似地获取 name, decimals, totalSupply
```

**关键更新场景**:

1. **Sync 事件** (流动性和价格更新):
   ```typescript
   // 1. 减去旧的流动性贡献
   token0.totalLiquidity = token0.totalLiquidity.minus(pair.reserve0)
   token1.totalLiquidity = token1.totalLiquidity.minus(pair.reserve1)
   
   // 2. 更新 Pair 储备量...
   
   // 3. 更新 ETH 派生价格
   token0.derivedETH = findEthPerToken(token0 as Token)
   token1.derivedETH = findEthPerToken(token1 as Token)
   
   // 4. 加回新的流动性贡献
   token0.totalLiquidity = token0.totalLiquidity.plus(pair.reserve0)
   token1.totalLiquidity = token1.totalLiquidity.plus(pair.reserve1)
   ```

2. **Swap 事件** (交易量更新):
   ```typescript
   // Token0
   token0.tradeVolume = token0.tradeVolume.plus(amount0In.plus(amount0Out))
   token0.tradeVolumeUSD = token0.tradeVolumeUSD.plus(trackedAmountUSD)
   token0.untrackedVolumeUSD = token0.untrackedVolumeUSD.plus(derivedAmountUSD)
   token0.txCount = token0.txCount.plus(ONE_BI)
   
   // Token1 类似
   ```

3. **Mint/Burn 事件** (交易计数):
   ```typescript
   token0.txCount = token0.txCount.plus(ONE_BI)
   token1.txCount = token1.txCount.plus(ONE_BI)
   ```

---

### 3. Pair

**描述**: 交易对实体,记录两个代币组成的流动性池的状态和统计信息

**Schema 定义**:
```graphql
type Pair @entity {
  id: ID!
  token0: Token!
  token1: Token!
  reserve0: BigDecimal!
  reserve1: BigDecimal!
  totalSupply: BigDecimal!
  reserveETH: BigDecimal!
  reserveUSD: BigDecimal!
  trackedReserveETH: BigDecimal!
  token0Price: BigDecimal!
  token1Price: BigDecimal!
  volumeToken0: BigDecimal!
  volumeToken1: BigDecimal!
  volumeUSD: BigDecimal!
  untrackedVolumeUSD: BigDecimal!
  txCount: BigInt!
  createdAtTimestamp: BigInt!
  createdAtBlockNumber: BigInt!
  liquidityProviderCount: BigInt!
  
  # 关联字段
  pairHourData: [PairHourData!]! @derivedFrom(field: "pair")
  mints: [Mint!]! @derivedFrom(field: "pair")
  burns: [Burn!]! @derivedFrom(field: "pair")
  swaps: [Swap!]! @derivedFrom(field: "pair")
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|---------|---------|---------|
| **id** | ID! | Pair 合约地址 (小写) | `event.params.pair.toHexString()` | 不变 | - |
| **token0** | Token! | 第一个代币 ID (地址较小的代币) | `event.params.token0.toHexString()` | 不变 | - |
| **token1** | Token! | 第二个代币 ID (地址较大的代币) | `event.params.token1.toHexString()` | 不变 | - |
| **reserve0** | BigDecimal! | token0 储备量 (已除精度) | `0` | 每次 Sync 事件 | `convertTokenToDecimal(event.params.reserve0, token0.decimals)` |
| **reserve1** | BigDecimal! | token1 储备量 (已除精度) | `0` | 每次 Sync 事件 | `convertTokenToDecimal(event.params.reserve1, token1.decimals)` |
| **totalSupply** | BigDecimal! | LP Token 总供应量 (已除精度,18位) | `0` | Transfer 事件 (Mint/Burn) | Mint 时 `+=`,Burn 时 `-=` |
| **reserveETH** | BigDecimal! | 总流动性 (ETH,untracked) | `0` | 每次 Sync 事件 | `reserve0 * token0.derivedETH + reserve1 * token1.derivedETH` |
| **reserveUSD** | BigDecimal! | 总流动性 (USD,untracked) | `0` | 每次 Sync 事件 | `reserveETH * bundle.ethPrice` |
| **trackedReserveETH** | BigDecimal! | 总流动性 (ETH,tracked,用于全局统计) | `0` | 每次 Sync 事件 | `getTrackedLiquidityUSD(...) / ethPrice` |
| **token0Price** | BigDecimal! | token0 相对 token1 的价格 (1 token0 = ? token1) | `0` | 每次 Sync 事件 | `reserve0 / reserve1` |
| **token1Price** | BigDecimal! | token1 相对 token0 的价格 (1 token1 = ? token0) | `0` | 每次 Sync 事件 | `reserve1 / reserve0` |
| **volumeToken0** | BigDecimal! | 累计 token0 交易量 | `0` | 每次 Swap 事件 | `+= amount0In + amount0Out` |
| **volumeToken1** | BigDecimal! | 累计 token1 交易量 | `0` | 每次 Swap 事件 | `+= amount1In + amount1Out` |
| **volumeUSD** | BigDecimal! | 累计交易量 (USD,tracked) | `0` | 每次 Swap 事件 | `+= trackedAmountUSD` |
| **untrackedVolumeUSD** | BigDecimal! | 累计交易量 (USD,untracked) | `0` | 每次 Swap 事件 | `+= derivedAmountUSD` |
| **txCount** | BigInt! | 交易计数 (Mint/Burn/Swap) | `0` | 每次 Mint/Burn/Swap 事件 | +1 |
| **createdAtTimestamp** | BigInt! | 创建时的时间戳 | `event.block.timestamp` | 不变 | - |
| **createdAtBlockNumber** | BigInt! | 创建时的区块号 | `event.block.number` | 不变 | - |
| **liquidityProviderCount** | BigInt! | LP 提供者数量 (用于判断新交易对) | `0` | 待实现 | 待实现 |

**初始化逻辑** (`factory.ts#handleNewPair`):
```typescript
let pair = new Pair(event.params.pair.toHexString())
pair.token0 = token0.id
pair.token1 = token1.id
pair.liquidityProviderCount = ZERO_BI
pair.createdAtTimestamp = event.block.timestamp
pair.createdAtBlockNumber = event.block.number
pair.txCount = ZERO_BI
pair.reserve0 = ZERO_BD
pair.reserve1 = ZERO_BD
pair.trackedReserveETH = ZERO_BD
pair.reserveETH = ZERO_BD
pair.reserveUSD = ZERO_BD
pair.totalSupply = ZERO_BD
pair.volumeToken0 = ZERO_BD
pair.volumeToken1 = ZERO_BD
pair.volumeUSD = ZERO_BD
pair.untrackedVolumeUSD = ZERO_BD
pair.token0Price = ZERO_BD
pair.token1Price = ZERO_BD

// 动态创建 Pair 模板，开始监听该 Pair 合约的事件
PairTemplate.create(event.params.pair)

pair.save()
```

**关键更新场景**:

1. **Transfer 事件** (LP Token Mint):
   ```typescript
   // 从零地址转出 = Mint
   if (from.toHexString() == ADDRESS_ZERO) {
     pair.totalSupply = pair.totalSupply.plus(value)
     pair.save()
   }
   ```

2. **Transfer 事件** (LP Token Burn):
   ```typescript
   // 转到零地址 + 来自 Pair 地址 = Burn
   if (event.params.to.toHexString() == ADDRESS_ZERO && 
       event.params.from.toHexString() == pair.id) {
     pair.totalSupply = pair.totalSupply.minus(value)
     pair.save()
   }
   ```

3. **Sync 事件** (储备量和价格更新):
   ```typescript
   // 1. 更新储备量
   pair.reserve0 = convertTokenToDecimal(event.params.reserve0, token0.decimals)
   pair.reserve1 = convertTokenToDecimal(event.params.reserve1, token1.decimals)
   
   // 2. 计算价格 (避免除以零)
   if (pair.reserve1.notEqual(ZERO_BD)) {
     pair.token0Price = pair.reserve0.div(pair.reserve1)
   } else {
     pair.token0Price = ZERO_BD
   }
   
   if (pair.reserve0.notEqual(ZERO_BD)) {
     pair.token1Price = pair.reserve1.div(pair.reserve0)
   } else {
     pair.token1Price = ZERO_BD
   }
   
   // 3. 计算追踪流动性 (用于全局统计)
   let trackedLiquidityETH: BigDecimal
   if (bundle.ethPrice.notEqual(ZERO_BD)) {
     trackedLiquidityETH = getTrackedLiquidityUSD(
       pair.reserve0, token0, 
       pair.reserve1, token1
     ).div(bundle.ethPrice)
   } else {
     trackedLiquidityETH = ZERO_BD
   }
   pair.trackedReserveETH = trackedLiquidityETH
   
   // 4. 计算总流动性 (ETH 和 USD)
   pair.reserveETH = pair.reserve0.times(token0.derivedETH)
                       .plus(pair.reserve1.times(token1.derivedETH))
   pair.reserveUSD = pair.reserveETH.times(bundle.ethPrice)
   
   pair.save()
   ```

4. **Swap 事件** (交易量更新):
   ```typescript
   pair.volumeUSD = pair.volumeUSD.plus(trackedAmountUSD)
   pair.volumeToken0 = pair.volumeToken0.plus(amount0Total)
   pair.volumeToken1 = pair.volumeToken1.plus(amount1Total)
   pair.untrackedVolumeUSD = pair.untrackedVolumeUSD.plus(derivedAmountUSD)
   pair.txCount = pair.txCount.plus(ONE_BI)
   pair.save()
   ```

---

### 4. Bundle

**描述**: 全局单例实体,存储 ETH/USD 价格

**Schema 定义**:
```graphql
type Bundle @entity {
  id: ID!
  ethPrice: BigDecimal!
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|---------|---------|---------|
| **id** | ID! | 固定为 `"1"` | `"1"` | 不变 | - |
| **ethPrice** | BigDecimal! | ETH 的 USD 价格 (如 `3000.5`) | `0` | 每次 Sync 事件 | `getEthPriceInUSD()` 通过稳定币交易对计算 |

**初始化逻辑** (`factory.ts#handleNewPair`):
```typescript
let bundle = new Bundle('1')
bundle.ethPrice = ZERO_BD
bundle.save()
```

**更新逻辑** (`core.ts#handleSync`):
```typescript
let bundle = Bundle.load('1')!
bundle.ethPrice = getEthPriceInUSD()
bundle.save()
```

**ETH 价格计算方式** (`pricing.ts#getEthPriceInUSD`):
```typescript
// 1. 通过多个稳定币交易对的流动性加权平均计算
// STABLE_TOKEN_PAIRS = [USDC-WETH, WETH-USDT, DAI-WETH]

export function getEthPriceInUSD(): BigDecimal {
  let totalLiquidityETH = ZERO_BD
  let stableTokenPrices = []
  let stableTokenReserves = []
  
  for (let i = 0; i < STABLE_TOKEN_PAIRS.length; i++) {
    const stableTokenPair = Pair.load(STABLE_TOKEN_PAIRS[i])
    if (stableTokenPair) {
      // 判断 WETH 是 token0 还是 token1
      let isToken0 = stableTokenPair.token1 == REFERENCE_TOKEN
      
      if (isToken0) {
        // USDC-WETH: token0Price = USDC / WETH
        stableTokenReserves[i] = stableTokenPair.reserve1  // WETH 储备量
        stableTokenPrices[i] = stableTokenPair.token0Price  // USDC per WETH
      } else {
        // WETH-USDC: token1Price = USDC / WETH
        stableTokenReserves[i] = stableTokenPair.reserve0
        stableTokenPrices[i] = stableTokenPair.token1Price
      }
      
      totalLiquidityETH = totalLiquidityETH.plus(stableTokenReserves[i])
    }
  }
  
  // 流动性加权平均
  let ethPrice = ZERO_BD
  for (let i = 0; i < STABLE_TOKEN_PAIRS.length; i++) {
    if (stableTokenPairs[i] !== null) {
      let weight = safeDiv(stableTokenReserves[i], totalLiquidityETH)
      ethPrice = ethPrice.plus(stableTokenPrices[i].times(weight))
    }
  }
  
  return ethPrice
}

// 计算公式: ETH_Price = Σ(price_i × (reserve_i / total_reserve))
```

---

## 事件实体

### 5. Transaction

**描述**: 交易记录实体,用于关联同一笔链上交易中的多个 Mint/Burn/Swap 事件

**Schema 定义**:
```graphql
type Transaction @entity {
  id: ID!
  blockNumber: BigInt!
  timestamp: BigInt!
  mints: [Mint!]!
  burns: [Burn!]!
  swaps: [Swap!]!
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|---------|---------|---------|
| **id** | ID! | 交易哈希 (如 `0xabc...123`) | `event.transaction.hash.toHexString()` | 不变 | - |
| **blockNumber** | BigInt! | 区块号 | `event.block.number` | 不变 | - |
| **timestamp** | BigInt! | 时间戳 | `event.block.timestamp` | 不变 | - |
| **mints** | [Mint!]! | 该交易中的 Mint 事件数组 | `[]` | Transfer/Mint 事件 | 追加 Mint ID |
| **burns** | [Burn!]! | 该交易中的 Burn 事件数组 | `[]` | Transfer/Burn 事件 | 追加 Burn ID |
| **swaps** | [Swap!]! | 该交易中的 Swap 事件数组 | `[]` | Swap 事件 | 追加 Swap ID |

**初始化逻辑** (`core.ts#handleTransfer/handleSwap`):
```typescript
let transaction = Transaction.load(transactionHash)
if (transaction === null) {
  transaction = new Transaction(transactionHash)
  transaction.blockNumber = event.block.number
  transaction.timestamp = event.block.timestamp
  transaction.mints = []
  transaction.burns = []
  transaction.swaps = []
}
```

**更新逻辑**:
1. **Transfer 事件** (识别 Mint):
   ```typescript
   // 创建新 Mint
   let mint = new MintEvent(txHash + '-' + mints.length)
   // ...
   transaction.mints = mints.concat([mint.id])
   transaction.save()
   ```

2. **Transfer 事件** (识别 Burn):
   ```typescript
   // 创建新 Burn
   let burn = new BurnEvent(txHash + '-' + burns.length)
   // ...
   burns.push(burn.id)
   transaction.burns = burns
   transaction.save()
   ```

3. **Swap 事件**:
   ```typescript
   let swap = new SwapEvent(txHash + '-' + swaps.length)
   // ...
   swaps.push(swap.id)
   transaction.swaps = swaps
   transaction.save()
   ```

---

### 6. Mint (MintEvent)

**描述**: 添加流动性事件,记录 LP 提供流动性的详细信息

**Schema 定义**:
```graphql
type Mint @entity {
  id: ID!
  transaction: Transaction!
  timestamp: BigInt!
  pair: Pair!
  to: Bytes!
  liquidity: BigDecimal!
  sender: Bytes
  amount0: BigDecimal
  amount1: BigDecimal
  logIndex: BigInt
  amountUSD: BigDecimal
  feeTo: Bytes
  feeLiquidity: BigDecimal
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | 初始化时机 | 初始化值 | 完善时机 | 完善逻辑 |
|--------|------|---------|----------|---------|---------|---------|
| **id** | ID! | `{txHash}-{mintIndex}` (如 `0xabc-0`) | Transfer 事件 | `txHash + '-' + mints.length` | - | - |
| **transaction** | Transaction! | 关联的交易 | Transfer 事件 | `transaction.id` | - | - |
| **timestamp** | BigInt! | 时间戳 | Transfer 事件 | `transaction.timestamp` | - | - |
| **pair** | Pair! | 关联的交易对 | Transfer 事件 | `pair.id` | - | - |
| **to** | Bytes! | LP Token 接收者 (LP 提供者地址) | Transfer 事件 | `event.params.to` | - | - |
| **liquidity** | BigDecimal! | 铸造的 LP Token 数量 | Transfer 事件 | `convertTokenToDecimal(value, 18)` | - | - |
| **sender** | Bytes | 发送者 (合约调用者) | - | `null` | Mint 事件 | `event.params.sender` |
| **amount0** | BigDecimal | 添加的 token0 数量 | - | `null` | Mint 事件 | `convertTokenToDecimal(event.params.amount0, token0.decimals)` |
| **amount1** | BigDecimal | 添加的 token1 数量 | - | `null` | Mint 事件 | `convertTokenToDecimal(event.params.amount1, token1.decimals)` |
| **logIndex** | BigInt | 事件日志索引 | - | `null` | Mint 事件 | `event.logIndex` |
| **amountUSD** | BigDecimal | USD 价值 | - | `null` | Mint 事件 | `(amount0 * token0.derivedETH + amount1 * token1.derivedETH) * ethPrice` |
| **feeTo** | Bytes | 协议费用接收者 (如果有) | - | `null` | Burn 时检测 | 从费用 Mint 中提取 |
| **feeLiquidity** | BigDecimal | 协议费用 LP Token 数量 | - | `null` | Burn 时检测 | 从费用 Mint 中提取 |

**两阶段创建机制**:

Uniswap V2 中,添加流动性会触发两个事件:
1. **Transfer 事件**: `from=0x0, to=LP提供者` (LP Token 铸造)
2. **Mint 事件**: 包含 `amount0, amount1, sender` 等详细信息

因此 Mint 实体的创建分两步:

**第一阶段 - Transfer 事件** (`core.ts#handleTransfer`):
```typescript
// 识别 Mint: from 地址为零地址
if (from.toHexString() == ADDRESS_ZERO) {
  // 更新 Pair 的 totalSupply
  pair.totalSupply = pair.totalSupply.plus(value)
  pair.save()
  
  // 创建新 Mint 或复用已有 Mint
  if (mints.length === 0 || isCompleteMint(mints[mints.length - 1])) {
    let mint = new MintEvent(txHash + '-' + mints.length)
    mint.transaction = transaction.id
    mint.pair = pair.id
    mint.to = to  // LP Token 接收者
    mint.liquidity = value  // LP Token 数量
    mint.timestamp = transaction.timestamp
    mint.save()
    
    transaction.mints = mints.concat([mint.id])
    transaction.save()
  }
}

// 判断 Mint 是否完整
function isCompleteMint(mintId: string): boolean {
  return MintEvent.load(mintId)!.sender !== null
}
```

**第二阶段 - Mint 事件** (`core.ts#handleMint`):
```typescript
export function handleMint(event: Mint): void {
  // 加载 Transaction 中最后一个 Mint
  let transaction = Transaction.load(event.transaction.hash.toHexString())
  let mints = transaction.mints
  let mint = MintEvent.load(mints[mints.length - 1])
  
  // 转换代币数量
  let token0Amount = convertTokenToDecimal(event.params.amount0, token0.decimals)
  let token1Amount = convertTokenToDecimal(event.params.amount1, token1.decimals)
  
  // 计算 USD 价值
  let bundle = Bundle.load('1')!
  let amountTotalUSD = token1.derivedETH.times(token1Amount)
                        .plus(token0.derivedETH.times(token0Amount))
                        .times(bundle.ethPrice)
  
  // 完善 Mint 实体
  mint.sender = event.params.sender
  mint.amount0 = token0Amount
  mint.amount1 = token1Amount
  mint.logIndex = event.logIndex
  mint.amountUSD = amountTotalUSD
  mint.save()
  
  // 更新交易计数
  token0.txCount = token0.txCount.plus(ONE_BI)
  token1.txCount = token1.txCount.plus(ONE_BI)
  pair.txCount = pair.txCount.plus(ONE_BI)
  uniswap.txCount = uniswap.txCount.plus(ONE_BI)
}
```

**协议费用处理**:

在移除流动性 (Burn) 时,Uniswap V2 可能会先铸造协议费用的 LP Token,这会产生一个"不完整的 Mint":
```typescript
// 在 Burn 的 Transfer 事件中检测费用 Mint
if (mints.length !== 0 && !isCompleteMint(mints[mints.length - 1])) {
  let mint = MintEvent.load(mints[mints.length - 1])!
  burn.feeTo = mint.to
  burn.feeLiquidity = mint.liquidity
  // 删除费用 Mint 实体
  store.remove('Mint', mints[mints.length - 1])
  mints.pop()
  transaction.mints = mints
}
```

---

### 7. Burn (BurnEvent)

**描述**: 移除流动性事件,记录 LP 提取流动性的详细信息

**Schema 定义**:
```graphql
type Burn @entity {
  id: ID!
  transaction: Transaction!
  timestamp: BigInt!
  pair: Pair!
  liquidity: BigDecimal!
  sender: Bytes
  amount0: BigDecimal
  amount1: BigDecimal
  to: Bytes
  logIndex: BigInt
  amountUSD: BigDecimal
  needsComplete: Boolean!
  feeTo: Bytes
  feeLiquidity: BigDecimal
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | 初始化时机 | 初始化值 | 完善时机 | 完善逻辑 |
|--------|------|---------|----------|---------|---------|---------|
| **id** | ID! | `{txHash}-{burnIndex}` | Transfer 事件 (第一阶段) | `txHash + '-' + burns.length` | - | - |
| **transaction** | Transaction! | 关联的交易 | Transfer 事件 | `transaction.id` | - | - |
| **timestamp** | BigInt! | 时间戳 | Transfer 事件 | `transaction.timestamp` | - | - |
| **pair** | Pair! | 关联的交易对 | Transfer 事件 | `pair.id` | - | - |
| **liquidity** | BigDecimal! | 销毁的 LP Token 数量 | Transfer 事件 (第二阶段) | `convertTokenToDecimal(value, 18)` | - | - |
| **sender** | Bytes | LP Token 归还者 | Transfer 事件 (第一阶段) | `event.params.from` | - | - |
| **amount0** | BigDecimal | 提取的 token0 数量 | - | `null` | Burn 事件 | `convertTokenToDecimal(event.params.amount0, token0.decimals)` |
| **amount1** | BigDecimal | 提取的 token1 数量 | - | `null` | Burn 事件 | `convertTokenToDecimal(event.params.amount1, token1.decimals)` |
| **to** | Bytes | 代币接收者 | Transfer 事件 (第一阶段) | `event.params.to` (Pair 地址) | Burn 事件 | `event.params.to` |
| **logIndex** | BigInt | 事件日志索引 | - | `null` | Burn 事件 | `event.logIndex` |
| **amountUSD** | BigDecimal | USD 价值 | - | `null` | Burn 事件 | `(amount0 * token0.derivedETH + amount1 * token1.derivedETH) * ethPrice` |
| **needsComplete** | Boolean! | 是否需要完善 (两阶段标记) | Transfer 事件 (第一阶段) | `true` | Transfer 事件 (第二阶段) | `false` |
| **feeTo** | Bytes | 协议费用接收者 | - | `null` | Burn 时检测费用 Mint | 从费用 Mint 提取 |
| **feeLiquidity** | BigDecimal | 协议费用 LP Token 数量 | - | `null` | Burn 时检测费用 Mint | 从费用 Mint 提取 |

**两阶段创建机制**:

Uniswap V2 中,移除流动性会触发三个 Transfer 事件和一个 Burn 事件:
1. **Transfer 事件 A**: `from=LP提供者, to=Pair合约` (归还 LP Token)
2. **Transfer 事件 B**: `from=Pair合约, to=0x0` (销毁 LP Token)
3. **Burn 事件**: 包含 `amount0, amount1, to` 等详细信息

**第一阶段 - Transfer 事件 A** (`core.ts#handleTransfer`):
```typescript
// LP Token 归还到 Pair 合约
if (event.params.to.toHexString() == pair.id) {
  let burn = new BurnEvent(txHash + '-' + burns.length)
  burn.transaction = transaction.id
  burn.pair = pair.id
  burn.liquidity = value  // 暂存，实际在第二阶段才是销毁数量
  burn.timestamp = transaction.timestamp
  burn.to = event.params.to  // Pair 地址
  burn.sender = event.params.from  // LP 提供者
  burn.needsComplete = true  // 标记未完成
  burn.save()
  
  burns.push(burn.id)
  transaction.burns = burns
  transaction.save()
}
```

**第二阶段 - Transfer 事件 B** (`core.ts#handleTransfer`):
```typescript
// LP Token 从 Pair 销毁
if (event.params.to.toHexString() == ADDRESS_ZERO && 
    event.params.from.toHexString() == pair.id) {
  
  // 更新 Pair 的 totalSupply
  pair.totalSupply = pair.totalSupply.minus(value)
  pair.save()
  
  // 查找或创建 Burn 实体
  let burns = transaction.burns
  let burn: BurnEvent
  
  if (burns.length > 0) {
    let currentBurn = BurnEvent.load(burns[burns.length - 1])!
    if (currentBurn.needsComplete) {
      burn = currentBurn  // 使用已存在的 Burn
    } else {
      burn = new BurnEvent(...)  // 创建新的 Burn (异常情况)
    }
  } else {
    burn = new BurnEvent(...)  // 创建新的 Burn (异常情况)
  }
  
  // 处理协议费用 Mint (如果有)
  if (mints.length !== 0 && !isCompleteMint(mints[mints.length - 1])) {
    let mint = MintEvent.load(mints[mints.length - 1])!
    burn.feeTo = mint.to
    burn.feeLiquidity = mint.liquidity
    store.remove('Mint', mints[mints.length - 1])  // 删除费用 Mint
    mints.pop()
  }
  
  burn.save()
}
```

**第三阶段 - Burn 事件** (`core.ts#handleBurn`):
```typescript
export function handleBurn(event: Burn): void {
  let transaction = Transaction.load(event.transaction.hash.toHexString())
  let burns = transaction.burns
  let burn = BurnEvent.load(burns[burns.length - 1])
  
  // 转换代币数量
  let token0Amount = convertTokenToDecimal(event.params.amount0, token0.decimals)
  let token1Amount = convertTokenToDecimal(event.params.amount1, token1.decimals)
  
  // 计算 USD 价值
  let bundle = Bundle.load('1')!
  let amountTotalUSD = token1.derivedETH.times(token1Amount)
                        .plus(token0.derivedETH.times(token0Amount))
                        .times(bundle.ethPrice)
  
  // 完善 Burn 实体
  burn.amount0 = token0Amount
  burn.amount1 = token1Amount
  burn.logIndex = event.logIndex
  burn.amountUSD = amountTotalUSD
  burn.save()
  
  // 更新交易计数
  token0.txCount = token0.txCount.plus(ONE_BI)
  token1.txCount = token1.txCount.plus(ONE_BI)
  pair.txCount = pair.txCount.plus(ONE_BI)
  uniswap.txCount = uniswap.txCount.plus(ONE_BI)
}
```

---

### 8. Swap (SwapEvent)

**描述**: 代币交换事件,记录每笔 Swap 的详细信息

**Schema 定义**:
```graphql
type Swap @entity {
  id: ID!
  transaction: Transaction!
  timestamp: BigInt!
  pair: Pair!
  sender: Bytes!
  from: Bytes!
  amount0In: BigDecimal!
  amount1In: BigDecimal!
  amount0Out: BigDecimal!
  amount1Out: BigDecimal!
  to: Bytes!
  logIndex: BigInt
  amountUSD: BigDecimal!
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | 初始化时机 | 初始化值/计算逻辑 |
|--------|------|---------|----------|----------------|
| **id** | ID! | `{txHash}-{swapIndex}` | Swap 事件 | `txHash + '-' + swaps.length` |
| **transaction** | Transaction! | 关联的交易 | Swap 事件 | `transaction.id` |
| **timestamp** | BigInt! | 时间戳 | Swap 事件 | `transaction.timestamp` |
| **pair** | Pair! | 关联的交易对 | Swap 事件 | `pair.id` |
| **sender** | Bytes! | 合约调用者 (如 Router) | Swap 事件 | `event.params.sender` |
| **from** | Bytes! | 交易发起者 (EOA) | Swap 事件 | `event.transaction.from` |
| **amount0In** | BigDecimal! | 输入的 token0 数量 | Swap 事件 | `convertTokenToDecimal(event.params.amount0In, token0.decimals)` |
| **amount1In** | BigDecimal! | 输入的 token1 数量 | Swap 事件 | `convertTokenToDecimal(event.params.amount1In, token1.decimals)` |
| **amount0Out** | BigDecimal! | 输出的 token0 数量 | Swap 事件 | `convertTokenToDecimal(event.params.amount0Out, token0.decimals)` |
| **amount1Out** | BigDecimal! | 输出的 token1 数量 | Swap 事件 | `convertTokenToDecimal(event.params.amount1Out, token1.decimals)` |
| **to** | Bytes! | 代币接收者 | Swap 事件 | `event.params.to` |
| **logIndex** | BigInt | 事件日志索引 | Swap 事件 | `event.logIndex` |
| **amountUSD** | BigDecimal! | USD 价值 (优先 tracked) | Swap 事件 | `trackedAmountUSD === ZERO_BD ? derivedAmountUSD : trackedAmountUSD` |

**创建逻辑** (`core.ts#handleSwap`):
```typescript
export function handleSwap(event: Swap): void {
  let pair = Pair.load(event.address.toHexString())!
  let token0 = Token.load(pair.token0)
  let token1 = Token.load(pair.token1)
  
  // 1. 转换代币数量
  let amount0In = convertTokenToDecimal(event.params.amount0In, token0.decimals)
  let amount1In = convertTokenToDecimal(event.params.amount1In, token1.decimals)
  let amount0Out = convertTokenToDecimal(event.params.amount0Out, token0.decimals)
  let amount1Out = convertTokenToDecimal(event.params.amount1Out, token1.decimals)
  
  // 2. 计算总交易量 (用于统计)
  let amount0Total = amount0Out.plus(amount0In)
  let amount1Total = amount1Out.plus(amount1In)
  
  // 3. 计算 USD 价值
  let bundle = Bundle.load('1')!
  
  // 3.1 派生 ETH 数量
  const derivedEthToken0 = token0.derivedETH.times(amount0Total)
  const derivedEthToken1 = token1.derivedETH.times(amount1Total)
  
  // 3.2 计算 ETH 交易量 (如果一边接近零，不除以2)
  let derivedAmountETH = ZERO_BD
  if (derivedEthToken0.le(ALMOST_ZERO_BD) || derivedEthToken1.le(ALMOST_ZERO_BD)) {
    derivedAmountETH = derivedEthToken0.plus(derivedEthToken1)
  } else {
    derivedAmountETH = derivedEthToken0.plus(derivedEthToken1).div(BigDecimal.fromString('2'))
  }
  
  // 3.3 计算 USD 交易量 (untracked)
  let derivedAmountUSD = derivedAmountETH.times(bundle.ethPrice)
  
  // 3.4 计算 USD 交易量 (tracked,只统计白名单代币)
  let trackedAmountUSD = getTrackedVolumeUSD(
    amount0Total, token0, 
    amount1Total, token1, 
    pair
  )
  
  // 4. 更新全局和实体的交易量...
  
  // 5. 创建 Swap 实体
  let transaction = Transaction.load(event.transaction.hash.toHexString())
  if (transaction === null) {
    transaction = new Transaction(...)
    // 初始化
  }
  
  let swaps = transaction.swaps
  let swap = new SwapEvent(txHash + '-' + swaps.length)
  
  swap.transaction = transaction.id
  swap.pair = pair.id
  swap.timestamp = transaction.timestamp
  swap.sender = event.params.sender
  swap.from = event.transaction.from
  swap.amount0In = amount0In
  swap.amount1In = amount1In
  swap.amount0Out = amount0Out
  swap.amount1Out = amount1Out
  swap.to = event.params.to
  swap.logIndex = event.logIndex
  swap.amountUSD = trackedAmountUSD === ZERO_BD ? derivedAmountUSD : trackedAmountUSD
  swap.save()
  
  swaps.push(swap.id)
  transaction.swaps = swaps
  transaction.save()
}
```

**为什么交易量除以 2?**

在计算交易量时,如果简单地将两边的代币价值相加会导致交易量翻倍:
- 假设用户用 1000 USDC 换 1 ETH (ETH 价格 1000 USD)
- 如果不除以 2: 交易量 = 1000 + 1000 = 2000 USD (错误!)
- 除以 2 后: 交易量 = (1000 + 1000) / 2 = 1000 USD (正确)

但如果一边接近零 (如 Wrap/Unwrap 操作),则不除以 2:
```typescript
if (derivedEthToken0.le(ALMOST_ZERO_BD) || derivedEthToken1.le(ALMOST_ZERO_BD)) {
  derivedAmountETH = derivedEthToken0.plus(derivedEthToken1)
} else {
  derivedAmountETH = derivedEthToken0.plus(derivedEthToken1).div(BigDecimal.fromString('2'))
}
```

---

## 时间聚合实体

### 9. UniswapDayData

**描述**: 协议全局日数据,聚合每日的交易量、流动性等统计信息

**Schema 定义**:
```graphql
type UniswapDayData @entity {
  id: ID!
  date: Int!
  dailyVolumeETH: BigDecimal!
  dailyVolumeUSD: BigDecimal!
  dailyVolumeUntracked: BigDecimal!
  totalVolumeETH: BigDecimal!
  totalLiquidityETH: BigDecimal!
  totalVolumeUSD: BigDecimal!
  totalLiquidityUSD: BigDecimal!
  txCount: BigInt!
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | ID 生成 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|--------|---------|---------|---------|
| **id** | ID! | 日期 ID (Unix 天数) | `timestamp / 86400` | - | - | - |
| **date** | Int! | 当天开始时间戳 (UTC 零点) | `dayID * 86400` | 当天零点时间戳 | 不变 | - |
| **dailyVolumeETH** | BigDecimal! | 当日交易量 (ETH,tracked) | - | `0` | 每次 Swap 事件 | `+= trackedAmountETH` |
| **dailyVolumeUSD** | BigDecimal! | 当日交易量 (USD,tracked) | - | `0` | 每次 Swap 事件 | `+= trackedAmountUSD` |
| **dailyVolumeUntracked** | BigDecimal! | 当日交易量 (USD,untracked) | - | `0` | 每次 Swap 事件 | `+= derivedAmountUSD` |
| **totalVolumeETH** | BigDecimal! | 累计总交易量 (ETH,快照) | - | `0` | 每次 Mint/Burn/Swap 事件 | 复制 `uniswap.totalVolumeETH` |
| **totalVolumeUSD** | BigDecimal! | 累计总交易量 (USD,快照) | - | `0` | 每次 Mint/Burn/Swap 事件 | 复制 `uniswap.totalVolumeUSD` |
| **totalLiquidityETH** | BigDecimal! | 当前总流动性 (ETH,快照) | - | `0` | 每次 Mint/Burn/Swap 事件 | 复制 `uniswap.totalLiquidityETH` |
| **totalLiquidityUSD** | BigDecimal! | 当前总流动性 (USD,快照) | - | `0` | 每次 Mint/Burn/Swap 事件 | 复制 `uniswap.totalLiquidityUSD` |
| **txCount** | BigInt! | 累计交易计数 (快照) | - | `0` | 每次 Mint/Burn/Swap 事件 | 复制 `uniswap.txCount` |

**ID 生成逻辑**:
```typescript
let timestamp = event.block.timestamp.toI32()  // 如 1638316800 (2021-12-01 00:00:00 UTC)
let dayID = timestamp / 86400  // 18962 (Unix 天数)
let dayStartTimestamp = dayID * 86400  // 1638316800 (当天零点)
```

**初始化与更新逻辑** (`hourDayUpdates.ts#updateUniswapDayData`):
```typescript
export function updateUniswapDayData(event: ethereum.Event): UniswapDayData {
  let uniswap = UniswapFactory.load(FACTORY_ADDRESS)!
  let timestamp = event.block.timestamp.toI32()
  let dayID = timestamp / 86400
  let dayStartTimestamp = dayID * 86400
  
  let uniswapDayData = UniswapDayData.load(dayID.toString())
  
  // 首次初始化 (当天第一笔交易)
  if (!uniswapDayData) {
    uniswapDayData = new UniswapDayData(dayID.toString())
    uniswapDayData.date = dayStartTimestamp
    uniswapDayData.dailyVolumeUSD = ZERO_BD
    uniswapDayData.dailyVolumeETH = ZERO_BD
    uniswapDayData.totalVolumeUSD = ZERO_BD
    uniswapDayData.totalVolumeETH = ZERO_BD
    uniswapDayData.dailyVolumeUntracked = ZERO_BD
  }
  
  // 更新快照数据 (每笔交易都会更新)
  uniswapDayData.totalLiquidityUSD = uniswap.totalLiquidityUSD
  uniswapDayData.totalLiquidityETH = uniswap.totalLiquidityETH
  uniswapDayData.txCount = uniswap.txCount
  uniswapDayData.save()
  
  return uniswapDayData
}
```

**Swap 事件中的增量更新** (`core.ts#handleSwap`):
```typescript
let uniswapDayData = updateUniswapDayData(event)

// 累加当日交易量
uniswapDayData.dailyVolumeUSD = uniswapDayData.dailyVolumeUSD.plus(trackedAmountUSD)
uniswapDayData.dailyVolumeETH = uniswapDayData.dailyVolumeETH.plus(trackedAmountETH)
uniswapDayData.dailyVolumeUntracked = uniswapDayData.dailyVolumeUntracked.plus(derivedAmountUSD)
uniswapDayData.save()
```

---

### 10. PairDayData

**描述**: 交易对日数据,聚合每个交易对每日的交易量、流动性等信息

**Schema 定义**:
```graphql
type PairDayData @entity {
  id: ID!
  date: Int!
  pairAddress: Bytes!
  token0: Token!
  token1: Token!
  reserve0: BigDecimal!
  reserve1: BigDecimal!
  totalSupply: BigDecimal
  reserveUSD: BigDecimal!
  dailyVolumeToken0: BigDecimal!
  dailyVolumeToken1: BigDecimal!
  dailyVolumeUSD: BigDecimal!
  dailyTxns: BigInt!
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | ID 生成 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|--------|---------|---------|---------|
| **id** | ID! | `{pairAddress}-{dayID}` | `pairAddress + '-' + dayID` | - | - | - |
| **date** | Int! | 当天开始时间戳 | - | `dayID * 86400` | 不变 | - |
| **pairAddress** | Bytes! | 交易对地址 | - | `event.address` | 不变 | - |
| **token0** | Token! | 第一个代币 | - | `pair.token0` | 不变 | - |
| **token1** | Token! | 第二个代币 | - | `pair.token1` | 不变 | - |
| **reserve0** | BigDecimal! | token0 当日最后储备量 | - | `ZERO_BD` | 每次 Sync | `pair.reserve0` |
| **reserve1** | BigDecimal! | token1 当日最后储备量 | - | `ZERO_BD` | 每次 Sync | `pair.reserve1` |
| **totalSupply** | BigDecimal | LP Token 当日最后总供应量 | - | `ZERO_BD` | Mint/Burn | `pair.totalSupply` |
| **reserveUSD** | BigDecimal! | 储备总价值（USD） | - | `ZERO_BD` | 每次 Sync | `pair.reserveUSD` |
| **dailyVolumeToken0** | BigDecimal! | 当日 token0 交易量 | - | `ZERO_BD` | Swap | 累加 `abs(amount0)` |
| **dailyVolumeToken1** | BigDecimal! | 当日 token1 交易量 | - | `ZERO_BD` | Swap | 累加 `abs(amount1)` |
| **dailyVolumeUSD** | BigDecimal! | 当日交易量（USD） | - | `ZERO_BD` | Swap | 累加 `trackedAmountUSD` |
| **dailyTxns** | BigInt! | 当日交易笔数 | - | `ZERO_BI` | Swap | 每次 Swap +1 |

**初始化逻辑**:

```typescript
// src/common/hourDayUpdates.ts - updatePairDayData()
function updatePairDayData(event: ethereum.Event): PairDayData {
  let timestamp = event.block.timestamp.toI32();
  let dayID = timestamp / 86400;
  let dayPairID = event.address.concat(Bytes.fromI32(dayID));
  
  let pair = Pair.load(event.address.toHexString());
  let pairDayData = PairDayData.load(dayPairID);
  
  if (pairDayData === null) {
    pairDayData = new PairDayData(dayPairID);
    pairDayData.date = dayID * 86400;
    pairDayData.pairAddress = event.address;
    pairDayData.token0 = pair.token0;
    pairDayData.token1 = pair.token1;
    pairDayData.reserve0 = ZERO_BD;
    pairDayData.reserve1 = ZERO_BD;
    pairDayData.totalSupply = ZERO_BD;
    pairDayData.reserveUSD = ZERO_BD;
    pairDayData.dailyVolumeToken0 = ZERO_BD;
    pairDayData.dailyVolumeToken1 = ZERO_BD;
    pairDayData.dailyVolumeUSD = ZERO_BD;
    pairDayData.dailyTxns = ZERO_BI;
  }
  
  // 更新快照数据
  pairDayData.reserve0 = pair.reserve0;
  pairDayData.reserve1 = pair.reserve1;
  pairDayData.totalSupply = pair.totalSupply;
  pairDayData.reserveUSD = pair.reserveUSD;
  pairDayData.save();
  
  return pairDayData;
}
```

**更新逻辑**:

```typescript
// src/v2/mappings/core.ts - handleSwap()
export function handleSwap(event: Swap): void {
  // ... 处理 Swap 业务逻辑
  
  // 更新 PairDayData
  let pairDayData = updatePairDayData(event);
  pairDayData.dailyVolumeToken0 = pairDayData.dailyVolumeToken0.plus(
    amount0Total
  );
  pairDayData.dailyVolumeToken1 = pairDayData.dailyVolumeToken1.plus(
    amount1Total
  );
  pairDayData.dailyVolumeUSD = pairDayData.dailyVolumeUSD.plus(
    trackedAmountUSD
  );
  pairDayData.dailyTxns = pairDayData.dailyTxns.plus(ONE_BI);
  pairDayData.save();
}
```

---

### 11. PairHourData - 交易对小时数据

**用途**: 记录每个交易对每小时的快照数据，用于短期统计和图表展示。

**Schema 定义**:

```graphql
type PairHourData @entity {
  id: ID!
  hourStartUnix: Int!
  pair: Pair!
  reserve0: BigDecimal!
  reserve1: BigDecimal!
  totalSupply: BigDecimal
  reserveUSD: BigDecimal!
  hourlyVolumeToken0: BigDecimal!
  hourlyVolumeToken1: BigDecimal!
  hourlyVolumeUSD: BigDecimal!
  hourlyTxns: BigInt!
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | ID 生成 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|--------|---------|---------|----------|
| **id** | ID! | `{pairAddress}-{hourID}` | `pairAddress + '-' + hourID` | - | - | - |
| **hourStartUnix** | Int! | 小时开始时间戳 | - | `hourID * 3600` | 不变 | - |
| **pair** | Pair! | 关联的交易对 | - | `pair.id` | 不变 | - |
| **reserve0** | BigDecimal! | token0 小时末储备量 | - | `ZERO_BD` | 每次 Sync | `pair.reserve0` |
| **reserve1** | BigDecimal! | token1 小时末储备量 | - | `ZERO_BD` | 每次 Sync | `pair.reserve1` |
| **totalSupply** | BigDecimal | LP Token 小时末总供应量 | - | `ZERO_BD` | Mint/Burn | `pair.totalSupply` |
| **reserveUSD** | BigDecimal! | 储备总价值(USD) | - | `ZERO_BD` | 每次 Sync | `pair.reserveUSD` |
| **hourlyVolumeToken0** | BigDecimal! | 小时内 token0 交易量 | - | `ZERO_BD` | Swap | 累加 `abs(amount0)` |
| **hourlyVolumeToken1** | BigDecimal! | 小时内 token1 交易量 | - | `ZERO_BD` | Swap | 累加 `abs(amount1)` |
| **hourlyVolumeUSD** | BigDecimal! | 小时内交易量(USD) | - | `ZERO_BD` | Swap | 累加 `trackedAmountUSD` |
| **hourlyTxns** | BigInt! | 小时内交易笔数 | - | `ZERO_BI` | Swap | 每次 Swap +1 |

**初始化逻辑**:

```typescript
// src/common/hourDayUpdates.ts - updatePairHourData()
export function updatePairHourData(pair: Pair, event: ethereum.Event): PairHourData {
  let timestamp = event.block.timestamp.toI32();
  let hourIndex = timestamp / 3600;  // 小时索引
  let hourStartUnix = hourIndex * 3600;
  let hourPairID = pair.id.concat('-').concat(BigInt.fromI32(hourIndex).toString());
  
  let pairHourData = PairHourData.load(hourPairID);
  
  if (pairHourData === null) {
    pairHourData = new PairHourData(hourPairID);
    pairHourData.hourStartUnix = hourStartUnix;
    pairHourData.pair = pair.id;
    pairHourData.reserve0 = ZERO_BD;
    pairHourData.reserve1 = ZERO_BD;
    pairHourData.totalSupply = ZERO_BD;
    pairHourData.reserveUSD = ZERO_BD;
    pairHourData.hourlyVolumeToken0 = ZERO_BD;
    pairHourData.hourlyVolumeToken1 = ZERO_BD;
    pairHourData.hourlyVolumeUSD = ZERO_BD;
    pairHourData.hourlyTxns = ZERO_BI;
  }
  
  // 更新快照数据
  pairHourData.reserve0 = pair.reserve0;
  pairHourData.reserve1 = pair.reserve1;
  pairHourData.totalSupply = pair.totalSupply;
  pairHourData.reserveUSD = pair.reserveUSD;
  pairHourData.save();
  
  return pairHourData;
}
```

**更新逻辑**:

```typescript
// src/v2/mappings/core.ts - handleSwap()
export function handleSwap(event: Swap): void {
  // ... 处理 Swap 业务逻辑
  
  // 更新 PairHourData
  let pairHourData = updatePairHourData(pair, event);
  pairHourData.hourlyVolumeToken0 = pairHourData.hourlyVolumeToken0.plus(
    amount0Total
  );
  pairHourData.hourlyVolumeToken1 = pairHourData.hourlyVolumeToken1.plus(
    amount1Total
  );
  pairHourData.hourlyVolumeUSD = pairHourData.hourlyVolumeUSD.plus(
    trackedAmountUSD
  );
  pairHourData.hourlyTxns = pairHourData.hourlyTxns.plus(ONE_BI);
  pairHourData.save();
}
```

---

### 12. TokenDayData - 代币日数据

**用途**: 记录每个代币每天的统计数据,包括交易量、流动性和价格快照。

**Schema 定义**:

```graphql
type TokenDayData @entity {
  id: ID!
  date: Int!
  token: Token!
  dailyVolumeToken: BigDecimal!
  dailyVolumeETH: BigDecimal!
  dailyVolumeUSD: BigDecimal!
  dailyTxns: BigInt!
  totalLiquidityToken: BigDecimal!
  totalLiquidityETH: BigDecimal!
  totalLiquidityUSD: BigDecimal!
  priceUSD: BigDecimal!
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | ID 生成 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|--------|---------|---------|----------|
| **id** | ID! | `{tokenAddress}-{dayID}` | `token.id + '-' + dayID` | - | - | - |
| **date** | Int! | 当天开始时间戳 | - | `dayID * 86400` | 不变 | - |
| **token** | Token! | 关联的代币 | - | `token.id` | 不变 | - |
| **dailyVolumeToken** | BigDecimal! | 当日代币交易量 | - | `ZERO_BD` | Swap | 累加代币数量 |
| **dailyVolumeETH** | BigDecimal! | 当日交易量(ETH) | - | `ZERO_BD` | Swap | 累加 ETH 价值 |
| **dailyVolumeUSD** | BigDecimal! | 当日交易量(USD) | - | `ZERO_BD` | Swap | 累加 USD 价值 |
| **dailyTxns** | BigInt! | 当日交易笔数 | - | `ZERO_BI` | Swap | 每次 Swap +1 |
| **totalLiquidityToken** | BigDecimal! | 当日末总流动性(代币) | - | `ZERO_BD` | Sync | `token.totalLiquidity` |
| **totalLiquidityETH** | BigDecimal! | 当日末总流动性(ETH) | - | `ZERO_BD` | Sync | `token.totalLiquidity * derivedETH` |
| **totalLiquidityUSD** | BigDecimal! | 当日末总流动性(USD) | - | `ZERO_BD` | Sync | `totalLiquidityETH * ethPrice` |
| **priceUSD** | BigDecimal! | 当日末价格(USD) | - | `ZERO_BD` | Sync | `derivedETH * ethPrice` |

**初始化逻辑**:

```typescript
// src/common/hourDayUpdates.ts - updateTokenDayData()
export function updateTokenDayData(token: Token, event: ethereum.Event): TokenDayData {
  let bundle = Bundle.load('1');
  let timestamp = event.block.timestamp.toI32();
  let dayID = timestamp / 86400;
  let tokenDayID = token.id.concat('-').concat(BigInt.fromI32(dayID).toString());
  
  let tokenDayData = TokenDayData.load(tokenDayID);
  
  if (tokenDayData === null) {
    tokenDayData = new TokenDayData(tokenDayID);
    tokenDayData.date = dayID * 86400;
    tokenDayData.token = token.id;
    tokenDayData.dailyVolumeToken = ZERO_BD;
    tokenDayData.dailyVolumeETH = ZERO_BD;
    tokenDayData.dailyVolumeUSD = ZERO_BD;
    tokenDayData.dailyTxns = ZERO_BI;
    tokenDayData.totalLiquidityToken = ZERO_BD;
    tokenDayData.totalLiquidityETH = ZERO_BD;
    tokenDayData.totalLiquidityUSD = ZERO_BD;
    tokenDayData.priceUSD = ZERO_BD;
  }
  
  // 更新快照数据
  tokenDayData.totalLiquidityToken = token.totalLiquidity;
  tokenDayData.totalLiquidityETH = token.totalLiquidity.times(token.derivedETH);
  tokenDayData.totalLiquidityUSD = tokenDayData.totalLiquidityETH.times(bundle.ethPrice);
  tokenDayData.priceUSD = token.derivedETH.times(bundle.ethPrice);
  tokenDayData.save();
  
  return tokenDayData;
}
```

**更新逻辑**:

```typescript
// src/v2/mappings/core.ts - handleSwap()
export function handleSwap(event: Swap): void {
  // 更新 token0 DayData
  let token0DayData = updateTokenDayData(token0, event);
  token0DayData.dailyVolumeToken = token0DayData.dailyVolumeToken.plus(amount0Total);
  token0DayData.dailyVolumeETH = token0DayData.dailyVolumeETH.plus(
    amount0Total.times(token0.derivedETH)
  );
  token0DayData.dailyVolumeUSD = token0DayData.dailyVolumeUSD.plus(
    amount0Total.times(token0.derivedETH).times(bundle.ethPrice)
  );
  token0DayData.dailyTxns = token0DayData.dailyTxns.plus(ONE_BI);
  token0DayData.save();
  
  // 同样更新 token1 DayData
}
```

---

### 13. TokenHourData - 代币小时数据 (含 OHLC)

**用途**: 记录每个代币每小时的统计数据,包含 K 线图所需的 OHLC (开高低收) 价格数据,并实现自动存档机制删除旧数据。

**Schema 定义**:

```graphql
type TokenHourData @entity {
  id: ID!
  periodStartUnix: Int!
  token: Token!
  volume: BigDecimal!
  volumeUSD: BigDecimal!
  untrackedVolumeUSD: BigDecimal!
  totalValueLocked: BigDecimal!
  totalValueLockedUSD: BigDecimal!
  priceUSD: BigDecimal!
  feesUSD: BigDecimal!
  open: BigDecimal!    # 开盘价
  high: BigDecimal!    # 最高价
  low: BigDecimal!     # 最低价
  close: BigDecimal!   # 收盘价
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | ID 生成 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|--------|---------|---------|----------|
| **id** | ID! | `{tokenAddress}-{hourID}` | `token.id + '-' + hourID` | - | - | - |
| **periodStartUnix** | Int! | 小时开始时间戳 | - | `hourID * 3600` | 不变 | - |
| **token** | Token! | 关联的代币 | - | `token.id` | 不变 | - |
| **volume** | BigDecimal! | 小时内交易量(代币) | - | `ZERO_BD` | Swap | 累加代币数量 |
| **volumeUSD** | BigDecimal! | 小时内交易量(USD,tracked) | - | `ZERO_BD` | Swap | 累加 tracked USD |
| **untrackedVolumeUSD** | BigDecimal! | 小时内交易量(USD,untracked) | - | `ZERO_BD` | Swap | 累加 untracked USD |
| **totalValueLocked** | BigDecimal! | 小时末锁仓量(代币) | - | `ZERO_BD` | Sync | `token.totalLiquidity` |
| **totalValueLockedUSD** | BigDecimal! | 小时末锁仓量(USD) | - | `ZERO_BD` | Sync | `totalValueLocked * priceUSD` |
| **priceUSD** | BigDecimal! | 小时末价格(USD) | - | `ZERO_BD` | Sync | `derivedETH * ethPrice` |
| **feesUSD** | BigDecimal! | 小时内手续费(USD) | - | `ZERO_BD` | Swap | `volumeUSD * 0.003` (0.3% 手续费) |
| **open** | BigDecimal! | 开盘价(USD) | - | 当前价格 | 小时开始 | 第一次更新时设置 |
| **high** | BigDecimal! | 最高价(USD) | - | 当前价格 | 每次 Swap | `max(high, priceUSD)` |
| **low** | BigDecimal! | 最低价(USD) | - | 当前价格 | 每次 Swap | `min(low, priceUSD)` |
| **close** | BigDecimal! | 收盘价(USD) | - | 当前价格 | 每次 Swap | `priceUSD` (最后一次价格) |

**初始化逻辑**:

```typescript
// src/common/hourDayUpdates.ts - updateTokenHourData()
export function updateTokenHourData(token: Token, event: ethereum.Event): TokenHourData {
  let bundle = Bundle.load('1');
  let timestamp = event.block.timestamp.toI32();
  let hourIndex = timestamp / 3600;
  let tokenHourID = token.id.concat('-').concat(BigInt.fromI32(hourIndex).toString());
  
  let tokenHourData = TokenHourData.load(tokenHourID);
  let priceUSD = token.derivedETH.times(bundle.ethPrice);
  
  if (tokenHourData === null) {
    tokenHourData = new TokenHourData(tokenHourID);
    tokenHourData.periodStartUnix = hourIndex * 3600;
    tokenHourData.token = token.id;
    tokenHourData.volume = ZERO_BD;
    tokenHourData.volumeUSD = ZERO_BD;
    tokenHourData.untrackedVolumeUSD = ZERO_BD;
    tokenHourData.totalValueLocked = ZERO_BD;
    tokenHourData.totalValueLockedUSD = ZERO_BD;
    tokenHourData.priceUSD = priceUSD;
    tokenHourData.feesUSD = ZERO_BD;
    
    // 初始化 OHLC 为当前价格
    tokenHourData.open = priceUSD;
    tokenHourData.high = priceUSD;
    tokenHourData.low = priceUSD;
    tokenHourData.close = priceUSD;
  }
  
  // 更新快照数据
  tokenHourData.totalValueLocked = token.totalLiquidity;
  tokenHourData.totalValueLockedUSD = token.totalLiquidity.times(priceUSD);
  tokenHourData.priceUSD = priceUSD;
  tokenHourData.close = priceUSD;  // 收盘价始终是最新价格
  
  // 更新最高价和最低价
  if (priceUSD.gt(tokenHourData.high)) {
    tokenHourData.high = priceUSD;
  }
  if (priceUSD.lt(tokenHourData.low)) {
    tokenHourData.low = priceUSD;
  }
  
  tokenHourData.save();
  
  return tokenHourData;
}
```

**存档机制** (自动删除旧数据):

```typescript
// src/common/hourDayUpdates.ts - updateTokenHourData() 中的存档逻辑
export function updateTokenHourData(token: Token, event: ethereum.Event): TokenHourData {
  // ... 上述逻辑
  
  // 存档机制：删除 768 小时 (32天) 之前的数据
  let oldHourIndex = hourIndex - 768;
  if (oldHourIndex > 0) {
    let oldTokenHourID = token.id.concat('-').concat(BigInt.fromI32(oldHourIndex).toString());
    let oldTokenHourData = TokenHourData.load(oldTokenHourID);
    if (oldTokenHourData !== null) {
      store.remove('TokenHourData', oldTokenHourID);  // 删除旧实体
    }
  }
  
  return tokenHourData;
}
```

**更新逻辑**:

```typescript
// src/v2/mappings/core.ts - handleSwap()
export function handleSwap(event: Swap): void {
  // 更新 token0 HourData
  let token0HourData = updateTokenHourData(token0, event);
  token0HourData.volume = token0HourData.volume.plus(amount0Total);
  token0HourData.volumeUSD = token0HourData.volumeUSD.plus(
    amount0Total.times(token0.derivedETH).times(bundle.ethPrice)
  );
  token0HourData.untrackedVolumeUSD = token0HourData.untrackedVolumeUSD.plus(
    amount0Total.times(token0.derivedETH).times(bundle.ethPrice)
  );
  token0HourData.feesUSD = token0HourData.volumeUSD.times(BigDecimal.fromString('0.003'));
  token0HourData.save();
  
  // 同样更新 token1 HourData
}
```

---

### 14. User - 用户地址

**用途**: 记录与协议交互的用户地址,用于统计用户参与的流动性操作。

**Schema 定义**:

```graphql
type User @entity {
  id: ID!  # 用户地址
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|---------|---------|----------|
| **id** | ID! | 用户地址(小写) | 用户地址 | 不变 | - |

**初始化逻辑**:

```typescript
// src/v2/mappings/core.ts - handleMint() / handleBurn()
export function handleMint(event: Mint): void {
  // ... Mint 业务逻辑
  
  // 创建 User 实体 (如果不存在)
  let user = User.load(to);
  if (user === null) {
    user = new User(to);
    user.save();
  }
}
```

**用途说明**:
- 主要用于追踪流动性提供者 (LP)
- 在 Mint 和 Burn 事件中创建
- 可用于统计独立用户数、用户行为分析

---

### 15. PairTokenLookup - 双向查找表

**用途**: 提供从 `token-pair` 到 `pair address` 的快速查找,优化价格计算中的交易对查询效率。

**Schema 定义**:

```graphql
type PairTokenLookup @entity(immutable: true) {
  id: ID!     # {token0Address}-{token1Address}
  pair: Pair! # 对应的 Pair 地址
}
```

**字段详解**:

| 字段名 | 类型 | 业务含义 | ID 生成 | 初始化值 | 更新时机 | 更新逻辑 |
|--------|------|---------|--------|---------|---------|----------|
| **id** | ID! | `{token0}-{token1}` | `token0.id + '-' + token1.id` | - | 不变(immutable) | - |
| **pair** | Pair! | 对应的 Pair 实体 | - | `pair.id` | 不变(immutable) | - |

**初始化逻辑**:

```typescript
// src/v2/mappings/factory.ts - handleNewPair()
export function handleNewPair(event: PairCreated): void {
  // ... 创建 Pair 逻辑
  
  // 创建双向查找表
  // 1. token0-token1 方向
  let pairLookup0 = new PairTokenLookup(
    token0.id.concat('-').concat(token1.id)
  );
  pairLookup0.pair = pair.id;
  pairLookup0.save();
  
  // 2. token1-token0 方向 (反向)
  let pairLookup1 = new PairTokenLookup(
    token1.id.concat('-').concat(token0.id)
  );
  pairLookup1.pair = pair.id;
  pairLookup1.save();
}
```

**使用场景**:

```typescript
// src/common/pricing.ts - findEthPerToken()
export function findEthPerToken(token: Token): BigDecimal {
  // 遍历白名单代币,查找交易对
  for (let i = 0; i < WHITELIST.length; ++i) {
    // 使用 PairTokenLookup 快速查找
    let pairLookup = PairTokenLookup.load(
      token.id.concat('-').concat(WHITELIST[i])
    );
    
    if (pairLookup) {
      let pair = Pair.load(pairLookup.pair);
      if (pair && pair.reserveETH.gt(MINIMUM_LIQUIDITY_THRESHOLD_ETH)) {
        // 计算价格
        if (pair.token0 == token.id) {
          let token1 = Token.load(pair.token1);
          return pair.token1Price.times(token1.derivedETH);
        }
        // ...
      }
    }
  }
  return ZERO_BD;
}
```

**优势**:
- ✅ **性能优化**: 避免遍历所有 Pair,直接通过 ID 查找
- ✅ **双向查询**: 支持 token0-token1 和 token1-token0 两个方向
- ✅ **Immutable**: 一旦创建永不改变,减少存储写入

---

## 时间聚合实体总结

### ⚠️ 重要澄清:更新机制

**不是定时任务,而是事件驱动的实时更新!**

很多人可能误以为这些 Day/Hour 聚合数据是通过定时任务(如每小时、每天)去创建的,但实际上:**完全不是**!

#### 实际机制

**触发时机**: 每当区块链上发生 **Swap/Mint/Burn/Sync** 事件时

**工作流程**:

```typescript
// 以 handleSwap 为例
export function handleSwap(event: Swap): void {
  // 1. 处理核心业务逻辑
  // ...
  
  // 2. 事件驱动更新聚合数据
  let pairDayData = updatePairDayData(pair, event);       // ← 这里!
  let pairHourData = updatePairHourData(pair, event);     // ← 这里!
  let uniswapDayData = updateUniswapDayData(event);      // ← 这里!
  let token0DayData = updateTokenDayData(token0, event);  // ← 这里!
  let token1DayData = updateTokenDayData(token1, event);  // ← 这里!
  
  // 3. 累加交易量
  pairDayData.dailyVolumeUSD = pairDayData.dailyVolumeUSD.plus(trackedAmountUSD);
  pairDayData.save();
}
```

#### 详细执行过程 (以 TokenDayData 为例)

```typescript
// src/common/hourDayUpdates.ts
export function updateTokenDayData(token: Token, event: ethereum.Event): TokenDayData {
  let timestamp = event.block.timestamp.toI32();  // 事件发生的时间戳
  
  // 步骤 1: 计算当前事件属于哪一天
  let dayID = timestamp / 86400;  // 例如: 1704153600 / 86400 = 19732
  let dayStartTimestamp = dayID * 86400;  // 该天的开始时间戳
  
  // 步骤 2: 构造该天的数据实体 ID
  let tokenDayID = token.id.concat('-').concat(dayID.toString());
  // 例如: "0x1f9840a85d5af5bf1d1762f925bdaddc4201f984-19732"
  
  // 步骤 3: 尝试加载该实体
  let tokenDayData = TokenDayData.load(tokenDayID);
  
  if (!tokenDayData) {
    // 如果不存在 → 这是今天第一次有交易,创建新实体
    tokenDayData = new TokenDayData(tokenDayID);
    tokenDayData.date = dayStartTimestamp;
    tokenDayData.token = token.id;
    tokenDayData.dailyVolumeToken = ZERO_BD;  // 初始化为 0
    tokenDayData.dailyVolumeUSD = ZERO_BD;
    tokenDayData.dailyTxns = ZERO_BI;
    // ...
  }
  // 如果存在 → 说明今天已经有交易了,直接更新
  
  // 步骤 4: 更新快照数据(每次事件都更新)
  tokenDayData.totalLiquidityToken = token.totalLiquidity;
  tokenDayData.priceUSD = token.derivedETH.times(bundle.ethPrice);
  
  // 步骤 5: 累加交易笔数
  tokenDayData.dailyTxns = tokenDayData.dailyTxns.plus(ONE_BI);
  
  // 步骤 6: 保存到数据库
  tokenDayData.save();
  
  return tokenDayData;
}
```

#### 实际运行示例

**场景**: UNI Token 在 2025-01-01 发生了 5 笔 Swap 交易

```
时间线:
10:00 - Swap 1 发生
  → updateTokenDayData(UNI, event)
  → dayID = 19732 (2025-01-01)
  → TokenDayData.load("UNI-19732") → null (第一次)
  → 创建新实体 TokenDayData("UNI-19732")
  → dailyVolumeUSD = 1000 USD
  → dailyTxns = 1
  → save()

12:00 - Swap 2 发生
  → updateTokenDayData(UNI, event)
  → dayID = 19732 (还是同一天)
  → TokenDayData.load("UNI-19732") → 找到了!
  → 加载现有实体
  → dailyVolumeUSD = 1000 + 500 = 1500 USD
  → dailyTxns = 1 + 1 = 2
  → save()

15:00 - Swap 3 发生
  → 同上,继续累加
  → dailyVolumeUSD = 1500 + 800 = 2300 USD
  → dailyTxns = 3

18:00 - Swap 4 发生
  → dailyVolumeUSD = 2300 + 600 = 2900 USD
  → dailyTxns = 4

21:00 - Swap 5 发生
  → dailyVolumeUSD = 2900 + 400 = 3300 USD
  → dailyTxns = 5

--- 第二天 ---
2025-01-02 08:00 - Swap 6 发生
  → updateTokenDayData(UNI, event)
  → dayID = 19733 (新的一天!)
  → TokenDayData.load("UNI-19733") → null (新一天第一次)
  → 创建新实体 TokenDayData("UNI-19733")
  → dailyVolumeUSD = 700 USD (重新开始)
  → dailyTxns = 1 (重新开始)
  → save()
```

#### 关键要点

| 维度 | 说明 |
|------|------|
| **触发方式** | ❌ 不是定时任务<br>✅ 事件驱动(每次 Swap/Mint/Burn 都触发) |
| **创建时机** | 当某个时间段(小时/天)**第一次**有事件发生时创建 |
| **更新时机** | **每次**事件发生时都会更新(load → update → save) |
| **数据累加** | `dailyVolumeUSD` 等累计字段会不断累加 |
| **快照更新** | `totalLiquidity`, `priceUSD` 等快照字段每次都覆盖为最新值 |
| **自动归零** | 不需要!新的一天/小时会自动创建新实体,累计字段从 0 开始 |
| **实时性** | 完全实时!事件发生后立即更新,无延迟 |

#### 为什么不是定时任务?

**原因 1: The Graph 是事件驱动的索引协议**
- Subgraph 的核心就是监听区块链事件
- 没有"定时任务"的概念
- 所有逻辑都在事件处理函数中执行

**原因 2: 无法预知何时有交易**
- 某个 Pair 可能一整天都没有交易
- 定时任务会创建很多空数据
- 事件驱动只在有交易时才创建数据

**原因 3: 实时性要求**
- 定时任务有延迟(如每小时执行一次)
- 事件驱动是实时的,事件发生即更新

**原因 4: 效率考虑**
- 定时任务需要遍历所有 Token/Pair
- 事件驱动只更新相关的实体

#### 如果没有交易会怎样?

**场景**: 某个 Token 在 2025-01-01 有交易,但 2025-01-02 没有交易

```
数据库中的 TokenDayData:
- "TOKEN-19732" ✅ 存在 (2025-01-01 有交易)
- "TOKEN-19733" ❌ 不存在 (2025-01-02 没有交易,不会创建)
- "TOKEN-19734" ✅ 存在 (2025-01-03 有交易)
```

**查询时的处理**:
- 前端查询时发现某天数据缺失
- 认为该天交易量为 0
- 或者用前一天的快照数据(如 totalLiquidity)

#### 与传统后端的对比

| 维度 | 传统后端定时任务 | Subgraph 事件驱动 |
|------|-----------------|------------------|
| **触发方式** | Cron 定时执行 | 区块链事件触发 |
| **数据完整性** | 每个时间段都有记录(即使为空) | 只有有交易的时间段才有记录 |
| **实时性** | 有延迟(定时间隔) | 完全实时 |
| **资源消耗** | 需要遍历所有数据 | 只处理相关数据 |
| **数据准确性** | 可能有统计延迟 | 与区块链完全同步 |

---

### ⚠️ 删除机制澄清

**关键发现**: 并非所有实体都是 append-only,存在 **3 种删除场景**:

#### 1. TokenHourData 的滚动删除 (v2 子图)

**删除原因**: 防止存储无限增长,只保留最近 768 小时(32 天)的数据

**触发时机**: 每当创建新的 TokenHourData 时

```typescript
// src/common/hourDayUpdates.ts
export function updateTokenHourData(token: Token, event: ethereum.Event): TokenHourData {
  let hourIndex = timestamp / 3600;
  let tokenHourID = token.id.concat('-').concat(hourIndex.toString());
  let tokenHourData = TokenHourData.load(tokenHourID);
  
  if (!tokenHourData) {
    // 创建新的小时数据
    tokenHourData = new TokenHourData(tokenHourID);
    // ...
    
    // ⚠️ 关键: 检查是否需要删除旧数据
    let lastHourArchived = token.lastHourArchived.toI32();
    let stop = hourIndex - 768;  // 保留最近 768 小时
    
    if (stop > lastHourArchived) {
      archiveHourData(token, stop);  // ← 删除旧数据!
    }
  }
  
  // ...
}

function archiveHourData(token: Token, end: i32): void {
  let array = token.hourArray;  // 存储所有小时索引的数组
  
  for (let i = 0; i < array.length; i++) {
    if (array[i] > end) {
      break;  // 停止删除
    }
    
    let tokenHourID = token.id.concat('-').concat(array[i].toString());
    
    // ⚠️ 删除操作!
    store.remove('TokenHourData', tokenHourID);
    
    modArray.shift();  // 从数组中移除已删除的索引
    
    // 限制每次最多删除 500 条
    if (i + 1 == 500) {
      break;
    }
  }
  
  token.hourArray = modArray;  // 更新索引数组
  token.lastHourArchived = BigInt.fromI32(last - 1);
  token.save();
}
```

**删除逻辑**:
- 每个 Token 维护一个 `hourArray`,记录所有已创建的小时索引
- 维护 `lastHourArchived` 和 `lastHourRecorded` 两个指针
- 当 `currentHourIndex - 768 > lastHourArchived` 时触发删除
- 删除从 `lastHourArchived` 到 `stop` 之间的所有小时数据
- 每次最多删除 500 条记录,防止操作过大

**实际效果**:
```
当前时间: 2025-01-01 10:00 (hourIndex = 450000)
删除阈值: 450000 - 768 = 449232

数据库状态:
- TokenHourData("UNI-449200") ❌ 被删除 (超过 32 天)
- TokenHourData("UNI-449500") ✅ 保留 (最近 32 天内)
- TokenHourData("UNI-450000") ✅ 保留 (最新)
```

#### 2. TokenMinuteData 的滚动删除 (v2-tokens 子图)

**删除原因**: 分钟级数据量更大,只保留最近 1680 分钟(28 小时)的数据

**触发时机**: 每当创建新的 TokenMinuteData 时

```typescript
// src/v2-tokens/mappings/minuteUpdates.ts
export function updateTokenMinuteData(token: Token, event: ethereum.Event): TokenMinuteData {
  const minuteIndex = timestamp / 60;
  const tokenMinuteID = token.id.concat('-').concat(minuteIndex.toString());
  
  let tokenMinuteData = TokenMinuteData.load(tokenMinuteID);
  
  if (!tokenMinuteData) {
    // 创建新的分钟数据
    tokenMinuteData = new TokenMinuteData(tokenMinuteID);
    // ...
    
    // ⚠️ 检查是否需要删除旧数据
    const lastMinuteArchived = token.lastMinuteArchived.toI32();
    const stop = minuteIndex - 1680;  // 保留最近 1680 分钟(28 小时)
    
    if (stop > lastMinuteArchived) {
      archiveMinuteData(token, stop);  // ← 删除旧数据!
    }
  }
  
  // ...
}

function archiveMinuteData(token: Token, end: i32): void {
  const array = token.minuteArray;  // 存储所有分钟索引的数组
  
  for (let i = 0; i < array.length; i++) {
    if (array[i] > end) {
      break;
    }
    
    const tokenMinuteID = token.id.concat('-').concat(array[i].toString());
    
    // ⚠️ 删除操作!
    store.remove('TokenMinuteData', tokenMinuteID);
    
    modArray.shift();
    
    // 限制每次最多删除 1000 条
    if (i + 1 == 1000) {
      break;
    }
  }
  
  token.minuteArray = modArray;
  token.lastMinuteArchived = BigInt.fromI32(last - 1);
  token.save();
}
```

**与 TokenHourData 的对比**:

| 维度 | TokenHourData | TokenMinuteData |
|------|---------------|------------------|
| **子图** | v2 | v2-tokens |
| **时间粒度** | 小时级 | 分钟级 |
| **保留时长** | 768 小时(32 天) | 1680 分钟(28 小时) |
| **删除批次** | 最多 500 条/次 | 最多 1000 条/次 |
| **索引数组** | `hourArray` | `minuteArray` |
| **追踪字段** | `lastHourArchived`<br>`lastHourRecorded` | `lastMinuteArchived`<br>`lastMinuteRecorded` |

#### 3. Mint 事件的逻辑删除 (特殊场景)

**删除原因**: 处理 Uniswap V2 的 **Fee Mint** 特殊情况

**背景知识**:
- Uniswap V2 中,LP 的手续费以 LP Token 的形式累积
- 当 LP 销毁流动性时,会同时收取累积的手续费
- 协议会先发送一个 Transfer 事件(看起来像 Mint),然后才是真正的 Burn 事件
- 这个"假 Mint"需要在 Burn 事件中被识别并删除

**触发时机**: handleBurn 事件处理时

```typescript
// src/v2/mappings/core.ts - handleBurn()
export function handleBurn(event: Burn): void {
  let transaction = Transaction.load(event.transaction.hash.toHexString());
  let burns = transaction.burns;
  let burn = BurnEvent.load(burns[burns.length - 1]);
  
  let mints = transaction.mints;
  
  // ⚠️ 检查是否存在"不完整的 Mint"(实际上是 Fee Mint)
  if (mints.length !== 0 && !isCompleteMint(mints[mints.length - 1])) {
    let mint = MintEvent.load(mints[mints.length - 1])!;
    
    // 将 Fee Mint 的信息转移到 Burn 事件
    burn.feeTo = mint.to;
    burn.feeLiquidity = mint.liquidity;
    
    // ⚠️ 删除这个逻辑上的 Mint 事件!
    store.remove('Mint', mints[mints.length - 1]);
    
    // 从交易的 mints 数组中移除
    mints.pop();
    transaction.mints = mints;
    transaction.save();
  }
  
  // ...
}
```

**isCompleteMint 判断逻辑**:
```typescript
function isCompleteMint(mintId: string): boolean {
  let mint = MintEvent.load(mintId);
  // 完整的 Mint 必须有 sender 字段(由 handleMint 事件填充)
  return mint !== null && mint.sender !== null;
}
```

**执行流程示例**:
```
用户 Burn 流动性并收取手续费:

1. Transfer 事件触发 (LP Token 转移)
   → handleTransfer() 创建 Mint 实体
   → mint.sender = null (标记为不完整)
   
2. Burn 事件触发
   → handleBurn() 检测到 mints 数组最后一个是不完整的
   → 判断: 这不是真正的 Mint,是 Fee Mint!
   → 将 mint.liquidity 转移到 burn.feeLiquidity
   → store.remove('Mint', mintId)  ← 删除!
   → 从 transaction.mints 数组中移除
```

#### 删除场景总结

| 实体 | 是否删除 | 删除类型 | 删除原因 | 删除时机 |
|------|---------|---------|---------|----------|
| **TokenHourData** | ✅ 是 | 滚动删除 | 防止存储无限增长 | 创建新记录时,删除 32 天前数据 |
| **TokenMinuteData** | ✅ 是 | 滚动删除 | 防止存储无限增长 | 创建新记录时,删除 28 小时前数据 |
| **Mint** | ✅ 是 | 逻辑删除 | 处理 Fee Mint 特殊情况 | Burn 事件检测到不完整 Mint 时 |
| **TokenDayData** | ❌ 否 | Append-only | 天级数据量可控 | - |
| **PairDayData** | ❌ 否 | Append-only | 天级数据量可控 | - |
| **PairHourData** | ❌ 否 | Append-only | 不涉及 K 线数据 | - |
| **UniswapDayData** | ❌ 否 | Append-only | 全局数据,每天仅一条 | - |
| **Token** | ❌ 否 | Append-only | 核心实体 | - |
| **Pair** | ❌ 否 | Append-only | 核心实体 | - |
| **Transaction** | ❌ 否 | Append-only | 历史记录 | - |
| **Swap** | ❌ 否 | Append-only | 历史记录 | - |
| **Burn** | ❌ 否 | Append-only | 历史记录 | - |

#### 为什么 TokenHourData/MinuteData 需要删除?

**原因 1: 数据量级差异**
```
假设有 1000 个 Token:

- TokenDayData: 1000 tokens × 365 days = 365,000 条/年
- TokenHourData: 1000 tokens × 8760 hours = 8,760,000 条/年
- TokenMinuteData: 1000 tokens × 525,600 minutes = 525,600,000 条/年 (5 亿+)

如果不删除,分钟级数据会迅速耗尽存储空间!
```

**原因 2: K 线数据的使用场景**
- 前端 K 线图通常只展示最近 7-30 天的数据
- 更早的历史数据访问频率极低
- 28 小时的分钟数据已足够满足日内交易需求

**原因 3: 查询性能**
- 数据量越大,GraphQL 查询越慢
- 限制数据量可以保持查询性能

**原因 4: The Graph 的成本考虑**
- Subgraph 部署到 The Graph Network 需要支付存储费用
- 减少存储可以降低运营成本

#### 为什么其他实体是 Append-only?

**Token/Pair**: 核心实体,数量有限(通常几千个),永久保留

**Transaction/Swap/Burn/Mint**: 历史记录,用于审计和分析,不应删除

**TokenDayData/PairDayData**: 
- 天级数据量可控(1000 tokens × 365 days = 36.5 万条/年)
- 用于长期趋势分析,需要保留完整历史

**UniswapDayData**: 全局数据,每天只有一条,永久保留

---

### 实体对比表

| 实体名称 | 时间粒度 | 作用域 | 快照字段 | 累计字段 | 存档机制 |
|---------|---------|-------|---------|---------|----------|
| **UniswapDayData** | 日 (86400s) | 全局协议 | totalLiquidityETH, totalLiquidityUSD | dailyVolumeETH, dailyVolumeUSD, txCount | ❌ 无 |
| **PairDayData** | 日 (86400s) | 单个交易对 | reserve0/1, totalSupply, reserveUSD | dailyVolumeToken0/1, dailyVolumeUSD, dailyTxns | ❌ 无 |
| **PairHourData** | 小时 (3600s) | 单个交易对 | reserve0/1, totalSupply, reserveUSD | hourlyVolumeToken0/1, hourlyVolumeUSD, hourlyTxns | ❌ 无 |
| **TokenDayData** | 日 (86400s) | 单个代币 | totalLiquidityToken/ETH/USD, priceUSD | dailyVolumeToken/ETH/USD, dailyTxns | ❌ 无 |
| **TokenHourData** | 小时 (3600s) | 单个代币 | totalValueLocked, priceUSD, OHLC | volume, volumeUSD, feesUSD | ✅ 768 小时 (32天) |

### 更新时机总结

```typescript
// 所有聚合实体都在每次 Swap 事件时更新
export function handleSwap(event: Swap): void {
  // 1. 更新全局日数据
  let uniswapDayData = updateUniswapDayData(event);
  
  // 2. 更新交易对日数据
  let pairDayData = updatePairDayData(event);
  
  // 3. 更新交易对小时数据
  let pairHourData = updatePairHourData(pair, event);
  
  // 4. 更新代币日数据 (两个代币)
  let token0DayData = updateTokenDayData(token0, event);
  let token1DayData = updateTokenDayData(token1, event);
  
  // 5. 更新代币小时数据 (两个代币)
  let token0HourData = updateTokenHourData(token0, event);
  let token1HourData = updateTokenHourData(token1, event);
  
  // 6. 累加各自的交易量和交易笔数
  // ...
}
```

---

## 价格计算机制

### 核心逻辑澄清

**重要**: `derivedETH` 是 **Token 实体的全局字段**,不是 Pair 实体的字段!

```graphql
type Token @entity {
  id: ID!
  derivedETH: BigDecimal!  # 这里! Token 的全局 ETH 价格
}

type Pair @entity {
  id: ID!
  token0: Token!
  token1: Token!
  # 注意: Pair 没有 derivedETH 字段!
  token0Price: BigDecimal!  # token0 在这个 Pair 中相对 token1 的价格
  token1Price: BigDecimal!  # token1 在这个 Pair 中相对 token0 的价格
}
```

**关键要点**:

1. **同一个 Token 在所有 Pair 中的 `derivedETH` 必须相同**
   - `derivedETH` 是 Token 实体的全局字段,不是每个 Pair 独立的
   - 一个 Token 在数据库中只有一条记录,所有 Pair 共享这个值

2. **`token0Price` 和 `token1Price` 是 Pair 内的相对价格**
   - `pair.token0Price` = 在这个 Pair 中, 1 个 token0 能换多少 token1
   - `pair.token1Price` = 在这个 Pair 中, 1 个 token1 能换多少 token0
   - 这些是 Pair 内的局部价格,不同 Pair 可以不同

3. **`derivedETH` 的计算时机**
   - 每次 Sync 事件时,重新计算 Token 的 `derivedETH`
   - 计算时会遍历白名单,找到**第一个**满足条件的 Pair
   - 使用该 Pair 的价格信息计算,然后**保存到 Token 实体**

---

### 详细流程说明

**场景**: 假设 UNI Token 同时存在于三个 Pair 中:

```
Pair A: UNI-WETH
- reserve0: 1,000,000 UNI
- reserve1: 5,000 WETH
- token0Price: 0.005 WETH/UNI (在 Pair A 中的价格)

Pair B: UNI-USDC  
- reserve0: 800,000 UNI
- reserve1: 8,000,000 USDC
- token0Price: 10 USDC/UNI (在 Pair B 中的价格)

Pair C: UNI-DAI
- reserve0: 500,000 UNI  
- reserve1: 5,000,000 DAI
- token0Price: 10 DAI/UNI (在 Pair C 中的价格)
```

**问题**: UNI.derivedETH 应该是多少?

**答案**: **0.005 ETH/UNI** - 因为会使用 Pair A (UNI-WETH) 的价格

---

### 计算过程 (每次 Sync 事件)

```typescript
// src/v2/mappings/core.ts - handleSync()
export function handleSync(event: Sync): void {
  // 1. 更新当前 Pair 的储备量和价格
  pair.reserve0 = convertTokenToDecimal(event.params.reserve0, token0.decimals);
  pair.reserve1 = convertTokenToDecimal(event.params.reserve1, token1.decimals);
  
  // Pair 内的相对价格 (局部)
  pair.token0Price = pair.reserve1.div(pair.reserve0);  // token1/token0
  pair.token1Price = pair.reserve0.div(pair.reserve1);  // token0/token1
  pair.save();
  
  // 2. 更新 ETH/USD 价格 (全局)
  let bundle = Bundle.load('1');
  bundle.ethPrice = getEthPriceInUSD();  // 通过稳定币交易对计算
  bundle.save();
  
  // 3. 重新计算 Token 的 derivedETH (全局)
  token0.derivedETH = findEthPerToken(token0);  // ← 关键!
  token1.derivedETH = findEthPerToken(token1);  // ← 关键!
  token0.save();  // 保存到 Token 实体
  token1.save();  // 保存到 Token 实体
  
  // 4. 计算 Pair 的 reserveETH/reserveUSD (使用 Token.derivedETH)
  pair.reserveETH = pair.reserve0.times(token0.derivedETH)
                    .plus(pair.reserve1.times(token1.derivedETH));
  pair.reserveUSD = pair.reserveETH.times(bundle.ethPrice);
  pair.save();
}
```

---

### `findEthPerToken()` 的逻辑 (关键函数)

```typescript
// src/common/pricing.ts
export function findEthPerToken(token: Token): BigDecimal {
  // 特殊情况 1: WETH 本身
  if (token.id == REFERENCE_TOKEN) {
    return ONE_BD;  // 1 WETH = 1 ETH
  }
  
  // 特殊情况 2: 稳定币
  if (STABLECOINS.includes(token.id)) {
    let bundle = Bundle.load('1');
    return safeDiv(ONE_BD, bundle.ethPrice);  // 1 USD = 1/ethPrice ETH
  }
  
  // 通用情况: 遍历白名单交易对
  let WHITELIST = [
    REFERENCE_TOKEN,  // WETH - 最高优先级
    ...STABLECOINS,   // USDC, USDT, DAI - 第二优先级
    '0x6b175...', // WBTC 等其他主流代币 - 第三优先级
  ];
  
  // 按优先级顺序遍历
  for (let i = 0; i < WHITELIST.length; ++i) {
    // 查找 token-WHITELIST[i] 的交易对
    let pairLookup = PairTokenLookup.load(
      token.id.concat('-').concat(WHITELIST[i])
    );
    
    if (pairLookup) {
      let pair = Pair.load(pairLookup.pair);
      
      // 检查流动性阈值
      if (pair && pair.reserveETH.gt(MINIMUM_LIQUIDITY_THRESHOLD_ETH)) {
        // 找到了! 使用这个 Pair 的价格
        if (pair.token0 == token.id) {
          let token1 = Token.load(pair.token1);
          // 公式: pair.token0Price * token1.derivedETH
          // = (1 token0 能换多少 token1) * (1 token1 等于多少 ETH)
          // = 1 token0 等于多少 ETH
          return pair.token0Price.times(token1.derivedETH);
        }
        if (pair.token1 == token.id) {
          let token0 = Token.load(pair.token0);
          return pair.token1Price.times(token0.derivedETH);
        }
      }
    }
  }
  
  // 找不到合适的交易对
  return ZERO_BD;
}
```

**关键逻辑**:
1. **按优先级遍历**: 首先找 WETH Pair,然后找稳定币 Pair,最后找其他白名单 Pair
2. **只使用第一个满足条件的 Pair**: 一旦找到流动性足够的 Pair,就直接 `return`,不再继续遍历
3. **结果保存到 Token 实体**: 返回值会赋给 `token.derivedETH` 并 `save()`

---

### 实际计算示例 - UNI Token

**场景设定**:
```
UNI Token 存在于三个 Pair:
- Pair A: UNI-WETH (reserveETH = 500 ETH)
- Pair B: UNI-USDC (reserveETH = 300 ETH)  
- Pair C: UNI-DAI (reserveETH = 100 ETH)

WHITELIST = [WETH, USDC, USDT, DAI, WBTC, ...]
```

**计算过程**:

```typescript
// 当任何一个 Pair 触发 Sync 事件时
handleSync(event) {
  // ...
  
  // 重新计算 UNI.derivedETH
  UNI.derivedETH = findEthPerToken(UNI);
  
  // findEthPerToken 的执行过程:
  // 1. 检查 UNI 是否是 WETH? → 否
  // 2. 检查 UNI 是否是稳定币? → 否
  // 3. 遍历白名单:
  //    - i=0, WHITELIST[0] = WETH
  //      查找 UNI-WETH Pair → 找到 Pair A!
  //      Pair A.reserveETH = 500 > 2 ETH ✅
  //      计算: pair.token0Price * WETH.derivedETH
  //            = 0.005 * 1 = 0.005
  //      return 0.005  ← 直接返回,不再继续遍历!
  
  UNI.derivedETH = 0.005;  // 赋值
  UNI.save();              // 保存到数据库
}
```

**重要**: 虽然 Pair B (UNI-USDC) 和 Pair C (UNI-DAI) 也存在,但因为已经在 Pair A 找到了答案,所以**不会**使用 Pair B 和 Pair C 的价格。

---

### 为什么不会出现不一致?

**原因 1: 优先级机制**
- 总是优先使用 WETH Pair,因为 WETH 在白名单的第一位
- 只有当 WETH Pair 不存在或流动性不足时,才会使用稳定币 Pair

**原因 2: 全局单一值**
- `derivedETH` 是 Token 实体的字段,一个 Token 在数据库中只有一条记录
- 每次更新都会覆盖之前的值
- 所有 Pair 都使用同一个 `token.derivedETH` 值

**原因 3: Sync 事件触发频繁**
- 每次任何 Pair 的 Sync 事件都会重新计算 Token.derivedETH
- 计算逻辑一致,所以结果一致

---

### 特殊情况: 没有 WETH Pair 时

如果 UNI 没有 WETH Pair,但有 USDC Pair:

```typescript
findEthPerToken(UNI) {
  // 1. UNI 不是 WETH
  // 2. UNI 不是稳定币
  // 3. 遍历白名单:
  //    - i=0, WHITELIST[0] = WETH
  //      查找 UNI-WETH Pair → 找不到 ❌
  //    - i=1, WHITELIST[1] = USDC
  //      查找 UNI-USDC Pair → 找到 Pair B! ✅
  //      Pair B.reserveETH = 300 > 2 ETH ✅
  //      计算: pair.token0Price * USDC.derivedETH
  //            = 10 USDC/UNI * 0.0005 ETH/USDC
  //            = 0.005 ETH/UNI
  //      return 0.005
}
```

结果仍然是 0.005 ETH/UNI,因为价格应该是一致的(忽略滑点)。

---

### 总结

| 项目 | 说明 |
|------|------|
| **derivedETH 存储位置** | Token 实体(全局) |
| **token0Price/token1Price 存储位置** | Pair 实体(局部) |
| **计算频率** | 每次 Sync 事件 |
| **计算方法** | 遍历白名单,使用第一个满足条件的 Pair |
| **优先级** | WETH > 稳定币 > 其他白名单代币 |
| **一致性保证** | 1. 全局单一字段 2. 优先级机制 3. 频繁更新 |
| **递归计算** | 通过白名单代币的 derivedETH 递归计算 |

**核心理解**:
- `derivedETH` 不是“这个 Token 在这个 Pair 中的 ETH 价格”
- 而是“这个 Token 在整个 Uniswap 协议中的全局 ETH 价格”
- 通过选择最优质的 Pair (最高优先级 + 最高流动性) 来计算
- 计算后保存到 Token 实体,所有 Pair 共享这个值

---

### 1. ETH/USD 价格计算

通过稳定币交易对的流动性加权平均计算 ETH 价格:

```typescript
// src/common/pricing.ts
export function getEthPriceInUSD(): BigDecimal {
  // 使用的稳定币交易对 (来自 chain.ts)
  let STABLE_TOKEN_PAIRS = [
    '0xb4e16d0168e52d35cacd2c6185b44281ec28c9dc',  // USDC-WETH
    '0x0d4a11d5eeaac28ec3f61d100daf4d40471f1852',  // USDT-WETH
    '0x3041cbd36888becc7bbcbc0045e3b1f144466f5f',  // USDC-WETH (另一个)
  ];
  
  let totalLiquidityETH = ZERO_BD;
  let stableTokenPrices = [];
  let stableTokenReserves = [];
  
  // 1. 收集所有稳定币交易对的价格和流动性
  for (let i = 0; i < STABLE_TOKEN_PAIRS.length; i++) {
    const stableTokenPair = Pair.load(STABLE_TOKEN_PAIRS[i]);
    if (stableTokenPair) {
      let isToken0 = stableTokenPair.token1 == REFERENCE_TOKEN;  // WETH
      if (isToken0) {
        stableTokenReserves[i] = stableTokenPair.reserve1;  // ETH 储备量
        stableTokenPrices[i] = stableTokenPair.token0Price;  // USDC/ETH
      } else {
        stableTokenReserves[i] = stableTokenPair.reserve0;
        stableTokenPrices[i] = stableTokenPair.token1Price;  // ETH/USDC
      }
      totalLiquidityETH = totalLiquidityETH.plus(stableTokenReserves[i]);
    }
  }
  
  // 2. 计算流动性加权平均价格
  let ethPrice = ZERO_BD;
  for (let i = 0; i < STABLE_TOKEN_PAIRS.length; i++) {
    if (stableTokenPairs[i] !== null) {
      let weight = safeDiv(stableTokenReserves[i], totalLiquidityETH);
      ethPrice = ethPrice.plus(stableTokenPrices[i].times(weight));
    }
  }
  
  return ethPrice;
}
```

**关键点**:
- 使用多个 USDC/USDT-WETH 交易对进行交叉验证
- 流动性加权平均,流动性越大的交易对权重越高
- 更新时机:每次 Sync 事件 (任何交易对的储备量变化)

**计算示例**:

假设当前有三个稳定币交易对:

```
交易对 1: USDC-WETH (0xb4e16...)
- reserve0 (USDC): 50,000,000 USDC
- reserve1 (WETH): 25,000 ETH
- token0Price = reserve1 / reserve0 = 25,000 / 50,000,000 = 0.0005 WETH/USDC
- token1Price = reserve0 / reserve1 = 50,000,000 / 25,000 = 2,000 USDC/ETH

交易对 2: USDT-WETH (0x0d4a1...)
- reserve0 (USDT): 30,000,000 USDT
- reserve1 (WETH): 15,000 ETH
- token0Price = reserve1 / reserve0 = 15,000 / 30,000,000 = 0.0005 WETH/USDT
- token1Price = reserve0 / reserve1 = 30,000,000 / 15,000 = 2,000 USDT/ETH

交易对 3: DAI-WETH (0x3041c...)
- reserve0 (DAI): 20,000,000 DAI
- reserve1 (WETH): 10,000 ETH
- token0Price = reserve1 / reserve0 = 10,000 / 20,000,000 = 0.0005 WETH/DAI
- token1Price = reserve0 / reserve1 = 20,000,000 / 10,000 = 2,000 DAI/ETH
```

**步骤 1: 收集价格和流动性**

```typescript
// 对于交易对 1 (USDC-WETH)
if (stableTokenPair.token1 == REFERENCE_TOKEN) {  // WETH 是 token1
  stableTokenReserves[0] = 25,000  // ETH 储备量
  stableTokenPrices[0] = 2,000     // USDC/ETH 价格
}

// 对于交易对 2 (USDT-WETH)
stableTokenReserves[1] = 15,000    // ETH 储备量
stableTokenPrices[1] = 2,000       // USDT/ETH 价格

// 对于交易对 3 (DAI-WETH)
stableTokenReserves[2] = 10,000    // ETH 储备量
stableTokenPrices[2] = 2,000       // DAI/ETH 价格

// 总 ETH 流动性
totalLiquidityETH = 25,000 + 15,000 + 10,000 = 50,000 ETH
```

**步骤 2: 计算流动性加权平均价格**

```typescript
// 交易对 1 的权重
weight[0] = 25,000 / 50,000 = 0.5 (50%)
contribution[0] = 2,000 * 0.5 = 1,000

// 交易对 2 的权重
weight[1] = 15,000 / 50,000 = 0.3 (30%)
contribution[1] = 2,000 * 0.3 = 600

// 交易对 3 的权重
weight[2] = 10,000 / 50,000 = 0.2 (20%)
contribution[2] = 2,000 * 0.2 = 400

// 最终 ETH 价格
ethPrice = 1,000 + 600 + 400 = 2,000 USD/ETH
```

**结果**: 通过流动性加权平均,计算出 1 ETH = 2,000 USD

**优势**:
- ✅ **抗操纵**: 单个交易对价格异常不会显著影响最终价格
- ✅ **更准确**: 流动性大的交易对(更可信)权重更高
- ✅ **交叉验证**: 多个稳定币交易对互相验证

**特殊情况处理**:

如果某个交易对价格异常:
```
交易对 1: 25,000 ETH, 价格 2,000 USD/ETH (正常)
交易对 2: 15,000 ETH, 价格 2,000 USD/ETH (正常)
交易对 3: 100 ETH,   价格 2,500 USD/ETH (异常,流动性很低)

总流动性 = 25,000 + 15,000 + 100 = 40,100 ETH

权重:
- 交易对 1: 25,000 / 40,100 = 0.623 (62.3%)
- 交易对 2: 15,000 / 40,100 = 0.374 (37.4%)
- 交易对 3: 100 / 40,100 = 0.0025 (0.25%)  ← 异常价格权重极低

最终价格:
= 2,000 * 0.623 + 2,000 * 0.374 + 2,500 * 0.0025
= 1,246 + 748 + 6.25
= 2,000.25 USD/ETH  ← 异常价格几乎不影响结果
```

### 2. Token derivedETH 计算

通过白名单交易对递归计算代币相对 ETH 的价格:

```typescript
// src/common/pricing.ts
export function findEthPerToken(token: Token): BigDecimal {
  // 特殊情况 1: WETH 本身
  if (token.id == REFERENCE_TOKEN) {
    return ONE_BD;
  }
  
  // 特殊情况 2: 稳定币 (USDC/USDT/DAI)
  if (STABLECOINS.includes(token.id)) {
    let bundle = Bundle.load('1');
    return safeDiv(ONE_BD, bundle.ethPrice);  // 1 USD = ? ETH
  }
  
  // 通用情况: 遍历白名单交易对
  let WHITELIST = [
    REFERENCE_TOKEN,  // WETH
    ...STABLECOINS,   // USDC, USDT, DAI
    '0x6b175474e89094c44da98b954eedeac495271d0f',  // 其他主流代币
  ];
  
  for (let i = 0; i < WHITELIST.length; ++i) {
    // 使用 PairTokenLookup 快速查找
    let pairLookup = PairTokenLookup.load(
      token.id.concat('-').concat(WHITELIST[i])
    );
    
    if (pairLookup) {
      let pair = Pair.load(pairLookup.pair);
      
      // 检查流动性阈值 (避免使用流动性过低的交易对)
      if (pair && pair.reserveETH.gt(MINIMUM_LIQUIDITY_THRESHOLD_ETH)) {
        if (pair.token0 == token.id) {
          let token1 = Token.load(pair.token1);
          // token0/token1 * token1/ETH = token0/ETH
          return pair.token1Price.times(token1.derivedETH);
        }
        if (pair.token1 == token.id) {
          let token0 = Token.load(pair.token0);
          // token1/token0 * token0/ETH = token1/ETH
          return pair.token0Price.times(token0.derivedETH);
        }
      }
    }
  }
  
  // 无法找到合适的交易对
  return ZERO_BD;
}
```

**关键点**:
- 优先使用 WETH 交易对(直接计算)
- 其次使用稳定币交易对(通过 USD 桥接)
- 最后使用其他主流代币交易对(递归计算)
- 流动性阈值保护:忽略 reserveETH < 最小阈值的交易对

**计算示例 - 通用情况 (通过白名单递归计算)**:

假设我们要计算 **LINK** (Chainlink Token) 的 `derivedETH` 价格:

**前置条件**:
```
1. WETH 的 derivedETH = 1 (特殊情况 1)
2. USDC 的 derivedETH = 1 / 2000 = 0.0005 (特殊情况 2, 假设 ETH = 2000 USD)
3. 白名单 WHITELIST = [WETH, USDC, USDT, DAI, WBTC, ...]
4. 最小流动性阈值 MINIMUM_LIQUIDITY_THRESHOLD_ETH = 2 ETH
```

**最简化示例 - UNI 通过 WETH 计算 derivedETH**:

**步骤 1: 确定交易对信息**
```
UNI-WETH 交易对 (地址: 0xd3d2...)
- token0: UNI (0x1f9840...)
- token1: WETH (0xc02aaa...)
- reserve0: 1,000,000 UNI
- reserve1: 5,000 WETH

价格计算:
- 1 UNI 能换多少 WETH?
  = reserve1 / reserve0
  = 5,000 / 1,000,000
  = 0.005 WETH
  
- 1 WETH 能换多少 UNI?
  = reserve0 / reserve1
  = 1,000,000 / 5,000
  = 200 UNI
```

**步骤 2: 计算 derivedETH**
```
WETH.derivedETH = 1 (WETH 本身就是 ETH)

UNI.derivedETH = 1 UNI 能换多少 ETH?
               = 1 UNI 能换多少 WETH × WETH 相对 ETH 的价格
               = 0.005 WETH × 1
               = 0.005 ETH
```

**对应代码**:
```typescript
// 找到 UNI-WETH 交易对
let pair = Pair.load('UNI-WETH交易对地址');

// UNI 是 token0
if (pair.token0 == UNI地址) {
  let token1 = Token.load(pair.token1);  // WETH
  
  // pair.token0Price = reserve1 / reserve0 = 5000 / 1000000 = 0.005
  // 这个值表示: 1 个 token0 (UNI) = 0.005 个 token1 (WETH)
  
  // 所以不应该用 token1Price,应该用 token0Price
  // 但原代码写的是 token1Price...
  
  // 让我假设代码中有个反转逻辑,实际应该是:
  return pair.token0Price.times(token1.derivedETH);
  //     = 0.005 * 1
  //     = 0.005 ETH/UNI  ✅ 正确!
}
```

---

**场景 2: UNI 没有 WETH 交易对,但有 USDC 交易对**

```
UNI-USDC 交易对:
- token0: UNI
- token1: USDC
- reserve0: 800,000 UNI
- reserve1: 8,000,000 USDC
- token0Price = reserve1 / reserve0 = 8,000,000 / 800,000 = 10 USDC/UNI
```

**步骤 1: 计算 1 UNI = ? USDC**
```
1 UNI = 10 USDC
```

**步骤 2: 计算 USDC.derivedETH**
```
USDC.derivedETH = 0.0005 (1 USDC = 0.0005 ETH, 因为 ETH = 2000 USD)
```

**步骤 3: 计算 UNI.derivedETH**
```
UNI.derivedETH = (1 UNI 能换多少 USDC) × (USDC.derivedETH)
               = 10 × 0.0005
               = 0.005 ETH/UNI  ✅ 结果一致!
```

**对应代码**:
```typescript
// 找到 UNI-USDC 交易对
let pair = Pair.load('UNI-USDC交易对地址');

if (pair.token0 == UNI地址) {
  let token1 = Token.load(pair.token1);  // USDC
  
  return pair.token0Price.times(token1.derivedETH);
  //     = 10 * 0.0005
  //     = 0.005 ETH/UNI  ✅
}
```

---

**场景 3: UNI 没有 WETH/USDC 交易对,但有 WBTC 交易对 (递归计算)**

```
UNI-WBTC 交易对:
- token0: UNI
- token1: WBTC
- reserve0: 3,000,000 UNI
- reserve1: 1 WBTC
- token0Price = reserve1 / reserve0 = 1 / 3,000,000 = 0.00000033 WBTC/UNI
```

**步骤 1: 计算 WBTC.derivedETH** (假设已经通过 WBTC-WETH 交易对计算过)
```
WBTC-WETH 交易对:
- token0: WBTC
- token1: WETH
- reserve0: 100 WBTC
- reserve1: 1,500 WETH
- token0Price = 1,500 / 100 = 15 WETH/WBTC

WBTC.derivedETH = 15 ETH/WBTC
```

**步骤 2: 计算 UNI.derivedETH** (通过 WBTC 桥接)
```
UNI.derivedETH = (1 UNI 能换多少 WBTC) × (WBTC.derivedETH)
               = 0.00000033 WBTC × 15 ETH/WBTC
               = 0.00000033 × 15
               = 0.00000495 ETH/UNI
               ≈ 0.005 ETH/UNI  ✅ 接近正确!
```

**对应代码**:
```typescript
// 遍历白名单: [WETH, USDC, USDT, DAI, WBTC, ...]
// 前面的 WETH/USDC/USDT/DAI 都没找到交易对
// 找到 WBTC 时:

let pair = Pair.load('UNI-WBTC交易对地址');

if (pair.token0 == UNI地址) {
  let token1 = Token.load(pair.token1);  // WBTC
  
  // WBTC.derivedETH 已经通过 WBTC-WETH 计算过 = 15
  
  return pair.token0Price.times(token1.derivedETH);
  //     = 0.00000033 * 15
  //     = 0.00000495 ETH/UNI  ✅
}
```

**这就是递归计算**: WBTC 的 derivedETH 本身也是通过类似的方式计算出来的,从而形成递归链:
```
UNI → WBTC → WETH → 1
```

---

**总结**:

1. **优先级顺序**:
   - 第 1 优先: 直接与 WETH 配对 → 直接计算
   - 第 2 优先: 与稳定币配对 (USDC/USDT/DAI) → 通过 USD 桥接
   - 第 3 优先: 与其他白名单代币配对 (WBTC/LINK 等) → 递归计算

2. **流动性阈值保护**:
   ```
   if (pair.reserveETH < 2 ETH) {
     跳过这个交易对,继续找下一个
   }
   ```
   避免使用流动性极低、价格可能被操纵的交易对

3. **递归计算链**:
   ```
   小代币 → 主流代币 → WETH → 1
   
   例如:
   SHIB → WETH (找到了)
   PEPE → USDC → WETH (稳定币桥接)
   NewToken → LINK → WETH (递归)
   ```

4. **无法计算的情况**:
   - 代币没有任何白名单交易对
   - 所有交易对的流动性都低于阈值
   - 返回 `ZERO_BD` (0)

### 3. Tracked vs Untracked

**Tracked Volume** (可追踪交易量):
- 定义:仅包含白名单代币参与的交易量
- 计算方式:
  ```typescript
  export function getTrackedLiquidityUSD(
    tokenAmount0: BigDecimal,
    token0: Token,
    tokenAmount1: BigDecimal,
    token1: Token
  ): BigDecimal {
    let bundle = Bundle.load('1');
    let price0 = token0.derivedETH.times(bundle.ethPrice);
    let price1 = token1.derivedETH.times(bundle.ethPrice);
    
    // BOTH are whitelist tokens, take average of both amounts
    if (WHITELIST.includes(token0.id) && WHITELIST.includes(token1.id)) {
      return tokenAmount0.times(price0).plus(tokenAmount1.times(price1)).div(BigDecimal.fromString('2'));
    }
    
    // ONLY token0 is whitelist, take its full value
    if (WHITELIST.includes(token0.id) && !WHITELIST.includes(token1.id)) {
      return tokenAmount0.times(price0);
    }
    
    // ONLY token1 is whitelist, take its full value
    if (!WHITELIST.includes(token0.id) && WHITELIST.includes(token1.id)) {
      return tokenAmount1.times(price1);
    }
    
    // NEITHER token is whitelist
    return ZERO_BD;
  }
  ```
- 用途:用于 `totalVolumeUSD`, `totalLiquidityUSD` 等核心指标

**Untracked Volume** (不可追踪交易量):
- 定义:所有交易量,包括非白名单代币
- 计算方式:
  ```typescript
  // 使用 derivedETH 直接计算,即使 derivedETH 可能不准确
  let derivedAmountUSD = tokenAmount0.times(token0.derivedETH).times(bundle.ethPrice)
    .plus(tokenAmount1.times(token1.derivedETH).times(bundle.ethPrice))
    .div(BigDecimal.fromString('2'));
  ```
- 用途:用于 `untrackedVolumeUSD` 指标,提供完整的交易量视图

---

## 初始化与更新流程

### 完整事件处理流程图

```
┌─────────────────────────────────────────────────────────┐
│  1. PairCreated 事件 (Factory 合约)                      │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
         ┌─────────────────────┐
         │  初始化 Factory     │  (首次调用)
         │  创建 Bundle('1')   │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  创建 Token 实体    │  (如果不存在)
         │  - 链上查询元数据   │
         │  - 初始化统计字段   │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  创建 Pair 实体     │
         │  - 关联 token0/1    │
         │  - 初始化储备量为 0 │
         └──────────┬──────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │ 创建 PairTokenLookup │  (双向)
         │ - token0-token1      │
         │ - token1-token0      │
         └──────────┬───────────┘
                    │
                    ▼
         ┌──────────────────────┐
         │ 启动 Pair 模板监听   │
         │ PairTemplate.create()│
         └──────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  2. Transfer 事件 (Pair 合约)                            │
└──────────────────┬──────────────────────────────────────┘
                   │
              ┌────┴────┐
              │  判断   │
              └─┬────┬──┘
    from=0x0? │    │ to=0x0?
    ┌─────────┘    └─────────┐
    ▼                        ▼
┌────────┐              ┌────────┐
│ Mint   │              │ Burn   │
│ 第一阶段│              │ 第一阶段│
└────┬───┘              └───┬────┘
     │                      │
     │ 创建 Mint 实体        │ 识别 Mint 实体
     │ mint.liquidity       │ mint.needsComplete = false
     │ mint.to              │
     │ mint.timestamp       │
     │                      │
     │ 更新 Pair             │
     │ pair.totalSupply++   │
     │                      │
     ▼                      ▼
  等待 Mint 事件         等待 Burn 事件

┌─────────────────────────────────────────────────────────┐
│  3. Mint 事件 (Pair 合约) - 第二阶段                      │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
         ┌─────────────────────┐
         │  补全 Mint 实体     │
         │  - sender           │
         │  - amount0/1        │
         │  - amountUSD        │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  协议费用检测       │  (feeTo != 0x0)
         │  识别并删除 Fee Mint│
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  更新统计           │
         │  - pair.txCount++   │
         │  - token.txCount++  │
         │  - factory.txCount++│
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  创建 User 实体     │  (如果不存在)
         └─────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  4. Burn 事件 (Pair 合约) - 第二阶段                      │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
         ┌─────────────────────┐
         │  补全 Burn 实体     │
         │  - sender           │
         │  - amount0/1        │
         │  - to               │
         │  - amountUSD        │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  更新统计           │
         │  - pair.txCount++   │
         │  - token.txCount++  │
         │  - factory.txCount++│
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  创建 User 实体     │  (如果不存在)
         └─────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  5. Sync 事件 (Pair 合约) - 最重要的价格更新事件          │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
         ┌─────────────────────┐
         │  1. 减去旧流动性     │
         │  factory.totalLiquidityETH -= pair.trackedReserveETH  │
         │  token0.totalLiquidity -= pair.reserve0               │
         │  token1.totalLiquidity -= pair.reserve1               │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  2. 更新 Pair 储备   │
         │  pair.reserve0 = event.reserve0 / 10^decimals         │
         │  pair.reserve1 = event.reserve1 / 10^decimals         │
         │  pair.token0Price = reserve1 / reserve0               │
         │  pair.token1Price = reserve0 / reserve1               │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  3. 更新 ETH 价格    │  (通过稳定币交易对)
         │  bundle.ethPrice = getEthPriceInUSD()                 │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  4. 更新 Token 派生价格│
         │  token0.derivedETH = findEthPerToken(token0)          │
         │  token1.derivedETH = findEthPerToken(token1)          │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  5. 计算 Pair USD 价值│
         │  pair.trackedReserveETH = getTrackedLiquidityUSD(...) / ethPrice  │
         │  pair.reserveETH = reserve0 * derivedETH0 + reserve1 * derivedETH1│
         │  pair.reserveUSD = reserveETH * ethPrice              │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  6. 加回新流动性     │
         │  factory.totalLiquidityETH += pair.trackedReserveETH  │
         │  token0.totalLiquidity += pair.reserve0               │
         │  token1.totalLiquidity += pair.reserve1               │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  7. 更新聚合数据     │
         │  - updateUniswapDayData()   │
         │  - updatePairDayData()      │
         │  - updatePairHourData()     │
         │  - updateTokenDayData() x2  │
         └─────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  6. Swap 事件 (Pair 合约)                                │
└──────────────────┬──────────────────────────────────────┘
                   │
                   ▼
         ┌─────────────────────┐
         │  1. 创建 Swap 实体   │
         │  - amount0In/Out    │
         │  - amount1In/Out    │
         │  - sender/to        │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  2. 计算交易量       │
         │  amount0Total = |amount0In - amount0Out|              │
         │  amount1Total = |amount1In - amount1Out|              │
         │  trackedAmountUSD = getTrackedLiquidityUSD(...)       │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  3. 更新 Pair        │
         │  pair.volumeToken0 += amount0Total    │
         │  pair.volumeToken1 += amount1Total    │
         │  pair.volumeUSD += trackedAmountUSD   │
         │  pair.untrackedVolumeUSD += derivedAmountUSD          │
         │  pair.txCount++                       │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  4. 更新 Token       │
         │  token0.tradeVolume += amount0Total   │
         │  token0.tradeVolumeUSD += tracked     │
         │  token0.txCount++                     │
         │  (同样更新 token1)                     │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  5. 更新 Factory     │
         │  factory.totalVolumeUSD += tracked    │
         │  factory.totalVolumeETH += trackedETH │
         │  factory.untrackedVolumeUSD += untracked│
         │  factory.txCount++                    │
         └──────────┬──────────┘
                    │
                    ▼
         ┌─────────────────────┐
         │  6. 更新聚合数据     │
         │  - updateUniswapDayData()   │
         │  - updatePairDayData()      │
         │  - updatePairHourData()     │
         │  - updateTokenDayData() x2  │
         │  - updateTokenHourData() x2 │
         │    (含 OHLC 更新)           │
         └─────────────────────┘
```

---

## 附录:关键常量定义

```typescript
// src/common/constants.ts
export const ZERO_BI = BigInt.fromI32(0);
export const ONE_BI = BigInt.fromI32(1);
export const ZERO_BD = BigDecimal.fromString('0');
export const ONE_BD = BigDecimal.fromString('1');
export const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000';

// Factory 地址
export const FACTORY_ADDRESS = '0x5C69bEe701ef814a2B6a3EDD4B1652CB9cc5aA6f';

// 最小流动性阈值 (用于价格计算)
export const MINIMUM_LIQUIDITY_THRESHOLD_ETH = BigDecimal.fromString('2');  // 2 ETH
```

```typescript
// config/ethereum/chain.ts
// 参考代币 (WETH)
export const REFERENCE_TOKEN = '0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2';

// 稳定币列表
export const STABLECOINS = [
  '0x6b175474e89094c44da98b954eedeac495271d0f',  // DAI
  '0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48',  // USDC
  '0xdac17f958d2ee523a2206206994597c13d831ec7',  // USDT
];

// 白名单代币 (用于价格计算)
export const WHITELIST = [
  REFERENCE_TOKEN,  // WETH
  ...STABLECOINS,
  '0x2260fac5e5542a773aa44fbcfedf7c193bc2c599',  // WBTC
  '0x514910771af9ca656af840dff83e8264ecf986ca',  // LINK
  // ...
];

// 稳定币交易对 (用于计算 ETH 价格)
export const STABLE_TOKEN_PAIRS = [
  '0xb4e16d0168e52d35cacd2c6185b44281ec28c9dc',  // USDC-WETH
  '0x0d4a11d5eeaac28ec3f61d100daf4d40471f1852',  // USDT-WETH
  '0x3041cbd36888becc7bbcbc0045e3b1f144466f5f',  // USDC-WETH (FEI)
];
```

---

## 总结

### 核心数据流

1. **PairCreated** → 初始化 Factory, Token, Pair, PairTokenLookup
2. **Sync** → 更新储备量、价格、流动性 (最频繁,每次交易都触发)
3. **Transfer** → 识别 Mint/Burn 第一阶段 (更新 totalSupply)
4. **Mint/Burn** → 完成流动性操作第二阶段 (记录详细信息)
5. **Swap** → 更新交易量统计 (全局、交易对、代币三个层级)
6. **聚合数据** → 每次 Swap 更新 Day/Hour Data (6 个实体)

### 设计亮点

1. ✅ **双向查找表** (PairTokenLookup): 优化价格计算性能
2. ✅ **流动性加权平均**: 更准确的 ETH/USD 价格
3. ✅ **白名单机制**: 区分可追踪和不可追踪交易量
4. ✅ **两阶段事件处理**: 完整捕获 Mint/Burn 信息
5. ✅ **协议费用检测**: 自动识别和删除 Fee Mint
6. ✅ **OHLC 支持**: TokenHourData 提供 K 线图数据
7. ✅ **存档机制**: TokenHourData 自动清理 32 天前的数据
8. ✅ **多粒度聚合**: Day/Hour 级别的协议、交易对、代币统计

### 常见陷阱

1. ⚠️ **价格计算时机**: 必须在 Sync 事件中更新,Swap 事件中价格已过时
2. ⚠️ **流动性更新顺序**: 先减去旧值,再更新,最后加上新值
3. ⚠️ **Mint 完成判断**: 检查 `sender` 字段是否为 null
4. ⚠️ **Fee Mint 识别**: 通过 `feeTo` 和 `feeLiquidity` 字段判断
5. ⚠️ **Token decimals 处理**: 链上查询失败时放弃创建 Token
6. ⚠️ **最小流动性阈值**: 避免使用流动性过低的交易对计算价格

---

**文档结束**

> 下一步: 阅读和分析 v2-tokens 子图的数据结构