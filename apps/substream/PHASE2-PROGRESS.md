# Phase 2 执行进度

最后更新：2025-12-22 21:15

---

## ✅ 已完成的工作

### 1. 配置文件更新 ✅
- ✅ `substreams-v2.yaml` - 所有模块的 initialBlock 更新为 `9573280`
- ✅ `src/constants.rs` - 完整的配置常量（67行）
  - Factory 地址（两链相同）
  - Oracle 地址（两链不同）
  - 白名单代币（7个，两链相同）
  - 初始区块号配置
  - Helper 函数

### 2. 核心文件修改 ✅

#### `src/lib.rs` - 基础改造已完成 ✅
- ✅ 删除 `mod ticks_idx;`
- ✅ 新增 `pub mod constants;`
- ✅ 删除 Position 相关导入
- ✅ 更新导入语句使用 `UNISWAP_V2_FACTORY`
- ✅ `map_pools_created` → `map_pairs_created`
  - 使用 `abi::factory::events::PairCreated`
  - 修改日志输出为 "pair addr"
  - 固定费率为 3000（0.3%）
  - tick_spacing 设为 0
  - 移除 ERROR_POOL 检查
- ✅ `store_pools_created` → `store_pairs_created`
  - 使用 `pair:{address}` 前缀
- ✅ `store_pool_count` → `store_pair_count`
  - 使用 `factory:pairCount` 键
- ✅ `map_tokens_whitelist_pools` 日志更新
  - "pool" → "pair" 术语统一
- ✅ `map_extract_data_types` 简化
  - 删除 Tick 和 Position 相关变量
  - 删除 `fee_growth_global_updates`
  - 使用 `pair:{address}` 前缀查询
  - 调用 `filtering::extract_pool_events`（待实现）
  - 移除 Tick/Position 事件填充

#### `src/price.rs` ✅
- ✅ 导入 `constants` 模块
- ✅ 使用 `constants::WHITELIST_TOKENS`
- ✅ 更新 WETH 地址为 vETH: `0xe91d02e66a9152fee1bc79c1830121f6507a4f6d`
- ✅ 更新 STABLE_COINS 为 DripSwap 的稳定币（vUSDC、vUSDT、vDAI）
- ✅ 删除 V3 主网相关常量

#### `src/utils.rs` ✅
- ✅ 导入 `constants` 模块
- ✅ 使用 `constants::WHITELIST_TOKENS`
- ✅ `UNISWAP_V3_FACTORY` 改为 `UNISWAP_V2_FACTORY`
- ✅ Factory 地址更新为 `0x6c9258026a9272368e49bbb7d0a78c17bbe284bf`
- ✅ 删除 V3 特有常量（NON_FUNGIBLE_POSITION_MANAGER、ERROR_POOL）

### 3. Git 配置 ✅
- ✅ 根目录 `.gitignore` 新增 Substreams 规则
- ✅ 删除 `apps/substream/.gitignore`

---

## ⏭️ 待执行（核心改造）

### 关键提示

当前状态：`src/lib.rs` 基础改造完成，但还有大量依赖文件需要修改。

主要阻塞点：
1. **filtering.rs 函数不存在**：`extract_pool_events` 需要创建或修改
2. **ABI 未生成**：需要运行 `cargo build` 生成 `abi::factory::events::PairCreated`
3. **Proto 类型不匹配**：Proto 仍使用 V3 结构，需根据 V2 proto 调整
4. **V3 特有代码**：大量 Tick、Position、Flash 相关代码需要删除
5. **V2 事件缺失**：Sync、Transfer、Pending 状态机尚未实现

### 下一步核心任务

#### 任务 1：修改 `src/filtering.rs`
- [ ] 删除 `extract_pool_events_and_positions` 函数
- [ ] 创建 `extract_pool_events` 函数（简化版，只处理 Swap/Mint/Burn）
- [ ] 删除 Tick 提取函数
- [ ] 删除 Position 提取函数
- [ ] 删除 `extract_fee_growth_update` 函数
- [ ] 新增 `extract_sync_event` 函数（V2 特有）

#### 任务 2：继续修改 `src/lib.rs`
- [ ] 删除所有 Tick 相关函数（`store_ticks_liquidities`）
- [ ] 删除所有 Position 相关函数（`store_positions`）
- [ ] 修改 `store_pool_sqrt_price` 为 `store_pair_reserves`
- [ ] 修改 `store_pool_liquidities` 逻辑（V2 使用 reserves）
- [ ] 删除 `store_min_windows` 和 `store_max_windows`（或简化）
- [ ] 大幅简化 `graph_out` 函数，删除 Position/Tick 处理

#### 任务 3：简化 `src/price.rs`
- [ ] 删除 `sqrt_price_x96_to_token_prices` 函数
- [ ] 新增 `calculate_token_prices_from_reserves` 函数
- [ ] 修改 `get_eth_price_in_usd` 使用 Oracle RPC 调用
- [ ] 修改 `find_eth_per_token` 逻辑（使用 reserves 而非 sqrtPrice）

#### 任务 4：修改 `src/db.rs`
- [ ] Pool → Pair 实体转换
- [ ] 删除 Position/Tick 相关实体函数
- [ ] 删除 PositionSnapshot 相关处理
- [ ] 简化实体变更逻辑

#### 任务 5：删除无用文件
- [ ] 删除 `src/ticks_idx.rs`
- [ ] 检查并删除 `src/storage/` 中的 V3 特有文件

#### 任务 6：编译测试
- [ ] 运行 `cargo build` 生成 proto 和 ABI 绑定
- [ ] 修复所有编译错误
- [ ] 确保可以成功编译

---

## 📊 工作量预估

| 任务 | 预估时间 | 状态 |
|------|---------|------|
| 配置文件更新 | 1 小时 | ✅ 已完成 |
| 基础常量配置 | 1 小时 | ✅ 已完成 |
| lib.rs 基础改造 | 2 小时 | ✅ 已完成 |
| filtering.rs 改造 | 2-3 小时 | ⏭️ 待执行 |
| lib.rs 深度改造 | 3-4 小时 | ⏭️ 待执行 |
| price.rs 简化 | 2-3 小时 | ⏭️ 待执行 |
| db.rs 改造 | 3-4 小时 | ⏭️ 待执行 |
| 删除无用文件 | 0.5 小时 | ⏭️ 待执行 |
| 编译调试 | 4-6 小时 | ⏭️ 待执行 |

**总进度**：~25% 完成

**已完成**：4 小时
**剩余工作量**：13-17 小时

---

## 🎯 建议执行策略

### 当前状态总结

✅ **已完成（4 小时）**：
- 配置文件更新（constants.rs、substreams-v2.yaml）
- 基础常量配置（合约地址、白名单）
- `src/lib.rs` 基础改造（函数重命名、删除 Position 导入、简化事件提取）
- `src/price.rs` 和 `src/utils.rs` 配置更新

⏭️ **待完成（13-17 小时）**：
- `src/filtering.rs` 改造（删除 V3 特有函数，创建 V2 简化版）
- `src/lib.rs` 深度改造（删除 Tick/Position 函数，修改 reserves 逻辑）
- `src/price.rs` 简化（reserves 计算价格、Oracle 调用）
- `src/db.rs` 改造（Pool → Pair，删除 V3 实体）
- 编译调试

### 方案 A：渐进式改造（推荐）
由于剩余工作量较大，建议分多次会话完成：

**第一轮**（3-4 小时）：
1. 修改 `src/filtering.rs`，创建 `extract_pool_events` 函数
2. 删除 `src/lib.rs` 中的 Tick/Position 相关函数
3. 尝试编译，查看错误

**第二轮**（4-5 小时）：
1. 简化 `src/price.rs`，实现 reserves 计算价格
2. 修改 `src/lib.rs` 中的 reserves 逻辑
3. 继续编译调试

**第三轮**（4-5 小时）：
1. 修改 `src/db.rs`，删除 Position/Tick 实体
2. 删除无用文件
3. 最终编译测试

### 方案 B：一次性完成（不推荐）
- 需要连续工作 13-17 小时
- 容易出错，调试困难
- 不适合复杂项目

---

## 💡 当前建议

鉴于已完成 `src/lib.rs` 基础改造，建议：

**下一步：修改 `src/filtering.rs`**
1. 创建 `extract_pool_events` 函数（简化版）
2. 删除 Tick 和 Position 相关函数
3. 这是解决当前 `map_extract_data_types` 调用错误的关键

**或者：**

**暂停点**：当前是一个良好的暂停点
- 基础改造已完成（25% 进度）
- 核心改造有清晰的路线图
- 可以在下次会话继续

---

**当前状态**：Phase 2 进行中（25% 完成）

**下一步**：等待用户指示是继续还是暂停
