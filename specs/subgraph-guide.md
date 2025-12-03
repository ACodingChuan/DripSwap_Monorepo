# DripSwap Subgraph 开发与使用指南

**版本**：v1.0  
**状态**：草案  
**最后更新**：2025-01-15  

---

## 1. 背景

DripSwap 的前端和 BFF 需要一份低延迟的链上数据索引，当前在 `apps/subgraph/sepolia/` 中维护 Subgraph（部署在 The Graph Studio，endpoint `https://api.studio.thegraph.com/query/1716244/sepolia/v0.1.1`）。本文结合现有实现，介绍：

- `subgraph.yaml` 如何定义数据源与模板  
- `schema.graphql` 的实体建模规范  
- `src/*.ts` Mapping 的编写要点（AssemblyScript）  
- 常见 Query 样例及校验方式  

---

## 2. 从零开始：整体步骤

1. **分析需求**：确定要索引的合约、事件、实体（例如 Bridge 发送记录、VToken 状态、Uniswap Pair）。  
2. **准备 ABI**：从 `apps/contracts/abi/` 或区块浏览器下载并放入 `apps/subgraph/sepolia/abis/`。  
3. **编写 schema.graphql**：先设计实体结构，明确每个事件最终要落到哪个实体。  
4. **配置 subgraph.yaml**：定义 dataSource（固定地址）、template（动态部署的 Pair），指定 startBlock 和 handler 文件。  
5. **编写 mapping（src/*.ts）**：处理对应事件，创建/更新实体。  
6. **生成类型 & 构建**：`pnpm --dir apps/subgraph/sepolia codegen` → `pnpm --dir apps/subgraph/sepolia build`。  
7. **部署**：`graph deploy --studio sepolia`，记录返回的 Query URL。  
8. **验证**：使用 GraphiQL 或 curl 运行查询，核对字段与链上日志。  

---

## 3. subgraph.yaml 定义

文件路径：`apps/subgraph/sepolia/subgraph.yaml`

```yaml
specVersion: 1.3.0
schema:
  file: ./schema.graphql

templates:                # Uniswap Pair 动态模板
  - kind: ethereum/contract
    name: UniswapV2Pair
    network: sepolia
    source:
      abi: UniswapV2Pair
    mapping:
      file: ./src/pair.ts
      eventHandlers:
        - event: Swap(...)
          handler: handleSwap

dataSources:
  - kind: ethereum
    name: Bridge
    network: sepolia
    source:
      address: "0x9347..."
      startBlock: 9573280
      abi: Bridge
    mapping:
      file: ./src/bridge.ts
      entities:
        - BridgeTransfer
      eventHandlers:
        - event: TransferInitiated(...)
          handler: handleTransferInitiated
```

**实践要点**

1. **地址与 startBlock**：统一来自 `apps/contracts/deployments/{chain}/address_book.md`，避免硬编码错误。  
2. **模板 vs dataSource**：  
   - `templates` 用于“动态合约”场景（如 Pair），由 Factory 创建时在 mapping 中 `Template.create(address)`。  
   - `dataSources` 用于固定地址（Bridge、VToken、BurnMintPool、Oracle 等）。  
3. **ABI 来源**：放在 `apps/subgraph/sepolia/abis/`，通常复制 `apps/contracts/abi/*.json`（确保事件签名一致）。  
4. **事件命名**：Graph CLI 严格匹配 `(indexed ...)` 签名，顺序/类型需和 ABI 对齐。  

---

## 4. GraphQL Schema 约定

文件路径：`apps/subgraph/sepolia/schema.graphql`

示例（BridgeTransfer）：

```graphql
type BridgeTransfer @entity(immutable: false) {
  id: ID!
  messageId: Bytes!
  transferId: Bytes!
  sender: Bytes!
  receiver: Bytes
  token: Bytes!
  amount: BigInt!
  sourceChainSelector: BigInt
  destinationChainSelector: BigInt
  pool: Bytes
  payInLink: Boolean
  ccipFee: BigInt
  serviceFeePaid: BigInt
  status: String!
  blockNumber: BigInt!
  timestamp: BigInt!
  transactionHash: Bytes!
}
```

**建模原则**

1. **实体命名与业务对应**：`BridgeTransfer`、`PoolConfigEvent`、`VToken` 等，和 domain 一一对应。  
2. **字段类型**：  
   - `Bytes` 存地址、哈希。  
   - `BigInt` 存链上整型。  
   - `String/Boolean` 存枚举或标志。  
3. **immutable vs mutable**：事件型实体多为 `immutable: true`（一次写入），状态型（如 `BridgeTransfer.status`）设为 `false` 以便更新。  
4. **派生字段**：`@derivedFrom` 用于一对多关系（如 `Transaction` -> `swaps`），当前 DripSwap 子图沿用 Uniswap v2 模型。  

修改 Schema 后需重新运行 `pnpm --dir apps/subgraph/sepolia codegen`。

---

## 5. Mapping（src/*.ts）编写规范

Mapping 使用 AssemblyScript。常用路径：

- `src/bridge.ts`：处理 Bridge 事件  
- `src/burnmint.ts`：处理 BurnMintTokenPool 事件  
- `src/vtoken.ts`：处理 VToken 事件  
- `src/pair.ts` / `src/factory.ts`：Uniswap 交易  
- `src/oracle.ts`：Oracle `USDFeedUpdated`

**示例：Bridge Transfer 处理**

```ts
import { TransferInitiated } from '../generated/Bridge/Bridge'
import { BridgeTransfer } from '../generated/schema'

export function handleTransferInitiated(event: TransferInitiated): void {
  let transfer = new BridgeTransfer(event.params.messageId.toHexString())
  transfer.messageId = event.params.messageId
  transfer.transferId = event.params.messageId
  transfer.sender = event.params.sender
  transfer.receiver = event.params.receiver
  transfer.token = event.params.token
  transfer.amount = event.params.amount
  transfer.pool = event.params.pool
  transfer.payInLink = event.params.payInLink
  transfer.ccipFee = event.params.ccipFee
  transfer.serviceFeePaid = event.params.serviceFeePaid
  transfer.status = 'Initiated'
  transfer.blockNumber = event.block.number
  transfer.timestamp = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.save()
}
```

**编写要点**

1. **类型导入**：所有事件类型来自 `generated/`。新增数据源后需 `graph codegen` 以生成 TS 类型。  
2. **Entity 的 load / new**：状态型实体（如 `BridgeTransfer`）先 `load(id)` 再更新；纯事件实体直接 `new ID`。  
3. **复杂字段处理**：AssemblyScript 不支持 `any`，对 struct 需显式访问参数（如 `event.params.config.isEnabled`）。  
4. **字符串拼接**：需使用 `+`，并把 `BigInt` 转 `toString()`。  
5. **工具函数**：可在同文件内定义简单 helper，例如 `rateLimiterToJson(isEnabled: boolean, capacity: BigInt, rate: BigInt): string`。  

---

## 6. 数据来源与查询对照

| Query 实体            | 数据来源 (dataSource)              | handler 文件          | 典型用途/应返回的信息                                                                 |
|----------------------|------------------------------------|-----------------------|----------------------------------------------------------------------------------------|
| `tokens`             | Uniswap Factory + Pair 模板        | `src/factory.ts`/`src/pair.ts` | 仅包含参与 Uniswap 池的 Token（vETH/vUSDT/vBTC …），字段有 `symbol/name/decimals`。           |
| `vtokens`            | 各个 VToken 合约                   | `src/vtoken.ts`       | DripSwap 自定义 vToken 状态；与是否建池无关。例如 vSCR 未在池中，也能在这里看到 `totalSupply`。 |
| `bridgeTransfers`    | `Bridge` + `BurnMintTokenPool`     | `src/bridge.ts`、`src/burnmint.ts` | `TransferInitiated` 写入的发送记录以及 Burn/Mint 事件的补充（status/ccipFee/pool 等字段）。    |
| `poolConfigEvents`   | `BurnMintTokenPool`                | `src/burnmint.ts`     | 记录 ChainAdded/Configured/RateLimit 等配置变更，用于排查链路。                                         |
| `configEvents`       | `GuardedRouter`(已移除)、`ChainlinkOracle`、VToken | `src/oracle.ts`、`src/vtoken.ts` | Oracle 喂价更新、VToken Init/Paused 等。                                                             |
| `tokens/swaps/mints` | Uniswap Pair 模板                  | `src/pair.ts`         | 与 Uniswap 交易指标一致，主要供前端行情使用。                                                        |

因此，如果想查看 vSCR 状态，应查询 `vtokens`（VToken 数据源），而不是 `tokens`（Uniswap）。  

---

## 7. 查询与验证

### 7.1 基础查询

1. **同步状态**

```graphql
query SyncStatus {
  _meta {
    block {
      number
      timestamp
    }
  }
}
```

2. **最新跨链记录**

```graphql
query LatestBridgeTransfers {
  bridgeTransfers(first: 5, orderBy: timestamp, orderDirection: desc) {
    messageId
    sender
    receiver
    token
    amount
    pool
    payInLink
    ccipFee
  }
}
```

3. **池配置事件**

```graphql
query PoolConfigs {
  poolConfigEvents(
    first: 10
    orderBy: timestamp
    orderDirection: desc
    where: { pool: "0xfE81DBC7ec3AE383a7535f5aFAe817621f2f0e34" }
  ) {
    eventName
    remoteChainSelector
    outboundRateLimiterConfig
    inboundRateLimiterConfig
    timestamp
  }
}
```

4. **VToken 元数据（包含 vSCR）**

```graphql
query VTokens {
  vtokens {
    id
    symbol
    name
    decimals
    totalSupply
    totalMinted
    totalBurned
  }
}
```

> 如果只想查某个地址，记得全部小写：`where: { id: "0x4911fb3923f6da0cd4920f914991b0a742d88bfd" }`。

5. **Oracle 喂价更新**

```graphql
query OracleUpdates {
  configEvents(
    first: 5
    orderBy: timestamp
    orderDirection: desc
    where: { eventName: "USDFeedUpdated" }
  ) {
    contract
    params   # token, aggregator, decimals, fixedUsdE18
    timestamp
  }
}
```

### 7.2 校验方法

1. **字段来源对照表**：参照上一节表格，确认查询的实体对应哪个合约/事件。例如 `tokens` 来自 Uniswap，`vtokens` 来自 VToken。  
2. **事件重放**：在 Etherscan 打开目标交易，查看日志。对比 `transactionHash`、`blockNumber` 和实体里的值。  
3. **数量判断**：若期望出现的记录为空，检查：  
   - 事件是否真实发生且在 startBlock 之后；  
   - 地址是否大小写完全匹配（Graph 要求小写）。  
4. **交叉验证**：例如 vSCR 未出现在 `tokens` 查询，就回看数据来源——该实体只在 PairCreated 时写入，因此 vSCR 没有 pair 就不会出现；改查 `vtokens` 就能得到结果。  
5. **同步状态**：通过 `_meta { block { number } }` 查看子图已处理到的区块高度，确保 Mint 事件所在的块已被索引。  
6. **Schema 版本**：如果查询报 “no field XXX”，说明当前部署不包含该实体，需要重新 build/deploy 后使用最新 endpoint。  

通过这些步骤，可以快速判断查询结果是否符合预期，定位问题来源。

---

## 8. 工作流总结

1. **新增合约/事件**：更新 `subgraph.yaml` → 放置 ABI → `graph codegen`。  
2. **建模**：在 `schema.graphql` 定义实体，命名与业务一致。  
3. **Mapping**：在 `src/*.ts` 处理事件，严格遵循 AssemblyScript 类型；多网络场景注意 `startBlock`。  
4. **测试**：本地 `pnpm --dir apps/subgraph/sepolia build`；部署后使用 `_meta` + Query 验证。  
5. **监控**：Graph Studio 的 Health 面板可查看同步状态；如有失败检查日志（常见是 handler panic 或实体字段缺失）。  

---

## 9. 参考

- The Graph 文档：https://thegraph.com/docs/en/  
- DripSwap 合约地址簿：`apps/contracts/deployments/sepolia/address_book.md`  
- 现有 Mapping 示例：`apps/subgraph/sepolia/src/bridge.ts`, `src/burnmint.ts`, `src/vtoken.ts`
