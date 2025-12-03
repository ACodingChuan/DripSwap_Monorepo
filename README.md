# DripSwap Monorepo

该目录集成了 DripSwap 的所有子项目：前端、BFF、智能合约与 Subgraph。新的布局固定在 `apps/` 下，使用 pnpm workspace + Makefile 统一管理多语言项目。

## 目录

```
apps/
├── frontend      # React + Vite 前端
├── bff           # Spring Boot 后端
├── contracts     # Foundry 智能合约
└── subgraph
    └── sepolia   # The Graph 索引
```

根目录包含：

- `package.json` / `pnpm-workspace.yaml`：集中所有 Node 工程脚本与依赖锁。
- `pnpm-lock.yaml`：统一锁定前端与 Subgraph 依赖。
- `Makefile`：为 Java/Foundry/Graph 等非 Node 工程提供统一命令（`make contracts-test`、`make bff-dev`、`make subgraph-build`）。
- `eslint.config.js` / `.prettierrc`：共享 ESLint + Prettier 约定（前端与 Subgraph 可直接继承，无需重复配置）。
- `.gitignore`：已忽略 `.husky/` 与 `.pnpm-store/`；仓库默认不启用 Git Hooks。
- `specs/`：方案与开发指南。

## 📚 文档导航

### 架构与设计
- **[后端架构总体设计](specs/dripswap-backend-architecture.md)** - 单体 BFF 总体设计，三层数据读源、模块设计、微服务演进路径
- **[AI 开发指南](Agents.md)** - 项目结构、代码规范、Spec 编写规范

### 功能规格
- **[4.1 SWAP 规格](specs/4.1-SWAP.md)** - 交易功能详设
- **[4.2 Bridge 规格](specs/4.2-BRIDGE.md)** - 跨链桥接详设
- **[4.8 历史数据查询](specs/4.8-READ-FROM-ETL.md)** - 读侧聚合查询详设

### 进度跟踪
- **[项目进度](specs/PROJECT_PROGRESS.md)** - 版本迭代、已完成工作、待开发任务

---

## 🐳 Docker 快速启动

### 启动所有服务

```bash
# 启动数据库、缓存、追踪系统
docker-compose up -d

# 查看运行状态
docker-compose ps

# 查看日志
docker-compose logs -f postgres  # 查看 PostgreSQL 日志
docker-compose logs -f redis     # 查看 Redis 日志
docker-compose logs -f jaeger    # 查看 Jaeger 日志

# 停止所有服务
docker-compose down

# 清空数据并重启（开发调试用）
docker-compose down -v && docker-compose up -d
```

### 服务访问地址

| 服务 | 地址 | 说明 |
|-----|------|------|
| **PostgreSQL** | `localhost:5432` | 数据库连接，用户/密码见 `.env` |
| **PgAdmin** | `http://localhost:5050` | PostgreSQL 管理界面（admin/admin） |
| **Redis** | `localhost:6379` | 缓存存储，无认证 |
| **Jaeger UI** | `http://localhost:16686` | 分布式追踪可视化界面 |
| **BFF GraphQL** | `http://localhost:8080/graphql` | GraphQL 查询端点 |

### 环境配置

在根目录创建 `.env` 文件配置环境变量（可选，使用默认值时无需配置）：

```bash
# PostgreSQL
BFF_DB_USER=dripswap
BFF_DB_PASSWORD=dripswap
BFF_DB_NAME=dripswap
BFF_DB_PORT=5432

# Redis
BFF_REDIS_PORT=6379

# Jaeger OTLP
OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
```

---

## 常用命令
pnpm run frontend:dev        # 启动前端
pnpm run frontend:build      # 前端打包
pnpm run contracts:test      # Foundry 测试
pnpm run contracts:build     # 合约构建并提取 ABI
pnpm run bff:dev             # 启动 Spring Boot BFF
pnpm run bff:build           # 后端打包 (Maven)
pnpm run subgraph:codegen    # 生成 The Graph 代码
pnpm run subgraph:build      # 构建 Subgraph

# 聚合命令
pnpm run package:all         # 一键打包四个子项目
pnpm run test:all            # 一键测试 (前端/合约/Subgraph/BFF)
pnpm run lint:all            # 一键 lint
pnpm run format:all          # 一键格式化

# 或使用 Makefile
make frontend-dev
make contracts-test
make bff-dev
make subgraph-build
make package-all
make test-all
make lint-all
make format-all
```



## 环境变量约定

- BFF 后端
  - BFF_SERVER_PORT, BFF_DB_URL, BFF_DB_USER, BFF_DB_PASSWORD
  - BFF_REDIS_HOST, BFF_REDIS_PORT
  - BFF_SEPOLIA_RPC_HTTP, BFF_SEPOLIA_RPC_WS
  - BFF_SCROLL_SEPOLIA_RPC_HTTP, BFF_SCROLL_SEPOLIA_RPC_WS
  - OTEL_EXPORTER_OTLP_ENDPOINT
- 合约 (Foundry)
  - RPC_URL, DEPLOYER_PK, ETHERSCAN_API_KEY
- 前端 (Vite)
  - VITE_API_BASE_URL, VITE_SEPOLIA_RPC_URL, VITE_SCROLL_RPC_URL
  - VITE_WALLETCONNECT_PROJECT_ID
- Subgraph
  - GRAPH_NODE, GRAPH_NAME, GRAPH_IPFS

## 版本与构建
- Node >= 18（frontend/contracts/subgraph）
- Java 17（BFF）
- workspace 目录：`apps/frontend`, `apps/contracts`, `apps/subgraph/**`
- `.pnpm-store/` 与 `.husky/` 已在 `.gitignore` 中忽略，不参与版本控制。
