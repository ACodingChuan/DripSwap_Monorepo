# DripSwap 项目进展跟踪

> 记录项目的开发进度、版本更新、任务完成情况

**最后更新**：2025-11-25  
**维护人**：AI + 用户

---

## 📅 版本历史

### v0.0.3 (开发中)

**发布日期**：2025-11-27

#### 已完成的工作

**前端 (apps/frontend)**
- ✅ **Swap Quote 纯前端实现** (Phase 1)
  - 基于 `viem` 和 `wagmi` 的纯前端报价计算
  - 0ms 实时响应（无防抖）
  - 多链支持（Sepolia, Scroll Sepolia）
  - 移除对后端报价服务的依赖
  - 余额实时校验（Exceeds Balance 提示）
- ✅ **智能路由 (Smart Routing)**
  - Fast Path: 直连 Pair 并行数据获取 (1 RTT)
  - Fallback: 1-Hop 自动寻路 (via Base Tokens)
  - Price Impact & Fee 准确计算
- ✅ **交易执行**
  - 多跳路径支持 (`routePath` 传参)
  - Approval 状态管理
- ✅ **多链配置管理**
  - 动态 RPC URL（从环境变量读取）
  - 本地地址簿（contracts/chains.ts, contracts/index.ts）
- ✅ **UI 重构**
  - 路由信息展示 (Direct / via vETH)
  - 交易设置对话框 (Slippage/Deadline)
  - 全宽导航栏布局
  - 网络指示器优化（支持 Unsupported Network 显示）

**文档**
- ✅ **Spec 重构**
  - `4.1-SWAP.md`: 专注前端交互与写操作
  - `4.8-READ-FROM-ETL.md`: 统一后端读需求 (Swap History, Portfolio)
  - `DRIPSWAP_PROJECT_GUIDE.md`: 更新 Spec 编写原则 (交互 vs 读分离)

#### 进行中的工作
- 🔄 **基础设施建设** (BFF/ETL)
  - WebSocket 多链监听 (ChainEventListener)
  - 通用事件解码器 (EventDecoder)
  - 数据库 ETL 管道搭建 (Log -> DB)

---

### 待办里程碑 (Roadmap)

#### Phase 3: 跨链桥核心 (Bridge Core)
- [ ] 跨链合约交互 (Burn/Mint)
- [ ] 后端跨链状态追踪 (ETL: Bridge Events)

#### Phase 4: 统一读数据服务 (ETL + BFF) 🆕
*(原 Swap/Bridge 历史记录展示任务统一合并至此)*
- [ ] **ETL 数据仓库**
  - `swap_tx`, `bridge_tx` 表结构设计与迁移
- [ ] **GraphQL API**
  - `mySwaps`, `myBridges`, `portfolio` 接口实现
- [ ] **前端集成**
  - 统一历史活动页面 (Activity History)

#### Phase 5: Faucet 与风控


**发布日期**：2025-11-25

#### 已完成的工作

**后端 (BFF)**
- ✅ **Session Login 功能**（T0 阶段）
  - Web3 签名验证（基于 Web3j）
  - Nonce 生成与验证（防重放攻击）
  - Session 管理（内存存储，ConcurrentHashMap）
  - REST 端点：`GET /session/nonce`, `POST /session/login`
- ✅ **Java 环境优化**
  - 升级到 Java 17（支持 Lombok 1.18.30）
  - Maven Compiler Plugin 配置优化
  - 移除 JDK 24 兼容性问题
- ✅ **依赖管理优化**
  - OpenTelemetry BOM 统一版本管理（1.33.0）
  - GraphQL Extended Scalars 配置（Long/BigDecimal）
  - 移除冲突的 graphql-kickstart 依赖
- ✅ **配置优化**
  - GraphQL RuntimeWiringConfigurer 注册自定义标量
  - GraphiQL 启用（开发调试）
  - CORS 配置（允许前端跨域）

**组件状态**
- ✅ **GraphQL**
  - 状态：✅ 完全正常，随 Spring Boot 启动
  - Schema 加载成功（11 个查询端点）
  - 自定义标量已注册（Long, BigDecimal）
  - GraphiQL 界面可用：`http://localhost:8080/graphiql`
  - 端点：`POST /graphql`
- ⚠️ **OpenTelemetry**
  - 状态：⚠️ 使用 NoOp Tracer（不发送追踪数据）
  - 版本管理：通过 BOM 统一管理（1.33.0）
  - 版本冲突已解决
  - 生产环境需配置真实 OTLP Exporter
- ⚠️ **WebSocket 链上事件监听**
  - 状态：❌ 暂未启用（所有链 `enabled: false`）
  - 原因：测试网 RPC WebSocket 地址未配置
  - 影响：`raw_events` 表无数据，不影响其他功能
  - 启用方式：配置真实 WS RPC 地址并设置 `enabled: true`

**前端 (apps/frontend)**
- ✅ **Session Login 集成**
  - 自动登录流程（连接钱包后自动触发签名）
  - Zustand 状态管理（sessionId + address 持久化到 localStorage）
  - Auth Service 封装（getNonce, login）
  - 移除手动 Login 按钮，优化用户体验
- ✅ **钱包断开自动清理**
  - 监听钱包断开事件
  - 自动清除前端 session 状态
- ✅ **UI 优化**
  - 简化钱包连接流程
  - 移除冗余的 Disconnect 按钮
  - 只显示钱包地址 Badge

**文档**
- ✅ 创建 `SESSION_LOGIN_README.md`
  - 详细的功能说明和技术方案
  - 前后端实现细节
  - 故障排查记录
  - 生产环境改进建议（Redis、JWT、SIWE）

#### 技术亮点

1. **无感知登录体验**
   - 用户连接钱包后自动完成签名认证
   - 无需手动点击 Login 按钮
   - Session 持久化到 localStorage，刷新页面保持登录

2. **安全性设计**
   - 一次性 Nonce（防重放攻击）
   - EIP-191 标准签名验证
   - 大小写不敏感的地址匹配

3. **架构优化**
   - 前后端分离的认证流程
   - 适配器模式封装 API 调用
   - 为后续 Faucet/Bridge/Portfolio 功能奠定基础

#### 已知限制（T0 阶段）

- ⚠️ Session 存储在内存（后端重启丢失）
- ⚠️ Nonce 无过期时间
- ⚠️ 无 Rate Limiting
- ⚠️ 无数据库持久化

#### 生产环境改进方向

- 🎯 使用 Redis 存储 Session（支持分布式、过期时间）
- 🎯 实现 SIWE (Sign-In with Ethereum) 标准
- 🎯 使用 JWT 替代 Session ID
- 🎯 添加 Rate Limiting 防止滥用
- 🎯 记录登录历史到 PostgreSQL（审计）

---

### v0.0.1

**发布日期**：2025-11-22

#### 已完成的工作

**后端 (BFF)**
- ✅ 基础架构搭建（Spring Boot 3.2.5 + GraphQL + REST）
- ✅ 数据库迁移（Liquibase）
  - `raw_events` 表 - 原始区块链事件
  - `tx_records` 表 - 结构化交易记录
  - `demo_tx` 表 - Demo 交易
  - `chain_cursor` 表 - 链扫描游标
- ✅ GraphQL Schema 定义（查询接口）
  - `ping` - 健康检查
  - `latestRawEvents` - 原始事件查询
  - `recentTransactions` - 交易查询
  - `pairs`、`tokens` - Subgraph 聚合查询
  - `analyticsToken`、`analyticsPair` - 分析查询
- ✅ OpenTelemetry 链路追踪基础配置

**前端 (apps/frontend)**
- ✅ 项目架构（DDD + 六边形）
  - `app/routes/` - 页面路由（Swap、Pools、Bridge、Faucet、Wallet）
  - `infrastructure/adapters/` - BFF 适配器（Swap、Pools、Bridge、Faucet）
  - `app/services/` - 业务服务层
  - `domain/` - 领域模型和接口
- ✅ 代码规范配置（ESLint + Prettier）
- ✅ 适配器模式实现
  - `VITE_API_IMPL=bff` 连接真实后端
  - `VITE_API_IMPL=mock` 使用 Mock 数据

**智能合约 (apps/contracts)**
- ✅ 合约部署（Sepolia）
  - **UniswapV2** (Factory + Router)
  - **7个 vToken** (vETH, vUSDT, vUSDC, vDAI, vBTC, vLINK, vSCR)
  - **PriceOracle** (预言机)
  - **Bridge** (Chainlink CCIP 跨链)
  - **7个 BurnMintPool** (每个 vToken 一个)
  - **Permit2** (Uniswap 标准)
- ✅ 合约部署（Scroll Sepolia）
  - 所有核心合约已部署
  - Bridge 暂未部署
- ✅ 合约地址薄管理
  - `deployments/sepolia/address_book.md`
  - `deployments/scroll/address_book.md`

**Subgraph (数据索引)**
- ✅ Subgraph 部署（Sepolia）
  - Studio 端点：`https://api.studio.thegraph.com/query/1716244/dripswap-sepolia/version/latest`
  - 当前版本：v0.0.9
- ✅ 事件处理器（AssemblyScript）
  - `factory.ts` - Pair 创建事件
  - `pair.ts` - Swap、Mint、Burn、Sync 事件
  - `vtoken.ts` - VToken 转账、铸造、销毁事件
  - `bridge.ts` - 跨链事件
  - `burnmint.ts` - BurnMint Pool 事件
  - `guarded-router.ts` - Guard 配置事件
- ⚠️ Scroll Subgraph 部署待进行

#### 进行中的工作

- 🔄 前端与后端数据联调
- 🔄 Swap 功能测试

#### 待启动的工作

- ✅ **4.1 SWAP** - 纯前端 Quote + 智能路由 + 多链执行
- ⏳ **4.2 BRIDGE** - 写 + 状态机
- ⏳ **4.3 FAUCET** - 写 + 冷却/限额
- ⏳ **4.4 PORTFOLIO** - 组合读 (ETL)
- ⏳ **4.5 REGISTRY** - 元数据与灰度
- ⏳ **4.8 READ (ETL)** - 历史数据聚合 (原 4.6)
- ⏳ **4.7 ADMIN** - 策略与审计

---

## 🚀 里程碑

| 里程碑 | 目标 | 完成度 | 备注 |
|------|------|--------|------|
| **M0 基础底座** | 项目骨架、钱包接入、最小链路、Session Login | 90% | ✅ Session Login 完成 |
| **M1 Swap** | 读为主，纯前端 Quote，Subgraph 聚合 | 80% | 前端/合约交互 100% 完成，后端 ETL 待启动 |
| **M2 Bridge** | 跨链状态机 | 0% | 待启动 |
| **M3 Faucet** | 测试币发放 | 0% | 待启动 |
| **M4 Portfolio** | 用户组合数据 | 0% | 待启动 |
| **M5 观测与对账** | ETL 差异报告 | 0% | 待启动 |

---

## 📊 技术债务与改进 TODO

| 问题 | 优先级 | 状态 |
|-----|------|------|
| **Swap Quote Gas 估算** | 🟡 中 | ⏸️ 暂缓 (Phase 2) |
| - 需要考虑 Token 授权状态 | 🟡 中 | ⏳ 待设计 |
| - 需要考虑用户余额 | 🟡 中 | ✅ 已完成 |
| - 需要真实模拟交易执行 | 🟡 中 | ⏳ 待设计 |
| - 当前暂时移除 Gas Cost 显示 | 🟡 中 | ✅ 已完成 |
| **Session Login 生产化** | 🟡 中 | ⏳ 待 T1 阶段 |
| - Redis 存储 Session | 🟡 中 | ⏳ 待启动 |
| - 实现 SIWE 标准 | 🟡 中 | ⏳ 待启动 |
| - JWT 替代 Session ID | 🟡 中 | ⏳ 待启动 |
| - Rate Limiting | 🟡 中 | ⏳ 待启动 |
| **OpenTelemetry 生产化** | 🟢 低 | ⏳ 待需求确认 |
| - 配置真实 OTLP Exporter | 🟢 低 | ⏳ 待启动 |
| - 集成 Jaeger/Tempo 后端 | 🟢 低 | ⏳ 待启动 |
| **WebSocket 链上事件监听** | 🟡 中 | ⏳ 待需求确认 |
| - 配置真实 WS RPC 地址 | 🟡 中 | ⏳ 待启动 |
| - 启用 Sepolia 链监听 | 🟡 中 | ⏳ 待启动 |
| - 启用 Scroll Sepolia 链监听 | 🟡 中 | ⏳ 待启动 |
| Scroll Subgraph 部署 | 🔴 高 | ⏳ 待启动 |
| Scroll Bridge 合约部署 | 🔴 高 | ⏳ 待启动 |
| Faucet 合约部署 | 🟡 中 | ⏳ 待启动 |
| 后端 /health /readiness 端点 | 🟢 低 | ❌ 不计划 |
| 后端 REST 端点补充 | 🟡 中 | ⏳ 待需求确认 |

---

## 🔗 参考链接

- **项目指南**：`DRIPSWAP_PROJECT_GUIDE.md`
- **后端架构设计**：`dripswap-backend-architecture.md`
- **Session Login 文档**：`SESSION_LOGIN_README.md`
- **合约地址簿**：`apps/contracts/deployments/`
- **前端代码**：`apps/frontend`
- **合约代码**：`apps/contracts`
- **后端代码**：`apps/bff`
- **Subgraph Studio**：https://thegraph.com/studio/subgraph/dripswap-sepolia

---

## 📝 更新日志

| 日期 | 更新内容 | 更新人 |
|-----|---------|-------|
| 2025-11-28 | Swap 核心功能完成：智能路由、Fast Path 优化、多链执行；Spec 重构：4.1 专注交互，4.8 统一读服务 | AI |
| 2025-11-25 | Spec 文档规范更新：添加 When-Case-Do 用户故事格式；创建 4.1 SWAP 功能规范 | AI |
| 2025-11-25 | v0.0.2 发布：Session Login 功能完成，Java 17 + Lombok 配置优化 | AI |
| 2025-11-22 | 初版创建，记录当前进度 | AI |
