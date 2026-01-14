# Ponder 重构方案（apps/subgraph/uniswap，含 V2 + V2-Tokens + Bridge）

## 1. 目标与范围

目标：使用 Ponder 重建 `apps/subgraph/uniswap` 的全部行为，并保留 Java BFF
作为 API 层（方案 B）。结果必须 1:1 还原当前子图的功能（实体、派生指标、
时间序列与桥接数据）。

范围包含：
- V2 子图的实体与事件处理逻辑。
- V2-tokens 的时间序列功能（小时/分钟数据与归档行为）。
- Bridge 事件与配置历史。

不在范围内：
- BFF API 重构。
- 前端改造。

## 2. 需要你提供的地址与输入（占位）

以下信息需按链提供，地址请使用小写十六进制。

### 链与 RPC
- CHAIN_SEPOLIA_ID = 11155111
- CHAIN_SCROLL_SEPOLIA_ID = <TBD>
- RPC_HTTP_SEPOLIA = <TBD>
- RPC_HTTP_SCROLL_SEPOLIA = <TBD>
- RPC_WS_SEPOLIA（可选）= <TBD>
- RPC_WS_SCROLL_SEPOLIA（可选）= <TBD>

### 核心合约
- FACTORY_ADDRESS_SEPOLIA = <TBD>
- FACTORY_ADDRESS_SCROLL_SEPOLIA = <TBD>
- FACTORY_START_BLOCK_SEPOLIA = <TBD>
- FACTORY_START_BLOCK_SCROLL_SEPOLIA = <TBD>

- BRIDGE_ADDRESS_SEPOLIA = <TBD>
- BRIDGE_ADDRESS_SCROLL_SEPOLIA = <TBD>
- BRIDGE_START_BLOCK_SEPOLIA = <TBD>
- BRIDGE_START_BLOCK_SCROLL_SEPOLIA = <TBD>

### Oracle（ETH/USD Chainlink Aggregator）
- ORACLE_ETH_USD_SEPOLIA = <TBD>
- ORACLE_ETH_USD_SCROLL = <TBD>

### 定价配置
- REFERENCE_TOKEN（vETH）= <TBD>
- STABLECOINS = [<TBD>, <TBD>, <TBD>]
- WHITELIST_TOKENS = [<TBD>, <TBD>, ...]
- STABLE_TOKEN_PAIRS = [<TBD>, <TBD>, <TBD>]

### CCIP 选择器
- CCIP_SELECTOR_SEPOLIA = <TBD>
- CCIP_SELECTOR_SCROLL = <TBD>

## 3. Ponder 架构与配置

### 3.1 Ponder 配置（ponder.config.ts）

- `chains`：定义 `sepolia` 与 `scroll_sepolia`，配置 HTTP RPC（可选 WS）。
- `contracts`：
  - Factory：索引 `PairCreated` 事件。
  - Pair：使用 `factory()` 自动索引 Factory 创建的所有 Pair 地址。
    处理 `Mint`、`Burn`、`Swap`、`Transfer`、`Sync`。
  - Bridge：索引 `TransferInitiated` 与配置类事件。
- `blocks`：可选区块间隔任务（用于归档/清理，见第 7 节）。
- `ordering`：保持默认 `multichain`，除非需要隔离模式（见 3.3）。
- `database`：使用 Postgres，配置独立 schema。

### 3.2 ABI 集合

沿用子图 ABI：
- Factory ABI
- Pair ABI
- Bridge ABI
- ERC20 ABI（symbol、name、decimals、totalSupply）
- Oracle ABI（Chainlink Aggregator v3）

### 3.3 排序模式与 chain_id 列

当前 BFF 表使用 `(chain_id, id)` 复合主键。为了避免字段改名，建议继续
使用 Ponder 默认 `multichain` 排序。

若未来切换 `experimental_isolated`，需要把主键列改为 `chainId`
（Ponder 要求），并同步更新 BFF。

## 4. Schema 设计（Ponder 表）

### 4.1 类型映射规则

- GraphQL `ID` -> `text`
- GraphQL `Bytes` -> `hex`（小写地址/bytes）
- GraphQL `BigInt` -> `bigint`
- GraphQL `BigDecimal` -> 任选：
  - 方案 A（简单）：`real`，风险是精度损失。
  - 方案 B（安全）：`text` 存十进制字符串，BFF 侧转换。
  - 方案 C（定点数）：`bigint` + 1e18 缩放。

为确保 1:1 精度，建议选方案 B 或 C。本方案默认方案 B。

### 4.2 表清单（1:1 对齐子图）

主键模式：`(chain_id, id)`（除非特别注明）。

核心：
- uniswap_factory
- token
- pair
- user
- transaction
- mint
- burn
- swap
- bundle
- pair_token_lookup

Bridge：
- bridge_transfer
- bridge_config_event

时间序列：
- uniswap_day_data
- pair_day_data
- pair_hour_data
- token_day_data
- token_hour_data
- token_minute_data

Token 归档辅助字段（来自 v2-tokens）：
- token.last_minute_archived
- token.last_hour_archived
- token.last_minute_recorded
- token.last_hour_recorded
- token.minute_array（int[]）
- token.hour_array（int[]）

注：Ponder 支持 `.array()`，保留这些字段以完全模拟 v2-tokens 行为。

## 5. Views（Ponder onchainView）

View 用于弥补 The Graph 的 derived 字段语义差异，主要服务查询侧。

建议 View：

1) `token_latest_price`
   - 返回 token + priceUSD（bundle.ethPrice * token.derivedETH）。
   - 用于 BFF 的 token 详情 / explore tokens。

2) `pair_latest_state`
   - 返回 pair + reserves + prices + reserveUSD。

3) `recent_swaps`
   - 基于 swap 表按 timestamp 倒序。

4) `token_ohlc_hour`
   - 基于 token_hour_data 输出 OHLC 与成交量。

View 需在 `ponder.schema.ts` 里导出。注意限制：
- Store API 不能写 View。
- GraphQL 仅支持复数查询 + offset 分页。

## 6. 索引逻辑映射（子图 -> Ponder）

### 6.1 Factory：PairCreated -> handleNewPair

对应子图：`mappings/factory.ts`

Ponder 行为：
- 初始化 `uniswap_factory`（首次）。
- 初始化 `bundle`（id 固定为 "1"）。
- 创建 token0/token1（通过 `context.client` 读 symbol/name/decimals/totalSupply）。
- decimals 缺失时中断创建（保持与子图一致）。
- 创建 pair 初始数据。
- 创建 `pair_token_lookup` 双向索引。
- 使用 `factory()` 自动索引该 pair 的后续事件。

### 6.2 Pair：Transfer -> handleTransfer

对应子图：`mappings/core.ts::handleTransfer`

需保持的逻辑：
- 忽略初始铸币 `to == 0x0` 且 value == 1000。
- 创建 user（from/to）。
- 创建 transaction（若不存在）。
- Mint 判定（from == 0x0）：
  - 更新 pair.totalSupply。
  - 若上一笔 mint 完整，创建新 Mint 实体并加入 transaction.mints。
- Burn 第一阶段（to == pair）：
  - 创建 Burn 实体，needsComplete = true。
- Burn 第二阶段（to == 0x0 且 from == pair）：
  - 更新 totalSupply。
  - 完成 burn，处理 fee mint（删除 mint，设置 fee 字段）。

### 6.2.1 状态机模型（必须保留）

V2 子图的 Mint/Burn 处理是一个“交易内状态机”：
- Mint：先收到 Transfer(from=0x0)，创建“待完成 mint”，后续 Mint 事件补全。
- Burn：先收到 Transfer(to=pair)，创建“needsComplete=true”的 burn，后续
  Transfer(from=pair,to=0x0) 或 Burn 事件补全；如果中间出现 fee mint，需要
  回滚最后一个 mint 并把 fee 信息写回 burn。

Ponder 实现建议（两种方式二选一，需明确选型）：

方案 A（推荐，结构清晰）：
- 新建 `pending_mint` 与 `pending_burn` 表（主键：chain_id + txHash + idx）。
- Transfer 事件创建 pending 记录；Mint/Burn/Transfer 后续事件补全并删除 pending。
- Mint/Burn 实体保持与子图一致的 id 规则（txHash + index）。

方案 B（更贴近子图）：
- `transaction` 表保留 `mints`、`burns`、`swaps` 的 id 数组字段（text[]）。
- 通过数组末尾元素判断“上一次是否完成”，并进行替换/弹出。
- 需要严格依赖事件顺序与 logIndex。

无论选哪种，都必须实现：
- 交易内顺序依赖（Transfer/Mint/Burn 的“前后关系”）。
- `needsComplete` 标记与 fee mint 反向修正逻辑。

### 6.3 Pair：Sync -> handleSync

对应子图：`mappings/core.ts::handleSync`

需保持的逻辑：
- 更新 reserves、价格、trackedReserveETH。
- 从 Oracle 更新 bundle.ethPrice。
- 重新计算 token0/token1 的 derivedETH。
- 更新 factory 的全局流动性。

### 6.4 Pair：Mint/Burn/Swap

对应子图：`mappings/core.ts::handleMint/handleBurn/handleSwap`

需保持的逻辑：
- 更新 token 与 pair 的 txCount。
- 更新 tracked/untracked 交易量。
- 创建 swap/mint/burn 行（txHash + index）。
- 更新日/小时聚合（pair、token、uniswap）。

### 6.5 Bridge 事件

对应子图：`mappings/bridge.ts`

需保持的逻辑：
- TransferInitiated -> bridge_transfer
- TokenPoolRegistered/Removed、LimitsUpdated、PayMethodUpdated、ServiceFeeUpdated
  -> bridge_config_event
- token 存在则用其 decimals，否则按 18。
- `receiverChainName` 由 CCIP selector 映射。

### 6.6 V2-tokens 时间序列

对应子图：`v2-tokens/mappings/core.ts` 与 `minuteUpdates.ts`

需保持的逻辑：
- Sync/Swap 更新 token_hour_data 与 token_minute_data。
- 维护 `token.hour_array` 与 `token.minute_array` 用于归档。
- 归档窗口：
  - minute：保留最近 1680 分钟
  - hour：保留最近 768 小时

## 7. 时间序列归档策略（Ponder）

Ponder 没有 The Graph 的 `store.remove`。为了 1:1 行为：

方案 A（推荐）：
- 用 block interval 或自定义 API endpoint 执行 SQL 删除。
- 示例：删除 `token_minute_data` 中 `period_start_unix < now - 1680*60`。

方案 B：
- 不删除，只在查询侧过滤（BFF）。

若要求严格一致性，采用方案 A，并保留 token 的归档元数据字段。

## 8. 1:1 功能映射检查表

| 子图功能 | Ponder 目标 | Done |
| --- | --- | --- |
| UniswapFactory 实体 | uniswap_factory 表 | [ ] |
| Token 实体（元数据/统计） | token 表 + 读合约 | [ ] |
| Pair 实体 | pair 表 | [ ] |
| User 实体 | user 表 | [ ] |
| Transaction 实体 | transaction 表 | [ ] |
| Mint 实体 | mint 表 | [ ] |
| Burn 实体 | burn 表 | [ ] |
| Swap 实体 | swap 表 | [ ] |
| Bundle 实体 | bundle 表 | [ ] |
| PairTokenLookup 实体 | pair_token_lookup 表 | [ ] |
| BridgeTransfer 实体 | bridge_transfer 表 | [ ] |
| BridgeConfigEvent 实体 | bridge_config_event 表 | [ ] |
| UniswapDayData 实体 | uniswap_day_data 表 | [ ] |
| PairDayData 实体 | pair_day_data 表 | [ ] |
| PairHourData 实体 | pair_hour_data 表 | [ ] |
| TokenDayData 实体 | token_day_data 表 | [ ] |
| TokenHourData 实体 | token_hour_data 表 | [ ] |
| TokenMinuteData 实体 | token_minute_data 表 | [ ] |
| handleNewPair 逻辑 | Factory:PairCreated | [ ] |
| handleTransfer（mint/burn） | Pair:Transfer | [ ] |
| handleSync（价格/流动性） | Pair:Sync | [ ] |
| handleMint | Pair:Mint | [ ] |
| handleBurn | Pair:Burn | [ ] |
| handleSwap | Pair:Swap | [ ] |
| ETH/USD Oracle | readContract + bundle | [ ] |
| derivedETH 定价 | whitelist + pair lookup | [ ] |
| Pair/Token 日/小时聚合 | 时间序列表 | [ ] |
| Token 分钟数据 | token_minute_data | [ ] |
| 分钟/小时归档 | SQL 删除或定时任务 | [ ] |
| derivedFrom 关系 | relations 或 view | [ ] |

## 9. 实现注意事项

- Ponder 地址默认小写，所有地址与 id 需要统一小写。
- id 规则保持与子图一致：
  - PairDayData：pairAddress + "-" + dayIndex
  - PairHourData：pairAddress + "-" + hourIndex
  - TokenDayData：token + "-" + dayIndex
  - TokenHourData：token + "-" + hourIndex
  - TokenMinuteData：token + "-" + minuteIndex
  - Mint/Burn/Swap：txHash + "-" + index
  - BridgeTransfer/BridgeConfigEvent：txHash + "-" + logIndex

## 10. 你需要补充的内容

请先填写第 2 节的地址与参数。随后我将基于本方案生成具体的
`ponder.config.ts`、`ponder.schema.ts` 以及索引逻辑实现。
