# V2 Subgraph - Goldsky Deployment

## 概述

本子图基于 Sushiswap V2，已迁移适配 DripSwap Factory，支持 K 线数据（TokenHourData 和 TokenMinuteData）。

## 环境要求

- Node.js >= 18
- Goldsky CLI
- Mustache CLI

## 安装 Goldsky CLI

```bash
# 安装 Goldsky CLI
curl https://goldsky.com | sh

# 验证安装
goldsky --help

# 登录 Goldsky
goldsky login
```

## 安装依赖

```bash
# 在项目根目录
pnpm install

# 或在当前目录
npm install
```

## 部署流程

### 方式一：使用快捷命令（推荐）

```bash
# 部署到 Sepolia
npm run deploy-goldsky-sepolia

# 部署到 Scroll Sepolia
npm run deploy-goldsky-scroll
```

### 方式二：手动步骤

```bash
# 1. 生成配置文件和代码
export NETWORK=sepolia  # 或 scroll-sepolia
npm run generate

# 2. 构建子图
npm run build

# 3. 部署到 Goldsky
goldsky subgraph deploy dripswap-v2-$NETWORK/1.0.0 --path .
```

### 方式三：从 The Graph 迁移

如果您已经部署到 The Graph Studio，可以直接迁移：

```bash
# 获取 IPFS hash（从 The Graph Studio 复制）
goldsky subgraph deploy dripswap-v2-sepolia/1.0.0 \
  --from-ipfs-hash <YOUR_IPFS_HASH>
```

## 支持的网络

### Sepolia
- Factory: `0x6c9258026a9272368e49bbb7d0a78c17bbe284bf`
- Start Block: `9573280`
- Oracle: `0x694aa1769357215de4fac081bf1f309adc325306`

### Scroll Sepolia
- Factory: `0x6c9258026a9272368e49bbb7d0a78c17bbe284bf`
- Start Block: `14731854`
- Oracle: `0x59f1ec1f10bd7ed9b938431086bc1d9e233ecf41`

## Goldsky vs The Graph 命令对比

| 操作 | The Graph | Goldsky |
|------|-----------|----------|
| **安装 CLI** | `npm install -g @graphprotocol/graph-cli` | `curl https://goldsky.com \| sh` |
| **登录** | `graph auth --studio <KEY>` | `goldsky login` |
| **创建子图** | `graph create --studio <NAME>` | 不需要，部署时自动创建 |
| **部署** | `graph deploy --studio <NAME>` | `goldsky subgraph deploy <NAME>/<VERSION> --path .` |
| **从 IPFS 迁移** | 不支持 | `goldsky subgraph deploy <NAME> --from-ipfs-hash <HASH>` |

## 子图管理

```bash
# 查看子图列表
goldsky subgraph list

# 查看子图详情
goldsky subgraph info dripswap-v2-sepolia/1.0.0

# 查看子图日志
goldsky subgraph logs dripswap-v2-sepolia/1.0.0

# 删除子图版本
goldsky subgraph delete dripswap-v2-sepolia/1.0.0
```

## GraphQL 端点

部署成功后，Goldsky 会提供 GraphQL 端点：

```
https://api.goldsky.com/api/public/project_<PROJECT_ID>/subgraphs/dripswap-v2-sepolia/1.0.0/gn
```

## 查询示例

### 查询最新 Token 价格

```graphql
query {
  tokens(first: 10, orderBy: tradeVolumeUSD, orderDirection: desc) {
    id
    symbol
    name
    derivedETH
    tradeVolumeUSD
    totalLiquidity
  }
}
```

### 查询 K 线数据（分钟级）

```graphql
query TokenMinuteChart($token: Bytes!, $startTime: Int!) {
  tokenMinuteDatas(
    where: {
      token: $token
      periodStartUnix_gte: $startTime
    }
    orderBy: periodStartUnix
    orderDirection: asc
    first: 1000
  ) {
    periodStartUnix
    open
    high
    low
    close
    volumeUSD
    priceUSD
  }
}
```

### 查询 K 线数据（小时级）

```graphql
query TokenHourChart($token: Bytes!, $startTime: Int!) {
  tokenHourDatas(
    where: {
      token: $token
      periodStartUnix_gte: $startTime
    }
    orderBy: periodStartUnix
    orderDirection: asc
  ) {
    periodStartUnix
    open
    high
    low
    close
    volumeUSD
    priceUSD
  }
}
```

## 测试

```bash
# 运行单元测试
npm run test

# 本地部署测试
npm run deploy-local
```

## 版本管理

Goldsky 使用版本号管理子图：

```bash
# 部署新版本
goldsky subgraph deploy dripswap-v2-sepolia/1.0.1 --path .

# 回滚到旧版本（通过更新端点配置）
# 在 Goldsky Dashboard 中切换版本
```

## 监控和调试

### 查看同步状态

```bash
goldsky subgraph info dripswap-v2-sepolia/1.0.0
```

### 查看错误日志

```bash
goldsky subgraph logs dripswap-v2-sepolia/1.0.0 --level error
```

### 查看实时日志

```bash
goldsky subgraph logs dripswap-v2-sepolia/1.0.0 --follow
```

## 性能优化

### 1. 使用 retainBlocks

当前配置使用 `"retainBlocks": "auto"`，Goldsky 会自动优化存储。

### 2. 合理设置 startBlock

在 `config/*.json` 中设置正确的 `startBlock` 可以大幅提升首次同步速度。

### 3. 批量查询优化

使用分页查询，避免一次性获取大量数据：

```graphql
query {
  tokenDayDatas(
    first: 100
    skip: 0
    orderBy: date
    orderDirection: desc
  ) {
    id
    date
    priceUSD
  }
}
```

## 常见问题

### 1. 部署失败："Subgraph validation failed"

**原因**：schema 或 mappings 有错误  
**解决**：运行 `npm run build` 检查编译错误

### 2. 同步缓慢

**原因**：startBlock 设置过早  
**解决**：在 config 中设置为 Factory 部署的实际区块

### 3. 查询返回空数据

**原因**：子图还在同步中  
**解决**：运行 `goldsky subgraph info <name>` 查看同步进度

### 4. initCodeHash 错误

**原因**：配置的 initCodeHash 不正确  
**解决**：使用 `verify-pairs.py` 验证地址计算是否正确

```bash
python3 verify-pairs.py
```

## 更多资源

- [Goldsky 官方文档](https://docs.goldsky.com/)
- [Goldsky CLI 参考](https://docs.goldsky.com/reference/cli)
- [The Graph 迁移指南](https://docs.goldsky.com/subgraphs/migrate-from-the-graph)
- [MIGRATION_REPORT.md](./MIGRATION_REPORT.md) - 详细的迁移报告

## 支持

如有问题，请查看：
1. [Goldsky Discord](https://discord.gg/goldsky)
2. [Goldsky 文档](https://docs.goldsky.com/)
3. 项目 MIGRATION_REPORT.md