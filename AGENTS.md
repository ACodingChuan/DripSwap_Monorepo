 # DripSwap 项目开发指南

> **文档目的**：为 AI 开发助手提供完整的项目上下文，支持多 AI 协作开发
> 
> **最后更新**：2025-11-22  
> **适用于**：Claude、ChatGPT、Cursor 等 AI 开发工具的新会话

---

## 🎯 AI 开发规范

### 代码风格规范

#### 前端 (TypeScript/React)

**ESLint 配置** (`eslint.config.js`)

- ✅ TypeScript 严格类型检查
- ✅ React Hooks 规则强制
- ✅ JSX a11y 无障碍规则
- ⚠️ 关闭的规则：
  - `react/react-in-jsx-scope` - React 17+ 无需导入 React
  - `import/order` - 导入顺序不强制
  - `@typescript-eslint/require-await` - 异步函数不强制 await
  - `@typescript-eslint/no-unsafe-assignment` - 允许 any 赋值

**Prettier 格式化** (`.prettierrc`)

```json
{
  "singleQuote": true,        // 使用单引号
  "semi": true,               // 语句末尾分号
  "trailingComma": "es5",     // 尾部逗号
  "printWidth": 100           // 每行 100 字符
}
```

**具体要求**

1. **导入排序**（虽未强制，但建议）
   ```typescript
   // 1. 第三方库
   import React from 'react';
   import { useQuery } from '@tanstack/react-query';
   
   // 2. 项目内部绝对路径
   import { SwapPort } from '@/domain/ports/swap-port';
   
   // 3. 相对路径
   import { swapService } from '../services/swap-service';
   
   // 4. 类型导入
   import type { SwapResponse } from '@/domain/models/swap';
   ```

2. **变量命名**
   - 常量：`UPPER_SNAKE_CASE` (如 `MAX_SLIPPAGE`)
   - 变量/函数：`camelCase` (如 `handleSwap`, `userAddress`)
   - 类型/接口：`PascalCase` (如 `SwapPort`, `TokenPayload`)
   - 私有方法：`_camelCase` 或 `#privateMethod`

3. **注释规范**
   ```typescript
   // 单行注释：描述「是什么」
   const maxSlippage = 0.01;
   
   /**
    * 多行注释：描述「为什么」和「如何用」
    * @param tokenAddress - Token 地址
    * @returns 当前余额（wei 单位）
    */
   async function getBalance(tokenAddress: string): Promise<BigInt> {
     // 实现
   }
   ```

4. **错误处理**
   ```typescript
   try {
     const result = await swapService.swap(params);
     return result;
   } catch (error) {
     logger.error('Swap failed', { error, params });
     throw new Error(`Swap failed: ${error.message}`);
   }
   ```

#### 后端 (Java)

**代码风格**

1. **命名规范**
   - 类：`PascalCase` (如 `QueryResolver`, `DemoTxService`)
   - 方法：`camelCase` (如 `getUserById`, `processRawEvents`)
   - 常量：`UPPER_SNAKE_CASE` (如 `DEFAULT_TIMEOUT`)
   - 包名：`com.dripswap.bff.modules.{domain}` (小写)

2. **注释规范**
   ```java
   /**
    * 处理原始区块链事件
    * 定时任务，每 5 秒扫描一次 raw_events 表
    */
   @Scheduled(cron = "*/5 * * * * *")
   public void processRawEvents() {
       // 实现
   }
   ```

3. **异常处理**
   ```java
   try {
       List<RawEvent> events = rawEventRepository.findAll();
       // 处理
   } catch (Exception e) {
       logger.error("Error processing events", e);
       span.recordException(e);
       throw new RuntimeException("Processing failed", e);
   } finally {
       span.end();
   }
   ```

4. **OpenTelemetry 追踪**
   ```java
   Span span = tracer.spanBuilder("methodName")
       .setAttribute("chain_id", chainId)
       .setAttribute("tx_hash", txHash)
       .startSpan();
   try {
       // 业务逻辑
   } finally {
       span.end();
   }
   ```

#### 智能合约 (Solidity)

1. **命名规范**
   - 合约：`PascalCase` (如 `BurnMintPool`)
   - 函数：`camelCase` (如 `swapExactTokensForTokens`)
   - 常量：`UPPER_SNAKE_CASE` (如 `DEFAULT_HARD_BPS`)
   - 私有变量：`_camelCase` (如 `_owner`)
   - 接口：`IPascalCase` (如 `IUniswapV2Router`)

2. **注释规范**
   ```solidity
   /// @notice 执行交换操作
   /// @param tokenIn 输入代币地址
   /// @param amountIn 输入金额
   /// @return amountOut 输出金额
   function swap(
       address tokenIn,
       uint256 amountIn
   ) external returns (uint256 amountOut) {
       // 实现
   }
   ```

---

### Spec 文档规范

用于记录新功能的详细设计文档。格式如下：

#### 文件位置

```
specs/
├── 4.1-SWAP.md
├── 4.2-BRIDGE.md
├── 4.3-FAUCET.md
└── ...
```

#### Spec 编写原则（关键更新）

> **原则：前端交互 vs 后端读取分离**
> 
> 1. **交互类 Spec** (如 `4.1-SWAP.md`, `4.2-BRIDGE.md`)：
>    - **只包含**：核心业务流程、链上交互（写操作）、实时链上查询（如 `eth_call` 查价格/余额）。
>    - **严禁包含**：后端历史数据查询、统计分析、K线图等依赖 ETL/DB 的需求。
>    - **目的**：确保核心功能（MVP）不被复杂的后端索引逻辑阻塞。
> 
> 2. **读取类 Spec** (统一为 `4.8-READ-FROM-ETL.md`)：
>    - **包含所有**：各模块的历史记录查询（Swap History, Bridge History）、复杂聚合查询（Portfolio）、报表统计。
>    - **目的**：统一设计数据模型和 API，避免重复造轮子。

> 3. **状态标记规范**：
>    - **未完成/待开发**的任务或功能点，必须显式标记，并建议使用红色高亮（如果支持）或 `🔴` 前缀。
>    - 例如：`<span style="color:red">Gas 费用估算 (待开发)</span>` 或 `🔴 Gas 费用估算`。

> 4. **暂不使用 Protobuf**：
>    - 虽然后端架构文档 (`dripswap-backend-architecture.md`) 中提到了 PB (Protobuf)，但在当前的开发阶段（MVP/Phase 1-2），我们**暂不引入 Protobuf**。
>    - 所有数据交互和存储应优先使用 **JSON**、**GraphQL Schema** 和 **Java POJO**。
>
> 5. **写操作优先前端直调**：
>    - **原则**：一切能直接由前端调用合约完成的写操作（如 Swap, Bridge Send, Faucet Claim），必须由前端直接调用区块链，**严禁**通过后端 REST 接口转发私钥或代付 Gas（除非是 Meta-Transaction 且明确设计）。
>    - **修正**：后端架构文档中关于 `REST (写)` 的部分如果是代理链上交互，应视为设计冗余，Spec 编写时予以修正。后端仅保留必要的业务逻辑接口（如 Policy 校验、Admin 管理）。

#### Spec 文档模板

```
# {功能名称} Spec

**版本**：v1.0  
**状态**：草案 | 讨论中 | 已批准 | 开发中 | 已完成  
**最后更新**：YYYY-MM-DD  

---

## 1. 背景与动机

为什么需要这个功能？解决什么问题？

---

## 2. 目标与范围

### 功能目标
- 目标 1：...
- 目标 2：...

### 范围
- 包含：...
- 不包含：...

---

## 3. 需求定义

### 用户故事

**格式要求**：使用 **When-Case-Do** 结构，明确前端界面交互流程

**Story 1：** 作为 {用户角色}，我想 {执行某操作}，以便 {获得某价值}

**前端交互流程**（When-Case-Do）：

**WHEN** 我想 {执行某操作} 时
- **前端展示**：
  - 页面位置：`/app/routes/{page}.tsx`
  - 组件状态：显示 {具体 UI 元素}
  - 初始数据：加载 {数据来源}

**CASE** 我点击/输入 {具体操作}
- **前端响应**：
  - UI 变化：{按钮状态/输入框/加载动画}
  - 数据请求：调用 `{AdapterName}.{methodName}()`
  - 参数传递：`{ param1, param2 }`

**DO** 系统应该
- **成功场景**：
  - 后端返回：`{ data: {...} }`
  - 前端展示：更新 {具体 UI 区域} 显示 {结果}
  - 用户反馈：Toast 提示 "{成功消息}"
- **失败场景**：
  - 错误处理：显示 {错误提示}
  - 降级方案：{备选操作}

**接受标准**：
- [ ] 页面加载时，正确显示 {初始状态}
- [ ] 点击 {按钮} 后，触发 {预期行为}
- [ ] 成功时，界面显示 {成功状态}
- [ ] 失败时，显示 {错误提示}

### 功能需求

| 需求 ID | 描述 | 优先级 |
|--------|------|-------|
| FR-001 | ... | P0 |
| FR-002 | ... | P1 |

---

## 4. 技术方案

### 4.1 架构设计

前端 → 适配器 → Service → Repository → Data Source

### 4.2 数据模型

**数据库表**
sql
CREATE TABLE table_name (
  id BIGSERIAL PRIMARY KEY,
  ...
);


**GraphQL 类型**

graphql
type PayloadName {
  id: ID!
  field1: String!
  field2: Int
}


### 4.3 API 设计

**REST 端点** (如果有写操作)

POST /api/{feature}
Request: { ... }
Response: { success: bool, data: {...} }

**GraphQL 查询** (读操作)

graphql
type Query {
  {feature}(filter: FilterInput): [Payload!]!
}

### 4.4 流程图

用户操作 → 前端调用 → 后端处理 → 数据库存储 → 事件触发

---

## 5. 实现细节

### 5.1 前端实现

- [ ] 组件开发
- [ ] 适配器实现
- [ ] Service 接口

### 5.2 后端实现

- [ ] Controller/Resolver
- [ ] Service 业务逻辑
- [ ] Repository 数据访问

### 5.3 Subgraph 实现

- [ ] 事件处理器
- [ ] Schema 更新

### 5.4 合约实现

- [ ] 智能合约编码
- [ ] 单元测试
- [ ] 部署脚本

---

## 6. 测试计划

### 单元测试
- 前端：Vitest
- 后端：JUnit
- 合约：Foundry

### 集成测试
- 前后端集成
- 链上交互测试

### 测试覆盖率目标
- 后端：> 80%
- 前端：> 70%

---

## 7. 风险与缓解

| 风险 | 影响 | 概率 | 缓解方案 |
|-----|------|------|----------|
| ... | ... | ... | ... |

---

## 8. 交付清单

- [ ] 代码完成
- [ ] 测试通过
- [ ] 文档完善
- [ ] Code Review 通过
- [ ] 部署完成

---

## 9. 参考文档

- [后端架构设计](../dripswap-backend-architecture.md)
- [项目指南](../DRIPSWAP_PROJECT_GUIDE.md)
- [项目进展](../PROJECT_PROGRESS.md)
```

#### Spec 编写建议

1. **讨论前创建** - 在开发前与团队（包括 AI）讨论
2. **迭代更新** - 随着理解深化持续更新
3. **标记状态** - 草案 → 讨论 → 批准 → 开发 → 完成
4. **细节程度** - 足够指导开发，避免过度设计

---

## 📌 项目概述

### 核心定位

DripSwap 是一个**基于测试网的跨链 DEX 演示项目**，核心特点：

- **复用 Uniswap V2 池子** + 自定义虚拟代币（vToken）
- **Faucet 发放测试币**，让用户无需真实测试币即可体验完整 DEX 流程
- **跨链桥接**：基于 Chainlink CCIP 实现 vToken 的跨链转移


### 技术栈总览

```
┌─────────────────────────────────────────────────────────────┐
│                     DripSwap 全栈架构                         │
├─────────────────────────────────────────────────────────────┤
│ 前端 (DripSwap_Fronted/)                                     │
│   React + Vite + TypeScript + TanStack Router              │
│   RainbowKit + Wagmi + Viem (Web3 连接)                     │
│   TailwindCSS + Radix UI (UI 组件)                          │
├─────────────────────────────────────────────────────────────┤
│ 后端 (DripSwap_BFF/)                                         │
│   Spring Boot 3.2.5 + Java 17                              │
│   GraphQL (读) + REST (写)                                  │
│   PostgreSQL + Redis + Web3j                                │
│   OpenTelemetry (链路追踪)                                   │
├─────────────────────────────────────────────────────────────┤
│ 合约 (DripSwap_Contract/)                                    │
│   Foundry (Solidity 0.8.x)                                  │
│   Uniswap V2 (0.5.16 / 0.6.6)                               │
│   Chainlink CCIP (跨链桥)                                    │
│   OpenZeppelin (权限/代理)                                   │
├─────────────────────────────────────────────────────────────┤
│ 索引 (DripSwap_Subgraph/)                                    │
│   The Graph (AssemblyScript)                                │
│   Sepolia Testnet 部署                                       │
│   GraphQL 查询端点                                           │
└─────────────────────────────────────────────────────────────┘
```

### 当前网络支持

| 网络 | Chain ID | RPC | 合约部署 | Subgraph |
|------|----------|-----|----------|----------|
| **Ethereum Sepolia** | 11155111 | Alchemy | ✅ 已部署 | ✅ 已部署 |
| **Scroll Sepolia** | 534351 | Alchemy | ✅ 已部署 | ⏳ 待部署 |

**注意事项**：
- Faucet 合约暂未部署
- Sepolia Subgraph 端点：`https://api.studio.thegraph.com/query/1716244/sepolia/version/latest`
- 合约部署详情见：`DripSwap_Contract/deployments/`

---

## 🗂️ 项目结构

```
DripSwap Monorepo/
├── apps/
│   ├── frontend/                 # React + Vite 前端
│   ├── bff/                      # Spring Boot 后端
│   ├── contracts/                # Foundry 智能合约
│   └── subgraph/
│       └── sepolia/              # The Graph 索引（Sepolia）
├── README.md
├── Makefile
├── package.json / pnpm-workspace.yaml
├── docker-compose.yaml
└── specs/
    ├── 4.1-SWAP.md
    ├── 4.2-BRIDGE.md
    ├── 4.8-READ-FROM-ETL.md
    └── dripswap-backend-architecture.md
```

---

## 🎯 项目关键组件

### 1. 前端 (DripSwap_Fronted/)

#### 技术栈

| 类别 | 技术 | 用途 |
|-----|------|------|
| **框架** | React 18 + Vite | 前端渲染 + 构建工具 |
| **路由** | TanStack Router | 类型安全的路由系统 |
| **Web3** | Wagmi 2.x + Viem | 区块链交互 |
| **钱包** | RainbowKit | 钱包连接 UI |
| **状态管理** | Zustand + TanStack Query | 全局状态 + 服务端缓存 |
| **UI** | TailwindCSS + Radix UI | 样式 + 无障碍组件 |
| **表单** | React Hook Form + Zod | 表单处理 + 验证 |

#### 目录结构说明

```
src/
├── app/                    # 应用层
│   ├── routes/            # 页面路由组件
│   └── providers/         # 全局 Provider
├── domain/                 # 领域层
│   ├── swap/              # Swap 业务逻辑
│   └── bridge/            # Bridge 业务逻辑
├── infrastructure/         # 基础设施层
│   ├── web3/              # Web3 配置和 Hooks
│   └── api/               # API 客户端
└── shared/                 # 共享层
    ├── components/        # 通用组件
    ├── hooks/             # 通用 Hooks
    └── utils/             # 工具函数
```

#### 关键命令

```bash
pnpm --dir apps/frontend dev              # 启动开发服务器 (Vite)
pnpm --dir apps/frontend build            # 生产构建
pnpm --dir apps/frontend test             # 运行单元测试 (Vitest)
pnpm --dir apps/frontend test:ui          # Vitest UI 模式
pnpm --dir apps/frontend e2e              # 运行 E2E 测试 (Playwright)
pnpm --dir apps/frontend typecheck        # TypeScript 类型检查
pnpm --dir apps/frontend lint             # ESLint 代码检查
pnpm --dir apps/frontend format           # Prettier 格式化
```

#### 核心依赖版本

```json
{
  "react": "^18.3.1",
  "wagmi": "^2.12.33",
  "viem": "^2.21.53",
  "@rainbow-me/rainbowkit": "^2.2.9",
  "@tanstack/react-router": "^1.74.7",
  "@tanstack/react-query": "^5.56.0"
}
```

---

### 2. 后端 BFF (DripSwap_BFF/)

#### 架构模式

**DDD (领域驱动设计) + 六边形架构**

**核心开发模式**：Subgraph 数据同步 → Postgres 权威账本 → GraphQL 读 + REST 写

```
┌──────────────────────────────────────────────────────────────┐
│              对外接口层                                       │
│  GraphQL (读，来源=自建 Postgres) + REST (写，风控/记录)     │
└──────────────┬───────────────────────────────────────────────┘
               ↓
┌──────────────────────────────────────────────────────────────┐
│              BFF 聚合层                                       │
│  QueryResolver (读聚合) + RestController (写命令)             │
└──────────────┬───────────────────────────────────────────────┘
               ↓
┌──────────────────────────────────────────────────────────────┐
│         领域模块 (modules/)                                   │
│  swap / bridge / faucet / portfolio / registry / admin        │
└──────────────┬───────────────────────────────────────────────┘
               ↓
┌──────────────────────────────────────────────────────────────┐
│         适配层 + 数据层                                       │
│  ┌─ SubgraphSync (定期)──────▶ Postgres (结构化数据)        │
│  ├─ WsConnectionManager (实时)──▶ raw_events + 缓存失效     │
│  ├─ AnalyticsService──────────▶ 统计 & 缓存                │
│  └─ Redis──────────────────▶ Cache-Aside + 流控/冷却       │
└──────────────────────────────────────────────────────────────┘
```

**数据流三层**：
- 🟢 **一级读源**：PostgreSQL（Subgraph 同步后的结构化数据）
- 🟡 **二级读源**：Redis（短期缓存）
- 🔴 **三级兜底**：raw_events（WS 监听的最近事件，用于对账）
#### 核心模块说明

| 模块 | 路径 | 职责 | 关键类 |
|-----|------|------|-------|
| **链上事件监听** | `modules/chains/events/` | WebSocket 监听多链关键事件（Bridge/关键Token） | ChainEventListener, EventDecoder, RawEventPersister |
| **交易解析** | `modules/tx/service/` | 定时扫描 raw_events → 对账/触发缓存失效 | TxService (定时任务) |
| **REST 接口** | `modules/rest/` | 写操作接口（幂等性） | DemoTxController, DemoTxService |
| **GraphQL 查询** | `modules/gql/resolvers/` | 读聚合接口（来源=自建 Postgres） | QueryResolver |
| **分析服务** | `modules/analytics/` | Token/Pair/TVL 统计 | AnalyticsService |
| **Subgraph 同步** | `modules/subgraph/` | 从 The Graph 批量拉取并入库 | SubgraphSyncService, SubgraphClient |
#### 数据流向与同步模式

**核心流程**：The Graph ──(定期同步 1-5 分钟)──▶ Postgres ──(GraphQL 查询)──▶ 前端
            区块链 ──(WebSocket 实时)──▶ raw_events ──(缓存失效)──▶ Redis

```
┌────────────────────────────────────────────────────────────────┐
│                    数据输入来源                                  │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│ The Graph Subgraph (主结构化数据源)                              │
│   • pairs(reserve0, reserve1, volumeUSD, ...)                  │
│   • tokens(symbol, decimals, totalSupply, ...)                 │
│   • swaps(amount, timestamp, ...)                              │
│          │                                                     │
│          └─(HTTP GraphQL 分页拉取, 定时 1-5 分钟)─▶               │
│                                                                │
│ 区块链 RPC (WebSocket 实时事件)                                  │
│   • SwapEvent / MessageSent / MessageReceived / Transfer       │
│          │                                                     │
│          └─(WS 订阅, 实时)─▶                                    │
│                                                               │
└────────┬──────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│                 BFF 处理与落库                                   │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│ ┌─ SubgraphSyncService (定时任务, 每 1-5 分钟)                    │
│ │  ├─ 查询 Subgraph                                             │  
│ │  ├─ 解析并入库 Postgres                                        │
│ │  │  • pair_cache (池子数据)                                    │
│ │  │  • token_meta (代币元数据)                                  │
│ │  │  • swap_tx (历史)                                          │
│ │  └─ 更新 sync_cursor (同步游标)                                │
│ │                                                              │
│ ├─ WsConnectionManager (实时)                                   │
│ │  ├─ 订阅关键事件                                               │
│ │  ├─ EventDecoder 解析                                        │
│ │  └─ RawEventPersister 入库 raw_events (append-only)          │
│ │                                                             │
│ └─ CacheInvalidator (实时)                                     │
│    └─ WS 事件触发精确缓存失效 (Redis)                             │
│                                                               │
└────────┬──────────────────────────────────────────────────────┘
         │
         ▼
┌────────────────────────────────────────────────────────────────┐
│              Postgres (权威链下账本)                             │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│ ┌────────────────┐      ┌────────────────┐      ┌────────────┐│
│ │  pair_cache    │      │  token_meta    │      │  sync_info ││
│ │  (活跃池子)    │      │  (代币元数据)  │      │ (游标等)   ││
│ └────────────────┘      └────────────────┘      └────────────┘│
│ ┌────────────────┐      ┌────────────────┐      ┌────────────┐│
│ │  swap_tx       │      │  bridge_tx     │      │ faucet_req ││
│ │  (历史记录)    │      │  (跨链记录)    │      │ (冷却/限额)││
│ └────────────────┘      └────────────────┘      └────────────┘│
│ ┌───────────────────────────────────────────────────────────┐  │
│ │ raw_events (WS 监听的原始事件, 用于兜底与对账)              │  │
│ └───────────────────────────────────────────────────────────┘  │
│                                                               │
└────────┬──────────────────────────────────────────────────────┘
         │
    ┌────┴────┐
    │ GraphQL │
    │ 查询    │
    ▼         ▼
┌───────┐ ┌────────┐
│ Redis │ │ 前端    │
│ 缓存   │ │ 展示    │
└───────┘ └────────┘
```

**关键特性**：
- ✅ **单一权威源**：Postgres 是链下唯一真相，避免多源数据不一致
- ✅ **读优先 Postgres**：所有 GraphQL 查询都打到自建 DB，不依赖 Subgraph 服务
- ✅ **实时监听补充**：WS 监听关键事件，一旦到达立即失效相关缓存（Cache-Aside）
- ✅ **兜底对账**：Subgraph 故障时降级到 raw_events；定期对比两源数据
- ✅ **幂等与流控**：Redis 存储去重 key、冷却计时、限额配额
#### 关键配置

**application.yaml**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/dripswap
    username: dripswap
    password: dripswap
  
  redis:
    host: localhost
    port: 6379

dripswap:
  chains:
    - id: sepolia
      chainId: 11155111
      rpc:
        http: "https://..."
        ws: "wss://..."
```

#### 技术栾

| 组件 | 技术 | 版本 |
|-----|------|------|
| **框架** | Spring Boot | 3.2.5 |
| **Java** | OpenJDK | 17 |
| **GraphQL** | Spring GraphQL + Kickstart | 15.1.0 |
| **区块链** | Web3j | 4.10.3 |
| **数据库** | PostgreSQL + JPA + Liquibase | - |
| **缓存** | Redis | - |
| **可观测** | OpenTelemetry | 1.33.0 |

---

## 📖 后端开发模式详解

> **当前阶段**：单体 BFF，不使用 Protobuf，所有数据交互基于 JSON + GraphQL Schema  
> **核心思路**：Subgraph 定期同步 → PostgreSQL 权威账本 → GraphQL 读接口 + REST 写接口  
> **详细设计** 👉 [后端架构总体设计](../specs/dripswap-backend-architecture.md)（第 2-9 章）

### 三层数据读源（优先级）

```
1️⃣  一级源：PostgreSQL
    ├─ pair_cache (Subgraph 同步，1-5 分钟一次)
    ├─ token_meta (Subgraph 同步)
    ├─ swap_tx (历史记录)
    └─ 所有 GraphQL 查询的唯一数据源

2️⃣  二级源：Redis  
    └─ Cache-Aside (TTL: 30-120s)
       用于加速重复查询，WS 事件到达时立即失效

3️⃣  三级兜底：raw_events
    └─ WS 监听的原始事件（append-only）
       Subgraph 故障时降级读取；用于对账兜底
```

### 数据同步周期

```
Subgraph API
    ↓ (HTTP GraphQL, 每 1-5 分钟)
SubgraphSyncService
    ├─ 拉取 pairs / tokens / swaps
    ├─ 解析与验证
    └─ Upsert 入 Postgres
       ├─ INSERT OR UPDATE pair_cache
       ├─ INSERT OR UPDATE token_meta
       └─ UPDATE sync_cursor (游标)
       
    ↓ (缓存失效)
    
Redis 中清除旧缓存
```

### WebSocket 实时监听

```
区块链 RPC (WebSocket)
    ↓ (关键事件)
    ├─ SwapEvent
    ├─ MessageSent (Bridge)
    ├─ MessageReceived (Bridge)
    ├─ Transfer (关键代币)
    └─ 其他重要事件
       
    ↓ (WsConnectionManager 监听)
    
EventDecoder → RawEvent
    ↓
RawEventPersister → 入库 raw_events
    ↓
CacheInvalidator → 精确失效 Redis 缓存
    例：pair:{pairId}:cache DELETE
```

### 缓存策略

| Key 前缀 | TTL | 用途 | 失效方式 |
|---------|-----|------|----------|
| `pair:{id}:cache` | 30-60s | 池子信息缓存 | WS Swap 事件触发 |
| `token:{id}:meta` | 60-120s | 代币元数据 | Subgraph 同步后立即失效 |
| `portfolio:{addr}` | 30s | 用户组合缓存 | WS 事件触发 |
| `idem:{domain}:{clientTxId}` | 30min | 幂等去重 | 自动过期 |
| `cooldown:{addr}:faucet` | 24h | 冷却计时 | 自动过期 |
| `quota:{addr}:faucet:YYYYMMDD` | 24h | 日限额 | 自动过期 |

### REST 写操作（不代理链上写）

```
前端钱包签名 ──▶ 区块链执行（写操作）

后端 REST 仅用于：
  ✅ 风险检查（冷却、限额、黑名单）
  ✅ 状态记录（将 tx_hash 入库）
  ✅ 缓存失效触发
  ✅ 审计与策略
  
严禁：❌ 后端转发私钥 ❌ 后端代付 Gas
```

### 开发流程

#### 新增读端点
1. 在 `specs/4.x-XXX.md` 中定义查询需求
2. 在 `schema.graphqls` 中添加 Type 与 Query
3. 实现 `QueryResolver` 方法
   ```java
   @QueryMapping
   public List<PayloadType> myQuery(...) {
     // 1. 查询 Redis 缓存
     // 2. 缓存未命中 → 查询 Postgres
     // 3. 返回 + 写缓存（TTL）
   }
   ```
4. 编写单元测试（mock Postgres + Redis）
5. 前端调用 GraphQL

#### 新增写端点
1. 在 `specs/4.x-XXX.md` 中定义写需求
2. 创建 REST Controller
   ```java
   @RestController
   public class MyController {
     @PostMapping("/api/v1/action")
     public ResponseEntity doAction(@RequestBody MyRequest req) {
       // 1. 前置检查（冷却、限额）
       // 2. 业务执行（调用外部服务或记录）
       // 3. 缓存失效（Redis）
       // 4. 返回结果
     }
   }
   ```
3. 在 Postgres 中创建相关表
4. 使用 Redis 的流控/冷却
5. 编写集成测试（TestContainers）
6. 前端调用 REST

### 故障处理

#### Subgraph 故障
```
尝试查询 Subgraph ──▶ 超时/错误
                 ↓
              降级到 raw_events
                 ↓
          返回最近的 WS 监听事件
                 ↓
          告警：Subgraph 不可用
```

#### WS 连接断开
```
WsConnectionManager ──▶ 连接断开
                     ↓
              自动重连逻辑
                     ↓
          查询 sync_cursor 表
                     ↓
        从上次断点恢复订阅
                     ↓
       补扫缺失的块与事件
```

#### 数据不一致
```
定时对账任务
  ├─ 比较 pair_cache (Postgres) vs raw_events (WS)
  ├─ 如果差异 > 阈值 ──▶ 告警
  └─ 可选：自动修复（以链上为准）
```

---

**详细实现参考** → [specs/backend-development-mode.md](../specs/backend-development-mode.md)

#### 关键命令

```bash
mvn clean package -DskipTests   # 打包
./start.sh                      # 启动服务
mvn spring-boot:run             # 开发模式启动
```

#### 数据库表结构（Liquibase）

```sql
-- 原始事件表（append-only）
raw_events (
  id BIGSERIAL PRIMARY KEY,
  chain_id VARCHAR(50),
  block_number BIGINT,
  tx_hash VARCHAR(66),
  log_index INT,
  event_sig VARCHAR(66),
  raw_data TEXT,
  created_at TIMESTAMP
)

-- 结构化交易表
tx_records (
  id BIGSERIAL PRIMARY KEY,
  chain_id VARCHAR(50),
  block_number BIGINT,
  tx_hash VARCHAR(66),
  event_sig VARCHAR(66),
  decoded_name VARCHAR(50),
  decoded_data TEXT,
  status VARCHAR(20),
  created_at TIMESTAMP
)

-- Demo 交易表
demo_tx (
  id BIGSERIAL PRIMARY KEY,
  tx_hash VARCHAR(66) UNIQUE,
  chain_id VARCHAR(50),
  status VARCHAR(20) DEFAULT 'pending',
  created_at TIMESTAMP
)
```

---

### 3. 智能合约 (DripSwap_Contract/)

#### 合约架构

```
核心合约组件：

1. DEX 层（复用 Uniswap V2）
   ├── UniswapV2Factory  (0.5.16)
   ├── UniswapV2Pair     (0.5.16)
   └── UniswapV2Router02 (0.6.6)

2. vToken 层（虚拟代币）
   ├── VToken (ERC20 + Burnable + Mintable)
   ├── vETH / vUSDT / vUSDC / vDAI / vBTC / vLINK / vSCR
   └── Faucet (测试币发放)

3. 跨链层（CCIP）
   ├── Bridge (主桥接合约)
   └── BurnMintPool_* (每个 vToken 的 Pool)
```

#### 已部署合约地址 (Sepolia)

**核心合约**

| 合约 | 地址 | 说明 |
|-----|------|------|
| **UniswapV2Factory** | `0x6C9258026A9272368e49bBB7D0A78c17BBe284BF` | Pair 工厂 |
| **UniswapV2Router** | `0x2358DC77bB41a275195E49427A8ae78e61aE9040` | 路由合约 |
| **PriceOracle** | `0x7e8F17B349fD0f6b8A89d7c0640F232E15C68Ff3` | 价格预言机 |
| **Bridge** | `0x9347B320e42877855Cc6E66e5E5d6f18216CEEe7` | 跨链桥 |
| **Permit2** | `0x000000000022D473030F116dDEE9F6B43aC78BA3` | Uniswap Permit2 |

**vToken (虚拟代币)**

| Token | 地址 | Decimals |
|-------|------|----------|
| **vETH** | `0xE91d02E66a9152Fee1BC79c1830121F6507a4F6D` | 18 |
| **vUSDT** | `0xBAcDBe38Df8421d0AA90262BEB1C20d32a634fe7` | 6 |
| **vUSDC** | `0x46A906fcA4487C87f0d89D2d0824EC57bdAa947D` | 6 |
| **vDAI** | `0x0C156E2F45a812ad743760A88d73fB22879BC299` | 18 |
| **vBTC** | `0xAeA8C2F08b10Fe1853300dF4332E462b449e19D6` | 8 |
| **vLINK** | `0x1A95d5d1930b807B62B20f3cA6b2451Ffc75B454` | 18 |
| **vSCR** | `0x4911Fb3923F6DA0cd4920F914991B0A742d88Bfd` | 18 |

**BurnMintPool (跨链 Pool)**

| Token | Pool 地址 |
|-------|----------|
| **vETH** | `0xfE81DBC7ec3AE383a7535f5aFAe817621f2f0e34` |
| **vUSDT** | `0x7E4E689a73e6ffAE9B761148926d3fAD3664f116` |
| **vUSDC** | `0xA9CceE83eA56AEB484Cf72b90FA81392719cEcab` |
| **vDAI** | `0xF774dC8f6D0c92e6cB2E0260dCc720c5E1571d31` |
| **vBTC** | `0x0Ee1e426b2DCE06a34DF8f23463e2559F75ba880` |
| **vLINK** | `0x4BE437a25237C511d316a8c8Bc594b422abAd2d1` |
| **vSCR** | `0xf985F69e6bE82F3EDeF82A2FE256b0eF4d114bd0` |

**CCIP 配置**

- **Router**: `0x0BF3dE8c5D3e8A2B34D2BEeB17ABfCeBaf363A59`
- **LINK Token**: `0x779877A7B0D9E8603169DdbD7836e478b4624789`
- **Chain Selector**: `16015286601757825753`

#### Foundry 配置 (foundry.toml)

```toml
[profile.default]
src = "src"
out = "out"
solc_version = "auto_detect"
optimizer = true
optimizer_runs = 2000
via_ir = true

# V2 Core (0.5.16)
[profile.v2core]
src = "lib/v2-core/contracts"
out = "out-v2core"
solc_version = "0.5.16"
optimizer_runs = 999999

# V2 Router (0.6.6)
[profile.v2router]
src = "contracts-v2-router"
out = "out-v2router"
solc_version = "0.6.6"
optimizer_runs = 999999
```

#### 部署流程 (Makefile)

```bash
# 完整部署流程
make deploy-all NETWORK=sepolia

# 包含 Etherscan 验证
make deploy-all-verify NETWORK=sepolia

# 慢速部署（避免 RPC 速率限制）
make deploy-all-verify-slow NETWORK=sepolia

# 单独部署步骤
make deploy-v2        NETWORK=sepolia  # UniswapV2
make deploy-tokens    NETWORK=sepolia  # vTokens
make deploy-oracle    NETWORK=sepolia  # Oracle

make deploy-pairs     NETWORK=sepolia  # 创建交易对
make deploy-bridge    NETWORK=sepolia  # Bridge
make deploy-burnmint  NETWORK=sepolia  # BurnMint Pools
```


---

### 4. Subgraph (DripSwap_Subgraph/dripswap-sepolia/)

#### 核心功能

The Graph 子图索引区块链事件，提供 GraphQL 查询接口。

#### 监听的合约和事件

| 合约 | 关键事件 | 用途 |
|-----|---------|------|
| **UniswapV2Factory** | PairCreated | 仅 Subgraph 同步（索引创建对） |
| **UniswapV2Pair** | Swap, Mint, Burn, Sync, Transfer | 以 Subgraph 同步为主（历史/批量），WS 仅用于精确失效 |
| **VToken (7个)** | Transfer, Minted, Burned, Approval | 同上 |
| **Bridge** | TransferInitiated, TokenPoolRegistered, MessageSent/Received | WS 实时 + Subgraph 同步（权威入库） |
| **BurnMintPool (7个)** | LockedOrBurned, ReleasedOrMinted | 同上 |


#### Schema 核心实体

```graphql
type Token @entity {
  id: ID!                    # Token 地址
  symbol: String!
  name: String!
  decimals: BigInt!
  totalSupply: BigInt!
  tradeVolume: BigDecimal!
  txCount: BigInt!
}

type Pair @entity {
  id: ID!                    # Pair 地址
  token0: Token!
  token1: Token!
  reserve0: BigDecimal!
  reserve1: BigDecimal!
  volumeUSD: BigDecimal!
  txCount: BigInt!
}

type VToken @entity {
  id: ID!                    # VToken 地址
  symbol: String!
  name: String!
  decimals: Int!
  totalSupply: BigInt!
  totalMinted: BigInt!
  totalBurned: BigInt!
}

type BridgeTransfer @entity {
  id: ID!                    # messageId
  fromChain: BigInt!
  toChain: BigInt!
  token: Bytes!
  amount: BigInt!
  sender: Bytes!
  receiver: Bytes!
  status: String!
}
```

#### 关键命令

```bash
# 生成类型
graph codegen

# 编译
graph build

# 部署到 The Graph Studio
graph auth <DEPLOY_KEY>
graph deploy --studio dripswap-sepolia --version-label 
```

#### 部署信息

- **Network**: Sepolia
- **Start Block**: 9573280
- **Studio Endpoint**: `https://api.studio.thegraph.com/query/1716244/sepolia/v0.1.1`
- **Playground**: `https://thegraph.com/studio/subgraph/dripswap-sepolia`

---

## 🛠️ 开发环境配置

### 前端环境

```bash
# 依赖管理
pnpm install

# 启动开发服务器
pnpm run frontend:dev

# 环境变量配置
# .env.development (开发环境)
VITE_API_IMPL=bff                    # mock | bff (切换数据源)
VITE_API_BASE_URL=http://localhost:8080
VITE_WALLETCONNECT_PROJECT_ID=demo_project_id

# .env.example (模板)
API_IMPL=mock
VITE_API_BASE_URL=http://localhost:8080
VITE_WALLETCONNECT_PROJECT_ID=your_project_id_here
FEATURE_STREAM=POLL
```

**重要**：`VITE_API_IMPL` 控制前端适配器模式：
- `mock`：使用 Mock 数据（开发初期）
- `bff`：连接真实 BFF 后端

### 后端环境

```bash
# 依赖管理
mvn clean install

# 启动服务
./start.sh
# 或
mvn spring-boot:run

# 数据库 (PostgreSQL)
docker-compose up -d postgres

# 缓存 (Redis)
docker-compose up -d redis

# 配置文件
src/main/resources/application.yaml
```

### 合约环境

```bash
# 安装 Foundry
curl -L https://foundry.paradigm.xyz | bash
foundryup

# 编译合约
make build

# 运行测试
make test

# 部署到测试网
make deploy-all-verify NETWORK=sepolia
```

### Subgraph 环境

```bash
# 安装依赖
pnpm install

# 生成类型
pnpm --dir apps/subgraph/sepolia run codegen

# 编译
pnpm --dir apps/subgraph/sepolia run build

# 部署
graph auth <DEPLOY_KEY>
pnpm --dir apps/subgraph/sepolia run deploy
```

**BFF 同步（Subgraph → Postgres）**
- 模块位置：`apps/bff`，包 `com.dripswap.bff.modules.subgraph.*`
- 配置：`application.yaml` 中 `subgraph` 节点（默认 2 分钟调度、batch=500、retry=3）  
  - sepolia endpoint：`https://api.studio.thegraph.com/query/1716244/sepolia/v0.1.1`，startBlock=9573280  
  - scroll-sepolia 预留，需部署后填入 endpoint/startBlock
- 数据落库表（Liquibase `003-subgraph-sync.xml`）：`pair_cache`、`token_meta`、`swap_tx`、`liquidity_tx`、`bridge_tx`、`bridge_leg`、`vtoken_state`、`sync_cursor`
- 实体策略：地址统一小写；事件实体 ID 默认 `tx_hash + log_index`；Bridge 仅 `TransferInitiated` 携带 messageId，其余池侧腿不强行关联。
- 关键查询指引：  
  - Uniswap 视角：`pairs/tokens`  
  - VToken 状态：`vtokens`（未入池的 vSCR 也在此）  
  - Bridge 发送：`bridgeSends/bridgeTransfers` status=Initiated  
  - Pool 腿：同一实体但 status=LockedOrBurned/ReleasedOrMinted，用 txHash+logIndex 识别

---

## 🧪 测试策略

### 前端测试

```bash
# 单元测试 (Vitest)
pnpm --dir apps/frontend test

# E2E 测试 (Playwright)
pnpm --dir apps/frontend e2e

# 类型检查
pnpm --dir apps/frontend typecheck
```

### 后端测试

```bash
# 单元测试
mvn test

# 集成测试
mvn verify
```

### 合约测试

```bash
# Foundry 测试
forge test -vvv

# Gas 报告
forge test --gas-report

# Coverage
forge coverage
```

---

## 📊 可观测性 (OpenTelemetry)

### Trace 标签规范

所有 Span 应包含以下标签（如适用）：

```
chain_id         # 链 ID (如 sepolia)
tx_hash          # 交易哈希
message_id       # CCIP 消息 ID
bridge_id        # 桥接 ID
client_tx_id     # 客户端交易 ID (幂等性)
user_address     # 用户地址
```

### 配置

```yaml
# application.yaml
otel:
  exporter:
    otlp:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4317}
  resource:
    attributes:
      service.name: dripswap-bff
      service.version: 1.0.0
```

### Jaeger UI

访问 `http://localhost:16686` 查看追踪链路。

---

## 🔐 安全与最佳实践

### 智能合约

- ✅ 使用 OpenZeppelin 标准库
- ✅ 所有写操作都有权限检查

- ✅ 跨链消息验证 (CCIP)
- ⚠️ 测试网项目，不处理真实资产

### 后端

- ✅ 幂等性：`client_tx_id` + Redis
- ✅ 频控：Redis rate limiting
- ✅ SQL 注入防护：JPA/Hibernate
- ✅ CORS 配置
- ✅ 健康检查：`/health` + `/readiness`

### 前端

- ✅ 签名前确认
- ✅ 滑点设置
- ✅ Gas 估算
- ✅ 交易状态追踪

---

## 🚀 部署流程

### 1. 部署合约

```bash
cd DripSwap_Contract
make deploy-all-verify-slow NETWORK=sepolia
```

### 2. 部署 Subgraph

```bash
cd DripSwap_Subgraph/dripswap-sepolia
graph codegen
graph build
graph auth <DEPLOY_KEY>
graph deploy --studio dripswap-sepolia -l v0.0.9
```

### 3. 启动后端

```bash
cd DripSwap_BFF
mvn clean package -DskipTests
./start.sh
```

### 4. 启动前端

```bash
cd DripSwap_Fronted
pnpm install
pnpm --dir apps/frontend build
pnpm preview
# 或部署到 Vercel/Netlify
```

---

## 📖 关键文档引用

| 文档 | 路径 | 用途 |
|-----|------|------|
| **后端架构设计** | `dripswap-backend-architecture.md` | 后端详细设计、任务清单 |
| **Subgraph Schema** | `DripSwap_Subgraph/.../schema.graphql` | 数据模型定义 |
| **合约部署记录** | `DripSwap_Contract/broadcast/` | 部署交易历史 |
| **GraphQL Schema** | `DripSwap_BFF/.../graphql/*.graphqls` | BFF 查询接口定义 |

---

## ❓ AI 开发常见问题

### Q1: 如何理解 BFF 的分层架构？

**A**: BFF 采用 DDD + 六边形架构，不同于传统 MVC：

```
传统 MVC:
  Controller → Service → DAO → Database

DDD + 六边形:
  Controller/Resolver (适配层)
    ↓
  Service (领域层，核心业务逻辑)
    ↓
  Repository/SubgraphClient (数据源适配)
    ↓
  Database/Subgraph/Redis (多数据源)
```

**关键点**：
- **TxService** 是后台定时任务，不对外暴露
- **QueryResolver** 只有 GraphQL 查询，没有对应的 REST
- **不是所有 Service 都需要两端适配**

### Q2: raw_events 和 tx_records 的区别？

**A**:

| 表 | 数据格式 | 数据来源 | 更新频率 | 用途 |
|----|---------|---------|---------|------|
| **raw_events** | 原始事件 JSON | WebSocket 监听 | 实时 | append-only，兜底数据源 |
| **tx_records** | 结构化交易 | TxService 定时处理 | 每 5 秒 | 分析、统计、查询 |

**流程**：
```
区块链事件 → raw_events (原始) → TxService 处理 → tx_records (结构化)
```

### Q3: GraphQL 和 REST 分别用于什么场景？

**A**:

```
GraphQL (读)：
  - 查询 Token/Pair 信息
  - 查询用户历史交易
  - 查询分析数据
  - 一次请求获取多种数据

REST (写)：
  - 提交交易
  - 请求 Faucet
  - 发起跨链桥接
  - 需要幂等性的操作
```

### Q4: 合约多版本 Solidity 如何管理？

**A**: Foundry 支持 Profile 机制：

```toml
[profile.default]        # 主合约 (0.8.x)
[profile.v2core]         # Uniswap V2 Core (0.5.16)
[profile.v2router]       # Uniswap V2 Router (0.6.6)
```

编译命令：
```bash
forge build                           # 主合约
FOUNDRY_PROFILE=v2core forge build    # V2 Core
FOUNDRY_PROFILE=v2router forge build  # V2 Router
```

### Q5: Subgraph 部署失败如何调试？

**A**: 常见问题检查清单：

1. **空字符串错误**：Schema 中 `String!` 不能为空
   ```typescript
   // ❌ 错误
   token.symbol = ''
   
   // ✅ 正确
   token.symbol = address.toHexString()
   ```

2. **版本冲突**：使用新版本号
   ```bash
   graph deploy --studio dripswap-sepolia -l v0.0.10
   ```

3. **ABI 不匹配**：重新生成 ABI
   ```bash
   cd DripSwap_Contract
   npm run extract-abi
   ```

4. **startBlock 太早**：确保区块已有合约部署
   ```yaml
   startBlock: 9573280  # 必须 >= 合约部署区块
   ```

### Q6: 如何新增一个 GraphQL 查询？

**A**: 三步走：

1. **定义 Schema** (`src/main/resources/graphql/schema.graphqls`)
   ```graphql
   type Query {
     myNewQuery(param: String!): MyType
   }
   
   type MyType {
     field1: String!
     field2: Int!
   }
   ```

2. **编写 Resolver** (`modules/gql/resolvers/QueryResolver.java`)
   ```java
   @QueryMapping
   public MyTypePayload myNewQuery(@Argument String param) {
       // 调用 Service 获取数据
       return service.getData(param);
   }
   ```

3. **定义 Payload** (`modules/gql/model/MyTypePayload.java`)
   ```java
   public class MyTypePayload {
       private String field1;
       private Integer field2;
       // getters/setters
   }
   ```

### Q7: 前端如何调用 BFF 的 GraphQL？

**A**: 使用 Apollo Client 或 fetch：

```typescript
// 方式 1: Apollo Client (推荐)
const { data } = useQuery(gql`
  query MyQuery($param: String!) {
    myNewQuery(param: $param) {
      field1
      field2
    }
  }
`, {
  variables: { param: "value" }
});

// 方式 2: 原生 fetch
fetch('http://localhost:8080/graphql', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    query: `
      query { myNewQuery(param: "value") { field1 field2 } }
    `
  })
})
```

---

## 🔧 故障排查

### 后端启动失败

**症状**：`DripSwapBffApplication` 启动报错

**检查清单**：
1. PostgreSQL 是否运行？`docker-compose up -d postgres`
2. Redis 是否运行？`docker-compose up -d redis`
3. 数据库连接配置是否正确？检查 `application.yaml`
4. Liquibase 迁移是否成功？查看启动日志

### Subgraph 索引滞后

**症状**：前端查询不到最新数据

**检查清单**：
1. Subgraph Studio 显示索引进度
2. 是否有索引错误？查看 Studio 错误日志
3. RPC 是否限流？切换 RPC 提供商
4. 降级使用 BFF 的 `raw_events` 表

### 合约交互失败

**症状**：前端发送交易失败

**检查清单**：
1. 钱包是否连接正确网络？
2. 合约地址是否正确？
3. Gas 估算是否成功？
4. 用户是否有足够余额？
5. 滑点设置是否合理？

---

## 📞 联系与支持

### 项目 GitHub

- **前端**：https://github.com/ACodingChuan/DripSwap_Fronted
- **合约**：https://github.com/ACodingChuan/DripSwap_Contract
- **后端 BFF**：https://github.com/ACodingChuan/DripSwap_BFF
- **Subgraph**：https://github.com/ACodingChuan/DripSwap_Subgraph

### 文档更新
本文档由 AI 辅助生成，需定期更新以反映最新项目状态。

---

## 📝 更新日志

| 日期 | 版本 | 更新内容 | 更新人 |
|-----|------|---------|-------|
| 2025-11-22 | v1.0 | 初版文档 | AI (Claude) |

---

## 🎯 AI 开发建议

### 开发新功能时

1. **先读架构文档**：`dripswap-backend-architecture.md`
2. **理解数据流**：前端 → BFF → Subgraph/Contract
3. **遵循分层**：不要跨层调用
4. **编写 Spec**：详细的功能规格文档
5. **同步前后端**：前后端并行开发

### 修复 Bug 时

1. **定位层级**：前端/BFF/合约/Subgraph？
2. **查看日志**：OpenTelemetry Trace
3. **检查数据**：数据库/Subgraph 数据是否正确？
4. **复现问题**：本地测试网复现
5. **编写测试**：防止回归

### 代码审查重点

1. **类型安全**：TypeScript/Java 严格类型
2. **错误处理**：try-catch + 日志
3. **幂等性**：写操作必须可重试
4. **性能**：缓存策略、数据库索引
5. **安全**：权限检查、输入验证

---

**祝开发顺利！🚀**
