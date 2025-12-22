# Phase 2 执行计划

开始时间：2025-12-22

---

## 一、Phase 2 目标

将 Uniswap V3 的 Rust 代码适配为 Uniswap V2，实现：
1. 删除 V3 特有功能（Tick、Position、Flash）
2. 新增 V2 特有功能（Sync 事件、Transfer 事件、Pending 状态机）
3. 简化价格计算逻辑（从 sqrtPrice 改为 reserve 比例）
4. 适配 V2 事件结构

---

## 二、需要修改的文件清单

### 核心文件（必须修改）

| 文件 | 当前行数 | 修改类型 | 优先级 |
|------|---------|---------|--------|
| `src/lib.rs` | 1395行 | 重度改造 | ⭐⭐⭐ |
| `src/filtering.rs` | 未知 | 重度改造 | ⭐⭐⭐ |
| `src/price.rs` | 未知 | 中度改造 | ⭐⭐⭐ |
| `src/db.rs` | 67.5KB | 重度改造 | ⭐⭐⭐ |
| `src/utils.rs` | 11KB | 轻度改造 | ⭐⭐ |

### 需要删除的文件

| 文件 | 原因 |
|------|------|
| `src/ticks_idx.rs` | V3 特有（Tick 索引） |
| `src/storage/` | V3 特有（Position 存储） |

### 需要新增的文件

| 文件 | 用途 |
|------|------|
| `src/constants.rs` | 合约地址、白名单配置 |
| `src/v2_events.rs` | V2 事件提取逻辑 |
| `src/pending.rs` | Pending Mint/Burn 状态机 |

---

## 三、详细改造计划

### 3.1 创建 `src/constants.rs`

**目的**：集中管理合约地址和白名单配置

**内容**：
```rust
// Sepolia 配置
pub const SEPOLIA_FACTORY: &str = "0x...";  // 待从部署配置获取
pub const SEPOLIA_ORACLE_ETH_USD: &str = "0x...";

// Scroll Sepolia 配置  
pub const SCROLL_FACTORY: &str = "0x...";
pub const SCROLL_ORACLE_ETH_USD: &str = "0x...";

// 白名单代币（Sepolia）
pub const SEPOLIA_WHITELIST_TOKENS: [&str; N] = [
    "0x...",  // WETH
    "0x...",  // USDC
    // ...
];

// 白名单代币（Scroll Sepolia）
pub const SCROLL_WHITELIST_TOKENS: [&str; N] = [
    "0x...",  // WETH
    "0x...",  // USDC
    // ...
];
```

### 3.2 修改 `src/lib.rs`

#### 改动 1：修改 imports

**删除**：
```rust
use crate::pb::uniswap::events::position_event::Type::{...};
use crate::pb::uniswap::events::{PoolSqrtPrice, PositionEvent};
// 所有 Tick、Position 相关 import
```

**新增**：
```rust
use crate::pb::uniswap::events::{PairReserves, Transfer, PendingMint, PendingBurn};
use crate::pending::{handle_transfer_for_pending, complete_mint, complete_burn};
```

#### 改动 2：`map_pools_created` 改为 `map_pairs_created`

**修改前**（V3）：
```rust
#[substreams::handlers::map]
pub fn map_pools_created(block: Block) -> Result<Pools, Error> {
    use abi::factory::events::PoolCreated;
    // ...处理 PoolCreated 事件
}
```

**修改后**（V2）：
```rust
#[substreams::handlers::map]
pub fn map_pairs_created(block: Block) -> Result<Pairs, Error> {
    use abi::factory::events::PairCreated;
    
    Ok(Pairs {
        pairs: block
            .events::<PairCreated>(&[&UNISWAP_V2_FACTORY])
            .filter_map(|(event, log)| {
                let token0_address = Hex(&event.token0).to_string();
                let token1_address = Hex(&event.token1).to_string();
                
                Some(Pair {
                    address: Hex(&event.pair).to_string(),
                    transaction_id: Hex(&log.receipt.transaction.hash).to_string(),
                    created_at_block_number: block.number,
                    created_at_timestamp: block.timestamp_seconds(),
                    log_ordinal: log.ordinal(),
                    token0: Some(rpc::create_uniswap_token(&token0_address)?),
                    token1: Some(rpc::create_uniswap_token(&token1_address)?),
                    reserve0: "0".to_string(),  // 初始储备为 0
                    reserve1: "0".to_string(),
                    total_supply: "0".to_string(),
                    ..Default::default()
                })
            })
            .collect(),
    })
}
```

#### 改动 3：修改 `map_extract_data_types`

**删除的事件提取**：
- Tick 相关（`extract_ticks_created`, `extract_ticks_updated`）
- Position 相关（`extract_positions_*`）
- Flash 相关

**新增的事件提取**：
```rust
// V2: Sync 事件
use abi::pair::events::Sync;
for (event, log) in block.events::<Sync>(&pair_addresses) {
    pair_reserves.push(PairReserves {
        pair_address: Hex(&log.address).to_string(),
        reserve0: event.reserve0.to_string(),
        reserve1: event.reserve1.to_string(),
        ordinal: log.ordinal(),
        token0: pair.token0_ref().address(),
        token1: pair.token1_ref().address(),
        log_ordinal: log.ordinal(),
    });
}

// V2: Transfer 事件（追踪 LP Token）
use abi::pair::events::Transfer;
for (event, log) in block.events::<Transfer>(&pair_addresses) {
    handle_transfer_for_pending(
        &event,
        log,
        &mut pending_mints,
        &mut pending_burns,
    );
}
```

### 3.3 创建 `src/pending.rs`

**目的**：实现 V2 的双阶段提交状态机

**核心函数**：

```rust
use crate::pb::uniswap::{PendingMint, PendingBurn};

pub fn handle_transfer_for_pending(
    event: &Transfer,
    log: &Log,
    pending_mints: &mut Vec<PendingMint>,
    pending_burns: &mut Vec<PendingBurn>,
) {
    let pair_address = Hex(&log.address).to_string();
    let from = Hex(&event.from).to_string();
    let to = Hex(&event.to).to_string();
    let value = event.value.to_string();
    
    // 场景 1：from = 0x0 -> Mint
    if from == "0x0000000000000000000000000000000000000000" {
        pending_mints.push(PendingMint {
            id: format!("{}-{}", log.receipt.transaction.hash, log.index),
            pair: pair_address,
            to: to.clone(),
            liquidity: value,
            transaction: Hex(&log.receipt.transaction.hash).to_string(),
            timestamp: log.block.timestamp_seconds(),
            log_ordinal: log.ordinal(),
        });
    }
    
    // 场景 2：to = pair 地址 -> Burn 准备
    else if to == pair_address {
        pending_burns.push(PendingBurn {
            id: format!("{}-{}", log.receipt.transaction.hash, log.index),
            pair: pair_address,
            liquidity: value,
            transaction: Hex(&log.receipt.transaction.hash).to_string(),
            timestamp: log.block.timestamp_seconds(),
            log_ordinal: log.ordinal(),
            needs_complete: true,
            fee_to: None,
            fee_liquidity: None,
        });
    }
    
    // 场景 3：to = 0x0 且 from = pair -> Burn 确认
    else if to == "0x0000000000000000000000000000000000000000" && from == pair_address {
        // 检测 feeMint（协议费）
        // 详细逻辑参考设计文档 2.12.1
    }
}

pub fn complete_mint(
    mint_event: &MintEvent,
    pending_mints: &[PendingMint],
) -> Option<Mint> {
    // 从 pending_mints 中找到对应的 Transfer 记录
    // 补充 amount0、amount1、sender 等字段
}

pub fn complete_burn(
    burn_event: &BurnEvent,
    pending_burns: &[PendingBurn],
) -> Option<Burn> {
    // 从 pending_burns 中找到对应的 Transfer 记录
    // 补充完整信息
}
```

### 3.4 修改 `src/price.rs`

#### 改动 1：简化价格计算

**删除**：
```rust
// V3: sqrtPriceX96 转换逻辑
pub fn sqrt_price_x96_to_token_prices(...) { ... }
```

**新增**：
```rust
// V2: 简单的比例计算
pub fn calculate_token_prices(
    reserve0: &BigDecimal,
    reserve1: &BigDecimal,
) -> (BigDecimal, BigDecimal) {
    let token0_price = if !reserve1.is_zero() {
        reserve0.div(reserve1)
    } else {
        BigDecimal::zero()
    };
    
    let token1_price = if !reserve0.is_zero() {
        reserve1.div(reserve0)
    } else {
        BigDecimal::zero()
    };
    
    (token0_price, token1_price)
}
```

#### 改动 2：新增 Oracle 调用

**新增**：
```rust
use abi::oracle;

pub fn get_eth_price_in_usd(oracle_address: &str) -> Result<(BigDecimal, BigInt), Error> {
    let oracle_contract = oracle::Oracle::new(oracle_address);
    let round_data = oracle_contract.latest_round_data()?;
    
    let decimals = oracle_contract.decimals().unwrap_or(8);
    let factor = BigDecimal::from(10u64.pow(decimals as u32));
    
    let price = BigDecimal::from_str(&round_data.answer.to_string())
        .unwrap()
        .div(&factor);
    
    Ok((price, round_data.round_id))
}
```

### 3.5 修改 `src/db.rs`

#### 改动 1：调整实体输出

**删除**：
- Position 相关实体
- Tick 相关实体
- PoolHourData 的 tick 字段

**修改**：
- `Pool` 改为 `Pair`
- 新增 `reserve0`、`reserve1`、`totalSupply` 字段
- 新增 `chainId`、`chainName` 字段（从环境变量读取）

**示例**：
```rust
pub fn pair_entity_changes(
    tables: &mut Tables,
    pairs: &[Pair],
    chain_id: i64,
    chain_name: &str,
) {
    for pair in pairs {
        tables
            .create_row("Pair", &pair.address)
            .set("chainId", chain_id)
            .set("chainName", chain_name)
            .set("token0", &pair.token0_ref().address())
            .set("token1", &pair.token1_ref().address())
            .set("reserve0", &pair.reserve0)
            .set("reserve1", &pair.reserve1)
            .set("totalSupply", &pair.total_supply)
            .set("createdAtTimestamp", pair.created_at_timestamp)
            .set("createdAtBlockNumber", pair.created_at_block_number);
    }
}
```

### 3.6 修改 `src/utils.rs`

#### 改动：更新常量

**删除**：
```rust
pub const UNISWAP_V3_FACTORY: &str = "0x...";
```

**新增**：
```rust
pub const UNISWAP_V2_FACTORY: &str = "0x...";  // 从 constants.rs 读取
```

---

## 四、改造步骤（执行顺序）

### Step 1：准备工作
1. ✅ 创建 `src/constants.rs`（待配置合约地址）
2. ✅ 创建 `src/pending.rs`（状态机逻辑）
3. ✅ 创建 `src/v2_events.rs`（事件提取）

### Step 2：删除 V3 代码
1. ❌ 删除 `src/ticks_idx.rs`
2. ❌ 删除 `src/storage/`
3. ❌ 从 `src/lib.rs` 删除相关 import 和函数

### Step 3：核心改造
1. ⏭️ 修改 `src/lib.rs`
   - `map_pools_created` → `map_pairs_created`
   - `map_extract_data_types` 适配 V2 事件
   - 删除 Tick/Position 相关模块

2. ⏭️ 修改 `src/filtering.rs`（如果存在）
   - 删除 Tick/Position 提取
   - 新增 Sync/Transfer 提取

3. ⏭️ 修改 `src/price.rs`
   - 简化价格计算
   - 新增 Oracle 调用
   - 保留 `find_eth_per_token`

4. ⏭️ 修改 `src/db.rs`
   - Pool → Pair
   - 新增 chain_id/chain_name
   - 删除 Position/Tick 实体

5. ⏭️ 修改 `src/utils.rs`
   - 更新常量

### Step 4：编译测试
1. ⏭️ 运行 `./build-v2.sh`
2. ⏭️ 修复编译错误
3. ⏭️ 本地测试

---

## 五、风险与应对

| 风险 | 级别 | 应对措施 |
|------|------|---------|
| Proto 路径不匹配 | 高 | 需要先编译 proto 生成 Rust 绑定 |
| ABI 绑定缺失 | 高 | 运行 build.rs 生成 |
| 状态机逻辑复杂 | 中 | 严格按照设计文档实现 |
| Oracle 调用失败 | 中 | 添加错误处理和降级逻辑 |

---

## 六、所需信息清单

### 必须配置（Phase 2 开始前）

1. **Sepolia 合约地址**：
   - [ ] Factory 地址
   - [ ] Oracle 地址（ETH/USD）
   - [ ] 白名单代币地址（WETH、USDC、USDT、DAI）

2. **Scroll Sepolia 合约地址**：
   - [ ] Factory 地址
   - [ ] Oracle 地址（ETH/USD）
   - [ ] 白名单代币地址

3. **初始区块号**：
   - [ ] Sepolia Factory 部署区块
   - [ ] Scroll Sepolia Factory 部署区块

---

## 七、预估工作量

| 任务 | 预估时间 | 复杂度 |
|------|---------|--------|
| 创建新文件 | 2-3 小时 | 中 |
| 删除 V3 代码 | 1-2 小时 | 低 |
| 修改 lib.rs | 4-6 小时 | 高 |
| 修改 price.rs | 2-3 小时 | 中 |
| 修改 db.rs | 3-4 小时 | 高 |
| 编译调试 | 4-6 小时 | 高 |

**总计**：16-24 小时（2-3 个工作日）

---

## 八、下一步行动

当前状态：**等待合约地址配置**

一旦获得合约地址，立即开始：
1. 填写 `src/constants.rs`
2. 执行 Step 1-4
3. 编译测试

---

**文档版本**：v1.0  
**创建时间**：2025-12-22  
**状态**：规划完成，等待执行
