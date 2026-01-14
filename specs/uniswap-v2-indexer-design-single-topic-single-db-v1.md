# Uniswap V2（Substreams → Kafka → Consumer → Postgres → GraphQL）设计文档（单 Topic / 单 Sink / 单套表版 v1）

> **版本说明**  
> 本文是在 `uniswap-v2-indexer-design-single-topic-single-db.md v0.2` 基础上，
> 结合当前仓库中的 Uniswap V2 子图源码（`apps/subgraph/uniswap`），
> 重写的一份 **对齐源码行为的设计文档**。
>
> 本版相对 v0.2 做了几个关键取舍：
> - **只保留 raw / ops / meta 三层**，不再引入单独的 app.* 业务层；
>   - 外部查询（GraphQL/REST）直接暴露 meta.* 表；
> - **meta 层即“子图等价层”**：对齐当前 v2 / v2-tokens 子图实体；
> - **不再单独建 token_metadata / oracle_price_by_block**：
>   - Token 元数据字段直接落在 meta.tokens 中；
>   - 当前仅维护最新的 Bundle（meta.bundle），历史 oracle 价格需要时走 RPC；
> - **新增 meta.token_minute_data 表**：对齐子图里的 TokenMinuteData，支持分钟级 K 线；
> - **消息流只处理 NEW / UNDO**，不消费 IRREVERSIBLE，以节省上游流量配额；
> - 暂不设计 Bridge 相关字段与逻辑，完全聚焦 Uniswap V2；
> - UNDO 的窗口回滚与重放逻辑只保留思路与接口，具体 SQL 细节留待开发时落地。

---

## 1. 背景与设计目标

### 1.1 为什么不是“监听事件 → 写一行表”

结合当前 `apps/subgraph/uniswap` 的 `schema.graphql` 和 `mappings/*.ts`：

- **Mint / Burn 是跨事件状态机**：
  - 一次逻辑上的 Mint/Burn 涉及多条链上事件（ERC20 Transfer + Pair.Mint/Burn），
    子图通过 Transaction 上的 `mints[] / burns[]` 队列来拼装；
  - Burn 涉及 fee-mint 纠偏：存在“本来是 fee 但被当作 Mint”的情况，需要在 Burn 时归入 `feeTo/feeLiquidity` 并删除对应 Mint。
- **Swap 驱动成交与行为统计**：
  - 更新 Token / Pair / Factory 的 volume / txCount；
  - 更新 TokenDayData / PairDayData / UniswapDayData 等时间桶。
- **Sync 对账储备与价格**：
  - 更新 Pair.reserve0/1、token0Price/token1Price、trackedReserveETH、reserveUSD；
  - 通过 Bundle + Oracle 算出 ETH/USD，进而推导 token.derivedETH。

在 Graph Node 模式下，reorg 被节点自动处理，开发者只看见“已经对账好的实体”；
在 Substreams + Kafka + 自研 Consumer 模式下，这些状态机与 reorg 策略必须**显式抽象为数据库层的设计**。

### 1.2 本文设计边界

- **输入**：来自 Substreams 或等价组件的“fork-aware 事件流”：`fork_step ∈ {NEW, UNDO}`；
- **中间**：Kafka 单个 topic（`uni_v2.events`）、Consumer 集群、Postgres；
- **输出**：
  - 一套 `meta.*` 表，直接对外暴露查询；
  - 一套 `raw.*` 事实表和 `ops.*` 运维表，用于幂等、重放和（未来）窗口回滚。

暂不覆盖：

- Bridge / CCIP 相关索引与业务；
- 深度 reorg（超出固定窗口）的处理策略；
- 复杂缓存层（Redis）与前端接口细节。

---

## 2. 总体架构（单 Topic / 单 Sink）

### 2.1 数据流

```text
Blockchain
  ↓ blocks/logs
Substreams endpoint (Pinax/StreamingFast)
  ↓ (fork-aware stream: NEW / UNDO)
substreams-sink-kafka  (常驻进程)
  ↓ 1 topic
Kafka topic: uni_v2.events
  ↓ consumer group
Indexer Consumer Service
  ├─ raw 事实层落库（append-only）
  ├─ 幂等去重（NEW）
  ├─ v2 状态机（Transfer→Mint/Burn，Swap 聚合，Sync 对账）
  ├─ 聚合（Factory/Pair/Token + Day/Hour/Minute）
  └─ （预留）UNDO：窗口回滚 + 重放
  ↓
Postgres（raw + ops + meta）
  ↓
GraphQL / REST（直接使用 meta.*）
```

### 2.2 核心取舍

- **接受**：NEW 写入后，在极少数情况下可能被 UNDO 修正；
- **不接受**：错误长期累积；
- **简化**：
  - 不再维护 app.* 业务表，直接使用 meta.* 作为查询层；
  - 消息流不消费 IRREVERSIBLE（上游不发送或本地忽略），
    只基于 NEW / UNDO 实现逻辑一致性；
  - Token 元数据与价格只维护“当前快照”（meta.tokens + meta.bundle），
    历史 oracle 价格需要时通过 RPC 按需查询。

---

## 3. Kafka 设计（单 Topic + fork-aware event_id）

### 3.1 Topic 与分区

- **Topic**：`uni_v2.events`（单 topic）；
- **Partition Key 推荐**：
  - Pair 相关事件：`key = pair_address`（PairCreated/Transfer/Mint/Burn/Swap/Sync）；
  - 目的：保证同一 Pair 的事件顺序，简化状态机实现。

### 3.2 消息信封

建议字段：

- `chain_id`
- `event_type`：`PAIR_CREATED | TRANSFER | MINT | BURN | SWAP | SYNC`
- `fork_step`：`NEW | UNDO`（不使用 IRREVERSIBLE）；
- `cursor`：Substreams 提供的游标，用于断点恢复；
- `block_number` / `block_hash` / `block_timestamp`
- `tx_hash` / `tx_index` / `log_index`
- `address`：事件发出合约地址（Factory / Pair）
- `pair_address`：对 Pair 事件必填
- `payload`：事件参数 JSON（amount0In/Out、sender 等）

### 3.3 fork-aware event_id

推荐：

```text
event_id = "{chain_id}:{block_hash}:{tx_hash}:{log_index}:{event_type}"
```

- 同一逻辑事件在 reorg 后 `block_hash` 会变化 → 新的 event_id；
- 旧 event_id 会收到 UNDO，新 event_id 会收到 NEW；
- 结合 `ops.event_status`，即可判断“当前有效”的事件集合。

---

## 4. 数据库 Schema（raw / ops / meta）

### 4.1 Schema 分层

- `raw`：来自 Kafka 的事实事件（append-only）；
- `ops`：幂等控制、事件状态（NEW/UNDO）、checkpoint、UNDO 请求；
- `meta`：
  - 对齐子图实体的业务表（直接对外查询）；
  - 兼容 v2 与 v2-tokens 的日/小时/分钟聚合；
  - Bundle 作为全局价格快照。

### 4.2 raw 层

```sql
CREATE TABLE raw.raw_events (
  id            bigserial primary key,
  chain_id      int         NOT NULL,
  event_id      text        NOT NULL,
  fork_step     text        NOT NULL, -- NEW / UNDO
  event_type    text        NOT NULL,
  block_number  bigint      NOT NULL,
  block_hash    text,
  block_time    timestamptz,
  tx_hash       text,
  tx_index      int,
  log_index     int,
  address       text,
  pair_address  text,
  payload       jsonb,
  ingested_at   timestamptz NOT NULL DEFAULT now(),
  UNIQUE(event_id, fork_step)
);

CREATE INDEX raw_events_block_idx
  ON raw.raw_events(chain_id, block_number);

CREATE INDEX raw_events_pair_order_idx
  ON raw.raw_events(chain_id, pair_address, block_number, tx_index, log_index);
```

### 4.3 ops 层

#### 4.3.1 幂等与事件状态

```sql
CREATE TABLE ops.processed_events (
  event_id      text      PRIMARY KEY,
  processed_at  timestamptz NOT NULL DEFAULT now(),
  block_number  bigint   NOT NULL,
  cursor        text
);

CREATE TABLE ops.event_status (
  event_id      text      PRIMARY KEY,
  status        text      NOT NULL, -- NEW / UNDO
  block_number  bigint    NOT NULL,
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ops.checkpoints (
  chain_id            int PRIMARY KEY,
  last_applied_block  bigint   NOT NULL DEFAULT 0,
  last_applied_cursor text,
  last_rebuild_at     timestamptz
);

CREATE TABLE ops.undo_requests (
  id           bigserial PRIMARY KEY,
  chain_id     int         NOT NULL,
  start_block  bigint      NOT NULL,
  end_block    bigint      NOT NULL,
  reason       text,
  created_at   timestamptz NOT NULL DEFAULT now(),
  processed_at timestamptz
);
```

> 说明：
> - `ops.processed_events`：只对 NEW 做幂等；
> - `ops.event_status`：记录某个 event_id 的“当前状态”（NEW 或 UNDO），
>   为未来的窗口重放提供过滤依据；
> - `ops.undo_requests`：预留给独立 UNDO 模块，用于触发某个区间的重建。

### 4.4 meta 层：子图等价实体

#### 4.4.1 全局与核心实体

根据 `src/v2/schema.graphql` 与 `src/v2-tokens/schema.graphql`，在 meta 层建表：

- **全局状态**：
  - `meta.uniswap_factory` ← `UniswapFactory`
  - `meta.bundle` ← `Bundle`（仅存当前 ETH 价格与 oracleRoundId 快照）
- **核心实体**：
  - `meta.tokens` ← `Token`
  - `meta.pairs` ← `Pair`
  - `meta.users` ← `User`
  - `meta.pair_token_lookup` ← `PairTokenLookup`

示例：

```sql
CREATE TABLE meta.uniswap_factory (
  chain_id          int     NOT NULL,
  factory_address   text    NOT NULL,
  pair_count        bigint  NOT NULL,
  total_volume_usd  numeric NOT NULL,
  total_volume_eth  numeric NOT NULL,
  untracked_volume_usd numeric NOT NULL,
  total_liquidity_usd numeric NOT NULL,
  total_liquidity_eth numeric NOT NULL,
  tx_count          bigint  NOT NULL,
  PRIMARY KEY (chain_id, factory_address)
);

CREATE TABLE meta.bundle (
  chain_id       int     PRIMARY KEY,
  eth_price      numeric NOT NULL,
  oracle_round_id bigint NOT NULL
);

CREATE TABLE meta.tokens (
  chain_id              int     NOT NULL,
  token_address         text    NOT NULL,
  symbol                text    NOT NULL,
  name                  text    NOT NULL,
  decimals              int     NOT NULL,
  total_supply          numeric NOT NULL,
  trade_volume          numeric NOT NULL,
  trade_volume_usd      numeric NOT NULL,
  untracked_volume_usd  numeric NOT NULL,
  tx_count              bigint  NOT NULL,
  total_liquidity       numeric NOT NULL,
  derived_eth           numeric NOT NULL,
  last_minute_archived  bigint  NOT NULL DEFAULT 0,
  last_hour_archived    bigint  NOT NULL DEFAULT 0,
  PRIMARY KEY (chain_id, token_address)
);

CREATE TABLE meta.pairs (
  chain_id              int     NOT NULL,
  pair_address          text    NOT NULL,
  token0_address        text    NOT NULL,
  token1_address        text    NOT NULL,
  reserve0              numeric NOT NULL,
  reserve1              numeric NOT NULL,
  total_supply          numeric NOT NULL,
  reserve_eth           numeric NOT NULL,
  reserve_usd           numeric NOT NULL,
  tracked_reserve_eth   numeric NOT NULL,
  token0_price          numeric NOT NULL,
  token1_price          numeric NOT NULL,
  volume_token0         numeric NOT NULL,
  volume_token1         numeric NOT NULL,
  volume_usd            numeric NOT NULL,
  untracked_volume_usd  numeric NOT NULL,
  tx_count              bigint  NOT NULL,
  created_at_block      bigint  NOT NULL,
  created_at_timestamp  timestamptz NOT NULL,
  liquidity_provider_count bigint NOT NULL,
  PRIMARY KEY (chain_id, pair_address)
);
```

> 注：Token 元数据（symbol/name/decimals/totalSupply）直接落在 `meta.tokens`，
> 不再建单独的 `token_metadata` 表；Oracle 历史价格不单独落库，需要时走 RPC。

#### 4.4.2 事件明细与聚合

- **事件明细**：
  - `meta.mints` ← `Mint`
  - `meta.burns` ← `Burn`
  - `meta.swaps` ← `Swap`
  - 不再单独维护 Transaction 表，必要时根据 raw + meta 还原。
- **日/小时/分钟聚合**：
  - `meta.uniswap_day_data` ← `UniswapDayData`
  - `meta.pair_day_data` ← `PairDayData`
  - `meta.pair_hour_data` ← `PairHourData`
  - `meta.token_day_data` ← `TokenDayData`
  - `meta.token_hour_data` ← `TokenHourData`
  - `meta.token_minute_data` ← `TokenMinuteData`（**新增，必须实现**）

`meta.token_minute_data` 建表示例：

```sql
CREATE TABLE meta.token_minute_data (
  chain_id         int     NOT NULL,
  token_address    text    NOT NULL,
  minute_id        int     NOT NULL, -- periodStartUnix / 60
  period_start     timestamptz NOT NULL,
  volume           numeric NOT NULL,
  volume_usd       numeric NOT NULL,
  untracked_volume_usd numeric NOT NULL,
  total_value_locked    numeric NOT NULL,
  total_value_locked_usd numeric NOT NULL,
  price_usd       numeric NOT NULL,
  fees_usd        numeric NOT NULL,
  open_price_usd  numeric NOT NULL,
  high_price_usd  numeric NOT NULL,
  low_price_usd   numeric NOT NULL,
  close_price_usd numeric NOT NULL,
  PRIMARY KEY (chain_id, token_address, minute_id)
);
```

> 实现上可以复用 v2-tokens 子图中的 minute 更新逻辑，转换为“基于 Swap 事件的分钟桶聚合”。

---

## 5. Consumer 处理流程

### 5.1 NEW 消息（幂等 + 状态机）

对每条 `fork_step = NEW` 的事件 e，执行：

1. **写 raw 层**：
   - 将事件插入 `raw.raw_events`（唯一键为 `(event_id, fork_step)`）。
2. **更新事件状态**：
   - `UPSERT ops.event_status(event_id, status='NEW', block_number)`。
3. **幂等 Gate**：
   - 尝试 `INSERT ops.processed_events(event_id, block_number, cursor)`；
   - 若主键冲突，说明该 event_id 已处理过 → 直接返回（raw 有记录，meta 不重复写）。
4. **调用对应 handler（见第 6 章）**：
   - PAIR_CREATED / TRANSFER / MINT / BURN / SWAP / SYNC；
   - handler 负责更新 meta.* 表中对应实体与聚合。
5. **更新 checkpoint**：
   - `ops.checkpoints.last_applied_block/cursor`。

> NEW 的同步逻辑基本可以直接参考当前子图源码中的 mapping 实现——本设计文档主要解决的是“落库和表结构”的问题。

### 5.2 UNDO 消息（预留逻辑，可在开发时补充）

当前仅约定：

- 收到 `fork_step = UNDO` 时：
  - 必须写入 `raw.raw_events`（完整记录事实）；
  - `ops.event_status` 中将对应 event_id 的 status 更新为 `UNDO`；
  - 可选：插入一条 `ops.undo_requests`，描述需要回滚/重放的区间；
- **具体的“窗口回滚 + 重放”策略**（例如固定 3 个区块窗口、如何删除/重算哪些 meta.* 表）
  将在实际开发任务时，结合运行经验和 reorg 深度再细化：
  - 可以沿用 v0.2 文档中的“删除窗口内明细 + 重新从 raw 重放有效 NEW”的思路；
  - 也可以基于 snapshot 优化重算成本。

> 换句话说：本版文档将 UNDO 的接口和数据结构预留好，
> 但不强行写死具体 SQL 和窗口大小，避免约束未来实现。

---

## 6. 事件处理：状态机与字段更新（对齐子图逻辑）

> 本章只关注 NEW 事件的处理逻辑，直接参考当前子图：
> - [`v2/mappings/factory.ts`](apps/subgraph/uniswap/src/v2/mappings/factory.ts)
> - [`v2/mappings/core.ts`](apps/subgraph/uniswap/src/v2/mappings/core.ts)

### 6.1 PAIR_CREATED（Factory.PairCreated）

- 更新：
  - `meta.uniswap_factory.pair_count += 1`，初始化 volume/liquidity/txCount；
  - 如 Bundle 不存在，创建 `meta.bundle`（eth_price = 0, oracle_round_id = 0）；
  - Token：
    - 若 token0/token1 不存在，调用 RPC 读取 symbol/name/decimals/totalSupply；
    - 初始化 tradeVolume/tradeVolumeUSD/untrackedVolumeUSD/totalLiquidity/txCount；
  - Pair：
    - 初始化 token0/token1，reserve0/1 = 0，volumeToken0/1 = 0，volumeUSD = 0；
    - created_at_block/created_at_timestamp 来自事件 block；
    - liquidity_provider_count = 0。
  - PairTokenLookup：
    - 写入 token0-token1 及 token1-token0 两条记录。

### 6.2 TRANSFER（LP Token Transfer）——Mint/Burn 状态机入口

参考 `handleTransfer`：

- **A) from = 0x0（Mint）**：
  - `pair.totalSupply += value`；
  - 如 Transaction 上最新的 Mint 为 complete 或不存在，则新建 pending Mint：
    - id = `txHash + '-' + index`；
    - 设置 `pair/to/liquidity/timestamp`；
  - 落在 `meta.mints` 中，标记为“pending”（可用一个布尔字段或 status 枚举）。

- **B) to = pair（Burn 第一步：用户 → Pair）**：
  - 新建 `needsComplete = true` 的 Burn：
    - 填 `pair/liquidity/timestamp/from/to`；
  - 作为 Transaction.burns 队列中的 pending 记录；

- **C) from = pair 且 to = 0x0（Burn 第二步：Pair → 0x0）**：
  - `pair.totalSupply -= value`；
  - 若 Transaction.burns 最后一条 `needsComplete=true`，则复用并补完；否则新建一条 Burn；
  - fee-mint 纠偏：
    - 若 Transaction.mints 最后一条仍为 pending：
      - 将其 `to/liquidity` 赋给 Burn 的 `feeTo/feeLiquidity`；
      - 删除对应 Mint（或标记为 deleted），并从 mints 队列中移除。

> 这里的 Transaction 队列可在应用层以内存结构维护，
> 落库时只将最终的 Mint/Burn 记录写入 meta.mints/meta.burns。

### 6.3 MINT（Pair.Mint）

参考 `handleMint`：

- 从 Transaction 找到最近的 pending Mint：
  - 若不存在，直接返回（防御性编程）。
- 计算：
  - 根据 token0/1 的 decimals，将 amount0/1 转为 BigDecimal；
  - 使用 Bundle.eth_price 与 token.derivedETH 计算 `amountTotalUSD`；
- 更新：
  - `token0.tx_count++`，`token1.tx_count++`；
  - `pair.tx_count++`，`uniswap_factory.tx_count++`；
  - 完成 Mint：
    - 填 `sender/amount0/amount1/logIndex/amountUSD`；
  - 更新日/小时/分钟聚合：
    - PairDayData, PairHourData, TokenDayData，TokenHour/Minute（视业务需要）。

### 6.4 BURN（Pair.Burn）

参考 `handleBurn`：

- 从 Transaction 找到最近的 pending Burn：
  - 若不存在，直接返回。
- 计算 token0Amount/token1Amount 与 amountTotalUSD；
- 更新：
  - `token0/1.tx_count++`；
  - `pair.tx_count++`，`uniswap_factory.tx_count++`；
  - 补全 Burn 的 `amount0/amount1/logIndex/amountUSD`；
  - 更新日/小时聚合：PairDay/PairHour/TokenDay。

### 6.5 SWAP（Pair.Swap）

参考 `handleSwap`：

- 转换数值：
  - `amount0In/amount1In/amount0Out/amount1Out` 按 decimals 转换；
  - `amount0Total = amount0In + amount0Out`；
  - `amount1Total = amount1In + amount1Out`；
- 计算价格与体量：
  - 通过 token.derivedETH 和 Bundle.eth_price：
    - `derivedAmountETH`、`derivedAmountUSD`；
  - 通过 `getTrackedVolumeUSD` 得到 `trackedAmountUSD`，再换算 `trackedAmountETH`；
- 更新 Token：
  - `tradeVolume/tradeVolumeUSD/untrackedVolumeUSD`；
  - `txCount++`；
- 更新 Pair：
  - `volumeToken0/volumeToken1/volumeUSD/untrackedVolumeUSD`；
  - `txCount++`；
- 更新 Factory：
  - `totalVolumeUSD/totalVolumeETH/untrackedVolumeUSD/txCount`；
- 写 Swap 明细：
  - `meta.swaps` 插入一行：id = `txHash + '-' + index`，
    填 `sender/from/to/amount*In/Out/amountUSD/logIndex/时间等`；
- 更新聚合：
  - `meta.uniswap_day_data`：日成交量累计；
  - `meta.pair_day_data/pair_hour_data`：Pair 维度交易量；
  - `meta.token_day_data/token_hour_data/token_minute_data`：Token 维度日/小时/分钟桶。

### 6.6 SYNC（Pair.Sync）

参考 `handleSync`：

- 更新储备：
  - 将 reserve0/1 按 decimals 转换；
  - 计算 `token0Price/token1Price`；
- 更新 Bundle 与 Token：
  - 通过外部 Oracle 或 on-chain 逻辑更新 Bundle.eth_price / oracle_round_id；
  - 重新计算 token.derivedETH（findEthPerToken）；
- 更新 Pair 与 Factory：
  - 计算 trackedReserveETH/reserveETH/reserveUSD；
  - 更新 Factory.totalLiquidityETH/USD；
  - 更新 token.totalLiquidity。

> SYNC 主要负责价格与储备对账，Swap 不直接更新 reserves，
> 这点与原版 v2 子图保持一致。

---

## 7. 示例：两笔 Swap + 一次 UNDO（思路级别）

本节与 v0.2 类似，只保留“流程级”思路：

1. Swap A、Swap B 依次到达，按 6.5 的规则写入 meta.* 表；
2. 某个区块发生 reorg，上游发送一条或多条 UNDO 消息；
3. Consumer 将对应 event_id 的 `ops.event_status.status` 设为 UNDO，
   并可选择写入一条 `ops.undo_requests` 来触发窗口回滚；
4. 回滚逻辑（待实现）：
   - 删除某个 block 区间的 swaps + 相关桶；
   - 从 raw 中筛选“仍为 NEW 的事件”重放；
   - 修复 meta.tokens / meta.pairs / meta.uniswap_factory 的聚合字段。

> 具体的窗口大小、删除/重算的 SQL 等，留待实际开发与压测后确定。

---

## 8. IRREVERSIBLE（不在当前设计中使用）

- 当前方案中，事件流只包含 NEW 和 UNDO，不引入 IRREVERSIBLE：
  - 上游可不发送 IRREVERSIBLE；
  - 或消费者直接忽略该类型消息；
- 未来若需要基于 finality 做数据归档或压缩，
  可在不修改 meta.* 表的前提下，增量引入 IRREVERSIBLE 的处理逻辑，
  仅影响 raw/ops 层（例如清理老数据、限制重放窗口）。

---

## 9. 性能与演进方向

- **首版目标**：
  - 保证 NEW 路径的正确性与行为对齐子图；
  - 预留 UNDO 的结构与接口，但不在首版完成所有重放细节；
- **后续优化方向**：
  - 引入 snapshot（按 block 或时间段）减少重算成本；
  - 对 minute 级别桶做批量聚合，降低写放大；
  - 结合具体链的 reorg 特性，确定合理的回滚窗口大小。

---

## 10. 开发阶段划分

- **M1：基础索引**  
  - 打通 Substreams → Kafka → Postgres(raw/ops/meta) 链路；
  - 完成 PAIR_CREATED / SWAP / SYNC 的 meta 写入；
  - 暂不处理 UNDO，仅记录 event_status；

- **M2：Mint/Burn 状态机 + Token/Pair/Factory 聚合**  
  - 实现 Transfer + Mint + Burn 状态机与 fee-mint 纠偏；
  - 补齐 Token/Pair/Factory 的 volume/txCount 聚合；

- **M3：Day/Hour/Minute 聚合（含 token-minute）**  
  - 实现 UniswapDayData / PairDayData / PairHourData / TokenDayData / TokenHourData / TokenMinuteData；

- **M4：UNDO 窗口回滚 + 重放**  
  - 结合实际 reorg 情况设计具体窗口策略与 SQL；
  - 实现从 raw + event_status 推导“有效事件”并重放修复 meta.*。
