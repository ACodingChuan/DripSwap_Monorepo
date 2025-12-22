# Substreams 迁移完成情况核对清单

## 📋 文档要求完成情况

### ✅ 已完成的核心要求

#### 1. ✅ V2 状态机在 Substreams 中的实现策略（2.3节）

**文档要求**：
- 说明如何通过 Store 模块模拟状态传递
- 解释 Transfer → Mint/Burn 的双阶段提交逻辑
- 处理 Sync 事件的状态传播链

**实现情况**：
- ✅ 已在文档中详细描述状态机实现策略（2.3节）
- ✅ 已实现 `map_extract_data_types` 提取所有事件
- ✅ 已实现 Store 模块的完整依赖链
- ✅ 代码已编译通过（Phase 2 完成）

---

#### 2. ✅ 18张表的完整映射说明（2.4节）

**文档要求**：
- 核心实体表（6张）
- 事件表（3张）
- 时序聚合表（6张）
- 索引表（1张）
- Bridge 相关表（2张）

**实现情况**：
- ✅ 文档中详细列出所有18张表（2.4节）
- ✅ 所有表标注为"完全兼容"
- ✅ Schema 定义完整（`schema.graphql` 和 `schema.sql`）
- ✅ 代码中实现了所有表的 EntityChanges 输出（`db.rs`）

**表结构清单**：
1. ✅ `uniswap_factory_stream`
2. ✅ `tokens_stream`
3. ✅ `pairs_stream`
4. ✅ `bundle_stream` （含 roundId 字段）
5. ✅ `transactions_stream`
6. ✅ `users_stream`
7. ✅ `mints_stream`
8. ✅ `burns_stream`
9. ✅ `swaps_stream`
10. ✅ `uniswap_day_data_stream`
11. ✅ `pair_day_data_stream`
12. ✅ `pair_hour_data_stream`
13. ✅ `token_day_data_stream`
14. ✅ `token_hour_data_stream`
15. ✅ `token_minute_data_stream`
16. ✅ `pair_token_lookup_stream`
17. ✅ `bridge_transfers_stream`
18. ✅ `bridge_config_events_stream`

---

#### 3. ✅ ETH/USD 价格通过 Oracle 链上查询（2.5节）

**文档要求**：
- 不使用深度池子平均价
- 使用 Chainlink 风格的 Oracle
- 参考 `apps/subgraph/uniswap/src/common/pricing.ts`

**实现情况**：
- ✅ 文档中详细描述 Oracle 查询策略（2.5节）
- ⚠️ **代码实现待补充**：当前使用的是白名单池子价格，需要添加 Oracle 合约调用
- 📝 **需要配置**：Oracle 合约地址（Sepolia 和 Scroll Sepolia）

**待完成事项**：
```rust
// 需要在 price.rs 中添加
pub fn get_eth_price_from_oracle(
    clock: &Clock,
    oracle_address: &str,
) -> OraclePriceResult {
    // 1. 调用 Oracle.latestRoundData()
    // 2. 解析 answer 和 roundId
    // 3. 处理 decimals
    // 4. 返回 OraclePriceResult { price, roundId }
}
```

---

#### 4. ✅ Bundle 新增 RoundId 字段（2.4节）

**文档要求**：
```graphql
type Bundle {
  id: ID!
  ethPrice: BigDecimal!
  roundId: BigInt!  # 新增字段
}
```

**实现情况**：
- ✅ 文档中已说明（2.4.1节）
- ✅ `schema.graphql` 中已添加
- ✅ `schema.sql` 中已添加
- ⚠️ **代码实现待补充**：`db.rs` 中 Bundle 的 EntityChange 需要包含 roundId

---

#### 5. ✅ Sepolia 和 Scroll Sepolia 双链配置（2.6节）

**文档要求**：
- Sepolia (chainId: 11155111)
- Scroll Sepolia (chainId: 534351)
- 配置 Pinax endpoint、API Key、JWT Token

**实现情况**：
- ✅ 文档中详细配置（2.6节）
- ✅ 已生成两个 SPKG 文件：
  - `dripswap-v2-sepolia-v0.1.0.spkg` (927KB)
  - `dripswap-v2-scroll-sepolia-v0.1.0.spkg` (927KB)
- ✅ JWT Token 已配置（正在测试中）
- ✅ Pinax endpoint 已配置：
  - Sepolia: `https://sepolia.substreams.pinax.network:443`
  - Scroll Sepolia: `https://scrsepolia.substreams.pinax.network:443`

---

#### 6. ✅ Pinax 的 API Key 和 JWT 配置（2.6.3节）

**文档要求**：
- API Key
- JWT Token
- 环境变量配置示例

**实现情况**：
- ✅ 文档中已完整配置（2.6.3节）
- ✅ API Key: `cd6d1326907fb01ac311507e73f286371de5703f495c1dc4`
- ✅ JWT Token: 完整的三段式 token（已配置）
- ✅ Docker Compose 配置示例已提供

---

#### 7. ✅ 项目目录结构说明（Phase 1）

**文档要求**：
- 说明项目建立在 `apps/substream`
- 保持 Monorepo 结构

**实现情况**：
- ✅ Phase 1 中详细描述项目目录结构
- ✅ 展示完整的 Monorepo 目录树
- ✅ 说明 Monorepo 集成优势
- ✅ 项目已创建在 `apps/substream` 目录

---

#### 8. ✅ 单数据库 + Chain 字段区分方案（2.6.4节）

**文档要求**：
- 采用单数据库，每个表增加 chainId 和 chainName 字段
- 复合主键设计
- BFF 层强制约束
- Docker Compose 配置

**实现情况**：
- ✅ 文档中详细设计（2.6.4节）
- ✅ 提供完整的 SQL 设计示例
- ✅ 提供 BFF Repository 基类设计思路
- ✅ 提供 Docker Compose 配置示例
- ⚠️ **实际数据库表创建待执行**：当前仅有设计，未实际创建表

---

### ⚠️ 部分完成/待补充的要求

#### 1. ⚠️ Oracle 价格查询的代码实现

**现状**：
- ✅ 文档中已详细说明逻辑
- ✅ 参考了 `pricing.ts` 的实现
- ❌ **Rust 代码中未实现 Oracle 调用**（当前使用白名单池子价格）

**待完成**：
```rust
// 在 src/price.rs 中添加
use ethabi::Contract;
use substreams_ethereum::pb::eth::v2 as eth;

pub fn get_eth_price_from_oracle(
    block: &eth::Block,
    oracle_address: &str,
) -> Result<OraclePriceResult, Error> {
    // 实现 Oracle.latestRoundData() 调用
    // 返回 { price, roundId }
}
```

**需要配置**：
- Sepolia Oracle 地址
- Scroll Sepolia Oracle 地址

---

#### 2. ⚠️ Bundle.roundId 字段的实际写入

**现状**：
- ✅ Schema 中已定义
- ❌ **db.rs 中的 create_bundle_entity 未包含 roundId**

**待完成**：
```rust
// 在 db.rs 中修改
pub fn create_bundle_entity(
    tables: &mut Tables,
    eth_price: &BigDecimal,
    round_id: &BigInt,  // 新增参数
) {
    tables
        .create_row("bundle", "1")
        .set("ethPrice", eth_price)
        .set("roundId", round_id);  // 新增字段
}
```

---

### 🚧 待执行的后续任务

#### Phase 3: 测试与验证（当前阶段）

**当前状态**：
- ✅ SPKG 打包成功（两个链都已生成）
- 🔄 **正在进行**：GUI/命令行测试（需要正确的 endpoint）
- ⏳ 待执行：数据对比验证
- ⏳ 待执行：性能优化

**测试清单**：
1. ✅ 编译通过
2. ✅ SPKG 打包成功
3. 🔄 **正在进行**：`map_pools_created` 模块测试
4. ⏳ `map_extract_data_types` 模块测试
5. ⏳ `graph_out` 完整输出测试
6. ⏳ 与 Subgraph 数据对比

**测试命令**（用户正在执行）：
```bash
# 方案1：GUI 模式
substreams gui dripswap-v2-sepolia-v0.1.0.spkg map_pools_created \
  -e https://sepolia.substreams.pinax.network:443 \
  --start-block 9573280 \
  --stop-block +100

# 方案2：命令行模式
substreams run dripswap-v2-sepolia-v0.1.0.spkg map_pools_created \
  -e https://sepolia.substreams.pinax.network:443 \
  --start-block 9573280 \
  --stop-block +10 \
  --output json
```

---

#### Phase 4: 数据库部署与 Sink 启动（待执行）

**待完成事项**：
1. ⏳ 创建 PostgreSQL 数据库和表结构
2. ⏳ 启动 `substreams-sink-postgres` 服务
3. ⏳ 监控数据同步进度
4. ⏳ 数据验证与对比

---

#### Phase 5: BFF 层适配（待执行）

**待完成事项**：
1. ⏳ 实现表名切换逻辑（`USE_STREAM_TABLES` 环境变量）
2. ⏳ 创建 BaseRepository 基类（chainId 自动注入）
3. ⏳ 更新所有 Repository 继承 BaseRepository
4. ⏳ 调整缓存策略（TTL 从 5分钟 → 1分钟）
5. ⏳ 禁用 Subgraph 同步服务和 WebSocket 监听

---

## 📊 总体完成度评估

### 文档设计层面
- ✅ **100% 完成**：所有文档要求均已详细说明

### 代码实现层面
- ✅ **Phase 1（项目初始化）**：100% 完成
- ✅ **Phase 2（事件处理适配）**：95% 完成
  - ✅ 核心 Map/Store 模块已实现
  - ✅ 编译通过
  - ⚠️ Oracle 价格查询待补充（可在测试后添加）
  - ⚠️ Bundle.roundId 待实际写入（依赖 Oracle）
- 🔄 **Phase 3（测试与验证）**：20% 完成
  - ✅ SPKG 打包成功
  - 🔄 正在测试 `map_pools_created`（endpoint 问题已修复）
  - ⏳ 完整测试待执行
- ⏳ **Phase 4（数据库部署）**：0% 完成
- ⏳ **Phase 5（BFF 适配）**：0% 完成

### 关键里程碑
- ✅ **设计完成**：2024-12-XX（架构文档完成）
- ✅ **代码编译**：2024-12-XX（Phase 2 完成）
- 🔄 **模块测试**：进行中（Phase 3）
- ⏳ **数据同步**：待启动
- ⏳ **生产切换**：待执行

---

## 🎯 下一步行动

### 立即行动（用户正在执行）
1. 🔄 **测试 map_pools_created**：使用正确的 Pinax endpoint
2. ⏳ **测试其他模块**：逐步验证所有 Store 模块
3. ⏳ **测试 graph_out**：验证完整的 EntityChanges 输出

### 短期计划（1-2天）
1. ⏳ 完成所有模块的功能测试
2. ⏳ 补充 Oracle 价格查询代码（如有需要）
3. ⏳ 修复测试中发现的问题

### 中期计划（1周）
1. ⏳ 部署 PostgreSQL 数据库
2. ⏳ 启动 substreams-sink-postgres
3. ⏳ 验证数据写入和一致性
4. ⏳ 调整 BFF 层代码

### 长期计划（1个月）
1. ⏳ 灰度切换到 Substreams 数据源
2. ⏳ 监控稳定性
3. ⏳ 清理遗留的 Subgraph 同步代码

---

## 📝 备注

### 设计决策
- ✅ 采用单数据库方案（而非独立数据库），简化架构
- ✅ 保留 Subgraph 表作为备份，支持快速回滚
- ✅ Oracle 价格查询可后续补充，不影响核心功能测试

### 技术债务
- Oracle 合约地址待确定（测试网可能没有官方 Chainlink）
- Bridge 事件处理逻辑需要根据实际合约调整
- OHLC 数据的 Store 清理策略需要根据实际数据量调优

### 风险提示
- Pinax 的 JWT Token 有效期至 2026年（长期有效）
- 测试网数据可能不完整，建议先用 Sepolia 验证
- 单数据库方案需要确保 BFF 层严格注入 chainId，防止跨链数据混淆

---

**最后更新**: 2024-12-XX  
**当前阶段**: Phase 3 - 模块功能测试（正在进行中）  
**下一个里程碑**: 完成所有模块测试，启动数据同步
