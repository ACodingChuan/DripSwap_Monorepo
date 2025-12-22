# DripSwap V2 Substreams

基于 Uniswap V2 协议的 Substreams 数据索引模块，支持 Sepolia 和 Scroll Sepolia 测试网。

## 架构说明

本模块从 StreamingFast 的 `substreams-uniswap-v3` 项目适配而来，删除了 V3 特有的 Tick 和 Position NFT 功能，保留了与 V2 兼容的核心逻辑。

### V3 到 V2 的主要改动

| 功能 | V3 | V2 |
|------|----|----|
| **价格机制** | sqrtPriceX96 + Tick | reserve0/reserve1 比例 |
| **流动性管理** | Position NFT | LP Token |
| **手续费** | 0.05%/0.3%/1% 可配置 | 固定 0.3% |
| **事件** | Swap/Mint/Burn/Tick/Position | Swap/Mint/Burn/Sync |
| **状态存储** | Tick 流动性追踪 | Reserve 同步 |

## 项目结构

```
apps/substream/
├── abi/                          # 合约 ABI（复用 apps/contracts/abi）
│   ├── factory.abi.json         # Factory 合约
│   ├── pair.abi.json            # Pair 合约
│   ├── oracle.abi.json          # Oracle 价格预言机
│   └── bridge.abi.json          # Bridge 跨链桥
├── proto/uniswap/v1/            # Protobuf 定义
│   └── uniswap.proto
├── src/                         # Rust 源码
│   ├── lib.rs                   # 主模块（Store 和 Map 函数）
│   ├── db.rs                    # EntityChanges 输出
│   ├── filtering.rs             # 事件提取
│   ├── price.rs                 # 价格计算
│   ├── constants.rs             # 配置常量
│   ├── math.rs                  # 数学工具
│   ├── utils.rs                 # 通用工具
│   └── ...
├── substreams.yaml              # Sepolia 配置
├── substreams.scroll-sepolia.yaml # Scroll Sepolia 配置
├── Cargo.toml                   # Rust 项目配置
├── build.sh                     # 构建脚本
└── README.md                    # 本文档
```

## 配置说明

### 多链配置 (src/constants.rs)

```rust
// Factory 地址（两链相同 - 确定性部署）
pub const UNISWAP_V2_FACTORY: &str = "0x6c9258026a9272368e49bbb7d0a78c17bbe284bf";

// Oracle 地址（链特定）
pub const SEPOLIA_ORACLE_ETH_USD: &str = "0x694aa1769357215de4fac081bf1f309adc325306";
pub const SCROLL_SEPOLIA_ORACLE_ETH_USD: &str = "0x59f1ec1f10bd7ed9b938431086bc1d9e233ecf41";

// 初始区块号
pub const SEPOLIA_INITIAL_BLOCK: u64 = 9573280;
pub const SCROLL_SEPOLIA_INITIAL_BLOCK: u64 = 14731854;

// 白名单代币（两链相同 - 确定性部署）
pub static WHITELIST_TOKENS: phf::Set<&'static str> = phf_set! {
    "0xe91d02e66a9152fee1bc79c1830121f6507a4f6d", // vETH
    "0x46a906fca4487c87f0d89d2d0824ec57bdaa947d", // vUSDC
    "0xbacdbe38df8421d0aa90262beb1c20d32a634fe7", // vUSDT
    "0x0c156e2f45a812ad743760a88d73fb22879bc299", // vDAI
    "0xaea8c2f08b10fe1853300df4332e462b449e19d6", // vBTC
    "0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454", // vLINK
};
```

## 快速开始

### 1. 安装依赖

```bash
# 安装 Rust 工具链
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source "$HOME/.cargo/env"

# 添加 WASM 目标
rustup target add wasm32-unknown-unknown

# 安装 Substreams CLI
brew install streamingfast/tap/substreams

# 安装 substreams-sink-postgres
brew install streamingfast/tap/substreams-sink-postgres
```

### 2. 编译和打包

```bash
# 使用构建脚本（推荐）
./build.sh

# 或手动执行
cargo build --target wasm32-unknown-unknown --release
substreams pack substreams.yaml
substreams pack substreams.scroll-sepolia.yaml
```

生成的 SPKG 文件:
- `dripswap-v2-sepolia-v0.1.0.spkg` (Sepolia)
- `dripswap-v2-scroll-sepolia-v0.1.0.spkg` (Scroll Sepolia)

### 3. 测试模块

```bash
# 测试 Sepolia（查看最近 100 个区块的事件）
substreams gui substreams.yaml map_extract_data_types -t +100

# 测试 Scroll Sepolia
substreams gui substreams.scroll-sepolia.yaml map_extract_data_types -t +100
```

### 4. 启动 Sink

#### Sepolia

```bash
substreams-sink-postgres run \
  "postgresql://user:pass@localhost:5432/dripswap?sslmode=disable" \
  "https://sepolia.substreams.pinax.network:443" \
  "dripswap-v2-sepolia-v0.1.0.spkg" \
  graph_out \
  --api-token="cd6d1326907fb01ac311507e73f286371de5703f495c1dc4" \
  --on-module-hash-mistmatch=warn \
  --undo-buffer-size=12
```

#### Scroll Sepolia

```bash
substreams-sink-postgres run \
  "postgresql://user:pass@localhost:5432/dripswap?sslmode=disable" \
  "https://scrsepolia.substreams.pinax.network:443" \
  "dripswap-v2-scroll-sepolia-v0.1.0.spkg" \
  graph_out \
  --api-token="cd6d1326907fb01ac311507e73f286371de5703f495c1dc4" \
  --on-module-hash-mistmatch=warn \
  --undo-buffer-size=12
```

## Substreams 模块说明

### Map 模块

| 模块名 | 输入 | 输出 | 说明 |
|--------|------|------|------|
| `map_pools_created` | Block | Pools | 监听 PairCreated 事件 |
| `map_tokens_whitelist_pools` | Pools | ERC20Tokens | 标记白名单代币 |
| `map_extract_data_types` | Block, Pools | Events | 提取 Swap/Mint/Burn/Sync 事件 |

### Store 模块

| 模块名 | UpdatePolicy | ValueType | 说明 |
|--------|--------------|-----------|------|
| `store_pools_created` | set | Pool | 存储 Pair 元数据 |
| `store_tokens` | add | int64 | Token 使用计数 |
| `store_pool_count` | add | bigint | Pair 总数 |
| `store_prices` | set | bigdecimal | Token 价格（reserve 比例） |
| `store_pool_liquidities` | set | bigint | Pair 的 reserve0/reserve1 |
| `store_eth_prices` | set | bigdecimal | ETH/USD 价格和 derivedETH |
| `store_token_tvl` | add | bigdecimal | Token TVL 累加 |
| `store_derived_tvl` | set | bigdecimal | USD/ETH 计价的 TVL |
| `store_derived_factory_tvl` | add | bigdecimal | Factory 全局 TVL |
| `store_swaps_volume` | add | bigdecimal | 交易量累加 |
| `store_min_windows` | min | bigdecimal | OHLC 低价 |
| `store_max_windows` | max | bigdecimal | OHLC 高价 |

### 输出模块

- **graph_out**: 输出 EntityChanges，包含 Pair/Token/Transaction/Swap/Mint/Burn 等实体

## 数据库表结构

Sink 会自动创建以下表（带 `_stream` 后缀）：

### 核心实体（6 张）
- `uniswap_factory_stream` - 协议全局统计
- `tokens_stream` - 代币元数据
- `pairs_stream` - 交易对信息
- `bundle_stream` - ETH 基准价格
- `users_stream` - 用户地址集合
- `transactions_stream` - 交易哈希

### 事件表（3 张）
- `swaps_stream` - 兑换事件
- `mints_stream` - 添加流动性事件
- `burns_stream` - 移除流动性事件

### 时序聚合表（6 张）
- `uniswap_day_data_stream` - 协议日维度统计
- `pair_day_data_stream` - Pair 日维度统计
- `pair_hour_data_stream` - Pair 小时维度统计
- `token_day_data_stream` - Token 日维度 OHLC
- `token_hour_data_stream` - Token 小时维度 OHLC
- `token_minute_data_stream` - Token 分钟维度 OHLC

### Bridge 相关表（2 张）
- `bridge_transfers_stream` - 跨链转账记录
- `bridge_config_events_stream` - Bridge 配置变更

### 索引表（1 张）
- `pair_token_lookup_stream` - Pair-Token 索引

## 开发说明

### 修改代码后重新编译

```bash
# 1. 修改 Rust 代码
# 2. 重新编译
./build.sh

# 3. 重启 Sink（Sink 会自动检测 SPKG 变化）
```

### 调试模式

```bash
# 启用详细日志
substreams gui substreams.yaml map_extract_data_types \
  -t +100 \
  --log-level=debug
```

### 常见问题

**Q: 编译报错 `can't find crate for 'core'`**
A: 需要添加 wasm32 target：`rustup target add wasm32-unknown-unknown`

**Q: Sink 报错 `module hash mismatch`**
A: 添加 `--on-module-hash-mistmatch=warn` 参数

**Q: 数据同步慢**
A: Substreams 首次同步历史数据需要时间，可以从最新区块开始：`--start-block=-100`

## 监控与维护

### 查看同步进度

```bash
# 查询最新区块
SELECT MAX(created_at_block_number) FROM pairs_stream WHERE chain_id = 11155111;

# 查看 Pair 数量
SELECT chain_id, chain_name, COUNT(*) FROM pairs_stream GROUP BY chain_id, chain_name;
```

### 性能优化

1. **数据库索引**: Sink 会自动创建必要的索引
2. **批量写入**: Sink 默认启用批量写入
3. **并行处理**: Substreams 自动利用多核 CPU

## 参考文档

- [Substreams 官方文档](https://substreams.streamingfast.io/)
- [Uniswap V2 白皮书](https://uniswap.org/whitepaper.pdf)
- [StreamingFast Uniswap V3 实现](https://github.com/streamingfast/substreams-uniswap-v3)
- [DripSwap 架构设计文档](../../.qoder/quests/subgraph-to-substream-architecture.md)

## License

MIT
