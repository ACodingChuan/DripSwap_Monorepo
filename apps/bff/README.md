# DripSwap BFF (Subgraph Sync)

DripSwap BFF 是一个“同步型 BFF”：从 The Graph Studio 的 Subgraph 拉取多链数据，落地到本地 PostgreSQL（20 张表），再供前端/其他服务做查询与展示。

本文档聚焦于：怎么配置 endpoints、怎么跑全量同步、怎么验证 20 张表都在更新、以及常见故障排查。

---

## 0. 当前实现概览

- 多链：`sepolia` / `scroll-sepolia`（可扩展）
- 多端点：
  - `endpoint`（V2 子图）：核心实体 + 事件 + Bridge + 协议/Pair 聚合
  - `endpoint-v2-tokens`（V2-tokens 子图）：Token minute/hour/day 时间序列（含 OHLC/fees 等）
- 同步入口：`POST /api/sync/full`（异步后台执行）
- 同步状态：写入 `sync_status`（按“chain + entityType”记录每步状态）
- 多链主键：表主键统一为 `(chain_id, id)`，避免跨链覆盖

---

## 1. 目录结构（你主要会改哪里）

```text
apps/bff/
  src/main/java/com/dripswap/bff/
    controller/SyncController.java         # /api/sync/full 入口
    sync/SubgraphSyncService.java          # 同步编排（每条链多个 step）
    sync/SubgraphClient.java               # GraphQL HTTP 客户端
    sync/*SyncHandler.java                 # 各表解析 + saveAll
    entity/*.java                          # 20 张表的 JPA 实体
    repository/*.java                      # 20 张表的 JPA Repository

  src/main/resources/
    application.yaml                       # subgraph.chains 配置
    db/changelog/*.xml                     # Liquibase 建表/改表
```

---

## 2. 数据源与配置（4 个 endpoint）

配置位置：`apps/bff/src/main/resources/application.yaml` → `subgraph.chains`。

每条链至少需要：

- `id`：链标识（如 `sepolia`）
- `chain-id`：EVM chainId（如 `11155111`）
- `enabled`：是否参与同步
- `endpoint`：V2 子图
- `endpoint-v2-tokens`：V2-tokens 子图（只用于 token 的 minute/hour/day 表）

当前启用的 4 个 endpoints（与你提供的一致）：

- Sepolia V2：`https://api.studio.thegraph.com/query/1718761/dripswap-v-2-sepolia/version/latest`
- Sepolia V2-tokens：`https://api.studio.thegraph.com/query/1718761/dripswap-v-2-tokens-sepolia/version/latest`
- Scroll Sepolia V2：`https://api.studio.thegraph.com/query/1716244/dripswap_v2_scroll_sepolia/version/latest`
- Scroll Sepolia V2-tokens：`https://api.studio.thegraph.com/query/1716244/drip-swap-v-2-tokens-scroll-sepolia/version/latest`

可用环境变量（见 `application.yaml`）：

- `SUBGRAPH_BATCH_SIZE`：默认 `500`
- `SUBGRAPH_RETRY_COUNT`：默认 `3`（目前主要用于配置保留，失败会记录到 `sync_status`，重试策略可后续补强）

---

## 3. 数据库（20 张表）与多链主键

Liquibase 位置：`apps/bff/src/main/resources/db/changelog/`

20 张表分组如下：

1) 核心实体（7）

- `uniswap_factory`：全局统计
- `tokens`：Token 元数据 + 派生价格（来自 V2 子图）
- `pairs`：Pair 元数据 + 储备与价格
- `bundle`：ETH 价格（`ethPrice`）
- `pair_token_lookup`：pair/token 组合索引
- `users`：用户地址集合
- `transactions`：交易 hash、区块号、时间戳

2) Bridge + 事件（5）

- `bridge_transfers`：跨链转账（CCIP）
- `bridge_config_events`：Bridge 配置事件
- `mints`：加池（Mint）
- `burns`：减池（Burn）
- `swaps`：兑换（Swap）

3) 时间聚合（6）

- `uniswap_day_data`：协议级日维度聚合
- `pair_day_data`：Pair 日维度聚合
- `pair_hour_data`：Pair 小时维度聚合
- `token_day_data`：Token 日维度聚合（来自 V2-tokens 子图）
- `token_hour_data`：Token 小时维度 OHLC/fees（来自 V2-tokens 子图）
- `token_minute_data`：Token 分钟维度 OHLC/fees（来自 V2-tokens 子图）

4) 同步控制（2）

- `sync_status`：每条链每个同步 step 的状态与错误信息
- `sync_errors`：错误记录扩展（当前可能为 0 行是正常情况）

### 多链主键（非常重要）

测试网可能出现“不同链合约地址相同”的情况（例如某些 vToken 地址在 Sepolia/Scroll Sepolia 一样）。如果表主键只有 `id`，后同步的链会覆盖先同步的链的数据。

已通过 `apps/bff/src/main/resources/db/changelog/005-multichain-primary-keys.xml` 把主键统一改成 `(chain_id, id)`，并在 JPA 层用 `ChainEntityId` 做复合主键。

升级/切换主键后建议执行一次“清库重跑”，见下文。

---

## 4. 同步机制与分页策略

### 4.1 触发方式

- `POST /api/sync/full`：触发全量同步（后台线程，不阻塞 HTTP）
- `GET /api/sync/status`：简单存活/占位接口（可用来判断服务已启动）

入口代码：`apps/bff/src/main/java/com/dripswap/bff/controller/SyncController.java`

### 4.2 同步编排（每条链的 step）

同步编排在 `apps/bff/src/main/java/com/dripswap/bff/sync/SubgraphSyncService.java`，按链循环执行（每个 step 进度写入 `sync_status`）：

核心实体：

- `uniswapFactories` → `uniswap_factory`
- `bundles` → `bundle`
- `tokens` → `tokens`
- `pairs` → `pairs`
- `users` → `users`
- `transactions` → `transactions`
- `pairTokenLookups` → `pair_token_lookup`

事件/Bridge：

- `swaps` → `swaps`
- `mints` → `mints`
- `burns` → `burns`
- `bridgeTransfers` → `bridge_transfers`
- `bridgeConfigEvents` → `bridge_config_events`

聚合：

- `uniswapDayData` → `uniswap_day_data`
- `pairDayData` → `pair_day_data`
- `pairHourData` → `pair_hour_data`
- `tokenMinuteData` → `token_minute_data`（V2-tokens 子图）
- `tokenHourData` → `token_hour_data`（V2-tokens 子图）
- `tokenDayData` → `token_day_data`（V2-tokens 子图）

### 4.3 分页策略（为什么有两套）

The Graph 的分页有两个常见策略：

1) `skip` 分页（简单但在大数据量/高并发场景可能不稳定）
   - 当前用于 V2 子图里多数实体：`first + skip`
2) `id_gt` 游标分页（更稳定）
   - 当前用于 V2-tokens 子图：`where: { id_gt: $lastId } orderBy: id asc`

说明：本项目当前以“全量同步”为主，数据量相对可控；后续如果要做高频增量同步，建议统一切换到游标分页 + `sync_status.last_synced_*` 做断点续传。

---

## 5. 运行与操作手册（本地）

### 5.1 启动方式

方式 1：一键脚本（启动 db + 启动 BFF + 触发全量同步）

```bash
cd apps/bff
./start-sync.sh
```

方式 2：手动

```bash
docker-compose up -d postgres redis

cd apps/bff
mvn spring-boot:run

curl -X POST http://localhost:8080/api/sync/full
```

### 5.2 清库重跑（推荐的“修复后”流程）

进入数据库：

```bash
docker exec -it dripswap-postgres psql -U dripswap -d dripswap
```

清空 20 张表（保留表结构）：

```sql
TRUNCATE uniswap_factory, tokens, pairs, bundle, pair_token_lookup, users, transactions,
bridge_transfers, bridge_config_events, mints, burns, swaps,
uniswap_day_data, pair_day_data, pair_hour_data, token_day_data, token_hour_data, token_minute_data,
sync_status, sync_errors
CASCADE;
```

然后重新触发：

```bash
curl -X POST http://localhost:8080/api/sync/full
```

---

## 6. 验证（确认 20 张表都在增长）

快速看每条链最关键的几张表：

```sql
SELECT chain_id, COUNT(*) FROM tokens GROUP BY chain_id ORDER BY chain_id;
SELECT chain_id, COUNT(*) FROM pairs GROUP BY chain_id ORDER BY chain_id;
SELECT chain_id, COUNT(*) FROM swaps GROUP BY chain_id ORDER BY chain_id;
SELECT chain_id, COUNT(*) FROM token_minute_data GROUP BY chain_id ORDER BY chain_id;
```

查看每个 step 是否都跑完（按更新时间倒序）：

```sql
SELECT key, chain_id, entity_type, sync_status, error_message, sync_start_time, sync_end_time
FROM sync_status
ORDER BY updated_at DESC;
```

一次性查看 20 表行数（用于快速验收）：

```sql
SELECT 'uniswap_factory' AS table_name, COUNT(*) AS cnt FROM uniswap_factory UNION ALL
SELECT 'tokens', COUNT(*) FROM tokens UNION ALL
SELECT 'pairs', COUNT(*) FROM pairs UNION ALL
SELECT 'bundle', COUNT(*) FROM bundle UNION ALL
SELECT 'pair_token_lookup', COUNT(*) FROM pair_token_lookup UNION ALL
SELECT 'users', COUNT(*) FROM users UNION ALL
SELECT 'transactions', COUNT(*) FROM transactions UNION ALL
SELECT 'bridge_transfers', COUNT(*) FROM bridge_transfers UNION ALL
SELECT 'bridge_config_events', COUNT(*) FROM bridge_config_events UNION ALL
SELECT 'mints', COUNT(*) FROM mints UNION ALL
SELECT 'burns', COUNT(*) FROM burns UNION ALL
SELECT 'swaps', COUNT(*) FROM swaps UNION ALL
SELECT 'uniswap_day_data', COUNT(*) FROM uniswap_day_data UNION ALL
SELECT 'pair_day_data', COUNT(*) FROM pair_day_data UNION ALL
SELECT 'pair_hour_data', COUNT(*) FROM pair_hour_data UNION ALL
SELECT 'token_day_data', COUNT(*) FROM token_day_data UNION ALL
SELECT 'token_hour_data', COUNT(*) FROM token_hour_data UNION ALL
SELECT 'token_minute_data', COUNT(*) FROM token_minute_data UNION ALL
SELECT 'sync_status', COUNT(*) FROM sync_status UNION ALL
SELECT 'sync_errors', COUNT(*) FROM sync_errors
ORDER BY table_name;
```

说明：

- `burns = 0`：如果链上没有 Burn 事件，属于正常
- `sync_errors = 0`：如果同步过程中没有异常，属于正常

---

## 7. 故障排查（优先看这几步）

### 7.1 看 `sync_status` 是否有失败

```sql
SELECT * FROM sync_status WHERE sync_status = 'failed' ORDER BY updated_at DESC;
```

如果 `error_message` 有值，优先修这个 step 的 GraphQL 查询/字段映射。

### 7.2 只有 `tokens` 有数据

常见原因：

- 之前 handler 还是占位符/未落库（现在应已修复）
- 子图端该实体为空（例如某链确实没有 burns）
- 表主键冲突导致“看起来像没写入”（已通过 `(chain_id,id)` 修复）

确认方式：跑完 `20 表行数` SQL，看哪些表确实为 0；再结合子图用 curl 验证实体是否存在。

### 7.3 子图查询报错（GraphQL errors / HTTP 非 200）

- 确认 `application.yaml` 的 endpoint 可访问
- 检查 `apps/bff/logs/bff-run.log`（如果你用 `nohup` 启动）或控制台输出
- The Graph schema 变更：如果字段名/实体名变了，需要同步更新 query & handler

### 7.4 多链数据被覆盖

- 确认已应用 `005-multichain-primary-keys.xml`
- 清库重跑（见 5.2）

---

## 8. 开发指南

### 8.1 本地构建与测试

```bash
cd apps/bff
mvn test
mvn clean package -DskipTests
```

### 8.2 新增一个要同步的实体（通用步骤）

1) Liquibase 建表（如需新增表）
2) 创建 `entity/*`（复合主键用 `@IdClass(ChainEntityId.class)`）
3) 创建 `repository/*Repository`
4) 创建 `sync/*SyncHandler`（解析 JsonNode 并 `saveAll`）
5) 在 `SubgraphSyncService` 加入：
   - GraphQL query
   - 分页策略（`skip` 或 `id_gt`）
   - `runStep(chainId, entityType, ...)` 编排与状态记录

---

## 9. 已知限制与后续方向

- 目前以“全量同步”为主；增量同步/断点续传还未完全落地（`sync_status.last_synced_*` 预留）
- 当前 `sync_errors` 主要预留，错误信息主要记录在 `sync_status.error_message`
- `skip` 分页在非常大数据量时可能不稳定；后续建议统一升级为游标分页
