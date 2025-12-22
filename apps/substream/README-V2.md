# DripSwap Uniswap V2 Substreams

基于 StreamingFast Uniswap V3 Substreams 改造的 Uniswap V2 数据索引方案，用于 DripSwap DEX 项目。

## 项目状态

**✅ Phase 1 已完成**（项目初始化）

### 已完成的工作

1. **项目结构搭建**
   - 从 `substreams-uniswap-v3` 复制基础代码到 `apps/substream/`
   - 清理 git 关联（`.git`、`.sfreleaser`、`.gitignore`、`CONTRIBUTING.md`）
   - 集成到 DripSwap Monorepo 结构

2. **ABI 文件配置**
   - 删除 V3 ABI（pool.json、NonfungiblePositionManager.json）
   - 复用 Subgraph 的 V2 ABI：
     - `factory.json` - Factory 合约
     - `pair.json` - Pair 合约
     - `ERC20.json` - ERC20 代币
     - `oracle.json` - 价格预言机
     - `bridge.json` - 跨链桥

3. **Protobuf Schema 适配**
   - 创建 `proto/uniswap/v2/uniswap.proto`
   - 删除 V3 特有定义（Tick、Position、Flash）
   - 新增 V2 特有定义：
     - `Pair` 替代 `Pool`（包含 reserve0/reserve1/totalSupply）
     - `PairReserves` 消息（Sync 事件）
     - `Transfer` 消息（LP Token 追踪）
     - `PendingMint` / `PendingBurn`（双阶段提交状态）

4. **Substreams 配置更新**
   - 创建 `substreams-v2.yaml`
   - 调整模块依赖链：
     - `map_pairs_created` 替代 `map_pools_created`
     - 新增 `store_pair_reserves`（存储 Sync 事件的 reserve）
     - 新增 `store_pending_mints` / `store_pending_burns`
     - 删除 `store_ticks_liquidities` 和 `store_positions`
   - 配置目标网络：Sepolia (initialBlock: 0)

5. **Rust 项目配置**
   - 更新 `Cargo.toml`（项目名改为 `substreams-uniswap-v2`）
   - 更新 `build.rs`（生成 V2 相关 ABI Rust 绑定）

## 目录结构

```
apps/substream/
├── abis/                    # V2 合约 ABI（来自 apps/subgraph/uniswap/abis）
│   ├── factory.json
│   ├── pair.json
│   ├── ERC20.json
│   ├── oracle.json
│   └── bridge.json
├── proto/uniswap/v2/        # V2 Protobuf 定义
│   └── uniswap.proto
├── src/                     # Rust 源码（待 Phase 2 改造）
│   ├── lib.rs
│   ├── db.rs
│   ├── events.rs
│   ├── price.rs
│   └── ...
├── substreams-v2.yaml       # V2 Substreams 配置
├── Cargo.toml
├── build.rs
└── README.md
```

## 下一步工作

### Phase 2：事件处理适配（1-2 周）

需要修改 Rust 代码以适配 V2 事件逻辑：

1. **修改 `map_pairs_created`**
   ```rust
   // 监听 Factory.PairCreated 事件
   // V2 事件签名：PairCreated(address indexed token0, address indexed token1, address pair, uint)
   ```

2. **修改 `map_extract_data_types`**
   - 删除 Position 相关事件处理
   - 删除 Tick 相关事件处理
   - **新增 Sync 事件处理**：
     ```rust
     use abi::pair::events::Sync;
     
     for (event, log) in block.events::<Sync>(&pair_addresses) {
         // 提取 reserve0, reserve1
         pair_reserves.push(PairReserves {
             pair_address: log.address,
             reserve0: event.reserve0.to_string(),
             reserve1: event.reserve1.to_string(),
             ...
         });
     }
     ```
   - **新增 Transfer 事件处理**（追踪 LP Token totalSupply）

3. **修改 `store_pair_reserves`**
   - 从 Sync 事件直接读取 reserve0/reserve1
   - V3 使用 StorageChange，V2 使用事件驱动

4. **修改 `store_prices`**
   - 简化价格计算：`token0Price = reserve0 / reserve1`
   - V3 需要 sqrtPriceX96 转换，V2 直接比例计算

5. **新增 V2 状态机逻辑**
   - 实现 `store_pending_mints` / `store_pending_burns`
   - 处理 Transfer → Mint/Burn 的双阶段提交
   - 检测协议费铸造（feeMint）

6. **修改价格计算模块**
   - 保留 `find_eth_per_token` 逻辑（白名单 Pair 遍历）
   - 调用 Oracle 合约获取 ETH/USD 价格

### Phase 3：测试与验证（1 周）

1. **本地测试**
   ```bash
   # 编译
   cargo build --release --target wasm32-unknown-unknown
   
   # 测试
   substreams gui substreams-v2.yaml graph_out -t +1000
   ```

2. **数据对比**
   - 对比 V2 Subgraph 和 Substreams 的数据一致性
   - 重点检查：Pair reserves、价格、TVL、交易量

3. **性能优化**
   - 调整 Store 的 `delete_prefix` 策略
   - 优化 Key 设计

## 配置信息

### 目标网络

| 网络 | Chain ID | Substreams 端点 |
|------|----------|----------------|
| Sepolia | 11155111 | https://sepolia.substreams.pinax.network:443 |
| Scroll Sepolia | 534351 | https://scrsepolia.substreams.pinax.network:443 |

### Pinax 认证

```bash
# API Key
export PINAX_API_KEY=cd6d1326907fb01ac311507e73f286371de5703f495c1dc4

# JWT Token
export PINAX_JWT_TOKEN="eyJhbGciOiJSUzI1NiIsImtpZCI6InN0cmVhbWluZ2Zhc3Qta2V5In0..."
```

### 合约地址（待配置）

需要在 `src/constants.rs` 中配置：

```rust
// Sepolia
pub const FACTORY_ADDRESS: &str = "0x...";  // 从部署配置获取
pub const ORACLE_ETH_USD: &str = "0x...";   // Chainlink Oracle 地址

// Scroll Sepolia
pub const SCROLL_FACTORY_ADDRESS: &str = "0x...";
pub const SCROLL_ORACLE_ETH_USD: &str = "0x...";
```

## 与 Monorepo 集成

### 数据库配置

Substreams 输出将通过 `substreams-sink-postgres` 写入同一 PostgreSQL：

```yaml
# docker-compose.yml
services:
  postgres:
    environment:
      - POSTGRES_DB=dripswap
      
  substreams-sink-sepolia:
    environment:
      - DSN=postgresql://dripswap_user:${DB_PASSWORD}@postgres:5432/dripswap
      - CHAIN_ID=11155111
      - CHAIN_NAME=sepolia
      
  substreams-sink-scroll:
    environment:
      - DSN=postgresql://dripswap_user:${DB_PASSWORD}@postgres:5432/dripswap
      - CHAIN_ID=534351
      - CHAIN_NAME=scroll-sepolia
```

### 表命名约定

- 新表：`{entity}_stream`（如 `pairs_stream`、`tokens_stream`）
- 旧表：保持原名（如 `pairs`、`tokens`）
- 主键：`(id, chain_id)` 复合主键

## 参考资料

- [StreamingFast Substreams 文档](https://substreams.streamingfast.io/)
- [Uniswap V3 Substreams 官方实现](https://github.com/streamingfast/substreams-uniswap-v3)
- [Uniswap V2 Subgraph 源码](https://github.com/Uniswap/v2-subgraph)
- [设计文档](../../.qoder/quests/subgraph-to-substream-architecture.md)

## 注意事项

1. **不要修改原 V3 代码**：当前 `src/` 目录下的代码仍是 V3 实现，Phase 2 将进行适配
2. **保持 Monorepo 结构**：所有路径引用使用相对路径，不要硬编码绝对路径
3. **多链支持**：代码编写时考虑 Sepolia 和 Scroll Sepolia 的兼容性
4. **测试优先**：每个模块改造完成后立即测试，避免累积问题
