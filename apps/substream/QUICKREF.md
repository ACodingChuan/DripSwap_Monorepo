# Phase 1 快速参考

## 📋 完成清单

- [x] 项目结构搭建（`apps/substream/`）
- [x] ABI 文件替换（从 Subgraph 复用）
- [x] Proto 定义创建（`proto/uniswap/v2/uniswap.proto`）
- [x] Substreams 配置（`substreams-v2.yaml`）
- [x] Rust 项目配置（`Cargo.toml`、`build.rs`）
- [x] 文档与脚本（README、构建脚本）

## 📂 关键文件位置

```
apps/substream/
├── abis/                       # V2 ABI 文件
├── proto/uniswap/v2/           # V2 Proto 定义
├── substreams-v2.yaml          # V2 配置
├── Cargo.toml                  # Rust 配置
├── build.rs                    # ABI 生成配置
├── build-v2.sh                 # 构建脚本
├── README-V2.md                # 项目文档
├── PHASE1-SUMMARY.md           # Phase 1 总结
└── PHASE1-VERIFICATION.md      # 验证报告
```

## 🚀 快速命令

```bash
# 进入项目目录
cd apps/substream

# 查看文档
cat README-V2.md
cat PHASE1-SUMMARY.md

# 查看配置
cat substreams-v2.yaml

# 查看 Proto 定义
cat proto/uniswap/v2/uniswap.proto

# （Phase 2）编译项目
./build-v2.sh
```

## ⚠️ 重要提醒

1. **不要尝试编译**：`src/` 目录仍是 V3 代码，需要 Phase 2 改造
2. **Proto 路径**：新代码使用 `uniswap.types.v2`，旧代码使用 `uniswap.types.v1`
3. **ABI 文件**：已从 `apps/subgraph/uniswap/abis/` 复制，不是符号链接

## 📚 文档索引

| 文档 | 用途 |
|------|------|
| `README-V2.md` | 项目说明、配置信息、后续步骤 |
| `PHASE1-SUMMARY.md` | Phase 1 完成总结、验证清单 |
| `PHASE1-VERIFICATION.md` | 详细验证报告、风险评估 |
| `QUICKREF.md` | 本文件（快速参考） |

## 🔧 Phase 2 准备

### 需要的信息

1. **合约地址**（从部署配置获取）：
   - Sepolia Factory 地址
   - Scroll Sepolia Factory 地址
   - Oracle 地址（两条链）

2. **白名单代币**：
   - WETH、USDC、USDT、DAI 等
   - 每条链的地址

3. **初始区块**：
   - Sepolia Factory 部署区块
   - Scroll Sepolia Factory 部署区块

### 主要任务

1. 修改 `src/events.rs` - 事件提取
2. 修改 `src/price.rs` - 价格计算
3. 修改 `src/db.rs` - 数据库输出
4. 新增 `src/constants.rs` - 常量配置
5. 修改 `src/lib.rs` - 模块导出

## 📊 模块依赖图

```
map_pairs_created
    ↓
store_pairs_created
    ↓
map_extract_data_types
    ↓
store_pair_reserves (Sync 事件)
    ↓
store_prices (token0Price/token1Price)
    ↓
store_eth_prices (ETH/USD + derivedETH)
    ↓
store_derived_tvl (USD/ETH TVL)
    ↓
graph_out (EntityChanges)
```

## 🌐 网络配置

| 网络 | Chain ID | Endpoint |
|------|----------|----------|
| Sepolia | 11155111 | sepolia.substreams.pinax.network:443 |
| Scroll Sepolia | 534351 | scrsepolia.substreams.pinax.network:443 |

## 🔑 认证信息

```bash
export PINAX_API_KEY=cd6d1326907fb01ac311507e73f286371de5703f495c1dc4
export PINAX_JWT_TOKEN="eyJhbGci..."
```

## ✅ 验证状态

**Phase 1**：✅ 已完成  
**Phase 2**：⏭️ 待开始  
**Phase 3**：⏭️ 待开始  

---

**最后更新**：2025-12-22  
**当前版本**：Phase 1 Complete
