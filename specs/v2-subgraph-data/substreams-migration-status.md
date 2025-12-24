# Substreams 迁移逻辑对照（V2 Subgraph）

> 参考文档：
> - `specs/v2-subgraph-data-structure.md`
> - `specs/v2-tokens-subgraph-data-structure.md`
> - 代码：`apps/substream/src`

---

## 1. 结论摘要

- **Swap 对 Factory 的更新**：当前 Substreams 逻辑会更新 `Factory.totalVolumeUSD/ETH`、`untrackedVolumeUSD`、`totalFeesUSD/ETH`，并通过 `store_total_tx_counts` 更新 `Factory.txCount`，与 V2 Subgraph 的意图一致。
- **整体迁移状态**：核心 Swap/Mint/Burn/Tx 统计已覆盖；**Transfer + Sync 主链路已接入**（Mint/Burn liquidity + Sync reserves/price/TVL），**fee mint 与 Oracle 价格链路已补齐**，剩余差异主要在 v2-tokens 的分钟归档机制。

---

## 2. Subgraph → Substreams 更新路径对照

### 2.1 交换类事件（Swap）

| 目标实体 | V2 Subgraph 更新逻辑 | 当前 Substreams 更新逻辑 | 结论 |
| --- | --- | --- | --- |
| Factory | `totalVolumeUSD/ETH`, `untrackedVolumeUSD`, `totalFeesUSD/ETH`, `txCount` | `store_swaps_volume` + `store_total_tx_counts` → `db::swap_volume_factory_entity_change` + `db::tx_count_factory_entity_change` | ✅ 一致 |
| Pool(Pair) | `volume*`, `feesUSD`, `txCount` | `store_swaps_volume` + `store_total_tx_counts` → `db::swap_volume_pool_entity_change` | ✅ 一致 |
| Token | `volume`, `volumeUSD`, `feesUSD`, `txCount` | `store_swaps_volume` + `store_total_tx_counts` → `db::swap_volume_token_entity_change` + `db::tx_count_token_entity_change` | ✅ 一致 |
| Transaction | 记录 Tx + 事件引用 | `events.transactions` → `db::transaction_entity_change` | ✅ 一致 |
| UniswapDayData / PairDayData / PairHourData / TokenDayData / TokenHourData | 日/小时聚合 | `store_swaps_volume` + `store_total_tx_counts` + `store_min/max_windows` → 对应 `db::*_windows_*` | ✅ 一致 |

**结论**：Swap 事件对 Factory 的更新逻辑 **已对齐**（计数与聚合路径一致）。

---

### 2.2 流动性事件（Mint / Burn）

| 目标实体 | V2 Subgraph | 当前 Substreams | 结论 |
| --- | --- | --- | --- |
| Mint / Burn | 依赖 Transfer → Mint/Burn 双阶段状态机（补齐 liquidity/to/sender） | Transfer 解析 + fee mint 识别（zero-mint transfer → Burn.feeTo/feeLiquidity） | ✅ 基本对齐 |
| Factory / Pool / Token txCount | Mint/Burn 计数 | `store_total_tx_counts` 计数池事件 | ✅ 一致 |
| Token/Pool TVL | Sync + 价格传播 | `store_token_tvl` + `store_derived_tvl` 基于 Sync reserves 驱动 | ✅ 对齐 |

---

### 2.3 Sync 事件（储备与价格）

| 目标实体 | V2 Subgraph | 当前 Substreams | 结论 |
| --- | --- | --- | --- |
| Pair.reserve0/1, price | Sync 事件直接更新 reserve 与价格 | Sync 写入 `PoolSqrtPrice`（reserve0/1），`store_prices` 用 reserves 计算价格 | ✅ 对齐 |
| Factory.totalLiquidity* | Sync 后基于 trackedReserve 更新 | `store_derived_tvl` 基于 Sync reserves 更新 | ✅ 对齐 |

**结论**：Sync 驱动的价格/储备链路已接入，剩余差异主要集中在 v2-tokens 的分钟归档机制。

---

### 2.4 Users

| 目标实体 | V2 Subgraph | 当前 Substreams | 结论 |
| --- | --- | --- | --- |
| User | `Transfer` 事件创建 `from/to` | 已补充 LP Transfer 的 from/to | ✅ 对齐 |

---

### 2.5 PairTokenLookup

| 目标实体 | V2 Subgraph | 当前 Substreams | 结论 |
| --- | --- | --- | --- |
| PairTokenLookup | PairCreated 时生成 token0-token1 / token1-token0 | `map_pools_created` → `db::pair_token_lookup_entity_changes` | ✅ 一致 |

---

### 2.6 TokenMinuteData

| 目标实体 | V2 Subgraph (v2-tokens) | 当前 Substreams | 结论 |
| --- | --- | --- | --- |
| TokenMinuteData | minuteUpdates.ts 独立逻辑 + 归档 | 基于 `store_total_tx_counts`/`store_swaps_volume`/`store_eth_prices` + `store_min/max_windows` | ⚠️ 只有主字段，缺少归档机制 |

---

### 2.7 Bridge 事件

| 目标实体 | V2 Subgraph | 当前 Substreams | 结论 |
| --- | --- | --- | --- |
| BridgeTransfer | `TransferInitiated` | `graph_out` 直接解析 Bridge 日志（按 Bridge 地址过滤） | ✅ 基本一致 |
| BridgeConfigEvent | TokenPoolRegistered/Removed、LimitsUpdated、PayMethodUpdated、ServiceFeeUpdated | 同名事件解析 | ✅ 基本一致 |

---

## 3. 迁移覆盖矩阵（18 张表）

| 表 | 当前状态 | 主要差异/风险 |
| --- | --- | --- |
| uniswap_factory_stream | ✅ 输出 | Factory ID 已切换为 V2 地址 |
| tokens_stream | ✅ 输出 | 基本一致 |
| pairs_stream | ✅ 输出（实体名 Pool） | Sync reserves/price 已接入 |
| bundle_stream | ✅ 输出 | 接入 Chainlink Oracle + roundId，失败回退 WETH/USDC |
| transactions_stream | ✅ 输出 | 基本一致 |
| users_stream | ✅ 输出 | 已补充 Transfer 创建逻辑 |
| mints_stream | ✅ 输出 | 已补齐 liquidity（Transfer），fee mint 已覆盖 |
| burns_stream | ✅ 输出 | 已补齐 liquidity（Transfer），fee mint 已覆盖 |
| swaps_stream | ✅ 输出 | 基本一致 |
| uniswap_day_data_stream | ✅ 输出 | TVL 改为 Sync 驱动 |
| pair_day_data_stream | ✅ 输出 | reserve/price 已接入 |
| pair_hour_data_stream | ✅ 输出 | reserve/price 已接入 |
| token_day_data_stream | ✅ 输出 | 基本一致 |
| token_hour_data_stream | ✅ 输出 | 基本一致 |
| token_minute_data_stream | ✅ 输出 | 缺归档逻辑 |
| pair_token_lookup_stream | ✅ 输出 | 基本一致 |
| bridge_transfers_stream | ✅ 输出 | 需要真实桥接事件区块验证 |
| bridge_config_events_stream | ✅ 输出 | 需要真实配置事件区块验证 |

---

## 4. 是否“全面迁移”结论

**结论：尚未完全对齐原 Subgraph 的核心状态机逻辑。**

已对齐：
- Swap/Mint/Burn 的基本事件写入
- Factory/Pool/Token 的成交统计与 TxCount
- PairTokenLookup、Bridge 事件基础逻辑
- TokenMinuteData 的基础输出

未对齐（关键差异）：
- TokenMinuteData 缺少 v2-tokens 的归档机制

边界差异（fee mint 识别，极端交易序列）：
- 同一交易同一池子出现多个 `Transfer(from=0x0)` 且 Mint 事件数量不足时，仅保留最后一个未匹配的 Transfer 作为 fee mint（多笔 fee mint 的场景可能丢失）。
- 若 Transfer 与 Mint 的 log 顺序异常（非“Transfer 先于 Mint”），匹配逻辑可能将正常 Mint 误判为 fee mint 或反之。
- 若交易中存在非标准 LP Transfer（非 0x0/Pair/Pair→0x0 三段式），可能影响 fee mint 匹配精度。

算法差异清单（与 V2 Subgraph 不同的实现方式）：
- ETH/USD 基准价：V2 Subgraph 仅使用 Chainlink Oracle（失败返回 0）；当前实现优先 Oracle，失败回退 vETH/vUSDT 池价。原因：保证测试网可用性与价格不中断。
- Oracle 细节：V2 Subgraph 当 decimals=0 时回退到 8；当前实现直接使用返回 decimals。原因：简化 RPC 处理逻辑（可能影响极端 feed）。
- fee mint 识别：V2 Subgraph 依赖 Transaction.mints/burns 的“未完成状态机”并删除最后的未完成 Mint；当前实现用 Transfer+Mint 的 logIndex 匹配来识别 fee mint，且不删除 Mint 记录。原因：在 Substreams 中避免跨事件维护复杂 pending 状态。
- Mint/Burn 状态机：V2 Subgraph 在 Transfer 阶段创建/标记“未完成” Mint/Burn，再由 Mint/Burn 事件补齐；当前实现在单次事件提取中用 TransferContext 补齐并直接输出。原因：简化状态管理，减少跨事件依赖。
- 价格派生阈值：V2 Subgraph 使用 `MINIMUM_LIQUIDITY_THRESHOLD_ETH = 0.001`；当前实现仍使用 V3 迁移默认 `52`。原因：沿用 V3 逻辑未下调（需评估是否改回 V2 阈值）。
- 交易量“新池”过滤：V2 Subgraph 用 `MINIMUM_USD_THRESHOLD_NEW_PAIRS` + `liquidityProviderCount` 约束；当前实现仅基于白名单双倍规则（未做新池过滤）。原因：避免引入 LiquidityPosition/LP 统计复杂度。
- LiquidityProviderCount：V2 Subgraph倾向按新增 LP 统计（或保持 0 用于过滤）；当前实现每次 Mint 增加计数。原因：缺少唯一 LP 识别与 Position 状态。
- TokenMinuteData 归档：V2‑tokens 会维护 minuteArray 并定期删除旧 minute；当前未实现归档。原因：不在本次范围，避免删除逻辑影响稳定性（可后续用 DB 层清理）。
- 聚合实现方式：V2 Subgraph 在事件处理器内直接写实体；当前实现通过 Store 累加（store_total_tx_counts/store_swaps_volume 等）再统一输出。原因：符合 Substreams 增量聚合模型。
- 字段补零：为复用 V3 schema，Swap/Pool 的 `sqrtPriceX96/tick/feeGrowth` 等字段在 V2 场景填 0。原因：兼容现有表结构。

---

差异-影响-修正对照表：

| 差异点 | 代码位置 | 影响 | 可选修正 |
| --- | --- | --- | --- |
| Oracle 失败回退池价 | `apps/substream/src/price.rs` | Oracle 失败时价格不为 0，可能与 Subgraph 输出不同 | 移除回退，仅返回 0 |
| Oracle decimals=0 处理 | `apps/substream/src/price.rs` | decimals=0 的极端 feed 价格可能缩放错误 | 与 Subgraph 一致：0→8 |
| fee mint 识别 + 不删除 Mint | `apps/substream/src/lib.rs`, `apps/substream/src/db.rs` | fee mint 与 Mint 输出可能并存 | 实现 pending 状态机 + 删除 Mint |
| Mint/Burn 状态机简化 | `apps/substream/src/filtering.rs` | 极端序列精度下降 | 按 Transaction 维护 pending Mint/Burn |
| 最小流动性阈值=52 | `apps/substream/src/price.rs`, `apps/subgraph/uniswap/src/common/chain.ts` | derivedETH 可能被过度过滤 | 调整为 0.001 或对齐配置 |
| 新池过滤缺失 | `apps/substream/src/utils.rs`, `apps/subgraph/uniswap/src/common/pricing.ts` | 小流动性池成交量被计入 | 加入 MINIMUM_USD_THRESHOLD_NEW_PAIRS |
| liquidityProviderCount 递增 | `apps/substream/src/lib.rs` | 统计含重复 LP | 引入唯一 LP 集合或保持 0 |
| TokenMinuteData 归档缺失 | `apps/subgraph/uniswap/src/v2-tokens/mappings/minuteUpdates.ts` | minute 数据无限增长 | DB 归档/定时清理（方案二） |
| 聚合方式差异 | `apps/substream/src/lib.rs`, `apps/substream/src/db.rs` | 统计为增量式 | 若需严格一致，改为事件内直接写 |
| V2 字段补零 | `apps/substream/src/filtering.rs` | 查询方需识别占位字段 | 增加 V2 专用 schema 或标记字段 |

---

## 5. 建议的下一步验证顺序（最小额度）

1. 先用 PairCreated 区块验证 `Pool/Token/PairTokenLookup/Factory` 基础输出
2. 用单个 Swap 区块验证：`Swap/Transaction/User/TokenMinute/Hour/Day/PoolHour/Day/UniswapDay`
3. 用 TransferInitiated 区块验证 BridgeTransfer
4. 用 LimitsUpdated/PayMethodUpdated 区块验证 BridgeConfigEvent

---

## 6. 备注

本报告是对当前 Substreams 实现与 V2 Subgraph 的逻辑对照，不代表最终业务正确性。后续若要 **严格等价迁移**，仍需补齐 v2-tokens 的分钟归档机制。
