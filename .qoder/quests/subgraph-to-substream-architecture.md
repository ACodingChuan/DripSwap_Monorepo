# DripSwap Subgraph 到 Substreams 迁移架构设计

## 方案说明

本文档采用基于 **StreamingFast Uniswap V3 Substreams** 的架构设计,针对 Uniswap V2 协议进行适配。该方案已经过主网验证,具有生产级代码质量,是最适合 DripSwap 项目的迁移方案。

---

## 一、项目背景与目标

### 1.1 当前架构痛点

DripSwap 当前使用 The Graph Subgraph 作为链上数据索引方案，存在以下问题：

- **数据同步复杂**：需要维护 WebSocket 监听 + BFF 轮询同步的双重机制
- **实时性不足**：依赖 Subgraph 托管服务的同步延迟（通常 1-5 分钟）
- **配额限制**：The Graph Studio 月查询限制 10 万次/端点
- **架构冗余**：WS 监听用于实时性，Subgraph 用于全量补漏，两套逻辑维护成本高

### 1.2 迁移目标

采用 StreamingFast Substreams 技术栈替换 The Graph Subgraph，实现：

- **数据直接落库**：Substreams 处理后的数据直接写入 PostgreSQL，无需 BFF 同步层
- **架构简化**：废弃 WebSocket 监听和复杂的轮询同步逻辑
- **保持兼容**：新表结构（XX_stream）与现有 Subgraph 数据结构保持一致，业务层无感知
- **平滑过渡**：通过表切换实现灰度迁移，支持回滚

### 1.3 技术选型

- **Substreams Runtime**:StreamingFast 提供的高性能数据流处理引擎
- **Sink 方案**:substreams-sink-postgres(官方提供的 PostgreSQL 数据写入工具)
- **参考实现**:`substreams-uniswap-v3`(StreamingFast 官方实现,生产级架构,针对 V2 进行适配)

---

## 二、V2 Subgraph 与 V3 Substreams 的架构差异分析

### 2.1 V2 Subgraph 的状态机制

**核心发现**:V2 Subgraph 使用基于事件顺序的**实时状态机模式**

通过阅读 `apps/subgraph/uniswap/src/v2/mappings/core.ts`,发现 V2 的关键设计:

**1. Transfer 事件驱动的状态转换**

```
Transfer 事件监听流程（handleTransfer）：
├─ 场景 1：LP Token 铸造（Mint 准备）
│   ├─ from = 0x0 → 识别为新铸造的 LP Token
│   ├─ 更新 Pair.totalSupply
│   └─ 创建 MintEvent（状态：未完成，等待 Mint 事件补充数据）
│
├─ 场景 2：LP Token 销毁（Burn 准备）
│   ├─ to = Pair 地址 → LP Token 归还到合约
│   ├─ 创建 BurnEvent（needsComplete = true）
│   └─ 等待后续 Transfer 和 Burn 事件完善数据
│
└─ 场景 3：LP Token 销毁确认
    ├─ to = 0x0 且 from = Pair 地址 → 最终销毁
    ├─ 更新 Pair.totalSupply
    └─ 处理协议费（feeMint 检测与删除）
```

**关键设计模式**：
- **状态延续**：通过 `transaction.mints`/`burns` 数组在同一交易内传递状态
- **双阶段提交**：Transfer → Mint/Burn，两个事件协同完成一次完整的流动性操作
- **费用检测**：识别协议费铸造（feeMint），将其从 Mint 转换为 Burn 的费用字段

**2. Sync 事件的核心作用**

```
Sync 事件处理（handleSync）：
├─ 更新 Pair 储备（reserve0/reserve1）
├─ 重新计算价格（token0Price = reserve0 / reserve1）
├─ 触发全局 ETH 价格更新（getEthPriceInUSD + findEthPerToken）
├─ 更新 Token 的 derivedETH（价格传播机制）
├─ 重新计算流动性（reserveUSD = reserveETH * ethPrice）
└─ 更新 Factory 的全局 TVL
```

**V2 的状态传播链**：
```
Sync 事件 → Pair.reserve 更新 
          → Bundle.ethPrice 重新计算（通过白名单池）
          → Token.derivedETH 广播更新（findEthPerToken 遍历所有 Pair）
          → Pair.reserveUSD/ETH 级联更新
          → Factory.totalLiquidityUSD 聚合更新
```

**3. 事件处理器的依赖关系**

```
同一交易内的事件处理顺序至关重要：
1. Transfer（创建 Mint/Burn 占位符）
2. Sync（更新储备和价格）
3. Mint/Burn/Swap（补充具体金额和 USD 价值）
```

---

### 2.2 V3 Substreams 的 Store 架构

**核心发现**：V3 使用**分层 Store 模块的状态累积模式**

通过阅读 `substreams-uniswap-v3/src/lib.rs` 和 `substreams.yaml`，发现 V3 的架构：

**1. Store 模块的依赖图**

```
数据流向：

map_pools_created（创建池子）
  ↓
store_pools_created（存储池子元数据）
  ↓
map_extract_data_types（提取事件：Swap/Mint/Burn/Tick/Position）
  ├→ store_pool_sqrt_price（存储 sqrtPrice + tick）
  ├→ store_pool_liquidities（存储流动性）
  ├→ store_native_amounts（存储原生代币数量）
  ├→ store_total_tx_counts（累加交易计数）
  └→ store_ticks_liquidities（Tick 流动性，V2 不需要）
  ↓
store_prices（价格计算）
  ├─ 输入：store_pools_created, map_extract_data_types
  ├─ 计算：sqrtPriceX96 → token0Price/token1Price
  └─ 输出：多 Key 价格（pool, pair, PoolDayData, PoolHourData）
  ↓
store_eth_prices（ETH 派生价格）
  ├─ 输入：store_prices, store_tokens_whitelist_pools, store_native_amounts, store_pool_liquidities
  ├─ 计算：find_eth_per_token（白名单遍历）
  └─ 输出：bundle（ETH/USD）, token:dprice:eth
  ↓
store_swaps_volume（交易量聚合）
  ├─ 输入：store_eth_prices, store_pools_created, store_total_tx_counts
  ├─ 计算：volume_usd, fee_usd（基于 tracked 价格）
  └─ 输出：pool/token/factory 的 volumeUSD、feesUSD
  ↓
store_token_tvl（代币 TVL 累加）
  ├─ 输入：map_extract_data_types（Mint/Burn/Swap 的 amount0/amount1）
  └─ 输出：pool:{address}:{token}:token0/token1, token:{address}
  ↓
store_derived_tvl（USD/ETH TVL 计算）
  ├─ 输入：store_token_tvl, store_eth_prices
  ├─ 计算：tokenAmount * derivedETH * ethPrice
  └─ 输出：pool/token 的 totalValueLockedUSD/ETH
  ↓
store_derived_factory_tvl（全局 TVL 聚合）
  ├─ 输入：store_derived_tvl（deltas 模式）
  └─ 输出：factory:totalValueLockedUSD/ETH
```

**2. 关键的 Store 更新策略**

| Store 模块 | UpdatePolicy | 说明 | V2 适配影响 |
|---|---|---|---|
| `store_prices` | **set** | 每次 Swap/Sync 覆盖价格 | V2 需要，价格计算更简单（reserve0/reserve1）|
| `store_pool_liquidities` | **set** | 覆盖流动性状态 | V2 需要，直接从 Sync 事件读取 reserve |
| `store_total_tx_counts` | **add** | 累加交易计数 | V2 需要，完全相同 |
| `store_swaps_volume` | **add** | 累加交易量 | V2 需要，逻辑完全相同 |
| `store_token_tvl` | **add** | 累加/减少 TVL | V2 需要，Mint(+)/Burn(-)/Swap(净变化) |
| `store_derived_tvl` | **set** | 覆盖派生 TVL | V2 需要，依赖 store_eth_prices |
| `store_ticks_liquidities` | **add** | Tick 流动性管理 | **V2 不需要**，删除此模块 |
| `store_positions` | **set** | Position NFT 状态 | **V2 不需要**，删除此模块 |
| `store_min_windows` | **min** | OHLC 低价/开盘价 | V2 需要，支持 TokenHourData OHLC |
| `store_max_windows` | **max** | OHLC 高价 | V2 需要，支持 TokenHourData OHLC |

**3. delete_prefix 自动清理机制**

```rust
// lib.rs store_prices 函数
let day_id: i64 = timestamp_seconds / 86400;
let prev_day_id = day_id - 1;

// 自动删除前一天的快照数据，避免 Store 无限增长
store.delete_prefix(0, &format!("PoolDayData:{prev_day_id}:"));
store.delete_prefix(0, &format!("PoolHourData:{prev_hour_id}:"));
```

**V2 适配关键**：
- 保留此机制用于 `PairDayData`、`PairHourData`、`TokenDayData`、`TokenHourData`
- V2 没有 Position/Tick 时序数据，清理逻辑更简单

**4. 多 Key 并发写入优化**

```rust
// lib.rs store_prices 函数
store.set_many(
    sqrt_price_update.ordinal,
    &vec![
        format!("pool:{pool_address}:{token0_addr}:token0"),
        format!("pair:{token0_addr}:{token1_addr}"), // 用于 find_eth_per_token
    ],
    &tokens_price.0,
);
```

**设计优势**：
- 一次价格更新同时写入多个索引 Key
- 避免重复计算和多次 Store 访问
- V2 适配时复用此模式(pair 双向索引)

### 2.3 V2 状态机在 Substreams 中的实现策略

**核心挑战**:Substreams 是无状态的流处理,如何重现 V2 的状态机?

**解决方案**:

**1. 利用 Store 模块模拟状态传递**

```
V2 Subgraph 模式:
  Transaction 实体 → 存储 mints/burns 数组 → 在后续事件中读取

Substreams 等价模式:
  store_pending_mints → 临时存储未完成的 Mint
  store_pending_burns → 临时存储未完成的 Burn
  
  在 Mint/Burn 事件处理器中:
    1. 读取 pending store
    2. 补充 amount0/amount1/amountUSD
    3. 删除 pending 记录
    4. 输出完整的 EntityChange
```

**2. 事件批处理与排序**

通过事件类型分组处理:
```
在 map_extract_data_types 中按事件类型分组:
- TRANSFER_EVENT_SIG → handle_transfer()
- SYNC_EVENT_SIG → handle_sync()
- MINT_EVENT_SIG → handle_mint()
- BURN_EVENT_SIG → handle_burn()
- SWAP_EVENT_SIG → handle_swap()

确保事件按 log.ordinal 顺序处理,模拟 V2 的顺序执行
```

**3. Sync 事件的处理时机**

```
V2 Subgraph 设计:
  每个 Mint/Burn/Swap 之后都会触发 Sync
  Sync 更新 reserve → 级联更新价格和 TVL

Substreams 适配:
  Option 1(推荐):
    - store_pool_liquidities 监听 Sync 事件
    - 后续 store_prices/store_eth_prices 自动基于最新 reserve 计算
    - 无需显式依赖 Sync 时序
  
  Option 2(更接近 V2):
    - map_extract_data_types 提取 Sync 事件
    - 在 Events proto 中增加 SyncEvent 类型
    - store_prices 优先处理 Sync,再处理 Swap
```

**推荐 Option 1**,因为 Substreams Store 的声明式依赖已经隐式保证了计算顺序。

### 2.4 完整表结构映射(18张表)

#### 2.4.1 核心实体表(6张)

| V2 Subgraph 表 | Substreams 表 | 主键 | 说明 | 映射完整性 |
|---|---|---|---|---|
| `uniswap_factory` | `uniswap_factory_stream` | id | 协议全局统计 | ✅ 完全兼容 |
| `tokens` | `tokens_stream` | id | 代币元数据与价格 | ✅ 完全兼容 |
| `pairs` | `pairs_stream` | id | 交易对储备与价格 | ✅ 完全兼容 |
| `bundle` | `bundle_stream` | id(固定为"1") | ETH/USD 基准价格 + RoundId | ✅ 新增 roundId 字段 |
| `transactions` | `transactions_stream` | id | 交易哈希与区块信息 | ✅ 完全兼容 |
| `users` | `users_stream` | id | 用户地址集合 | ✅ 完全兼容 |

**Bundle 表新增字段**:
```
type Bundle {
  id: ID!                    // 固定为 "1"
  ethPrice: BigDecimal!      // ETH/USD 价格(来自 Oracle)
  roundId: BigInt!           // Chainlink Oracle 的 Round ID(新增)
}
```

#### 2.4.2 事件表(3张)

| V2 Subgraph 表 | Substreams 表 | 主键 | 说明 | 映射完整性 |
|---|---|---|---|---|
| `mints` | `mints_stream` | id | 添加流动性事件 | ✅ 完全兼容 |
| `burns` | `burns_stream` | id | 移除流动性事件 | ✅ 完全兼容 |
| `swaps` | `swaps_stream` | id | 兑换交易事件 | ✅ 完全兼容 |

#### 2.4.3 时序聚合表(6张)

| V2 Subgraph 表 | Substreams 表 | 主键 | 说明 | 映射完整性 |
|---|---|---|---|---|
| `uniswap_day_data` | `uniswap_day_data_stream` | id | 协议日维度统计 | ✅ 完全兼容 |
| `pair_day_data` | `pair_day_data_stream` | id | Pair 日维度统计 | ✅ 完全兼容 |
| `pair_hour_data` | `pair_hour_data_stream` | id | Pair 小时维度统计 | ✅ 完全兼容 |
| `token_day_data` | `token_day_data_stream` | id | Token 日维度 OHLC | ✅ 完全兼容 |
| `token_hour_data` | `token_hour_data_stream` | id | Token 小时维度 OHLC | ✅ 完全兼容 |
| `token_minute_data` | `token_minute_data_stream` | id | Token 分钟维度 OHLC | ✅ 完全兼容 |

#### 2.4.4 索引表(1张)

| V2 Subgraph 表 | Substreams 表 | 主键 | 说明 | 映射完整性 |
|---|---|---|---|---|
| `pair_token_lookup` | `pair_token_lookup_stream` | id | Pair-Token 双向索引 | ✅ 完全兼容 |

#### 2.4.5 Bridge 相关表(2张)

| V2 Subgraph 表 | Substreams 表 | 主键 | 说明 | 映射完整性 |
|---|---|---|---|---|
| `bridge_transfers` | `bridge_transfers_stream` | id | 跨链转账记录 | ✅ 完全兼容 |
| `bridge_config_events` | `bridge_config_events_stream` | id | Bridge 配置变更 | ✅ 完全兼容 |

**总计**: 18张表全部映射完成,所有字段完全兼容,业务层无感知切换。

### 2.5 ETH/USD 价格计算策略

#### 2.5.1 设计原则

**不使用池子平均价**:改为直接读取链上 Oracle(Chainlink Price Feed 风格),避免使用深度池子的平均价格。

**实现逻辑**(参考 `apps/subgraph/uniswap/src/common/pricing.ts`):

```
getEthPriceInUSD() 流程:
1. 获取 Oracle 合约地址(根据链 ID 配置)
2. 调用 Oracle.latestRoundData() 获取最新价格
3. 返回 OraclePriceResult { price, roundId }
4. 将 price 和 roundId 写入 Bundle 表
```

#### 2.5.2 Oracle 配置

**Sepolia 链**:
- Oracle 地址:0x694aa1769357215de4fac081bf1f309adc325306 (ETH/USD Price Feed)
- Substreams Endpoint:`https://sepolia.substreams.pinax.network:443`

**Scroll Sepolia 链**:
- Oracle 地址:0x59f1ec1f10bd7ed9b938431086bc1d9e233ecf41 (ETH/USD Price Feed)
- Substreams Endpoint:`https://scrsepolia.substreams.pinax.network:443`

**通用配置**:
- API Key:`cd6d1326907fb01ac311507e73f286371de5703f495c1dc4`
- JWT Token:`eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL2FwaS5hY2NvdW50LnBpbmF4Lm5ldHdvcmsvdjEvIiwic3ViIjoiNjQ1MmZjYzktNzliZC00MzI0LWE4M2QtMGE5MTlkNWQzYTQyIiwiYXVkIjpbImh0dHBzOi8vYWNjb3VudC5waW5heC5uZXR3b3JrLyJdLCJleHAiOjIwODE2OTc1MTIsImlhdCI6MTc2NjMzNzUxMiwiYXBpX2tleV9pZCI6ImM4NWU5YzJhLTBhNDQtNDU0Ny04Y2Y0LWJlNTExY2U0NzA4YiJ9.8_foIjZAjsKCY4LJmq-UMBFEFPM4oRE8W6FaRHsc_Go`

#### 2.5.3 Substreams 实现

**Store 模块设计**:

```
store_eth_price_from_oracle:
  输入:Clock(区块时间戳)
  输出:StoreSetProto<OraclePrice>
  逻辑:
    1. 调用 Oracle 合约的 latestRoundData()
    2. 解析 answer 和 roundId
    3. 处理 decimals(默认 8,部分测试网可能返回 0)
    4. 验证 answer > 0,否则返回 0
    5. 写入 Store: "bundle:1" → { ethPrice, roundId }
```

**关键点**:
- 每个区块都查询一次 Oracle(保证实时性)
- 缓存 roundId,仅在 roundId 变化时更新价格
- 处理 Oracle 调用失败的情况(返回上一次的价格)

#### 2.5.4 findEthPerToken 逻辑保留

虽然 ETH/USD 价格改为 Oracle 查询,但 Token 的 derivedETH 计算仍然使用白名单池子遍历:

```
findEthPerToken(token) 流程:
1. 如果 token 是 REFERENCE_TOKEN(WETH) → 返回 1.0
2. 如果 token 是 STABLECOIN → 返回 1 / bundle.ethPrice
3. 遍历 WHITELIST,查找与 token 配对的 Pair
4. 检查 Pair 的 reserveETH > MINIMUM_LIQUIDITY_THRESHOLD_ETH
5. 返回 pair.tokenPrice * whitelistToken.derivedETH
6. 未找到 → 返回 0
```

这部分逻辑与 V2 Subgraph 完全一致。

### 2.6 多链部署配置

#### 2.6.1 Sepolia 链配置

**网络信息**:
- Chain ID:`11155111`
- RPC Endpoint:Pinax Firehose
- Substreams Endpoint:`https://sepolia.substreams.pinax.network:443`

**合约地址**:
- Factory 合约:0x6C9258026A9272368e49bBB7D0A78c17BBe284BF
- Oracle 合约(ETH/USD):0x694aa1769357215de4fac081bf1f309adc325306

**Substreams 配置**:
```yaml
network: sepolia

initialBlocks:
  map_pools_created: <起始区块>

params:
  map_pools_created:
    factory_address: "0x6C9258026A9272368e49bBB7D0A78c17BBe284BF"
  store_eth_price_from_oracle:
    oracle_address: "0x694aa1769357215de4fac081bf1f309adc325306"
```

#### 2.6.2 Scroll Sepolia 链配置

**网络信息**:
- Chain ID:`534351`
- RPC Endpoint:Pinax Firehose
- Substreams Endpoint:`https://scrsepolia.substreams.pinax.network:443`

**合约地址**:
- Factory 合约:0x6C9258026A9272368e49bBB7D0A78c17BBe284BF
- Oracle 合约(ETH/USD):0x59f1ec1f10bd7ed9b938431086bc1d9e233ecf41

**Substreams 配置**:
```yaml
network: scroll-sepolia

initialBlocks:
  map_pools_created: <起始区块>

params:
  map_pools_created:
    factory_address: "0x6C9258026A9272368e49bBB7D0A78c17BBe284BF"
  store_eth_price_from_oracle:
    oracle_address: "0x59f1ec1f10bd7ed9b938431086bc1d9e233ecf41"
```

#### 2.6.3 Pinax 认证配置

**环境变量**:
```bash
# Pinax API Key
PINAX_API_KEY=cd6d1326907fb01ac311507e73f286371de5703f495c1dc4

# Pinax JWT Token
PINAX_JWT_TOKEN=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJodHRwczovL2FwaS5hY2NvdW50LnBpbmF4Lm5ldHdvcmsvdjEvIiwic3ViIjoiNjQ1MmZjYzktNzliZC00MzI0LWE4M2QtMGE5MTlkNWQzYTQyIiwiYXVkIjpbImh0dHBzOi8vYWNjb3VudC5waW5heC5uZXR3b3JrLyJdLCJleHAiOjIwODE2OTc1MTIsImlhdCI6MTc2NjMzNzUxMiwiYXBpX2tleV9pZCI6ImM4NWU5YzJhLTBhNDQtNDU0Ny04Y2Y0LWJlNTExY2U0NzA4YiJ9.8_foIjZAjsKCY4LJmq-UMBFEFPM4oRE8W6FaRHsc_Go
```

**substreams-sink-postgres 配置**:
```yaml
services:
  # Sepolia 链
  substreams-sink-postgres-sepolia:
    image: ghcr.io/streamingfast/substreams-sink-postgres:latest
    environment:
      - SUBSTREAMS_ENDPOINT=https://sepolia.substreams.pinax.network:443
      - SUBSTREAMS_API_TOKEN=${PINAX_JWT_TOKEN}
      - DSN=postgresql://user:pass@postgres:5432/dripswap_sepolia
      - MANIFEST_PATH=/app/dripswap-v2-sepolia.spkg
      - OUTPUT_MODULE=graph_out
    restart: unless-stopped

  # Scroll Sepolia 链
  substreams-sink-postgres-scroll:
    image: ghcr.io/streamingfast/substreams-sink-postgres:latest
    environment:
      - SUBSTREAMS_ENDPOINT=https://scrsepolia.substreams.pinax.network:443
      - SUBSTREAMS_API_TOKEN=${PINAX_JWT_TOKEN}
      - DSN=postgresql://user:pass@postgres:5432/dripswap_scroll_sepolia
      - MANIFEST_PATH=/app/dripswap-v2-scroll-sepolia.spkg
      - OUTPUT_MODULE=graph_out
    restart: unless-stopped
```

#### 2.6.4 数据库设计策略

**采用单数据库 + Chain字段区分方案**

所有18张表统一增加链标识字段:
- `chain_id BIGINT NOT NULL`: 链ID(11155111 = Sepolia, 534351 = Scroll Sepolia)
- `chain_name VARCHAR NOT NULL`: 链名称('sepolia', 'scroll-sepolia')

**设计优势**:
1. **架构简洁**:单数据库实例即可支持双链,运维成本低
2. **跨链分析方便**:可轻松对比两链的TVL、交易量等数据
3. **成本低**:节省数据库资源,单机部署即可
4. **Schema统一**:18张表只需维护一套结构
5. **BFF层简单**:只需传入chainId参数即可切换链

**安全措施**:

**1. 复合主键设计**

所有表采用复合主键,确保同一实体在不同链的数据隔离:

```sql
-- 示例:pairs_stream表
CREATE TABLE pairs_stream (
  id VARCHAR NOT NULL,              -- pair地址
  chain_id BIGINT NOT NULL,         -- 链ID
  chain_name VARCHAR NOT NULL,      -- 链名称
  token0 VARCHAR NOT NULL,
  token1 VARCHAR NOT NULL,
  reserve0 NUMERIC,
  reserve1 NUMERIC,
  total_supply NUMERIC,
  reserve_usd NUMERIC,
  reserve_eth NUMERIC,
  token0_price NUMERIC,
  token1_price NUMERIC,
  volume_usd NUMERIC,
  tx_count BIGINT,
  created_at_timestamp BIGINT,
  created_at_block_number BIGINT,
  
  PRIMARY KEY (id, chain_id),       -- 复合主键
  CONSTRAINT valid_chain_id CHECK (chain_id IN (11155111, 534351))
);

-- 核心索引设计
CREATE INDEX idx_pairs_chain_token0 ON pairs_stream (chain_id, token0);
CREATE INDEX idx_pairs_chain_token1 ON pairs_stream (chain_id, token1);
CREATE INDEX idx_pairs_chain_reserve ON pairs_stream (chain_id, reserve_usd DESC);
```

**2. 复合索引策略**

所有查询索引都包含`chain_id`作为第一列:

```sql
-- tokens表索引
CREATE INDEX idx_tokens_chain_symbol ON tokens_stream (chain_id, symbol);
CREATE INDEX idx_tokens_chain_volume ON tokens_stream (chain_id, trade_volume_usd DESC);

-- swaps表索引
CREATE INDEX idx_swaps_chain_pair ON swaps_stream (chain_id, pair);
CREATE INDEX idx_swaps_chain_timestamp ON swaps_stream (chain_id, timestamp DESC);

-- 时序表索引
CREATE INDEX idx_pair_day_data_chain ON pair_day_data_stream (chain_id, date DESC);
CREATE INDEX idx_token_hour_data_chain ON token_hour_data_stream (chain_id, period_start_unix DESC);
```

**3. BFF层强制约束**

Repository基类自动注入chainId,防止跨链数据混淆:

设计思路:
```typescript
// apps/bff/src/database/base-repository.ts
abstract class BaseRepository {
  constructor(protected readonly chainId: number) {
    // 验证chainId合法性
    if (![11155111, 534351].includes(chainId)) {
      throw new Error(`Invalid chainId: ${chainId}`);
    }
  }
  
  // 所有查询自动注入chain_id
  protected addChainFilter(query: string): string {
    // 自动在WHERE子句中添加chain_id条件
    return query.includes('WHERE') 
      ? query.replace('WHERE', `WHERE chain_id = ${this.chainId} AND`)
      : query + ` WHERE chain_id = ${this.chainId}`;
  }
}

// apps/bff/src/database/pair-repository.ts
class PairRepository extends BaseRepository {
  async findById(pairId: string) {
    return this.db.query(
      'SELECT * FROM pairs_stream WHERE chain_id = $1 AND id = $2',
      [this.chainId, pairId]
    );
  }
  
  async getTopPairsByTVL(limit: number) {
    return this.db.query(
      'SELECT * FROM pairs_stream WHERE chain_id = $1 ORDER BY reserve_usd DESC LIMIT $2',
      [this.chainId, limit]
    );
  }
}

// 使用时必须指定chainId
const sepoliaRepo = new PairRepository(11155111);
const scrollRepo = new PairRepository(534351);
```

**4. Substreams Sink配置**

两个sink写入同一数据库,但自动注入不同的chainId:

```yaml
services:
  postgres:
    image: postgres:15
    environment:
      - POSTGRES_DB=dripswap
      - POSTGRES_USER=dripswap_user
      - POSTGRES_PASSWORD=${DB_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports:
      - "5432:5432"

  # Sepolia链sink
  substreams-sink-sepolia:
    image: ghcr.io/streamingfast/substreams-sink-postgres:latest
    environment:
      - SUBSTREAMS_ENDPOINT=https://sepolia.substreams.pinax.network:443
      - SUBSTREAMS_API_TOKEN=${PINAX_JWT_TOKEN}
      - DSN=postgresql://dripswap_user:${DB_PASSWORD}@postgres:5432/dripswap
      - MANIFEST_PATH=/app/dripswap-v2-sepolia.spkg
      - OUTPUT_MODULE=graph_out
      - CHAIN_ID=11155111          # 注入到所有EntityChange
      - CHAIN_NAME=sepolia
    volumes:
      - ./packages/substreams/sepolia.spkg:/app/dripswap-v2-sepolia.spkg
    depends_on:
      - postgres
    restart: unless-stopped

  # Scroll Sepolia链sink
  substreams-sink-scroll:
    image: ghcr.io/streamingfast/substreams-sink-postgres:latest
    environment:
      - SUBSTREAMS_ENDPOINT=https://scrsepolia.substreams.pinax.network:443
      - SUBSTREAMS_API_TOKEN=${PINAX_JWT_TOKEN}
      - DSN=postgresql://dripswap_user:${DB_PASSWORD}@postgres:5432/dripswap
      - MANIFEST_PATH=/app/dripswap-v2-scroll-sepolia.spkg
      - OUTPUT_MODULE=graph_out
      - CHAIN_ID=534351            # 注入到所有EntityChange
      - CHAIN_NAME=scroll-sepolia
    volumes:
      - ./packages/substreams/scroll-sepolia.spkg:/app/dripswap-v2-scroll-sepolia.spkg
    depends_on:
      - postgres
    restart: unless-stopped

volumes:
  postgres_data:
```

**5. Substreams模块适配**

在`graph_out`模块中为所有EntityChange注入chain字段:

逻辑设计:
```
// 在db.rs中修改entity_change函数
pub fn create_entity_change(
    table_name: &str,
    id: &str,
    chain_id: i64,        // 从环境变量读取
    chain_name: &str,     // 从环境变量读取
    fields: Vec<Field>,
) -> EntityChange {
    let mut all_fields = vec![
        Field { name: "chain_id".to_string(), new_value: chain_id.to_string() },
        Field { name: "chain_name".to_string(), new_value: chain_name.to_string() },
    ];
    all_fields.extend(fields);
    
    EntityChange {
        entity: table_name.to_string(),
        id: format!("{}-{}", id, chain_id),  // 复合ID
        fields: all_fields,
        ...
    }
}
```

**6. 数据一致性保障**

数据库级别的约束和触发器:

```sql
-- CHECK约束:限制chain_id只能为已知链
ALTER TABLE pairs_stream 
ADD CONSTRAINT valid_chain_id CHECK (chain_id IN (11155111, 534351));

ALTER TABLE tokens_stream 
ADD CONSTRAINT valid_chain_id CHECK (chain_id IN (11155111, 534351));

-- 对所有18张表应用相同约束
-- ...(其余16张表)

-- 唯一性约束:防止同一链的重复数据
ALTER TABLE pairs_stream 
ADD CONSTRAINT unique_pair_per_chain UNIQUE (id, chain_id);

-- 外键关联也需包含chain_id
ALTER TABLE swaps_stream
ADD CONSTRAINT fk_swaps_pair 
FOREIGN KEY (pair, chain_id) 
REFERENCES pairs_stream(id, chain_id);
```

**7. API层链切换**

BFF API根据请求头或参数动态切换链:

设计思路:
```typescript
// apps/bff/src/middleware/chain-context.ts
app.use((req, res, next) => {
  // 从请求头获取chainId
  const chainId = parseInt(req.headers['x-chain-id'] || '11155111');
  
  // 验证chainId
  if (![11155111, 534351].includes(chainId)) {
    return res.status(400).json({ error: 'Invalid chain ID' });
  }
  
  // 注入到请求上下文
  req.chainId = chainId;
  next();
});

// apps/bff/src/controllers/pool-controller.ts
class PoolController {
  async getPools(req, res) {
    const repo = new PairRepository(req.chainId);
    const pools = await repo.getTopPairsByTVL(100);
    res.json(pools);
  }
}
```

**优势总结**:

| 方面 | 单数据库方案 | 多数据库方案 |
|------|------------|-------------|
| 运维成本 | ✅ 低(单实例) | ❌ 高(多实例) |
| 跨链查询 | ✅ 简单(JOIN即可) | ❌ 复杂(跨库查询) |
| 数据隔离 | ⚠️ 应用层保证 | ✅ 数据库层天然隔离 |
| 扩展性 | ⚠️ 需要分片 | ✅ 独立扩展 |
| 成本 | ✅ 低 | ❌ 高 |
| 适用场景 | ✅ 测试网,小规模 | ✅ 主网,大规模 |

**结论**:对于DripSwap的测试网环境,单数据库方案完全够用,且运维成本更低。

### 2.7 为什么 V3 官方不用状态机方式?V2 必须用吗?


**核心原因：V2 和 V3 的业务模型根本不同**

##### V3 不需要状态机的原因

1. **V3 没有双阶段 Transfer 逻辑**
   - V3 使用 Position NFT，直接通过 `IncreaseLiquidity`/`DecreaseLiquidity` 事件完整携带所有数据
   - 不像 V2 需要 `Transfer → Mint` 两步才能拿到完整信息
   - V3 的事件本身就是"自包含"的，无需跨事件拼接状态

2. **V3 的流动性数据来自 StorageChange**
   ```rust
   // V3 直接从链上存储读取 liquidity 字段
   filtering::extract_pool_liquidities(
       &mut pool_liquidities, 
       log, 
       &call_view.call.storage_changes, 
       &pool
   );
   ```
   - 不需要监听 Sync 事件
   - Storage 变更本身就包含最终状态

3. **V3 的事件是原子性的**
   - 一个 Swap 事件 = {amount0, amount1, sqrtPrice, tick} 全部数据
   - 一个 Mint 事件 = {amount0, amount1, liquidity, tickLower, tickUpper} 全部数据
   - 不需要在多个事件间传递状态

##### V2 必须用状态机的原因

1. **Transfer 事件缺少关键信息**
   ```solidity
   // V2 Transfer 事件只有这些字段
   event Transfer(address indexed from, address indexed to, uint value);
   
   // Mint 事件才有 amount0/amount1
   event Mint(address indexed sender, uint amount0, uint amount1);
   ```
   - Transfer 先触发（铸造 LP Token）
   - Mint 后触发（提供 amount0/amount1）
   - **必须在内存中关联这两个事件**

2. **Sync 事件是唯一的 reserve 更新来源**
   ```solidity
   event Sync(uint112 reserve0, uint112 reserve1);
   ```
   - V2 合约没有在 Storage 中暴露 reserve
   - **必须监听 Sync 事件并手动维护 reserve 状态**

##### 如果直接照搬 V3 方式会怎样？

**直接回答：对 V2 不可行！必须用状态机！**

如果直接照搬 V3 的方式：

```rust
// ❌ 这样写会丢失数据
for log in trx.logs() {
    if let Some(mint) = Mint::match_and_decode(log) {
        // 问题：mint 事件里没有 `to` 和 `liquidity` 字段！
        // 这两个字段在 Transfer 事件里
        // V2 的 Mint 事件只有 sender, amount0, amount1
    }
}
```

**V2 Mint 事件定义**：
```solidity
event Mint(address indexed sender, uint amount0, uint amount1);
// 缺少：to (LP Token 接收者), liquidity (铸造的 LP Token 数量)
```

**V3 Mint 事件定义**：
```solidity
event Mint(
    address sender,
    address indexed owner,  // ← 有 owner
    int24 indexed tickLower,
    int24 indexed tickUpper,
    uint128 amount,  // ← 有 liquidity
    uint256 amount0,
    uint256 amount1
);
```

**如果强行用 V3 方式的后果**：

1. **Mint 表会缺少关键字段**
   - ❌ `to` 字段：不知道 LP Token 给了谁
   - ❌ `liquidity` 字段：不知道铸造了多少 LP Token

2. **Pair 的 totalSupply 无法追踪**
   - V2 没有 `totalSupply` 事件
   - 只能通过 Transfer 事件累加/减少

3. **Burn 表会缺少 sender**
   - Burn 事件没有 sender 字段
   - 只能从 Transfer 事件推导（谁把 LP Token 还给 Pair）

##### 最佳实践对比

| 实现方式 | V3 适用性 | V2 适用性 | 原因 |
|---|---|---|---|
| **V3 官方方式**（直接处理事件） | ✅ 完美 | ❌ 不可行 | V2 事件缺少关键字段 |
| **状态机方式**（pending store） | ⚠️ 过度设计 | ✅ 必须 | V2 需要跨事件拼接状态 |

**结论**：

- ✅ **状态机方式是 V2 的唯一可行方案**，不是设计选择，而是业务特性强制要求
- ✅ **保留了 V2 的完整语义**：Mint 表包含 `to` 和 `liquidity`（来自 Transfer）
- ✅ **复现了 V2 Subgraph 的逻辑**：官方 Subgraph 就是这么做的（`core.ts` handleTransfer）
- ✅ **利用了 Substreams 的优势**：用 Store 模块替代内存状态，性能更好

**V3 看起来更简单的原因**：V3 的合约设计本身就更合理，事件更自包含。这是 V3 吸取 V2 教训后的改进，但不意味着 V2 可以用同样的实现方式。

### 2.8 方案 B 核心特点(修订版)

与方案 A (Messari Schema) 相比，方案 B 基于 StreamingFast 官方的 Uniswap V3 Substreams 实现，具有以下显著优势：

#### 架构优势

1. **直接输出 Graph Schema**
   - 不依赖 Messari 的标准化 Schema，直接对齐 Uniswap Subgraph 的 GraphQL Schema
   - 无需复杂的字段映射，数据结构与 V2 Subgraph 天然兼容

2. **更精细的 Store 管理**
   - 使用 `delete_prefix` 自动清理过期快照数据（如前一天/小时的数据）
   - 减少 Store 内存占用，提高性能

3. **原生支持 OHLC 数据**
   - 内置 `store_min_windows` 和 `store_max_windows` 模块
   - 直接输出 `open`/`high`/`low`/`close` 字段到 Schema

4. **生产级代码质量**
   - StreamingFast 官方维护，经过主网实际验证
   - 完善的错误处理和边界情况处理

### 2.9 Uniswap V2 业务模型适配

虽然 V3 Substreams 是为 V3 设计的，但其核心架构同样适用于 V2，只需做以下业务适配：

#### 需要简化的部分

| V3 特性 | V2 处理方式 |
|---|---|
| **Tick 管理** | 删除，V2 无 Tick 机制 |
| **Position NFT** | 删除，V2 直接使用 LP Token |
| **变动费率** | 固定 0.3% |
| **sqrtPriceX96** | 简化为 `reserve0 / reserve1` 计算 |
| **feeGrowthGlobal** | 删除，V2 不需要 |

#### 需要增加的部分

| V2 特性 | 实现方式 |
|---|---|
| **Sync 事件** | 监听 `Sync` 事件更新 `reserve0`/`reserve1` |
| **LP Token totalSupply** | 监听 `Transfer` 事件更新 |
| **PairTokenLookup** | 增加双向索引表 |

### 2.10 核心模块设计(修订版)

参考 `substreams-uniswap-v3/substreams.yaml`，V2 适配需要以下模块（**16 个，删除 V3 特有的 2 个**）：

#### 2.10.1 Map 模块(3 个)

| 模块名 | 输入 | 输出 | 职责 | V2 适配修改 |
|---|---|---|---|---|
| `map_pools_created` | Block | Pools | 监听 **PairCreated** 事件 | ✅ 事件签名相同，仅修改合约地址 |
| `map_extract_data_types` | Block, store_pools | Events | 提取 Swap/Mint/Burn/**Sync**/Transfer 事件 | 🔧 **增加** Sync/Transfer，**删除** Tick/Position/Flash |
| `map_tokens_whitelist_pools` | map_pools_created | ERC20Tokens | 标记白名单代币（用于价格计算） | ✅ 无需修改 |

**关键修改点**：

```rust
// map_extract_data_types 需要增加的事件提取
use abi::pair::events::{Sync, Transfer};  // V2 特有

for log in trx.logs() {
    if let Some(event) = Sync::match_and_decode(log) {
        // 提取 reserve0, reserve1
        pool_liquidities.push(PoolLiquidity {
            pool_address: pool.address.clone(),
            token0: pool.token0_ref().address(),
            token1: pool.token1_ref().address(),
            liquidity: "0".to_string(),  // V2 无此概念
            reserve0: event.reserve0.to_string(),
            reserve1: event.reserve1.to_string(),
            log_ordinal: log.ordinal(),
        });
    }
    
    if let Some(event) = Transfer::match_and_decode(log) {
        // 用于追踪 LP Token totalSupply
        handle_transfer_for_total_supply(event, log);
    }
}
```

#### 2.10.2 Store 模块(13 个,删除 2 个 V3 特有模块)

| 模块名 | UpdatePolicy | ValueType | 职责 | V2 适配 |
|---|---|---|---|---|
| `store_pools_created` | set | proto:Pool | 存储 Pair 元数据 | ✅ 无需修改 |
| `store_tokens` | add | int64 | 计数 Token 使用次数 | ✅ 无需修改 |
| `store_pool_count` | add | bigint | Factory 的 pairCount | ✅ 无需修改 |
| `store_tokens_whitelist_pools` | append | string | Token 的白名单 Pair | ✅ 无需修改 |
| `store_pool_sqrt_price` | set | proto:PoolSqrtPrice | **不使用**，V2 用 reserve | 🔧 改名为 `store_pool_reserves` |
| `store_prices` | set | bigdecimal | Pair 价格（token0Price/token1Price） | 🔧 计算逻辑简化（reserve0/reserve1） |
| `store_pool_liquidities` | set | bigint | Pair 的 reserve0/reserve1 | 🔧 从 Sync 事件读取，非 StorageChange |
| `store_total_tx_counts` | add | bigint | 全局交易计数 | ✅ 无需修改 |
| `store_swaps_volume` | add | bigdecimal | 交易量和手续费累加 | ✅ 无需修改（V2 固定 0.3% 费率） |
| `store_native_amounts` | set | bigdecimal | Mint/Burn/Swap 的原生数量 | ✅ 无需修改 |
| `store_eth_prices` | set | bigdecimal | ETH/USD 价格 + Token 派生价格 | ✅ 无需修改（复用 find_eth_per_token） |
| `store_token_tvl` | add | bigdecimal | Token 在所有 Pair 中的总量 | ✅ 无需修改 |
| `store_derived_tvl` | set | bigdecimal | USD/ETH 计价的 TVL | ✅ 无需修改 |
| `store_derived_factory_tvl` | add | bigdecimal | Factory 全局 TVL | ✅ 无需修改 |
| `store_min_windows` | min | bigdecimal | OHLC 的 open/low | ✅ 无需修改 |
| `store_max_windows` | max | bigdecimal | OHLC 的 high | ✅ 无需修改 |
| ~~`store_ticks_liquidities`~~ | ~~add~~ | ~~bigint~~ | ~~Tick 流动性~~ | ❌ **删除**，V2 无 Tick |
| ~~`store_positions`~~ | ~~set~~ | ~~proto~~ | ~~Position NFT~~ | ❌ **删除**，V2 用 LP Token |

**关键模块的 V2 适配逻辑**：

**1. store_pool_liquidities（从 V3 的 Storage 变更改为 V2 的事件驱动）**

```rust
// V3 原逻辑：从 StorageChanges 读取 liquidity 字段
// V2 新逻辑：从 Sync 事件读取 reserve0/reserve1

#[substreams::handlers::store]
pub fn store_pool_liquidities(clock: Clock, events: Events, store: StoreSetBigInt) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id: i64 = timestamp_seconds / 86400;
    let hour_id: i64 = timestamp_seconds / 3600;
    let prev_day_id = day_id - 1;
    let prev_hour_id = hour_id - 1;

    // 自动清理过期快照
    store.delete_prefix(0, &format!("PairDayData:{prev_day_id}:"));
    store.delete_prefix(0, &format!("PairHourData:{prev_hour_id}:"));

    for pool_liquidity in events.pool_liquidities {
        let pool_address = &pool_liquidity.pool_address;
        let token0_address = &pool_liquidity.token0;
        let token1_address = &pool_liquidity.token1;
        
        // V2 特有：同时存储 reserve0 和 reserve1
        store.set_many(
            pool_liquidity.log_ordinal,
            &vec![
                format!("pool:{pool_address}:reserve0"),
                format!("PairDayData:{day_id}:{pool_address}:reserve0"),
                format!("PairHourData:{hour_id}:{pool_address}:reserve0"),
            ],
            &BigInt::try_from(pool_liquidity.reserve0).unwrap(),
        );
        
        store.set_many(
            pool_liquidity.log_ordinal,
            &vec![
                format!("pool:{pool_address}:reserve1"),
                format!("PairDayData:{day_id}:{pool_address}:reserve1"),
                format!("PairHourData:{hour_id}:{pool_address}:reserve1"),
            ],
            &BigInt::try_from(pool_liquidity.reserve1).unwrap(),
        );
        
        // V2 特有：存储 totalSupply（从 Transfer 事件累加）
        store.set_many(
            pool_liquidity.log_ordinal,
            &vec![
                format!("pool:{pool_address}:totalSupply"),
                format!("PairDayData:{day_id}:{pool_address}:totalSupply"),
                format!("PairHourData:{hour_id}:{pool_address}:totalSupply"),
            ],
            &pool_liquidity.total_supply,  // 新增字段
        );
    }
}
```

**2. store_prices（从 sqrtPrice 转换改为 reserve 比例计算）**

```rust
#[substreams::handlers::store]
pub fn store_prices(
    clock: Clock,
    events: Events,
    pools_store: StoreGetProto<Pool>,
    liquidities_store: StoreGetBigInt,  // 新增依赖
    store: StoreSetBigDecimal,
) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id: i64 = timestamp_seconds / 86400;
    let hour_id: i64 = timestamp_seconds / 3600;

    // V3 逻辑：遍历 sqrt_price 更新
    // V2 逻辑：遍历 Sync 事件，从 liquidities_store 读取 reserve
    for sync_event in events.sync_events {  // V2 新增的事件类型
        let pool_address = &sync_event.pool_address;
        let pool = pools_store.must_get_last(format!("pool:{pool_address}"));
        
        // 从 Store 读取最新的 reserve
        let reserve0 = liquidities_store
            .get_last(format!("pool:{pool_address}:reserve0"))
            .unwrap_or(BigInt::zero());
        let reserve1 = liquidities_store
            .get_last(format!("pool:{pool_address}:reserve1"))
            .unwrap_or(BigInt::zero());
        
        // V2 简化的价格计算
        let token0_price = if !reserve1.is_zero() {
            BigDecimal::from(reserve0) / BigDecimal::from(reserve1)
        } else {
            BigDecimal::zero()
        };
        
        let token1_price = if !reserve0.is_zero() {
            BigDecimal::from(reserve1) / BigDecimal::from(reserve0)
        } else {
            BigDecimal::zero()
        };
        
        // 多 Key 并发写入（复用 V3 模式）
        let token0_addr = pool.token0_ref().address();
        let token1_addr = pool.token1_ref().address();
        
        store.set_many(
            sync_event.ordinal,
            &vec![
                format!("pool:{pool_address}:{token0_addr}:token0"),
                format!("pair:{token0_addr}:{token1_addr}"),  // 用于 find_eth_per_token
                format!("PairDayData:{day_id}:{pool_address}:token0"),
                format!("PairHourData:{hour_id}:{pool_address}:token0"),
            ],
            &token0_price,
        );
        
        store.set_many(
            sync_event.ordinal,
            &vec![
                format!("pool:{pool_address}:{token1_addr}:token1"),
                format!("pair:{token1_addr}:{token0_addr}"),
                format!("PairDayData:{day_id}:{pool_address}:token1"),
                format!("PairHourData:{hour_id}:{pool_address}:token1"),
            ],
            &token1_price,
        );
    }
}
```

**3. 新增 store_pair_total_supply（V2 特有）**

```rust
#[substreams::handlers::store]
pub fn store_pair_total_supply(events: Events, store: StoreSetBigDecimal) {
    // 监听 LP Token 的 Transfer 事件
    for transfer_event in events.transfer_events {
        let pair_address = &transfer_event.pair_address;
        let current_supply = store
            .get_last(format!("pool:{pair_address}:totalSupply"))
            .unwrap_or(BigDecimal::zero());
        
        if transfer_event.from == ADDRESS_ZERO {
            // Mint: totalSupply += value
            store.set(
                transfer_event.ordinal,
                format!("pool:{pair_address}:totalSupply"),
                &(current_supply + transfer_event.value),
            );
        } else if transfer_event.to == ADDRESS_ZERO {
            // Burn: totalSupply -= value
            store.set(
                transfer_event.ordinal,
                format!("pool:{pair_address}:totalSupply"),
                &(current_supply - transfer_event.value),
            );
        }
    }
}
```

#### 2.10.3 Output 模块(1 个)

| 模块名 | 输入 | 输出 | 职责 |
|---|---|---|---|
| `graph_out` | 所有 Store deltas + Events | EntityChanges | 转换为 Graph Protocol 的实体变更格式，供 sink-postgres 写入 |

**V2 适配修改**：

```rust
// db.rs 需要增加的 entity change 函数
pub fn pair_total_supply_entity_change(
    tables: &mut Tables,
    total_supply_deltas: &Deltas<DeltaBigDecimal>,
) {
    for delta in total_supply_deltas.iter().key_first_segment_eq("pool") {
        let pool_address = key::segment_at(&delta.key, 1);
        
        if key::last_segment(&delta.key) == "totalSupply" {
            tables
                .update_row("Pool", &format!("0x{pool_address}"))
                .set("totalSupply", &delta.new_value);
        }
    }
}

// PairHourData 增加 reserve 快照字段
pub fn pair_hour_data_reserves_entity_change(
    tables: &mut Tables,
    liquidities_deltas: &Deltas<DeltaBigInt>,
) {
    for delta in liquidities_deltas.iter() {
        if let Some(time_id) = key::try_segment_at(&delta.key, 1) {
            if key::first_segment(&delta.key).starts_with("PairHourData") {
                let pool_address = key::segment_at(&delta.key, 2);
                let field_name = key::last_segment(&delta.key);  // "reserve0" or "reserve1"
                
                tables
                    .update_row("PairHourData", &format!("{}-{}", pool_address, time_id))
                    .set(field_name, &delta.new_value);
            }
        }
    }
}
```

| 模块名 | 更新策略 | 值类型 | Key 格式 | V2 适配 |
|---|---|---|---|---|
| `store_pools_created` | set | Pool | `pool:{address}` | ✅ 无需修改 |
| `store_tokens` | add | int64 | `token:{address}` | ✅ 无需修改 |
| `store_pool_count` | add | bigint | `factory:poolCount` | ✅ 无需修改 |
| `store_pool_sqrt_price` | set | PoolSqrtPrice | `pool:{address}` | 🔧 改为从 Sync 事件计算 |
| `store_pool_liquidities` | set | bigint | `pool:{address}` | 🔧 从 Sync 事件获取 reserve0/reserve1 |
| `store_prices` | set | bigdecimal | `pool:{pool}:{token}:token0` | ✅ 价格计算逻辑相同 |
| `store_eth_prices` | set | bigdecimal | `ethPrice` | ✅ 通过稳定币交易对计算 |
| `store_swaps_volume` | add | bigdecimal | `pool:{address}:volumeUSD` | ✅ 统计逻辑相同 |
| `store_total_tx_counts` | add | bigint | `pool:{address}:txCount` | ✅ 计数逻辑相同 |
| `store_min_windows` | min | bigdecimal | `PoolDayData:{dayID}:{pool}:token0` | ✅ OHLC 逻辑相同 |
| `store_max_windows` | max | bigdecimal | `PoolDayData:{dayID}:{pool}:token0` | ✅ OHLC 逻辑相同 |

**关键设计点**：

1. **自动清理过期数据**：
   ```rust
   // 在 store_prices 中清理前一天/小时的快照
   store.delete_prefix(0, &format!("PoolDayData:{prev_day_id}:"));
   store.delete_prefix(0, &format!("PoolHourData:{prev_hour_id}:"));
   ```

2. **多 Key 同时设置**：
   ```rust
   // 一次设置多个相关 Key
   store.set_many(
       ordinal,
       &vec![
           format!("pool:{pool_address}:{token0_addr}:token0"),
           format!("pair:{token0_addr}:{token1_addr}"), // 用于 find_eth_per_token
       ],
       &price,
   );
   ```

### 2.11 核心算法实现(方案 B)

#### 2.11.1 sqrtPrice 转换为 token0Price / token1Price

```rust
// V3 使用 sqrtPriceX96，V2 需要简化
// V3 逻辑：
fn sqrt_price_x96_to_token_prices(
    sqrt_price: BigDecimal,
    token0: &Token,
    token1: &Token,
) -> (BigDecimal, BigDecimal) {
    let q96 = BigDecimal::from_str("79228162514264337593543950336").unwrap(); // 2^96
    let price = (sqrt_price / q96).square();
    
    // 调整精度
    let decimal_adjustment = BigDecimal::from(10u64.pow(token0.decimals as u32))
        / BigDecimal::from(10u64.pow(token1.decimals as u32));
    
    let token0_price = price * decimal_adjustment;
    let token1_price = if !token0_price.is_zero() {
        BigDecimal::one() / token0_price
    } else {
        BigDecimal::zero()
    };
    
    (token0_price, token1_price)
}

// V2 简化逻辑（直接从 Sync 事件计算）：
fn calculate_pair_prices(
    reserve0: BigDecimal,
    reserve1: BigDecimal,
) -> (BigDecimal, BigDecimal) {
    let token0_price = if !reserve1.is_zero() {
        reserve0 / reserve1
    } else {
        BigDecimal::zero()
    };
    
    let token1_price = if !reserve0.is_zero() {
        reserve1 / reserve0
    } else {
        BigDecimal::zero()
    };
    
    (token0_price, token1_price)
}
```

#### 2.11.2 ETH 价格计算(复用 V3 逻辑)

```rust
// V3 的 find_eth_per_token 逻辑同样适用于 V2
fn find_eth_per_token(
    token_addr: &str,
    whitelist_pools: &[String],
    prices_store: &StoreGetBigDecimal,
    pools_store: &StoreGetProto<Pool>,
) -> BigDecimal {
    const WHITELIST_TOKENS: &[&str] = &[
        "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2", // WETH
        "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48", // USDC
        "0xdac17f958d2ee523a2206206994597c13d831ec7", // USDT
        "0x6b175474e89094c44da98b954eedeac495271d0f", // DAI
        "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599", // WBTC
    ];
    
    // 1. 遍历白名单 Token
    for whitelist_token in WHITELIST_TOKENS {
        let pair_key = format!("pair:{}:{}", token_addr, whitelist_token);
        
        if let Some(price) = prices_store.get_last(&pair_key) {
            // 检查流动性是否足够
            if let Some(pool) = pools_store.get_last(&format!("pool:{}", pair_key)) {
                if pool.total_value_locked_eth > MIN_LIQUIDITY_THRESHOLD {
                    // 获取白名单 Token 的 derivedETH
                    let whitelist_derived_eth = get_token_derived_eth(whitelist_token, prices_store);
                    return price * whitelist_derived_eth;
                }
            }
        }
    }
    
    BigDecimal::zero()
}
```

#### 2.11.3 OHLC 维护(复用 V3 逻辑)

```rust
// V3 的 store_min_windows / store_max_windows 直接适用于 V2
#[substreams::handlers::store]
pub fn store_min_windows(
    clock: Clock,
    prices_delta: Deltas<DeltaBigDecimal>,
    eth_prices_delta: Deltas<DeltaBigDecimal>,
    output: StoreMinBigDecimal,
) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id = timestamp_seconds / 86400;
    let hour_id = timestamp_seconds / 3600;
    
    // 处理价格变化
    for delta in prices_delta.deltas {
        if delta.key.starts_with("PoolDayData:") || delta.key.starts_with("PoolHourData:") {
            let price = delta.new_value;
            
            // 设置 open 价格（仅当天/小时第一次）
            let open_key = format!("{}-open", delta.key);
            if output.get_last(&open_key).is_none() {
                output.min(delta.ordinal, &open_key, &price);
            }
            
            // 更新 low 价格
            let low_key = format!("{}-low", delta.key);
            output.min(delta.ordinal, &low_key, &price);
        }
    }
}

#[substreams::handlers::store]
pub fn store_max_windows(
    clock: Clock,
    prices_delta: Deltas<DeltaBigDecimal>,
    eth_prices_delta: Deltas<DeltaBigDecimal>,
    output: StoreMaxBigDecimal,
) {
    let timestamp_seconds = clock.timestamp.unwrap().seconds;
    let day_id = timestamp_seconds / 86400;
    let hour_id = timestamp_seconds / 3600;
    
    // 处理价格变化
    for delta in prices_delta.deltas {
        if delta.key.starts_with("PoolDayData:") || delta.key.starts_with("PoolHourData:") {
            let price = delta.new_value;
            
            // 设置 open 价格（仅当天/小时第一次）
            let open_key = format!("{}-open", delta.key);
            if output.get_last(&open_key).is_none() {
                output.max(delta.ordinal, &open_key, &price);
            }
            
            // 更新 high 价格
            let high_key = format!("{}-high", delta.key);
            output.max(delta.ordinal, &high_key, &price);
        }
    }
}
```

### 2.12 V2 Subgraph 状态机在 Substreams 中的完整实现

#### 2.12.1 Transfer 事件的双阶段处理

**V2 Subgraph 的核心逻辑**（来自 `core.ts` handleTransfer）：

```typescript
// 阶段 1：检测 Mint 准备（Transfer from 0x0）
if (from.toHexString() == ADDRESS_ZERO) {
    // 创建未完成的 Mint 记录
    if (mints.length === 0 || isCompleteMint(mints[mints.length - 1])) {
        let mint = new MintEvent(txHash.concat('-').concat(mints.length.toString()));
        mint.to = to;
        mint.liquidity = value;
        mint.sender = null;  // 等待 Mint 事件补充
        transaction.mints = mints.concat([mint.id]);
    }
}

// 阶段 2：检测 Burn 准备（Transfer to Pair 地址）
if (to.toHexString() == pair.id) {
    let burn = new BurnEvent(txHash.concat('-').concat(burns.length.toString()));
    burn.liquidity = value;
    burn.needsComplete = true;  // 等待后续 Burn 事件
    transaction.burns = burns.concat([burn.id]);
}

// 阶段 3：检测 Burn 最终执行（Transfer to 0x0 from Pair）
if (to.toHexString() == ADDRESS_ZERO && from.toHexString() == pair.id) {
    // 处理协议费用（检测 feeMint）
    if (mints.length !== 0 && !isCompleteMint(mints[mints.length - 1])) {
        let mint = MintEvent.load(mints[mints.length - 1]);
        burn.feeTo = mint.to;
        burn.feeLiquidity = mint.liquidity;
        store.remove('Mint', mints[mints.length - 1]);  // 删除假的 Mint
    }
}
```

**Substreams 等价实现**：

```rust
// 在 Events proto 中定义新的事件类型
message TransferEvent {
    string transaction_id = 1;
    string pair_address = 2;
    string from = 3;
    string to = 4;
    string value = 5;  // LP Token 数量
    uint64 log_ordinal = 6;
    uint64 timestamp = 7;
}

message PendingMint {
    string id = 1;  // txHash-index
    string pair_address = 2;
    string to = 3;  // LP Token 接收者
    string liquidity = 4;
    uint64 ordinal = 5;
    uint64 timestamp = 6;
}

message PendingBurn {
    string id = 1;
    string pair_address = 2;
    string liquidity = 3;
    bool needs_complete = 4;
    uint64 ordinal = 5;
    uint64 timestamp = 6;
}

// map_extract_data_types 中提取 Transfer 事件
for log in trx.logs() {
    if let Some(transfer) = Transfer::match_and_decode(log) {
        // 检查是否为 LP Token Transfer（log.address == pair_address）
        if let Some(pool) = pools_store.get_last(format!("pool:{}", Hex(&log.address))) {
            transfer_events.push(TransferEvent {
                transaction_id: Hex(&trx.hash).to_string(),
                pair_address: Hex(&log.address).to_string(),
                from: Hex(&transfer.from).to_string(),
                to: Hex(&transfer.to).to_string(),
                value: transfer.value.to_string(),
                log_ordinal: log.ordinal(),
                timestamp: block.timestamp_seconds(),
            });
        }
    }
}

// store_pending_mints (updatePolicy: set, valueType: proto:PendingMint)
pub fn store_pending_mints(events: Events, store: StoreSetProto<PendingMint>) {
    for transfer in events.transfer_events {
        if transfer.from == ADDRESS_ZERO {
            // Mint 准备阶段
            let tx_id = &transfer.transaction_id;
            
            // 检查是否已有 pending mint
            let existing_count = count_pending_mints_in_tx(tx_id, &store);
            
            let mint = PendingMint {
                id: format!("{}-{}", tx_id, existing_count),
                pair_address: transfer.pair_address.clone(),
                to: transfer.to.clone(),
                liquidity: transfer.value.clone(),
                ordinal: transfer.log_ordinal,
                timestamp: transfer.timestamp,
            };
            
            store.set(transfer.log_ordinal, format!("pending_mint:{}", mint.id), &mint);
        }
    }
}

// store_pending_burns (updatePolicy: set, valueType: proto:PendingBurn)
pub fn store_pending_burns(events: Events, store: StoreSetProto<PendingBurn>) {
    for transfer in events.transfer_events {
        if transfer.to == transfer.pair_address {
            // Burn 准备阶段（LP Token 归还到 Pair）
            let tx_id = &transfer.transaction_id;
            let existing_count = count_pending_burns_in_tx(tx_id, &store);
            
            let burn = PendingBurn {
                id: format!("{}-{}", tx_id, existing_count),
                pair_address: transfer.pair_address.clone(),
                liquidity: transfer.value.clone(),
                needs_complete: true,
                ordinal: transfer.log_ordinal,
                timestamp: transfer.timestamp,
            };
            
            store.set(transfer.log_ordinal, format!("pending_burn:{}", burn.id), &burn);
        }
        
        if transfer.to == ADDRESS_ZERO && transfer.from == transfer.pair_address {
            // Burn 最终执行阶段
            let tx_id = &transfer.transaction_id;
            
            // 检查是否有未完成的 pending mint（协议费用检测）
            if let Some(pending_mint) = get_last_incomplete_mint(tx_id, &pending_mints_store) {
                // 更新对应的 burn 记录，标记为 feeMint
                if let Some(mut pending_burn) = get_last_burn(tx_id, &store) {
                    pending_burn.fee_to = Some(pending_mint.to.clone());
                    pending_burn.fee_liquidity = Some(pending_mint.liquidity.clone());
                    store.set(transfer.log_ordinal, format!("pending_burn:{}", pending_burn.id), &pending_burn);
                }
                
                // 删除假的 Mint
                pending_mints_store.delete_prefix(0, &format!("pending_mint:{}", pending_mint.id));
            }
        }
    }
}
```

#### 2.12.2 Mint/Burn 事件补充数据

**Substreams 实现**：

```rust
// 在 db.rs 的 graph_out 输出阶段
pub fn mint_entity_changes(
    tables: &mut Tables,
    mint_events: &Vec<events::Mint>,
    pending_mints_store: StoreGetProto<PendingMint>,
) {
    for mint_event in mint_events {
        let tx_id = &mint_event.transaction_id;
        
        // 从 pending store 读取对应的 Mint 记录
        if let Some(pending) = find_pending_mint(tx_id, &pending_mints_store) {
            tables
                .create_row("Mint", &pending.id)
                .set("transaction", tx_id)
                .set("timestamp", BigInt::from(mint_event.timestamp))
                .set("pair", &mint_event.pool_address)
                .set("to", &pending.to)
                .set("liquidity", &BigDecimal::from_str(&pending.liquidity).unwrap())
                .set("sender", &mint_event.sender)  // 来自 Mint 事件
                .set("amount0", &BigDecimal::from_str(&mint_event.amount0).unwrap())  // 来自 Mint 事件
                .set("amount1", &BigDecimal::from_str(&mint_event.amount1).unwrap())  // 来自 Mint 事件
                .set("logIndex", mint_event.log_index)
                .set("amountUSD", &calculate_usd_value(  // 基于当前价格计算
                    &mint_event.amount0,
                    &mint_event.amount1,
                    &eth_prices_store,
                ));
            
            // 清理 pending 记录
            pending_mints_store.delete_prefix(0, &format!("pending_mint:{}", pending.id));
        }
    }
}

pub fn burn_entity_changes(
    tables: &mut Tables,
    burn_events: &Vec<events::Burn>,
    pending_burns_store: StoreGetProto<PendingBurn>,
) {
    for burn_event in burn_events {
        let tx_id = &burn_event.transaction_id;
        
        if let Some(pending) = find_pending_burn(tx_id, &pending_burns_store) {
            let mut row = tables
                .create_row("Burn", &pending.id)
                .set("transaction", tx_id)
                .set("timestamp", BigInt::from(burn_event.timestamp))
                .set("pair", &burn_event.pool_address)
                .set("liquidity", &BigDecimal::from_str(&pending.liquidity).unwrap())
                .set("amount0", &BigDecimal::from_str(&burn_event.amount0).unwrap())
                .set("amount1", &BigDecimal::from_str(&burn_event.amount1).unwrap())
                .set("to", &burn_event.to)
                .set("logIndex", burn_event.log_index)
                .set("amountUSD", &calculate_usd_value(
                    &burn_event.amount0,
                    &burn_event.amount1,
                    &eth_prices_store,
                ))
                .set("needsComplete", false);
            
            // 处理协议费用字段
            if let Some(fee_to) = &pending.fee_to {
                row.set("feeTo", fee_to);
                row.set("feeLiquidity", &BigDecimal::from_str(&pending.fee_liquidity.unwrap()).unwrap());
            }
            
            pending_burns_store.delete_prefix(0, &format!("pending_burn:{}", pending.id));
        }
    }
}
```

#### 2.12.3 Sync 事件的全局状态传播

**V2 Subgraph 逻辑**（`core.ts` handleSync）：

```typescript
// 1. 更新 Pair 储备
pair.reserve0 = convertTokenToDecimal(event.params.reserve0, token0.decimals);
pair.reserve1 = convertTokenToDecimal(event.params.reserve1, token1.decimals);

// 2. 计算 Pair 价格
pair.token0Price = pair.reserve0.div(pair.reserve1);
pair.token1Price = pair.reserve1.div(pair.reserve0);

// 3. 触发 ETH 价格重新计算
let bundle = Bundle.load('1');
bundle.ethPrice = getEthPriceInUSD();  // 遍历白名单 Pair

// 4. 更新所有 Token 的 derivedETH
token0.derivedETH = findEthPerToken(token0);  // 递归遍历所有 Pair
token1.derivedETH = findEthPerToken(token1);

// 5. 级联更新流动性
let trackedLiquidityETH = getTrackedLiquidityUSD(...).div(bundle.ethPrice);
pair.trackedReserveETH = trackedLiquidityETH;
pair.reserveUSD = pair.reserveETH.times(bundle.ethPrice);

// 6. 更新 Factory 全局 TVL
uniswap.totalLiquidityETH = uniswap.totalLiquidityETH.plus(trackedLiquidityETH);
uniswap.totalLiquidityUSD = uniswap.totalLiquidityETH.times(bundle.ethPrice);
```

**Substreams 等价实现（利用分层 Store）**：

```
状态传播链：

Sync 事件 
  ↓
store_pool_liquidities（存储 reserve0/reserve1）
  ↓ (被 store_prices 读取)
store_prices（计算 token0Price/token1Price，基于 reserve 比例）
  ↓ (被 store_eth_prices 读取)
store_eth_prices（计算 bundle.ethPrice 和 token.derivedETH）
  ├→ price::get_eth_price_in_usd()  // 遍历白名单 Pair
  └→ price::find_eth_per_token()     // 递归遍历所有 Pair
  ↓ (被 store_derived_tvl 读取)
store_derived_tvl（计算 pair.reserveUSD/ETH）
  ↓ (被 store_derived_factory_tvl 读取)
store_derived_factory_tvl（累加 factory.totalLiquidityUSD/ETH）
```

**关键实现**：

```rust
// store_eth_prices 复用 V3 逻辑，自动遍历白名单
#[substreams::handlers::store]
pub fn store_eth_prices(
    clock: Clock,
    events: Events,  // 包含 Sync 事件
    pools_store: StoreGetProto<Pool>,
    prices_store: StoreGetBigDecimal,  // 从 store_prices 读取最新价格
    tokens_whitelist_pools_store: StoreGetRaw,
    total_native_amount_store: StoreGetBigDecimal,
    pool_liquidities_store: StoreGetBigInt,  // 从 store_pool_liquidities 读取最新 reserve
    output: StoreSetBigDecimal,
) {
    for sync_event in events.sync_events {
        let pool_address = &sync_event.pool_address;
        let pool = pools_store.must_get_last(format!("pool:{pool_address}"));
        let token0_addr = &pool.token0_ref().address();
        let token1_addr = &pool.token1_ref().address();
        
        // 计算 ETH 价格（遍历白名单 Pair，与 V2 Subgraph 逻辑相同）
        let bundle_eth_price_usd = price::get_eth_price_in_usd(&prices_store, ord);
        
        // 计算 Token 派生价格（递归遍历所有 Pair，与 V2 Subgraph 逻辑相同）
        let token0_derived_eth = price::find_eth_per_token(
            ord,
            &pool.address,
            token0_addr,
            &pools_store,
            &pool_liquidities_store,
            &tokens_whitelist_pools_store,
            &total_native_amount_store,
            &prices_store,
        );
        
        let token1_derived_eth = price::find_eth_per_token(...);
        
        // 输出到 Store
        output.set(ord, "bundle", &bundle_eth_price_usd);
        output.set(ord, format!("token:{token0_addr}:dprice:eth"), &token0_derived_eth);
        output.set(ord, format!("token:{token1_addr}:dprice:eth"), &token1_derived_eth);
    }
}
```

**验证结论**：

✅ **Substreams 通过分层 Store 的声明式依赖，完全可以重现 V2 Subgraph 的状态机逻辑**

关键点：
1. **状态延续**：通过 `store_pending_mints`/`store_pending_burns` 模拟 Transaction 实体的状态传递
2. **事件顺序**：Substreams 保证同一区块内的事件按 `log.ordinal` 顺序处理
3. **状态传播**：分层 Store 的依赖链自动保证计算顺序（reserve → price → derivedETH → TVL）
4. **双阶段提交**：Transfer + Mint/Burn 的两阶段逻辑通过 pending store 实现

### 2.13 Schema 设计

方案 B 直接输出 Uniswap V3 的 GraphQL Schema，对 V2 需要的调整：

#### 保留的实体

```graphql
type Factory @entity {
  id: ID!
  poolCount: BigInt!
  txCount: BigInt!
  totalVolumeUSD: BigDecimal!
  totalVolumeETH: BigDecimal!
  untrackedVolumeUSD: BigDecimal!
  totalFeesUSD: BigDecimal!
  totalFeesETH: BigDecimal!
  totalValueLockedUSD: BigDecimal!
  totalValueLockedETH: BigDecimal!
  totalValueLockedUSDUntracked: BigDecimal!
  totalValueLockedETHUntracked: BigDecimal!
  owner: ID!
}

type Bundle @entity {
  id: ID!
  ethPriceUSD: BigDecimal!  # V2 命名为 ethPrice
}

type Token @entity {
  id: ID!
  symbol: String!
  name: String!
  decimals: BigInt!
  totalSupply: BigInt!
  volume: BigDecimal!
  volumeUSD: BigDecimal!
  untrackedVolumeUSD: BigDecimal!
  feesUSD: BigDecimal!
  txCount: BigInt!
  poolCount: BigInt!
  totalValueLocked: BigDecimal!
  totalValueLockedUSD: BigDecimal!
  totalValueLockedUSDUntracked: BigDecimal!
  derivedETH: BigDecimal!
  whitelistPools: [Pool!]!
  tokenDayData: [TokenDayData!]! @derivedFrom(field: "token")
}

type Pool @entity {  # V2 命名为 Pair
  id: ID!
  createdAtTimestamp: BigInt!
  createdAtBlockNumber: BigInt!
  token0: Token!
  token1: Token!
  feeTier: BigInt!  # V2 固定 3000 (0.3%)
  
  # V2 特有字段
  reserve0: BigDecimal!  # V3 为 totalValueLockedToken0
  reserve1: BigDecimal!  # V3 为 totalValueLockedToken1
  totalSupply: BigDecimal!  # LP Token 总供应量
  
  # 价格字段
  token0Price: BigDecimal!
  token1Price: BigDecimal!
  
  # 交易统计
  volumeToken0: BigDecimal!
  volumeToken1: BigDecimal!
  volumeUSD: BigDecimal!
  untrackedVolumeUSD: BigDecimal!
  feesUSD: BigDecimal!
  txCount: BigInt!
  
  # TVL
  totalValueLockedUSD: BigDecimal!  # V2 命名为 reserveUSD
  totalValueLockedETH: BigDecimal!  # V2 命名为 reserveETH
  totalValueLockedETHUntracked: BigDecimal!
  totalValueLockedUSDUntracked: BigDecimal!
  
  # V2 特有
  trackedReserveETH: BigDecimal!  # 用于全局统计的 tracked 流动性
  
  # 快照
  poolHourData: [PoolHourData!]! @derivedFrom(field: "pool")
  poolDayData: [PoolDayData!]! @derivedFrom(field: "pool")
  
  # 事件
  mints: [Mint!]! @derivedFrom(field: "pool")
  burns: [Burn!]! @derivedFrom(field: "pool")
  swaps: [Swap!]! @derivedFrom(field: "pool")
}
```

#### 删除的实体（V3 特有）

- `Tick`：V2 无 Tick 机制
- `Position`：V2 使用 LP Token 而非 NFT
- `PositionSnapshot`：V2 不需要
- `Collect`：V2 无单独的 Collect 操作
- `Flash`：V2 不支持闪电贷

### 2.14 实施路线

#### Phase 1：项目初始化（1 周）

1. **项目目录结构**

   Substreams 项目将集成到现有的 Monorepo 结构中，保持项目统一性：

   ```
   DripSwap_Monorepo/
   ├── apps/
   │   ├── frontend/           # React 前端
   │   ├── bff/                # Spring Boot BFF 后端
   │   ├── contracts/          # Foundry 智能合约
   │   ├── subgraph/           # The Graph 子图（待迁移）
   │   └── substream/          # 新增：Substreams 模块
   │       ├── proto/           # Protobuf 定义文件
   │       │   ├── uniswap/
   │       │   │   └── v2/
   │       │   │       ├── pair.proto
   │       │   │       └── factory.proto
   │       │   └── events.proto
   │       ├── abi/             # 合约 ABI 文件
   │       │   ├── factory.abi.json
   │       │   └── pair.abi.json
   │       ├── src/             # Rust 源码
   │       │   ├── lib.rs       # Store 模块主逻辑
   │       │   ├── db.rs        # EntityChanges 输出
   │       │   ├── events.rs    # 事件提取与处理
   │       │   ├── price.rs     # 价格计算逻辑
   │       │   └── utils.rs     # 工具函数
   │       ├── substreams.yaml  # Substreams 配置文件
   │       ├── Cargo.toml       # Rust 项目配置
   │       ├── schema.sql       # PostgreSQL Schema
   │       └── build.sh         # 编译脚本
   ├── packages/                # 共享包
   ├── docker-compose.yml       # 服务编排（新增 Substreams Sink）
   └── pnpm-workspace.yaml      # Monorepo 配置
   ```

   **Monorepo 集成优势**：
   - 共享 TypeScript 类型定义（BFF 与 Substreams Schema 同步）
   - 统一版本管理（pnpm workspace）
   - 一键启动所有服务（docker-compose）
   - CI/CD 流水线统一管理

2. **Fork V3 Substreams 项目**
   ```bash
   # 在 Monorepo 根目录下执行
   cd apps
   git clone https://github.com/streamingfast/substreams-uniswap-v3.git substream
   cd substream
   
   # 清理 V3 特有文件
   rm -rf .git  # 合并到主仓库的 git 中
   ```

3. **简化 Schema**
   - 删除 Tick/Position/Flash 相关定义
   - 重命名 `Pool` 为 `Pair`
   - 增加 V2 特有字段（`reserve0`/`reserve1`/`totalSupply`）

4. **修改 ABI**
   - 替换为 Uniswap V2 Factory ABI（复用 `apps/contracts/abi/UniswapV2Factory.json`）
   - 替换为 Uniswap V2 Pair ABI（复用 `apps/contracts/abi/UniswapV2Pair.json`）
   - 利用 Monorepo 的符号链接或文件复制，避免重复维护

#### Phase 2：事件处理适配（1-2 周）

1. **修改 `map_extract_data_types`**
   - 删除 Position 相关事件处理
   - 删除 Tick 相关事件处理
   - **增加 Sync 事件处理**：
     ```rust
     // 监听 Pair.Sync 事件
     use abi::pair::events::Sync;
     
     for (event, log) in block.events::<Sync>(&pool_addresses) {
         pool_sqrt_prices.push(PoolSqrtPrice {
             pool_address: Hex(&log.address).to_string(),
             sqrt_price: calculate_sqrt_price_from_reserves(
                 event.reserve0,
                 event.reserve1,
             ).to_string(),
             ordinal: log.ordinal(),
             initialized: false,
         });
     }
     ```

2. **修改 `store_pool_liquidities`**
   - 从 `StorageChange` 改为从 Sync 事件直接读取 `reserve0`/`reserve1`

3. **修改 `store_prices`**
   - 简化 sqrtPrice 转换逻辑（V2 直接 `reserve0/reserve1`）

#### Phase 3：测试与验证（1 周）

1. **本地测试**
   ```bash
   substreams gui substreams.yaml graph_out -t +1000
   ```

2. **数据对比**
   - 对比 V2 Subgraph 和 Substreams 的数据一致性

3. **性能优化**
   - 调整 Store 的 `delete_prefix` 策略
   - 优化 Key 设计

---

## 三、BFF 层适配与数据访问

### 3.1 表切换策略

#### 3.1.1 双表并存阶段

**目标**:实现无缝迁移,支持灰度发布和快速回滚

**表命名规则**:
- 现有表:`uniswap_factory`、`pairs`、`tokens` 等(来自 Subgraph)
- 新表:`uniswap_factory_stream`、`pairs_stream`、`tokens_stream` 等(来自 Substreams)

**数据同步阶段**:

```
阶段 1:初始化(1-2 天)
├─ Substreams 开始处理历史数据,写入 _stream 表
├─ BFF 仍然读取原表,业务不受影响
└─ 监控 Substreams 同步进度,等待达到最新区块

阶段 2:数据验证(3-5 天)
├─ 比对关键数据(TVL、价格、交易量)
├─ 检查数据一致性,容差 < 0.01%
└─ 修复任何发现的计算差异

阶段 3:灰度发布(1 周)
├─ 通过环境变量 `USE_STREAM_TABLES=true` 切换数据源
├─ 先在测试环境切换,运行 2-3 天
├─ 生产环境分批次切换:10% → 50% → 100%
└─ 实时监控 API 响应时间和错误率

阶段 4:清理阶段(稳定后 1 个月)
├─ 确认 Substreams 运行稳定
├─ 关闭 Subgraph 同步服务
├─ 删除 WebSocket 监听逻辑
└─ (可选)归档或删除原表
```

#### 3.1.2 BFF 代码适配

**核心修改**:

**1. 增加表名切换逻辑**

在 `apps/bff/src/database/uniswap-repository.ts` 中增加表名动态选择:

逻辑设计:
```
// 读取环境变量
const USE_STREAM_TABLES = process.env.USE_STREAM_TABLES === 'true';

// 获取表名的辅助函数
function getTableName(baseName: string): string {
  return USE_STREAM_TABLES ? `${baseName}_stream` : baseName;
}

// 使用示例
const factoryTable = getTableName('uniswap_factory');
const pairsTable = getTableName('pairs');
const tokensTable = getTableName('tokens');

// SQL 查询示例
const query = `
  SELECT * FROM ${pairsTable}
  WHERE token0 = $1 AND token1 = $2
`;
```

**2. 删除同步逻辑**

当 `USE_STREAM_TABLES=true` 时:
- 禁用 `SubgraphSyncService`
- 禁用 WebSocket 事件监听
- 直接读取 PostgreSQL 中的 `_stream` 表

**3. 缓存层调整**

由于 Substreams 数据直接写入数据库,可以简化缓存逻辑:
- 保留 Redis 缓存用于热点数据(如 TVL Top 10)
- 删除 Subgraph 响应缓存
- 调整 TTL:由 5 分钟降为 1 分钟(数据实时性提升)

### 3.2 API 层修改

#### 3.2.1 无需修改的接口

由于表结构完全兼容,以下接口无需修改:

- `GET /api/pools` - 池子列表
- `GET /api/pools/:id` - 池子详情
- `GET /api/tokens` - 代币列表
- `GET /api/tokens/:id` - 代币详情
- `GET /api/transactions` - 交易列表
- `GET /api/stats/overview` - 协议统计

#### 3.2.2 需要删除的接口

当切换到 Substreams 后,以下接口可以删除:

- `POST /api/sync/trigger` - 手动触发同步(不再需要)
- `GET /api/sync/status` - 同步状态查询(不再需要)
- WebSocket 相关路由(不再需要)

### 3.3 监控与告警

#### 3.3.1 Substreams 运行监控

**关键指标**:

| 指标名 | 监控项 | 告警阈值 | 说明 |
|---|---|---|---|
| `substreams_block_height` | 当前处理的区块高度 | 落后 > 10 区块 | 数据同步延迟 |
| `substreams_processing_rate` | 区块处理速度 (blocks/s) | < 5 blocks/s | 性能下降 |
| `postgres_write_latency` | 数据库写入延迟 | > 100ms | 数据库压力 |
| `postgres_connection_count` | 数据库连接数 | > 80% 池大小 | 连接池耗尽 |
| `substreams_error_rate` | 错误率 | > 0.1% | 处理错误 |

**监控实现**:

通过 `substreams-sink-postgres` 的日志输出解析或 Prometheus exporter 收集指标。

#### 3.3.2 数据一致性检查

**定时检查脚本**:

每小时运行一次,对比 Subgraph 表和 Substreams 表的关键数据:

检查项:
- 协议总 TVL 差异 < 0.01%
- Top 10 池子的 reserve 差异 < 0.01%
- 24小时交易量差异 < 0.1%
- 最近 100 笔交易记录完全匹配

告警机制:
- 差异超过阈值时,发送 Slack 通知
- 连续 3 次检查失败,触发紧急告警

---

## 四、部署与运维

### 4.1 部署架构

#### 4.1.1 组件拓扑

```
生产环境部署拓扑:

├─ StreamingFast Firehose (由 StreamingFast 托管)
│   └─ 提供实时区块数据流
│
├─ Substreams Runtime (自建服务器)
│   ├─ Docker 镜像:ghcr.io/streamingfast/substreams:latest
│   ├─ CPU:8 核,内存:16GB
│   └─ 高并发处理区块数据
│
├─ substreams-sink-postgres (自建服务器)
│   ├─ Docker 镜像:ghcr.io/streamingfast/substreams-sink-postgres:latest
│   ├─ 负责将 EntityChanges 写入 PostgreSQL
│   └─ 需要配置 DSN 和 Substreams endpoint
│
├─ PostgreSQL (RDS 或自建)
│   ├─ 实例规格:db.r5.xlarge(4 vCPU, 32GB 内存)
│   ├─ 存储:500GB SSD,启用 IOPS 优化
│   └─ 同时存储 Subgraph 和 Substreams 数据
│
└─ BFF (现有服务)
    ├─ 通过环境变量切换数据源
    └─ 无需重启,热更新配置
```

#### 4.1.2 网络拓扑

**内网通信**:
- Substreams Runtime → Firehose:gRPC,端口 9000
- substreams-sink-postgres → Substreams Runtime:gRPC,端口 9001
- substreams-sink-postgres → PostgreSQL:TCP 5432
- BFF → PostgreSQL:TCP 5432

**外网访问**:
- BFF API:HTTPS 443
- Substreams Runtime (可选):gRPC 暗杀门,用于调试

### 4.2 部署步骤

#### Phase 1:基础设施准备

**1. 申请 StreamingFast API Key**

前往 https://app.streamingfast.io/ 注册并申请 API Key。

**2. 准备服务器**

推荐配置:
- Substreams Runtime:8 vCPU, 16GB RAM
- substreams-sink-postgres:4 vCPU, 8GB RAM
- PostgreSQL:db.r5.xlarge (RDS)或同等配置

**3. 数据库准备**

创建 `_stream` 后缀表:

通过 `substreams-sink-postgres` 自动生成表结构,或手动运行 DDL 脚本。

#### Phase 2:Substreams 开发与测试

**1. Fork V3 Substreams 项目**

命令行操作:
```bash
git clone https://github.com/streamingfast/substreams-uniswap-v3.git dripswap-substreams-v2
cd dripswap-substreams-v2
```

**2. 修改 substreams.yaml**

更新配置:
- 替换 Factory 合约地址
- 替换 Pair ABI
- 删除 Position/Tick 相关模块

**3. 本地测试**

运行命令:
```bash
substreams gui substreams.yaml graph_out -e mainnet.eth.streamingfast.io:443 -t +1000
```

验证输出数据的正确性。

#### Phase 3:生产环境部署

**1. 打包 Substreams 模块**

运行命令:
```bash
substreams pack substreams.yaml
```

生成 `dripswap-v2-substreams-v1.0.0.spkg`。

**2. 启动 substreams-sink-postgres**

Docker Compose 配置:

配置示例:
```yaml
services:
  substreams-sink-postgres:
    image: ghcr.io/streamingfast/substreams-sink-postgres:latest
    environment:
      - SUBSTREAMS_ENDPOINT=mainnet.eth.streamingfast.io:443
      - SUBSTREAMS_API_KEY=${STREAMINGFAST_API_KEY}
      - DSN=postgresql://user:pass@postgres:5432/dripswap
      - MANIFEST_PATH=/app/dripswap-v2-substreams-v1.0.0.spkg
      - OUTPUT_MODULE=graph_out
    volumes:
      - ./dripswap-v2-substreams-v1.0.0.spkg:/app/dripswap-v2-substreams-v1.0.0.spkg
    restart: unless-stopped
```

**3. 监控同步进度**

查看日志:
```bash
docker logs -f substreams-sink-postgres
```

等待同步到最新区块(预计 1-2 天)。

**4. 数据验证**

运行验证脚本,对比 Subgraph 和 Substreams 数据的一致性。

**5. BFF 切换**

修改环境变量:
```bash
export USE_STREAM_TABLES=true
```

重启 BFF 服务,切换数据源。

### 4.3 常见运维任务

#### 4.3.1 数据备份

**PostgreSQL 备份策略**:
- 全量备份:每天 00:00 UTC
- 增量备份:每小时
- 保留周期:全量 30 天,增量 7 天

#### 4.3.2 故障恢复

**场景 1:Substreams 处理停止**

故障现象:
- `substreams_block_height` 不再增长
- sink-postgres 日志显示连接错误

解决方案:
1. 检查 Firehose 连接状态
2. 检查 API Key 是否过期
3. 重启 sink-postgres 服务
4. 如果持续失败,切回 Subgraph 数据源(设置 `USE_STREAM_TABLES=false`)

**场景 2:数据库写入延迟**

故障现象:
- `postgres_write_latency` > 100ms
- 数据同步落后超过 10 区块

解决方案:
1. 检查数据库 CPU 和磁盘 IOPS
2. 优化索引(确保 `id`、`timestamp` 等字段有索引)
3. 考虑升级数据库实例规格
4. 启用 sink-postgres 的 batch 模式(默认已启用)

#### 4.3.3 版本升级

**Substreams 模块升级流程**:

1. 在测试环境验证新版本
2. 生成新的 `.spkg` 文件
3. 更新 Docker Compose 配置中的 `MANIFEST_PATH`
4. 重启 sink-postgres 服务
5. 监控同步状态,确认无错误

**注意**:如果 Store 模块的 Key 格式发生变化,需要清空 `_stream` 表并重新同步历史数据。

---

## 五、总结与展望

### 5.1 方案优势

**技术优势**:
1. **架构简化**:删除 WebSocket 监听 + BFF 轮询同步的双重机制,降低系统复杂度
2. **数据实时性提升**:数据直接写入数据库,无需等待 Subgraph 同步延迟(1-5 分钟)
3. **性能提升**:Substreams 利用多核 CPU 并行处理,同步速度远超 Subgraph
4. **成本降低**:无需支付 The Graph 的查询费用,仅需 StreamingFast Firehose API 费用(月付 $100-500)
5. **可维护性**:基于 StreamingFast 官方实现,代码质量高,社区支持完善

**业务价值**:
1. **平滑迁移**:双表并存 + 环境变量切换,支持灰度发布和快速回滚
2. **业务无感知**:表结构完全兼容,BFF API 无需修改,前端不受影响
3. **扩展性**:支持多链部署,可快速复制到其他 EVM 链

### 5.2 风险与应对

**潜在风险**:

| 风险项 | 影响 | 概率 | 应对措施 |
|---|---|---|---|
| Substreams 处理错误 | 数据不完整 | 低 | 保留 Subgraph 数据源,支持快速回滚 |
| Firehose 服务中断 | 数据同步停止 | 低 | StreamingFast 提供 99.9% SLA,可切回 Subgraph |
| 数据库性能瓶颈 | 写入延迟 | 中 | 优化索引、升级实例规格、启用 batch 写入 |
| 数据一致性问题 | 业务逻辑错误 | 低 | 定时检查脚本 + 告警机制 |
| 迁移过程复杂 | 交付延期 | 中 | 分阶段实施,每个阶段充分验证 |

### 5.3 后续优化方向

**短期优化(1-3 个月)**:
1. **完善监控系统**:集成 Prometheus + Grafana,实时监控关键指标
2. **优化数据库索引**:根据实际查询模式,增加组合索引
3. **删除遗留代码**:清理 Subgraph 同步逻辑和 WebSocket 监听代码

**中期优化(3-6 个月)**:
1. **多链支持**:将 Substreams 扩展到 Polygon、Arbitrum 等链
2. **实时聚合层**:在 Substreams 中直接计算 24h 交易量、TVL 等指标,减少 BFF 计算压力
3. **历史数据归档**:将超过 6 个月的历史数据移至冷存储,降低数据库成本

**长期规划(6-12 个月)**:
1. **定制化 Substreams 模块**:根据 DripSwap 特有业务逻辑(如跨链 Bridge),开发定制化模块
2. **流式计算层**:将价格预警、异常交易检测等逻辑移至 Substreams,实现实时计算
3. **去中心化演进**:探索自建 Firehose 节点,降低对 StreamingFast 的依赖

### 5.4 成功标准

**技术指标**:
- 数据同步延迟 < 5 秒
- 数据一致性 > 99.99%
- API 响应时间 < 100ms(P95)
- Substreams 运行稳定性 > 99.9%

**业务指标**:
- 用户感知的故障时间 < 1 小时/月
- 运维成本降低 30%(相比 Subgraph + WebSocket 方案)
- 新链接入时间 < 1 周

---

## 附录

### 附录 A:参考资料

- [StreamingFast Substreams 官方文档](https://substreams.streamingfast.io/)
- [Uniswap V3 Substreams 官方实现](https://github.com/streamingfast/substreams-uniswap-v3)
- [substreams-sink-postgres 文档](https://github.com/streamingfast/substreams-sink-postgres)
- [Uniswap V2 Subgraph 源码](https://github.com/Uniswap/v2-subgraph)
- [Uniswap V2 Core 合约](https://github.com/Uniswap/v2-core)

### 附录 B:术语表

| 术语 | 全称 | 说明 |
|---|---|---|
| Substreams | StreamingFast Substreams | 基于 gRPC 流式处理的区块链数据索引引擎 |
| Firehose | StreamingFast Firehose | 提供实时区块数据流的基础设施 |
| Sink | Substreams Sink | 将 Substreams 输出写入目标系统(如 PostgreSQL)的组件 |
| Store | Substreams Store Module | 用于状态累积的模块类型,支持 set/add/min/max 等更新策略 |
| Map | Substreams Map Module | 无状态的数据转换模块,用于提取和处理事件 |
| EntityChanges | Graph Protocol EntityChanges | The Graph 协议的实体变更格式,用于数据库写入 |
| BFF | Backend For Frontend | 前端专用后端服务层 |
| TVL | Total Value Locked | 协议锁定的总价值 |
| OHLC | Open/High/Low/Close | 开盘价/最高价/最低价/收盘价 |
