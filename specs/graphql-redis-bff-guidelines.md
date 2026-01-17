# GraphQL + Redis 在 DripSwap BFF 中的最佳实践与读码方法

本文总结 DripSwap（`apps/bff` + `apps/frontend`）在 MVP-1 阶段落地的经验：如何把 GraphQL 当作“前端专用 BFF API”来设计，并用 Redis 降本提速；以及如何从前端一个 query 反向追到后端所有关联代码，理解真实执行链路。

适用范围：
- 数据源是外部 Subgraph（Goldsky/The Graph），本地 DB 可以为空或不存在（MVP-1 默认）
- BFF 目标是“聚合 + 口径统一 + 缓存”，而不是“直接暴露 DB entity”

---

## 1. 先统一认知：GraphQL 在 BFF 里的“正确用法”

### 1.1 BFF 场景下，GraphQL 本质是强类型的“用例级查询语言”

在 DEX 前端里，一个页面需要的往往不是单表实体，而是“多个数据源聚合后的视图模型”：
- Explore Stats：`tvlUsd + volume24hUsd + series + fees`（跨多个 entity、带补点/排序）
- Explore Tokens：基础 token 列表 + 价格/涨跌/成交量等“派生字段”

所以出现 `exploreStats` / `exploreTokens` 这种“用例级 Query”是正常且常见的（interface / sushi 这类 BFF 都是这种风格）。

### 1.2 GraphQL 的特性不等于“必须暴露实体表”

GraphQL 的核心价值在 BFF 场景通常是：
- selection set：前端只取需要字段（不必像 REST 固定返回一坨）
- 字段级解析（Field Resolver）：按需计算 expensive 字段
- DataLoader：避免 N+1、在单次请求内批量合并外部调用
- schema 契约：类型稳定可演进（不要求 DB 结构和前端强耦合）

“Query 返回聚合对象”并不违背 GraphQL；关键在于执行层要真的利用 selection set + field resolver + DataLoader，而不是把 GraphQL 写成“一个 endpoint 的 REST handler”。

---

## 2. DripSwap 当前采用的落地模式（以 6.2 / 6.3 为例）

### 2.1 6.3 Explore Tokens：瘦 Query + Field Resolver + DataLoader（二级缓存）

目标：主查询只返回基础列表，计算字段交给 `@SchemaMapping`，并通过 DataLoader 批量拉取外部数据，进一步用 Redis 做跨请求二级缓存。

拆分方式（概念）：
- `Query.exploreTokens(...)`：只查 tokens 基础字段（可缓存到 Redis）
- `ExploreTokenRow.priceUsd/change1h/change1d/fdvUsd/volume24hUsd`：字段 resolver 按需触发
- `DataLoader`：一次请求里合并多个 token 的 hour/day/bundle 查询（避免 N+1）
- `Redis`：DataLoader 内部先 MGET，再 miss 才请求 Goldsky，然后回写 Redis（降低外部查询成本）

优点：
- 前端如果不请求 `change1h`，后端不会计算/请求 hour 数据
- 避免 N+1：例如 50 个 token 的 `change1d` 只会触发一次批量查询
- Redis 二级缓存可以显著减少“每次页面刷新都打 Goldsky”的成本

### 2.2 6.2 Explore Stats：Seed Query + Field Resolver + “summary/full” 两级 DataLoader

目标：既解耦字段，又避免把拆分做成多次上游调用（拆了字段但不拆请求次数）。

做法：
- `Query.exploreStats(chainId, days)` 只返回 seed（`chainId/days`）
- 字段 resolver 决定走哪种 loader：
  - `summary loader`：只查顶部数字（tvlUsd / volume24hUsd），不查 series
  - `full loader`：需要 series 时才查（并返回完整 payload）
- 两个 loader 都带 Redis read-through：先读 Redis，miss 才请求 Goldsky

优点：
- 只查顶部数字时不会把 `uniswapDayDatas(first:$days)` 拉回来
- 需要 series 时依然最多 1 次上游请求（full loader）

---

## 3. Redis 缓存如何设计才“合理”

### 3.1 分层：request 内与 request 间

GraphQL 常见两层缓存：
1) 请求内（request-scoped）：DataLoader 自带 memoization（同一个 key 不会重复调用）
2) 请求间（cross-request）：Redis（read-through 或 write-through）

推荐策略：
- “列表类基础数据”可以缓存到 Redis（例如 token list）
- “派生字段”不要直接塞进主列表缓存里，而是：
  - 用 DataLoader 按需计算
  - 用 Redis 做二级缓存（按 tokenId 粒度存），TTL 短、成本低

### 3.2 Key 粒度：越基础越粗，越时效越细

经验规则：
- 列表缓存：`ds:v2:{chain}:tokens:list:{limit}:{searchHash}`（粗粒度）
- 价格/统计缓存：`ds:v2:{chain}:token:{tokenId}:dayStats`（细粒度）
- ETH price：`ds:v2:{chain}:bundle:ethPrice`（中等粒度）

原因：
- 细粒度便于复用（多个页面/多个 query 会复用同一个 tokenId 的 stats）
- 时效数据更适合细粒度短 TTL，避免“缓存一坨大对象但局部过期”

### 3.3 TTL：用业务频率指导，不要拍脑袋

建议：
- 价格基准（ETH price）：60s
- token hour/day stats：60~300s（MVP-1 默认可先 60s，省心）
- explore stats summary/full：60s

如果后面加 webhook/WS 监听，可以把 TTL 延长并通过事件主动失效（invalidate），进一步节省成本。

### 3.4 “缓存不全”不是问题：GraphQL 允许按需补齐

常见误解：
- Redis 里缓存的 JSON “没有 priceUsd/change” ≠ 接口返回不出来

正确心智：
- Redis 可以只缓存基础对象（Query 返回的 seed/base list）
- 计算字段在 `@SchemaMapping` 里按需补齐（并通过 DataLoader + Redis 二级缓存加速）

---

## 4. 从前端接口读后端代码：一套固定追踪路径

下面是一条可重复使用的“从页面看到一个字段 -> 追到后端如何取数/缓存/计算”的路径。

### 4.1 第一步：找前端到底发了什么 GraphQL（selection set 是关键）

从页面组件开始：
- 找页面 route（例如 Explore Tokens / Explore）
- 找调用的 adapter（通常在 `services/` 或 `infrastructure/`）
- 找最终 GraphQL query 字符串与变量

为什么先看 selection set：
- 因为 GraphQL 的执行是“字段驱动”的：你没请求的字段不会触发 resolver

### 4.2 第二步：对照 schema，确定类型与字段归属

打开：
- `apps/bff/src/main/resources/graphql/schema.graphqls`

你要回答两个问题：
1) 这个 field 是 Query 上的吗？还是某个 type 上的？
2) field 的返回类型是什么？可空/不可空会影响错误处理方式

### 4.3 第三步：定位 QueryResolver（入口）

在后端搜 QueryMapping：
- `@QueryMapping` + 方法名（例如 `exploreTokens` / `exploreStats`）

阅读重点：
- 参数 normalize（chainId 映射、days clamp 等）
- Redis key/TTL（是否 read-through）
- 返回对象里“哪些字段填了，哪些没填”

### 4.4 第四步：找字段 resolver（SchemaMapping）

原则：
- 如果 schema 里某字段不在 QueryResolver 里算，那就去找：
  - `@SchemaMapping(typeName="Xxx", field="yyy")`

这一步你就能知道：
- “后端还会做哪些动作”
- “哪些字段会触发 DataLoader / 外部请求”

### 4.5 第五步：找 DataLoader 注册与批量实现

在 BFF 里，DataLoader 的定位方式：
- 搜 `BatchLoaderRegistry.forName("...")`
- 对照 `env.getDataLoader("...")` 的 name

阅读重点：
- batch query 是否用 `*_in` / `ids_in` 之类的过滤器（真正批量）
- Redis 二级缓存：是否 MGET / miss 才请求 / 写回 TTL

### 4.6 第六步：追到最底层数据源（SubgraphClient）

最终外部 HTTP 调用集中在：
- `apps/bff/src/main/java/com/dripswap/bff/sync/SubgraphClient.java`

你可以在这里打日志/断点，确认：
- endpoint 是否正确（chainId routing）
- query 是否符合 subgraph schema
- errors 处理是否会抛异常导致 GraphQL INTERNAL_ERROR

---

## 5. 常见坑与排查清单（基于真实踩坑）

### 5.1 DataLoader 为 null（field resolver NPE）

现象：
- `env.getDataLoader("xxx")` 返回 null

原因：
- loader 没有通过 Spring GraphQL 的 `BatchLoaderRegistry` 注册进执行器

正确做法：
- 用 `BatchLoaderRegistry.forName(...).registerMappedBatchLoader(...)` 注册

### 5.2 链 ID 不一致导致“查不到 endpoint -> fallback 出错”

现象：
- 前端没连钱包时默认 chainId=1（mainnet），但后端只配置 sepolia/scroll

建议：
- 前端 wagmi 只配置支持的链
- 后端对不支持的 chainId 直接返回 BAD_REQUEST（而不是去 DB fallback）

### 5.3 “看 Redis 值不全”引发误判

记住：
- Redis 可能只缓存 seed/base list
- 计算字段走 SchemaMapping + DataLoader（可能还有二级缓存 key）

排查时看：
- 这个字段是否在 selection set 里被请求了？
- 是否存在对应的 `@SchemaMapping`？
- DataLoader 是否注册、redis 二级 key 是否命中？

---

## 6. 代码组织建议（后续迭代可持续）

建议保持以下分层：
- `QueryResolver`：只做参数校验/归一化 + 返回 seed 或基础列表 + list 缓存
- `*FieldResolver`：只做字段口径/计算（纯函数风格，尽量无 IO）
- `*DataLoaderRegistrar`：只做 IO（Redis + Subgraph 批量请求）与映射
- `SubgraphClient`：只做 HTTP 与错误处理

这样你会得到：
- 可读性：读一个字段就能找到它的 resolver
- 性能：N+1 通过 DataLoader 控制
- 成本：通过 Redis 二级缓存把外部查询降下来

