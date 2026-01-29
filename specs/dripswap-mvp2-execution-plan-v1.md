# DripSwap MVP-2 详细开发计划：有人 Swap/Mint/Burn 以后，我们要做什么（v1）

> 关联文档：
> - `specs/dripswap-dex-functional-spec-v1.md`
> - `specs/dripswap-mvp-execution-plan-v1.md`（里面的 MVP-2 只有概要，这里是“怎么做”的细化）
>
> 最后更新：2026-01-20

---

## 0. 一句话解释 MVP-2

MVP-2 就一句话：**链上发生 Swap/Mint/Burn 以后，让 Goldsky 主动通知 BFF；BFF 收到通知后把“旧缓存”删掉（可选顺便预热），这样用户下一次刷新就能看到最新数据，而且我们不会一直狂打 Goldsky。**

你可以把 Redis 缓存理解为“截图”：
- 没有 MVP-2：截图只能靠“过期时间（TTL）”自动更新，可能很慢/很贵
- 有了 MVP-2：每次交易发生我们就知道“截图已经过时”，立刻删掉，下次查询会重新拍一张最新的

---

## 1. 为什么要做（解决的痛点）

现在 BFF 的读路径是 `Goldsky -> BFF -> Redis -> 前端`。

如果只靠 TTL，会出现：
- 用户刚做完一笔 Swap，但 Explore Transactions 还显示旧列表，因为缓存还没过期
- 为了“尽量新”，你把 TTL 设很短，结果前端每次刷新都要打 Goldsky，成本/稳定性会变差

所以 MVP-2 要做的是：**用“交易发生的信号”来精准让缓存失效，而不是靠运气等 TTL。**

---

## 2. MVP-2 做完以后，用户能看到的效果

以 Explore Transactions 为例：
- 用户或别人刚发生一笔 Swap/Mint/Burn
- 1~5 秒内（取决于 Goldsky 推送延迟），BFF 收到通知并删掉相关缓存
- 用户下一次刷新页面（或前端下一次轮询 GraphQL）就能看到新交易

并且：
- 热门页面命中 Redis 的概率更高
- Goldsky 被请求的次数更少

---

## 3. MVP-2 到底要做哪些“东西”（三件事）

### 3.1 让 Goldsky “推消息”给 BFF（Goldsky Pipeline）

我们要在 Goldsky 配置一个 pipeline（每条链一个：Sepolia / Scroll Sepolia）：
- 监听子图里这些实体的新增/变化：
  - `Swap`
  - `Mint`
  - `Burn`
- 一旦有变化，就 HTTP POST 到 BFF：
  - `POST {BFF_BASE_URL}/api/webhook/goldsky`

注意：MVP-2 **不要求** pipeline 把完整交易数据都推过来。只要给我们“这笔交易关联了哪些 token/pair”就够了（见 4）。

### 3.2 BFF 新增一个“收消息”的接口（Webhook Endpoint）

BFF 要新增一个接口专门接收 Goldsky 的通知：
- `POST /api/webhook/goldsky`

这个接口要做的事情很简单（但必须做对）：
1) **确认这请求真的是 Goldsky 发的**（防止别人乱打我们的 webhook）
2) **做去重（幂等）**：同一个事件可能会被 Goldsky 重试多次，我们只处理一次
3) **根据事件内容，生成“要删哪些缓存”的清单**
4) **删缓存**

### 3.3 删哪些缓存（核心工作：Redis 缓存失效）

当我们收到一条事件（例如 SWAP）时，我们会：
- 找到它关联的：
  - `chainId`（哪条链）
  - `pair`（哪个池子）
  - `token0`/`token1`（涉及哪些 token）
- 然后删掉“会因为这笔交易变旧”的 Redis key

删完以后：
- 用户下一次发起 GraphQL 查询时，BFF 会发现 Redis 没命中，就会去 Goldsky 拉最新数据并重新缓存

---

## 4. 我们希望 webhook 里至少带哪些信息（不需要很复杂）

你不用纠结 Goldsky 原生格式长什么样，BFF 可以做适配。我们只需要下面这些信息：

- `chainId`：11155111（Sepolia）/ Scroll Sepolia 的 chainId
- `type`：SWAP / MINT / BURN
- `txHash`：交易 hash（用于排查）
- `timestamp`：时间戳（用于排查/排序）
- `pair`：池子地址
- `token0`、`token1`：token 地址（用于删 token 相关缓存）
- `eventId`：事件唯一 id（用于去重；没有也行，BFF 可自己拼一个）

为什么一定要有 pair/token0/token1？
- 因为我们删缓存要“精准”
- 只给 txHash 不够，我们不知道要删哪个 token/pool 的缓存

---

## 5. 重点：Swap/Mint/Burn 发生时，我们“具体要做什么”（删缓存清单）

下面这部分是你最关心的：**每次发生 Swap/Mint/Burn，我们 BFF 具体干啥。**

为了方便理解，我把要删的缓存分三档：最小版 / 推荐版 / 完整版。

### 5.1 最小版（MVP-2 必须做到，收益最大、实现最稳）

目标：保证“最新交易列表”尽快更新。

当收到 SWAP / MINT / BURN 任意一种事件，都删：

1) Explore Transactions 列表缓存
- 现在 BFF 会缓存：`ds:v2:{chainId}:explore:tx:{limit}:all`
- 所以我们直接删匹配前缀的所有 size：
  - `ds:v2:{chainId}:explore:tx:*`

效果：
- Explore Transactions 在下一次刷新一定会重新从 Goldsky 拉

### 5.2 推荐版（建议默认做：让 Token/Pool 详情页的交易列表也快更新）

在“最小版”的基础上，再额外删：

2) Pool（pair）详情页的交易列表
- 精确 key：
  - `ds:v2:{chainId}:pool:{pair}:tx`

3) Token 详情页的交易列表
- 精确 key（token0/token1 都要删）：
  - `ds:v2:{chainId}:token:{token}:tx`

效果：
- Token Details / Pool Details 的交易列表也会快速看到最新交易

### 5.3 完整版（可开关：让 TVL/Volume/Fees/图表更快更新）

如果你希望“数字/图表”也尽快反映最新变化，那在上面基础上再删：

4) Explore Stats（顶部 TVL/Volume/曲线）
- BFF 用了这些 key（参考 `apps/bff/.../RedisKeys.java`）：
  - `ds:v2:{chainId}:explore:stats:summary`
  - `ds:v2:{chainId}:explore:stats:{days}`
- 所以可以直接删：
  - `ds:v2:{chainId}:explore:stats:*`

5) Explore Tokens 列表缓存（列表里如果展示 price/change/volume，就建议删）
- BFF key 是：
  - `ds:v2:{chainId}:tokens:list:{limit}:{searchHash}`
- 所以删：
  - `ds:v2:{chainId}:tokens:list:*`

6) Explore Pools 列表缓存（列表里如果展示 tvl/volume/apr，就建议删）
- BFF key 是：
  - `ds:v2:{chainId}:explore:pools:base:{limit}:{searchHash}`
- 所以删：
  - `ds:v2:{chainId}:explore:pools:*`

7) Pool 详情（储备/TVL 等）
- 精确 key：
  - `ds:v2:{chainId}:pool:{pair}:details`
- 以及可能的图表窗口缓存（这是“前缀删除”，需要 SCAN）：
  - `ds:v2:{chainId}:pool:{pair}:candles:*`
  - `ds:v2:{chainId}:pool:{pair}:dayWindow:*`

8) Token 相关的“派生统计缓存”（DataLoader 的二级缓存）
- 精确 key（token0/token1 都要删）：
  - `ds:v2:{chainId}:token:{token}:dayStats`
  - `ds:v2:{chainId}:token:{token}:hourStats`
  - `ds:v2:{chainId}:token:{token}:tvl`
  - `ds:v2:{chainId}:token:{token}:pools`

9) Token K 线窗口缓存（QueryResolver 自己缓存）
- 精确 key（token0/token1 都要删）：
  - `ds:v2:{chainId}:token:{token}:candles:MINUTE`
  - `ds:v2:{chainId}:token:{token}:candles:HOUR`
  - `ds:v2:{chainId}:token:{token}:candles:DAY`

关于 `token:{token}:detailsBase`：
- 这是 tokenDetails 的“基础信息缓存”
- 它里面包含 `derivedETH/totalLiquidity`，理论上交易/加减流动性会影响
- 但删它会让 token header 更频繁去打子图
- 建议：先不删（靠 TTL），等你觉得 header 数值不够新，再加到完整规则里

---

## 5.4 另外两类“实时性很强”的点（先记到文档里，等功能做完再加）

你提的 1 / 3 都属于“用户体感强，但依赖页面功能已完成”的增量增强：

### A) 新池子出现（PairCreated / Pair 新增）

用户体感：
- 有人创建新交易对后，Explore Pools / Token Details 的“相关池子列表”能尽快出现，不要等 TTL。

现状：
- 你说 Explore Pools 相关功能目前还没完成，所以现在做了也看不出收益。

等功能完成后建议补的 pipeline：
- 监听：`PairCreated`（或子图里的 `Pair` 新增）
- BFF 收到后只做“列表结构刷新”（不涉及金额）：
  - 删 `ds:v2:{chainId}:explore:pools:*`（池子列表会变）
  - 删 `ds:v2:{chainId}:token:{token0}:pools`、`ds:v2:{chainId}:token:{token1}:pools`（某个 token 的池子列表会变）

### B) 用户自己的列表（我的交易 / 我的流动性 / 我的操作记录）

用户体感：
- 用户做完操作后，最希望“我的页面”立刻刷新，这也是强实时诉求，而且不必依赖 TVL/价格。

现状：
- 你说“我的记录/我的流动性”目前也还没完成，所以现在先不做实际失效规则。

等功能完成后建议补的改动（不一定要新 pipeline，可以沿用 Swap/Mint/Burn webhook）：
- webhook 里额外带上相关地址字段（如 swap 的 from/to/sender，mint/burn 的 sender/to）
- BFF 收到后按地址删“用户维度”的缓存（示例 key，等你最终做了 schema 再定）：
  - `ds:v2:{chainId}:user:{address}:tx:*`
  - `ds:v2:{chainId}:user:{address}:positions:*`

---

## 6. BFF 端实现拆解（按“你要写哪些代码”来讲）

### 6.1 新增一个 Controller：接 webhook

- 新增 `POST /api/webhook/goldsky`
- 做三件事：
  1) 验证签名（或至少验证一个 secret header）
  2) 去重（幂等）
  3) 把事件交给“缓存失效服务”

### 6.2 做签名校验（防止别人刷你接口）

最简单的方式也可以：
- Goldsky 每次回调都带一个 `X-Webhook-Token: <secret>`
- BFF 比对不一致就 401

更标准的方式（推荐）：
- HMAC（timestamp + rawBody）
- 还能防重放（过期就拒绝）

### 6.3 幂等（去重）：Goldsky 重试时不要重复删

为什么要去重？
- 上游 HTTP 不稳定时会重试
- 你不去重也能删缓存，但日志会爆、Redis 操作会翻倍

怎么做最简单：
- 取 `webhookId` 或 `eventId`
- Redis 写一个“我处理过了”的标记：
  - `SET ds:v2:webhook:dedupe:{id} 1 NX EX 3600`
- 如果写不进去（说明已经处理过），直接返回 200/202

### 6.4 缓存失效服务：输入事件，输出“删 key 的清单”

做一个方法：
- 输入：`chainId + type + pair + token0 + token1`
- 输出：
  - 精确 key 列表（直接 DEL）
  - 前缀 key 列表（用 SCAN 找到再 DEL，带上限）

### 6.5 先同步删，还是异步删？

为了简单，最小实现可以“同步删”：
- webhook 来了就直接 DEL

但为了更稳（建议最终做成这样）：
- webhook 接收后“快速返回”
- 把事件扔到一个队列/worker 去做 DEL
- 这样 Redis 抖动时不会让 webhook 超时导致 Goldsky 重试风暴

---

## 7. 验收怎么做（你可以按这个步骤亲自验证）

### 7.1 先验证“webhook 通了”

1) 随便造一条 webhook（curl）打到 BFF
2) 看 BFF 日志能打印出：
   - chainId
   - type
   - pair/token0/token1
   - 准备删除多少 key

### 7.2 再验证“删缓存有效”

1) 打开前端 Explore Transactions，确保它已经产生了 Redis 缓存（你可以在 Redis 里看到 `ds:v2:{chainId}:explore:tx:*`）
2) 在链上发起一笔 Swap（或 Mint/Burn）
3) 等 webhook 到达
4) 观察 Redis：
   - `explore:tx:*` 被删
5) 刷新页面：
   - 能看到新交易

### 7.3 两条链都要测

Sepolia、Scroll Sepolia 都要做一次交易验证，因为 pipeline 是“一链一个”。

---

## 8. 建议排期（更像“这 10 天每天干啥”）

建议顺序：先把“现有页面立刻受益”的部分做完（transactions），再等 Explore Pools / 我的页面完成后回头补 A/B 两类增强（见 5.4）。

Day 1：把规则说清楚（就 5.1/5.2 选哪档），确认 Goldsky 能推哪些字段  
Day 2：Goldsky pipeline 配起来，能打到 staging（先不验签也行）  
Day 3：BFF 加 webhook 接口 + 最简单 token 校验 + 最小版删缓存（只删 explore:tx）  
Day 4：加幂等去重 + 单测  
Day 5：上线 staging 联调（两条链都通）  
Day 6：把推荐版删缓存加上（token tx/pool tx）  
Day 7：加“可选的队列/异步”或 “删除上限/开关”（避免 webhook/Redis 抖动放大）  
Day 8：压测/观察（删 key 频率、Goldsky 请求量）  
Day 9：灰度上生产（先最小版/推荐版）  
Day 10：等 Explore Pools / 我的页面做完后，再回来补 5.4（PairCreated、user 维度缓存）  

---

## 9. 常见坑（提前告诉你会踩什么）

- 坑 1：webhook 会重复投递  
  - 解决：一定要幂等（6.3）
- 坑 2：你用 SCAN 删除前缀 key，可能删太多导致 Redis 压力大  
  - 解决：先只删精确 key；前缀删除必须有“删除上限 + 开关”（5.3/6.4）
- 坑 3：只删 explore:tx，但 token/pool 详情页还是旧的  
  - 解决：开推荐版（5.2）

---

## 10. 备选方案（如果 Goldsky pipeline 暂时搞不定）

应急方案：BFF 定时每 10~30 秒去 Goldsky 看一次“最新一笔 swap 的 id”，如果变了，就执行 5.1/5.2 的删缓存。

缺点：
- 还是要打 Goldsky（但你能控制频率）
- 不如 webhook 精准、也不是真正每笔都触发
