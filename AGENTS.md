# DripSwap 项目结构分析

## 项目概述

DripSwap 是一个去中心化交易所（DEX）项目，基于 Uniswap V2 协议构建，支持多链部署和跨链桥接功能。项目采用 Monorepo 架构，集成前端、后端 BFF、智能合约和 Subgraph 数据索引服务。

### 核心定位

- **协议基础**：基于 Uniswap V2 AMM 机制的多链 DEX
- **跨链能力**：通过 Chainlink CCIP 实现代币跨链桥接（异步模式，前端提供 CCIP messageID 和官网跳转链接）
- **数据聚合**：使用 The Graph 进行链上数据索引，BFF 层进行多链数据聚合与缓存
- **用户体验**：React 前端提供交易、流动性管理、跨链转账等功能

### 当前支持链

- Sepolia 测试网（chainId: 11155111）
- Scroll Sepolia 测试网

### 业务流程

```
Faucet 领取测试代币
    ↓
Uniswap V2 交易生态（Swap/添加流动性/移除流动性）
    ↓
Bridge 跨链转账（CCIP）
    ↓
前端记录 messageID，提供 CCIP 官网跳转查询进度
```

---

## 整体架构

### 技术栈概览

| 层次 | 技术选型 | 职责 |
|------|---------|------|
| **前端** | React + Vite + TypeScript | 用户交互界面，Web3 钱包集成 |
| **BFF** | Spring Boot + GraphQL | 多链数据同步与聚合，缓存层 |
| **合约** | Solidity + Foundry | DEX 核心逻辑、跨链桥、预言机 |
| **索引** | The Graph Subgraph | 链上事件索引与查询 |
| **存储** | PostgreSQL + Redis | 关系数据库 + 缓存 |
| **追踪** | Jaeger | 分布式追踪 |

### 数据流向

#### 完整数据架构（三层同步机制）

```
【链上事件层】
区块链合约事件（Swap/Mint/Burn/Bridge/PairCreated 等）
         |
         +------------------+------------------+
         |                  |                  |
         v                  v                  v
    WebSocket 监听      The Graph 子图      链上直接查询
    （实时增量）         （全量/补漏）       （交易功能）
         |
         v
【数据处理层】
    解析事件日志
         |
         +------------------+------------------+
         |                  |                  |
         v                  v                  v
   更新 Redis 缓存      触发 BFF 同步      SSE 推送前端
   （业务热数据）      （持久化存储）      （实时更新）
         |
         v
【持久化层】
PostgreSQL（20张表）
   |
   +-- WS 触发同步（7张核心表）
   +-- 高频轮询同步（6张统计表，5-10分钟）
   +-- 低频轮询同步（5张聚合表，30-60分钟）
   +-- 控制表（2张）
         |
         v
【API 服务层】
GraphQL API + RESTful API
   +-- GraphQL: 数据查询（优先从 Redis 读取）
   +-- RESTful: 同步控制、自定义业务逻辑
         |
         v
【前端应用层】
React 前端
   +-- SSE 订阅实时消息
   +-- GraphQL 查询数据面板
   +-- Web3 直连链上（交易功能）
```

---

## 模块详解

### 1. 前端（apps/frontend）

#### 技术特点

- **框架**：React 18 + Vite
- **状态管理**：基于现代 Hooks
- **Web3 集成**：支持多钱包连接
- **样式方案**：Tailwind CSS
- **测试**：Playwright E2E 测试

#### 目录结构分析

```
src/
├── app/              # 应用层（路由、组件、服务）
│   ├── routes/       # 页面路由组件
│   ├── components/   # UI 组件
│   ├── services/     # 业务服务
│   └── store/        # 状态管理
├── domain/           # 领域层（业务逻辑）
│   ├── bridge/       # 跨链桥业务
│   ├── models/       # 数据模型
│   └── ports/        # 接口定义
├── infrastructure/   # 基础设施层（外部集成）
├── contracts/        # 合约 ABI
└── shared/           # 共享工具
```

#### 架构模式

采用**分层架构**，符合用户偏好的传统 MVC 思维：

- **App 层**：类似 Controller，处理用户交互与路由
- **Domain 层**：类似 Service，封装业务逻辑
- **Infrastructure 层**：类似 DAO，处理外部数据源（区块链、API）
- **Shared 层**：公共工具与组件

#### 核心功能

| 功能模块 | 说明 |
|---------|------|
| Swap | 代币兑换交易 |
| Pool | 流动性池管理（添加/移除） |
| Bridge | 跨链代币转账 |
| Explore | 数据看板（交易历史、统计） |
| Token Details | 代币详情与价格图表 |

---

### 2. BFF 后端（apps/bff）

#### 架构定位

**同步型 BFF**（Backend for Frontend）：

- 从多个 The Graph Subgraph 端点拉取数据
- 将数据标准化后写入本地 PostgreSQL
- 提供统一的 GraphQL API 给前端
- 使用 Redis 缓存热点查询

#### 技术特点

- **框架**：Spring Boot 2.x + Java 17
- **数据访问**：JPA + Liquibase
- **API 协议**：GraphQL（Spring GraphQL）
- **缓存策略**：Redis + TTL 分级
- **数据库迁移**：Liquibase 版本控制

#### 目录结构（类比传统 MVC）

| 目录 | 职责 | MVC 对应 |
|------|------|---------|
| `controller/` | REST 接口入口 | Controller 层 |
| `gql/` | GraphQL 查询解析器 | Controller 层（GraphQL 风格） |
| `sync/` | 数据同步服务与处理器 | Service 层（数据同步） |
| `entity/` | JPA 实体类（20张表） | Model 层 |
| `repository/` | JPA Repository | DAO 层 |
| `config/` | 配置类 | 配置层 |
| `util/` | 工具类 | 工具层 |

#### 数据模型（20张表）与同步策略

##### 核心实体（7张）

| 表名 | 说明 | 主键 | 同步策略 | 触发方式 |
|------|------|------|---------|----------|
| `uniswap_factory` | 协议全局统计 | (chain_id, id) | 高频轮询 | 5-10分钟 |
| `tokens` | 代币元数据与价格 | (chain_id, id) | 高频轮询 | 5-10分钟 |
| `pairs` | 交易对储备与价格 | (chain_id, id) | **WS 触发** | Sync 事件 |
| `bundle` | ETH 基准价格 | (chain_id, id) | 高频轮询 | 5-10分钟 |
| `pair_token_lookup` | Pair-Token 索引 | (chain_id, id) | 高频轮询 | 5-10分钟 |
| `users` | 用户地址集合 | (chain_id, id) | **WS 触发** | 任意交易事件 |
| `transactions` | 交易哈希与区块信息 | (chain_id, id) | **WS 触发** | 任意交易事件 |

##### Bridge 与事件（5张）

| 表名 | 说明 | 同步策略 | 触发方式 |
|------|------|---------|----------|
| `bridge_transfers` | 跨链转账记录（CCIP） | **WS 触发** | Bridge 事件 |
| `bridge_config_events` | Bridge 配置变更事件 | 低频轮询 | 30-60分钟 |
| `mints` | 添加流动性事件 | **WS 触发** | Mint 事件 |
| `burns` | 移除流动性事件 | **WS 触发** | Burn 事件 |
| `swaps` | 兑换交易事件 | **WS 触发** | Swap 事件 |

##### 时间聚合（6张）

| 表名 | 说明 | 数据来源 | 同步策略 | 触发方式 |
|------|------|---------|---------|----------|
| `uniswap_day_data` | 协议日维度统计 | 合并子图 | 低频轮询 | 30-60分钟 |
| `pair_day_data` | Pair 日维度统计 | 合并子图 | 低频轮询 | 30-60分钟 |
| `pair_hour_data` | Pair 小时维度统计 | 合并子图 | 高频轮询 | 5-10分钟 |
| `token_day_data` | Token 日维度 OHLC | 合并子图 | 低频轮询 | 30-60分钟 |
| `token_hour_data` | Token 小时维度 OHLC | 合并子图 | 高频轮询 | 5-10分钟 |
| `token_minute_data` | Token 分钟维度 OHLC | 合并子图 | 低频轮询 | 30-60分钟 |

##### 同步控制（2张）

| 表名 | 说明 | 用途 |
|------|------|------|
| `sync_status` | 同步状态与进度（按链+实体类型） | 记录全量/增量同步进度 |
| `sync_errors` | 同步错误记录 | 错误日志与重试管理 |

**同步策略分类说明**：

1. **WS 触发同步（7张表）**：由 WebSocket 监听到事件后，通过队列通知同步服务立即同步
2. **高频轮询（6张表）**：定时从 Subgraph 拉取，间隔 5-10 分钟
3. **低频轮询（5张表）**：定时从 Subgraph 拉取，间隔 30-60 分钟
4. **控制表（2张）**：按需更新，记录同步状态

#### 多链主键设计

**关键设计决策**：所有表主键统一为 `(chain_id, id)`

- **原因**：测试网可能出现跨链合约地址相同的情况
- **实现**：JPA 使用 `@IdClass(ChainEntityId.class)` 复合主键
- **影响**：避免数据覆盖，支持真正的多链隔离

#### 数据同步机制

##### 数据源配置

**Subgraph 端点（一链一子图，已合并 v2 + v2-tokens）**

| 链 | 端点 URL | 说明 |
|------|---------|------|
| Sepolia | https://api.studio.thegraph.com/query/1718761/dripswap-v-2-sepolia/version/latest | 核心实体 + 事件 + 时间聚合 |
| Scroll Sepolia | https://api.studio.thegraph.com/query/1716244/dripswap_v2_scroll_sepolia/version/latest | 核心实体 + 事件 + 时间聚合 |

**WebSocket RPC 端点（实时事件监听）**

| 链 | WebSocket URL |
|------|---------------|
| Sepolia | wss://eth-sepolia.g.alchemy.com/v2/DhJ0V7QwXBRjUtK5_kL8nbauZLdn5WRI |
| Scroll Sepolia | wss://scroll-sepolia.g.alchemy.com/v2/DhJ0V7QwXBRjUtK5_kL8nbauZLdn5WRI |

**配额限制**：The Graph Studio 月查询额度 10w 次/端点

##### 同步编排流程

服务：`SubgraphSyncService`

执行方式：

1. 遍历已启用的链
2. 按预定顺序同步各实体类型
3. 每个 step 记录状态到 `sync_status`
4. 使用分页策略拉取数据（skip 或 id_gt 游标）
5. 通过对应 Handler 解析并批量写入数据库

同步触发：

- 手动：`POST /api/sync/full`（异步后台执行）
- 未来可扩展：定时任务、增量同步

##### WebSocket 实时监听机制

**监听的合约事件**：

| 合约 | 事件 | 触发场景 | 数据更新 |
|------|------|---------|----------|
| **Pair** | `Swap` | 代币兑换 | swaps, transactions, pairs, tokens, users |
| **Pair** | `Mint` | 添加流动性 | mints, transactions, pairs, tokens, users |
| **Pair** | `Burn` | 移除流动性 | burns, transactions, pairs, tokens, users |
| **Pair** | `Sync` | 储备量同步 | pairs（reserves/price） |
| **Factory** | `PairCreated` | 创建交易对 | pairs, pair_token_lookup |
| **Bridge** | `CCIPSendRequested` | 跨链发送 | bridge_transfers |
| **ERC20** | `Transfer` | 代币转账 | users（可选，用于余额追踪） |

**WS 事件处理流程**：

```java
// 伪代码示例
onEvent(rawLog) {
    // 1. 解析事件日志
    Event event = parseLog(rawLog);
    
    // 2. 更新 Redis 业务缓存（多个缓存键）
    updateRedisCache(event);
    //   - ds:v2:{chain}:recentSwaps (最新交易列表)
    //   - ds:v2:{chain}:pair:{pairId}:reserves (Pair 储备)
    //   - ds:v2:{chain}:token:{tokenId}:price (Token 价格)
    //   - ds:v2:{chain}:stats (协议统计)
    
    // 3. SSE 推送前端（订阅用户实时更新）
    sseEmitter.send({
        type: event.type,
        chainId: event.chainId,
        data: event.data
    });
    
    // 4. 发送同步标志（触发 BFF 数据库同步）
    syncQueue.enqueue({
        chainId: event.chainId,
        tables: ["swaps", "pairs", "transactions", "users"],
        triggerType: "WS_EVENT"
    });
}
```

**关键设计点**：

- WS 解析的数据**仅存入 Redis**，不直接写数据库
- 数据库同步由独立的同步服务根据队列标志执行
- Redis 缓存作为前端查询的第一层，TTL 根据业务场景设置
- SSE 推送仅针对已订阅的在线用户

##### 分页策略



#### API 设计原则

**技术选型**：

- **GraphQL（主用）**：数据查询，充分利用 Redis 缓存
- **RESTful（辅用）**：同步控制、自定义业务逻辑（无法直接从子图数据推导的场景）

**GraphQL API 设计**

核心 Resolver：`QueryResolver`

主要查询：

| Query | 说明 | 数据来源 | 缓存策略 |
|-------|------|---------|---------|  
| `recentTransactions` | 最新交易列表 | **Redis 优先** | TTL 30分钟 |
| `exploreStats` | 协议统计数据 | **Redis 优先** | TTL 1分钟 |
| `exploreTokens` | Token 列表 | **Redis 优先** | TTL 1分钟 |
| `tokenDetails` | Token 详情 | **Redis 优先** | TTL 1分钟 |
| `tokenChart` | K线图数据 | **Redis 优先** | TTL 5分钟 |
| `tokenTransactions` | Token 交易历史 | **Redis 优先** | TTL 30分钟 |

缓存键格式：`ds:v2:{chain}:{type}:{params}`

缓存击穿保护：WS 更新缓存时使用 `SET NX EX`，避免并发写入

**RESTful API 设计**

| 端点 | 方法 | 说明 | 用途 |
|------|------|------|------|
| `/api/sync/full` | POST | 触发全量同步 | 管理员初始化数据 |
| `/api/sync/status` | GET | 查询同步状态 | 监控同步进度 |
| `/api/faucet/claim` | POST | 领取测试代币 | 自定义业务逻辑 |
| `/api/faucet/history` | GET | 领取历史记录 | 自定义业务表查询 |

**未来可能需要自定义业务表的场景**：

- Faucet 发放记录（需要额外的限额、冷却控制）
- 用户积分/奖励系统（无法从链上直接推导）
- 运营活动数据（如交易竞赛排行榜）

---

### 3. 智能合约（apps/contracts）

#### 技术栈

- **开发框架**：Foundry（Forge + Cast + Anvil）
- **语言**：Solidity
- **测试**：Foundry 测试框架

#### 目录结构

```
src/
├── bridge/           # 跨链桥合约
│   └── Bridge.sol    # Chainlink CCIP 桥接
├── faucet/           # 测试网水龙头
├── oracle/           # 价格预言机
├── tokens/           # 代币合约
└── vendor/           # Uniswap V2 核心合约
```

#### 核心合约模块

##### Bridge 模块

合约：`Bridge.sol`

功能：

- 基于 Chainlink CCIP 的跨链代币转账
- 支持多链配置与路由
- 锁定/解锁或销毁/铸造模式

##### Faucet 模块

功能：

- 测试网代币领取
- 限额与冷却时间控制

##### Oracle 模块

功能：

- Chainlink 价格喂价集成
- 为 DEX 提供外部价格参考

##### Tokens 模块

功能：

- ERC20 代币实现
- 支持跨链的 vToken（Virtual Token）

##### Vendor 模块

功能：

- Uniswap V2 核心合约（Factory、Router、Pair）
- 可能包含少量定制修改

#### 合约部署与管理

部署脚本：`script/` 目录

配置文件：`configs/{network}/` 按网络组织

ABI 输出：`abi/` 目录（供前端与 Subgraph 使用）

---

### 4. Subgraph 索引（apps/subgraph）

#### 定位

使用 The Graph 协议索引链上事件，为 BFF 提供数据源。

#### 结构

```
subgraph/
└── uniswap/          # Uniswap V2 子图
    ├── schema.graphql
    ├── subgraph.yaml
    └── src/          # 映射处理逻辑
```

#### 数据模式

两类 Subgraph：

1. **V2 Subgraph**：核心实体（Token、Pair、Factory）+ 事件（Swap、Mint、Burn）+ Bridge + 聚合数据
2. **V2-tokens Subgraph**：专注 Token 时间序列（minute/hour/day OHLC、费用等）

---

## 开发工作流

### 环境启动

#### Docker 服务

```bash
docker-compose up -d  # 启动 PostgreSQL、Redis、Jaeger
```

服务端口：

- PostgreSQL: 5432
- Redis: 6379
- Jaeger UI: 16686

#### 各模块启动

```bash
# 前端开发服务器
pnpm run frontend:dev

# BFF 后端
pnpm run bff:dev

# 触发数据同步
curl -X POST http://localhost:8080/api/sync/full
```

### 统一命令（pnpm workspace）

```bash
pnpm run package:all    # 一键打包所有子项目
pnpm run test:all       # 一键运行所有测试
pnpm run lint:all       # 代码检查
pnpm run format:all     # 代码格式化
```

### Makefile 支持

为非 Node 项目（Java、Foundry）提供便捷命令：

```bash
make bff-dev
make contracts-test
make subgraph-build
```

---

## 关键设计决策

### 1. Monorepo 架构

**优势**：

- 统一依赖管理（pnpm workspace）
- 代码复用（共享 ABI、类型定义）
- 简化 CI/CD 流程
- 便于跨模块重构

**工具链**：

- pnpm workspace：管理前端、Subgraph
- Maven：管理 BFF
- Foundry：管理合约
- Makefile：统一入口

### 2. BFF 数据同步模式

**为什么不直接查 Subgraph**：

- 多链数据需要聚合
- 前端需要快速响应（Redis 缓存）
- 避免前端直接依赖外部服务可用性
- 支持自定义聚合逻辑

**三层同步机制设计**：

1. **WebSocket 实时同步**（核心增量机制）
   - 监听链上事件，实时解析
   - 更新 Redis 缓存（多业务场景共享）
   - 触发数据库同步队列
   - SSE 推送前端在线用户

2. **定时轮询同步**（补漏 + 历史数据）
   - 高频轮询（5-10分钟）：价格敏感数据
   - 低频轮询（30-60分钟）：聚合统计数据
   - 使用游标分页（id_gt）避免大数据量问题

3. **全量同步**（初始化 + 重建）
   - 手动触发：`POST /api/sync/full`
   - 用于新链接入或数据重建
   - 按实体类型顺序执行，记录进度到 `sync_status`

**未来扩展方向**：

- 考虑使用 **Substreams** 替代 The Graph，直接流式写入数据库
- 自建 The Graph 节点，降低对托管服务依赖
- 引入消息队列（Kafka/RabbitMQ）解耦 WS 监听与数据同步

### 3. 多链支持策略

**数据隔离**：

- 所有表使用 `(chain_id, id)` 复合主键
- 配置文件按链组织（`configs/{network}/`）
- 前端通过 chainId 参数区分查询

**扩展性**：

- 新增链只需添加配置 + 部署合约 + 部署 Subgraph
- BFF 自动支持新链数据同步

### 4. 缓存分层策略

| 数据类型 | TTL | 理由 |
|---------|-----|------|
| 最新交易 | 30分钟 | 更新频率中等，用户关注度高 |
| 协议统计 | 1分钟 | 实时性要求高 |
| Token 列表 | 1分钟 | 价格变化频繁 |
| K线数据 | 5分钟 | 历史数据，更新频率低 |

---

## 项目现状与未来方向

### 已完成功能

#### 基础设施

- ✅ Monorepo 工程化搭建
- ✅ Docker Compose 本地环境（PostgreSQL + Redis + Jaeger）
- ✅ Liquibase 数据库版本控制
- ✅ Redis 缓存层集成
- ✅ Jaeger 分布式追踪

#### 核心业务

- ✅ 20张表数据模型设计（复合主键支持多链）
- ✅ 全量数据同步机制（The Graph → PostgreSQL）
- ✅ GraphQL API 查询服务（带 Redis 缓存）
- ✅ 前端部分功能：
  - Swap（代币兑换）
  - Bridge（跨链转账，已测试）
  - Explore（数据看板，初步完成）
  - Token Details（代币详情，初步完成）

#### 多链支持

- ✅ Sepolia + Scroll Sepolia 双链部署
- ✅ 多链主键设计避免数据冲突 `(chain_id, id)`
- ✅ 一链一子图架构（合并 v2 + v2-tokens）

#### 智能合约

- ✅ Uniswap V2 核心合约（Factory/Router/Pair）
- ✅ Chainlink CCIP Bridge 跨链桥
- ✅ Faucet 水龙头合约（未对接前端）
- ✅ Oracle 价格预言机集成

### 待完成功能（优先级排序）

#### P0（核心功能缺失）

- ❌ **WebSocket 实时监听服务**（最高优先级）
  - 监听 Swap/Mint/Burn/Sync/PairCreated/Bridge 事件
  - 解析原始日志并更新 Redis 缓存
  - 触发数据库同步队列
  - SSE 推送前端实时消息

- ❌ **定时轮询同步机制**
  - 高频轮询（5-10分钟）：tokens, uniswap_factory, bundle, pair_token_lookup, token_hour_data, pair_hour_data
  - 低频轮询（30-60分钟）：token_day_data, pair_day_data, uniswap_day_data, token_minute_data, bridge_config_events

- ❌ **前端交易功能**
  - 添加流动性（Mint）
  - 移除流动性（Burn）
  - 领取流动性奖励（Fees）
  - Faucet 领取测试代币

#### P1（用户体验优化）

- ❌ **数据面板完善**
  - Token 价格图表优化（OHLC K线）
  - Pair 交易历史详情页
  - 用户个人资产看板
  - 协议统计图表（TVL/Volume 趋势）

- ❌ **SSE 实时推送**
  - 前端订阅机制
  - 消息格式设计
  - 重连与心跳逻辑

- ❌ **Bridge 状态追踪**
  - 记录 CCIP messageID
  - 提供官网跳转链接
  - 本地状态更新（监听目标链事件）

#### P2（稳定性与监控）

- ❌ **错误重试机制**
  - 同步失败自动重试（指数退避）
  - 错误日志详细记录
  - 告警通知（邮件/钉钉）

- ❌ **监控告警体系**
  - Prometheus + Grafana 集成
  - 关键指标监控（同步延迟、缓存命中率、RPC 调用失败率）
  - 健康检查端点

- ❌ **分页优化**
  - 统一使用游标分页（id_gt）替代 skip
  - 大表查询性能优化

#### P3（未来扩展）

- 🔄 **自定义业务表**
  - Faucet 发放记录表（领取限额、冷却时间）
  - 用户积分/奖励表（未来运营活动）

- 🔄 **Substreams 集成**
  - 替代 The Graph 实现流式数据写入
  - 降低查询额度依赖

- 🔄 **自建 The Graph 节点**
  - 完全控制数据索引
  - 支持自定义聚合逻辑

### 技术演进路线图

#### 第一阶段：核心功能补全（当前）

**目标**：完成 MVP，实现完整的数据同步与交易功能

- WebSocket 实时监听服务
- 定时轮询同步机制
- 前端交易功能（添加/移除流动性、Faucet）
- SSE 实时推送
- 数据面板优化

**预期成果**：用户可以完整体验 Faucet → Swap/Pool → Bridge 全流程

#### 第二阶段：稳定性与性能（3个月内）

**目标**：提升系统稳定性和用户体验

- 错误重试与降级机制
- Prometheus + Grafana 监控
- 数据库索引优化
- Redis 缓存策略精细化
- 游标分页统一化
- 单元测试与集成测试覆盖

**预期成果**：系统可 7x24 稳定运行，监控可观测

#### 第三阶段：架构优化（6个月内）

**目标**：提升扩展性和维护性

- 引入消息队列（Kafka/RabbitMQ）解耦 WS 监听与同步
- 同步服务独立部署（微服务化）
- Substreams 集成或自建 The Graph 节点
- 多链接入自动化（配置化）
- CI/CD 流水线完善

**预期成果**：新增链只需配置，无需代码改动

#### 第四阶段：主网部署与扩展（1年内）

**目标**：支持主网和更多 L2

- 主网部署（Ethereum/Scroll）
- 更多 L2 支持（Arbitrum/Optimism/Base）
- 流动性聚合（跨 DEX 最优路由）
- 高可用架构（多副本、故障转移）
- 安全审计与压力测试

**预期成果**：生产级 DEX，支持真实资产交易

---

## 关键流程说明

### 用户 Swap 交易流程（完整链路）

```
【前端层】
用户在前端发起 Swap
    ↓
前端调用 Web3 钱包签名
    ↓
发送交易到区块链（Router 合约）
    ↓
【链上层】
Router.swapExactTokensForTokens()
    ↓
Pair.swap() 执行兑换
    ↓
触发 Swap 事件 + Sync 事件
    ↓
【监听层】
WebSocket 监听到 Swap 事件
    ↓
解析事件日志（from/to/amount0/amount1）
    ↓
【缓存更新层】
并行执行三件事：
  1. 更新 Redis 缓存
     - ds:v2:{chain}:recentSwaps（最新交易列表）
     - ds:v2:{chain}:pair:{pairId}:reserves（Pair 储备）
     - ds:v2:{chain}:token:{token0}:price（Token 价格）
     - ds:v2:{chain}:token:{token1}:price
     - ds:v2:{chain}:stats（协议统计）
  
  2. SSE 推送前端
     sseEmitter.send({
       type: "SWAP",
       chainId: 11155111,
       pair: "0x...",
       amount0: "100",
       amount1: "200"
     })
  
  3. 触发同步队列
     syncQueue.enqueue({
       chainId: 11155111,
       tables: ["swaps", "pairs", "transactions", "users"],
       priority: "HIGH"
     })
    ↓
【持久化层】
BFF 同步服务从队列消费
    ↓
查询 Subgraph 获取最新数据
    ↓
写入 PostgreSQL（swaps/pairs/transactions/users 表）
    ↓
更新 sync_status 记录同步时间
    ↓
【前端更新层】
前端通过两种方式感知更新：
  1. SSE 实时推送（已订阅用户）
  2. GraphQL 轮询查询（未订阅或离线后重连）
    ↓
用户看到最新交易数据
```

### Faucet 领取流程

```
【前端层】
用户点击领取测试代币
    ↓
前端调用 Faucet 合约 claim() 方法
    ↓
【链上层】
Faucet.claim(recipient) 检查限额和冷却时间
    ↓
触发 Transfer 事件（ERC20）
    ↓
【监听层】
WebSocket 监听到 Transfer 事件（可选）
    ↓
【业务记录层】
BFF RESTful API 记录领取历史
    ↓
POST /api/faucet/claim
{
  "address": "0x...",
  "txHash": "0x...",
  "amount": "100",
  "timestamp": 1703145600
}
    ↓
写入自定义业务表 faucet_claims
（包含额外字段：ip、user_agent、冷却到期时间）
    ↓
【前端更新层】
前端查询余额（直连链上）
    ↓
显示领取成功
```

### Bridge 跨链转账流程

```
【前端层】
用户发起跨链转账（Sepolia → Scroll Sepolia）
    ↓
前端调用 Bridge.sendToken()
    ↓
【源链层】
Bridge 合约锁定代币
    ↓
调用 Chainlink CCIP Router
    ↓
触发 CCIPSendRequested 事件（包含 messageID）
    ↓
【监听层】
WebSocket 监听到 Bridge 事件
    ↓
解析 messageID 和目标链信息
    ↓
【缓存更新层】
1. 更新 Redis 缓存
   ds:v2:{sourceChain}:bridge:pending:{messageID}
   
2. SSE 推送前端
   sseEmitter.send({
     type: "BRIDGE_SEND",
     messageID: "0x...",
     status: "PENDING"
   })
   
3. 触发同步队列
   syncQueue.enqueue({
     tables: ["bridge_transfers"],
     messageID: "0x..."
   })
    ↓
【前端展示层】
前端记录 messageID
    ↓
显示跨链状态（PENDING）
    ↓
提供 CCIP 官网跳转链接
https://ccip.chain.link/msg/{messageID}
    ↓
用户可在 CCIP 官网查看实际等待进度
    ↓
【目标链层】（异步，几分钟后）
CCIP 在目标链执行 ccipReceive()
    ↓
Bridge 合约释放/铸造代币
    ↓
触发 Transfer 事件
    ↓
【监听层】（目标链 WebSocket）
监听到 Transfer 事件
    ↓
更新 bridge_transfers 表状态为 COMPLETED
    ↓
SSE 推送前端状态更新（如果用户仍在线）
```

### 数据同步流程对比

#### 全量同步（手动触发）

```
管理员调用 POST /api/sync/full
    ↓
SubgraphSyncService 遍历配置的链
    ↓
按顺序同步实体类型：
  1. Bundle（ETH 价格基准）
  2. Tokens
  3. Pairs
  4. Users
  5. Transactions
  6. Swaps/Mints/Burns
  7. Bridge Transfers
  8. 时间聚合表（Day/Hour/Minute）
    ↓
每个 step：
  - 分页查询 Subgraph（游标分页）
  - 批量写入数据库
  - 更新 sync_status
    ↓
完成后返回同步报告
```

#### 增量同步（WS 触发）

```
WebSocket 监听到事件
    ↓
发送同步标志到队列
    ↓
IncrementalSyncService 消费队列
    ↓
查询 Subgraph（仅查询最新数据）
  - 使用 block_gte 过滤
  - 或使用 id_gt 游标
    ↓
写入数据库（INSERT ON CONFLICT UPDATE）
    ↓
更新 sync_status.last_synced_block
```

#### 定时轮询同步

```
定时任务触发（Cron 或 Spring Scheduled）
    ↓
根据表的同步策略执行：
  - 高频表：每 5-10 分钟
  - 低频表：每 30-60 分钟
    ↓
查询 sync_status 获取上次同步时间
    ↓
查询 Subgraph（timestamp_gte 过滤）
    ↓
批量写入数据库
    ↓
更新 sync_status
```
合约执行 Swap 逻辑（Pair 合约）
    ↓
触发 Swap 事件
    ↓
The Graph 索引事件
    ↓
BFF 同步服务拉取数据
    ↓
写入 swaps 表
    ↓
前端查询最新交易（GraphQL）
    ↓
显示交易历史
```

### 跨链 Bridge 流程

```
用户发起跨链转账
    ↓
前端调用 Bridge 合约（源链）
    ↓
Chainlink CCIP 跨链消息传递
    ↓
目标链 Bridge 合约接收并执行
    ↓
触发 BridgeTransfer 事件（两条链）
    ↓
The Graph 索引事件
    ↓
BFF 同步并写入 bridge_transfers 表
    ↓
前端查询跨链记录
```

### BFF 数据同步流程

```
触发全量同步 API
    ↓
遍历配置中的每条链
    ↓
依次同步各实体类型
    ↓
    ├─ 核心实体（Factory/Token/Pair...）
    ├─ 事件（Swap/Mint/Burn）
    ├─ Bridge（Transfer/Config）
    └─ 聚合（Day/Hour/Minute）
    ↓
每个 step：
    ├─ 查询 The Graph
    ├─ 分页拉取数据
    ├─ 解析 JSON 响应
    ├─ 批量写入数据库
    └─ 更新 sync_status
    ↓
同步完成
```

---

## 与传统 MVC 架构对比

为便于理解，将 BFF 结构映射到传统 MVC：

| 传统分层 | DripSwap BFF 对应 | 说明 |
|---------|------------------|------|
| **Controller** | `controller/` + `gql/` | REST 与 GraphQL 入口 |
| **Service** | `sync/` 服务类 | 业务逻辑与数据同步 |
| **DAO** | `repository/` | JPA Repository 数据访问 |
| **Model** | `entity/` | JPA 实体（20张表） |
| **Config** | `config/` | Spring 配置类 |
| **Util** | `util/` | 工具类 |

**差异点**：

- GraphQL Resolver 类似 Controller，但查询逻辑更复杂（涉及多表关联与缓存）
- Sync 服务既是 Service 也是后台任务，负责数据拉取与转换
- JPA Repository 已封装基础 CRUD，复杂查询通过自定义方法或 `@Query` 实现

---

## 环境变量与配置

### BFF 配置（application.yaml）

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `BFF_SERVER_PORT` | 服务端口 | 8080 |
| `BFF_DB_URL` | PostgreSQL 连接 | jdbc:postgresql://localhost:5432/dripswap |
| `BFF_DB_USER` | 数据库用户 | dripswap |
| `BFF_DB_PASSWORD` | 数据库密码 | dripswap |
| `BFF_REDIS_HOST` | Redis 主机 | localhost |
| `BFF_REDIS_PORT` | Redis 端口 | 6379 |
| `SUBGRAPH_BATCH_SIZE` | 同步批量大小 | 500 |
| `SUBGRAPH_RETRY_COUNT` | 重试次数 | 3 |
| `BFF_SEPOLIA_RPC_HTTP` | Sepolia RPC | - |
| `BFF_SCROLL_SEPOLIA_RPC_HTTP` | Scroll Sepolia RPC | - |

### 前端配置（.env）

| 配置项 | 说明 |
|--------|------|
| `VITE_API_BASE_URL` | BFF GraphQL 端点 |
| `VITE_SEPOLIA_RPC_URL` | Sepolia RPC |
| `VITE_SCROLL_RPC_URL` | Scroll Sepolia RPC |
| `VITE_WALLETCONNECT_PROJECT_ID` | WalletConnect 项目 ID |

### 合约配置

按网络组织：`apps/contracts/configs/{network}/`

包含：

- 合约地址
- 部署参数
- 网络配置

---

## 故障排查指南

### BFF 同步问题

#### 症状：只有部分表有数据

**排查步骤**：

1. 查看 `sync_status` 表确认失败 step
2. 检查 `error_message` 字段
3. 验证 Subgraph 端点可访问性
4. 确认表主键已升级到 `(chain_id, id)`

**解决**：

- 修复失败的 Handler 代码
- 清库重跑（`TRUNCATE` 20张表后重新同步）

#### 症状：多链数据被覆盖

**原因**：主键未包含 `chain_id`

**解决**：

- 确认已应用 Liquibase `005-multichain-primary-keys.xml`
- 清库重跑

#### 症状：Subgraph 查询报错

**排查**：

1. 检查 BFF 日志（`logs/bff-run.log`）
2. 手动 curl 测试 Subgraph 端点
3. 对比 schema 字段是否变更

**解决**：

- 更新 GraphQL query 字段映射
- 调整 Handler 解析逻辑

### 前端问题

#### 症状：数据不更新

**排查**：

1. 检查浏览器控制台网络请求
2. 确认 BFF GraphQL 端点可访问
3. 验证 Redis 缓存 TTL

**解决**：

- 清除 Redis 缓存测试
- 检查 BFF 是否正常同步数据

#### 症状：交易失败

**排查**：

1. 查看钱包交易详情
2. 检查合约地址是否正确
3. 验证用户余额与授权

**解决**：

- 确认前端使用的 ABI 与合约匹配
- 检查 RPC 节点状态

---

## 数据一致性保障

### 多链数据隔离

- 所有表强制 `(chain_id, id)` 复合主键
- JPA 层使用 `ChainEntityId` 复合主键类
- 查询时必须传入 `chainId` 参数

### 同步状态跟踪

- `sync_status` 表记录每个 step 的状态
- 支持查看同步进度与错误信息
- 预留 `last_synced_*` 字段用于增量同步

### 数据验证

同步后验证 SQL：

```sql
-- 按链统计核心表行数
SELECT chain_id, COUNT(*) FROM tokens GROUP BY chain_id;
SELECT chain_id, COUNT(*) FROM pairs GROUP BY chain_id;
SELECT chain_id, COUNT(*) FROM swaps GROUP BY chain_id;

-- 查看同步状态
SELECT * FROM sync_status ORDER BY updated_at DESC;
```

---

## 性能优化策略

### 数据库层

- 合理使用索引（`chain_id`, `timestamp`, `id` 等）
- 批量写入（`saveAll` 替代逐条 `save`）
- 连接池配置优化

### 缓存层

- Redis 缓存热点查询（TTL 分级）
- 缓存键设计：`ds:v2:{chain}:{type}:{params}`
- 缓存失效策略：TTL + 主动清除

### 查询优化

- 分页查询避免全表扫描
- 使用 JPA 投影减少查询字段
- 复杂聚合逻辑考虑物化视图

### 同步优化

- 分页批量拉取（当前 500 条/批）
- 并发同步多个实体类型（未来可考虑）
- 游标分页替代 skip 分页

---

## 测试策略

### 单元测试

- BFF：`mvn test`
- 前端：`pnpm run frontend:test`
- 合约：`forge test`

### 集成测试

- BFF + PostgreSQL + Redis 集成测试
- 前端 E2E 测试（Playwright）

### 测试数据

- 使用测试网（Sepolia、Scroll Sepolia）
- Faucet 合约提供测试代币
- Subgraph 索引测试网数据

---

## 安全考虑

### 智能合约

- Foundry 测试覆盖率
- 使用 OpenZeppelin 标准库
- 访问控制（Ownable、AccessControl）
- 重入攻击防护（ReentrancyGuard）

### BFF 安全

- 环境变量管理敏感配置
- 数据库连接加密
- API 速率限制（未来可加）
- 输入验证与参数化查询

### 前端安全

- 钱包签名验证
- HTTPS 通信
- 敏感信息脱敏展示

---

## 部署架构

### 本地开发

```
Docker Compose（PostgreSQL + Redis + Jaeger）
    ↓
本地 BFF（Spring Boot）
    ↓
本地前端（Vite Dev Server）
```

### 生产部署（建议）

```
前端：CDN（静态资源）+ Vercel/Netlify
BFF：云服务器（Docker 容器）+ 负载均衡
数据库：托管 PostgreSQL（RDS）
缓存：托管 Redis（ElastiCache）
追踪：Jaeger + Elasticsearch
```

---

## 版本依赖

| 组件 | 版本要求 |
|------|---------|
| Node.js | >= 18 |
| Java | 17 |
| pnpm | 10.16.1 |
| PostgreSQL | 15 |
| Redis | 7 |
| Solidity | ^0.8.x |
| Foundry | latest |

---

## 项目团队协作建议

### 代码规范

- ESLint + Prettier 统一格式
- 使用根目录配置文件（前端与 Subgraph 共享）
- Git Hooks（可选，已在 `.gitignore` 中忽略）

### 分支管理

建议采用 Git Flow：

- `main`：稳定版本
- `develop`：开发集成分支
- `feature/*`：功能分支
- `fix/*`：修复分支

### 文档维护

- 规格文档：`specs/` 目录
- README：各子项目独立维护
- API 文档：GraphQL Schema + Playground

### 开发流程

1. 从 `develop` 创建功能分支
2. 本地开发与测试
3. 提交 PR 到 `develop`
4. Code Review 后合并
5. 定期从 `develop` 发布到 `main`

---

## 常见任务参考

### 新增一条链

1. 部署合约到新链
2. 部署 Subgraph 到 The Graph
3. 更新 BFF `application.yaml` 配置
4. 更新前端链配置（RPC、合约地址）
5. 触发全量同步
6. 验证数据

### 新增一个实体类型同步

1. Liquibase 建表（如需）
2. 创建 JPA Entity（使用 `@IdClass(ChainEntityId.class)`）
3. 创建 Repository 接口
4. 创建 SyncHandler 解析逻辑
5. 在 `SubgraphSyncService` 添加同步 step
6. 测试同步与查询

### 修改 GraphQL API

1. 更新 `schema.graphqls`（如需）
2. 修改或新增 Resolver 方法
3. 实现查询逻辑（Repository + 缓存）
4. 测试 GraphQL Playground
5. 更新前端查询代码

### 调试同步问题

1. 查看 BFF 日志（控制台或 `logs/bff-run.log`）
2. 查询 `sync_status` 表确认失败 step
3. 手动测试 Subgraph 端点（curl）
4. 修复 Handler 或查询逻辑
5. 清库重跑验证

---

## 技术亮点

1. **Monorepo 多语言工程化**：统一管理 TypeScript、Java、Solidity 三种技术栈
2. **多链数据聚合**：支持多条链的数据同步与查询，避免前端复杂度
3. **分层缓存策略**：Redis TTL 分级，平衡实时性与性能
4. **复合主键设计**：`(chain_id, id)` 保障多链数据隔离
5. **可观测性**：集成 Jaeger 分布式追踪，便于排查问题
6. **数据库版本控制**：Liquibase 管理 schema 演进，支持回滚
7. **GraphQL 统一 API**：灵活查询，减少前端请求次数

---

## 参考资料

### 项目内文档

- `specs/`：功能规格与分析文档

### 外部资源

- Uniswap V2 文档：https://docs.uniswap.org/contracts/v2/overview
- The Graph 文档：https://thegraph.com/docs/
- Chainlink CCIP：https://docs.chain.link/ccip
- Spring GraphQL：https://spring.io/projects/spring-graphql
- Foundry Book：https://book.getfoundry.sh/

---

## 后续探讨方向

基于以上项目分析，我们可以探讨以下任务方向：

### 功能增强

- 用户资产管理（持仓、收益统计）
- 交易挖矿与流动性激励
- 更丰富的图表与数据可视化
- 移动端适配

### 性能优化

- 增量同步机制落地
- 数据库索引优化
- 查询性能分析与优化
- 缓存命中率监控

### 运维增强

- Prometheus + Grafana 监控
- 日志聚合与分析（ELK）
- 自动化部署（CI/CD）
- 灾难恢复方案

### 架构演进

- 同步服务独立（微服务）
- 消息队列引入（Kafka）
- 实时数据推送（WebSocket）
- 多地域部署

### 新链支持

- 主网部署（Ethereum、Arbitrum、Optimism 等）
- 更多测试网（Base、Linea 等）
- 跨链聚合路由

### 测试与质量

- 单元测试覆盖率提升
- 集成测试自动化
- 压力测试与性能基准
- 安全审计

---

**请告诉我您希望优先探讨或执行哪方面的任务，我可以基于当前项目结构提供更详细的设计方案。**

文件已完整更新!主要涵盖了您提供的所有背景信息,包括完整的用户流程、子图架构、当前已完成的全量同步机制、以及未来的实时数据处理规划。