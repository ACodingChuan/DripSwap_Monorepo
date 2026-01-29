# DripSwap MVP-4 PRD（v1）：Add / Remove Liquidity（纯前端直连链上，不依赖子图/BFF）

> 目标：把“加流动性/移除流动性”做成真正可用的链上交易闭环。
>
> 本文特点：只讲“前端该怎么读链上、怎么发交易、怎么做 UI”，尽量用白话写清楚每个任务要做什么。
>
> 关联：
> - `specs/dripswap-dex-functional-spec-v1.md`（总体方向）
> - `specs/dripswap-mvp-execution-plan-v1.md`（MVP-4 定义）
> - 现有实现参考：`apps/frontend/src/app/routes/swap.tsx`（已经是“纯前端直连链上”的模式）
>
> 最后更新：2026-01-20

---

## 0. 一句话说清 MVP-4

用户在前端选一个池子、填入数量，前端**自己从链上读数据**（余额/授权/储备/LP 余额），然后用钱包**直接调用 Router 合约**完成：
- Add Liquidity（给池子存两种 token，拿到 LP）
- Remove Liquidity（烧掉 LP，拿回两种 token）

全程不依赖 BFF、不依赖子图数据（子图只用于 Explore 看板，不用于交易页面的关键决策）。

---

## 1. 现状（你现在仓库里有什么）

前端已有页面：
- `apps/frontend/src/app/routes/pools-add.tsx`：目前是 UI + toast，占位
- `apps/frontend/src/app/routes/pools-remove.tsx`：目前是 UI + toast，占位
- `apps/frontend/src/app/routes/pool-details.tsx`：已有 “Add Liquidity” 按钮（现在跳 `/pools`）
- Swap 页：`apps/frontend/src/app/routes/swap.tsx` 已经是“前端直连链上”，可复用交互/错误处理/状态机思路

合约地址配置已在前端内置（可直接用）：
- `apps/frontend/src/contracts/index.ts`：`router` / `factory` / token list / 部分 pair 映射

---

## 2. MVP-4 的硬约束（必须遵守）

1) **交易页面的所有关键数据必须来自链上读取**
- 余额：用 `balanceOf` / wagmi `useBalance`
- 授权：用 `allowance`
- 池子储备：用 Pair 的 `getReserves`
- LP 余额：Pair(LP token) 的 `balanceOf`
- LP 总量：Pair 的 `totalSupply`

2) **页面不能用子图数据来决定交易参数**
- 例如：不要用子图的 reserveUSD/volume 来计算 minAmount / share
- 这些必须用链上值计算（否则一旦子图延迟，会导致交易失败/体验差）

3) **默认不做“创建新池子”**
- MVP-4 聚焦“对已有池子 Add/Remove”
- 如果用户选的两种 token 没有对应 pair（factory.getPair=0），页面要提示“该池子尚未创建”

---

## 3. MVP-4 要交付的页面与入口

### 3.1 页面清单（必须）

1) Add Liquidity 页：`/pools/add`
- 允许从 Pool Details 带参数跳转（推荐）：
  - `/pools/add?chainId=...&pair=0x...`
  - 或 `/pools/add?chainId=...&token0=...&token1=...`

2) Remove Liquidity 页：`/pools/remove`
- 同样支持从 Pool Details 带参数跳转：
  - `/pools/remove?chainId=...&pair=0x...`

### 3.2 入口调整（必须）

`apps/frontend/src/app/routes/pool-details.tsx` 里：
- “Add Liquidity” 按钮：跳到 `/pools/add?...`
- 建议新增一个 “Remove Liquidity” 按钮：跳到 `/pools/remove?...`

理由：用户在看某个池子详情时，最自然的动作就是对这个池子加/减流动性。

### 3.3 UI 参考（对齐 Sushi 的入口方式）

你给的截图里，“在 Pools 列表的每一行用三点菜单（…）提供 Add/Remove” 是非常符合用户直觉的入口。

Sushi 参考实现（列表行内菜单）：
- `sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/_ui/pools-table.tsx`
  - `actions` column：三点菜单
  - `Add liquidity` / `Remove liquidity` 两个菜单项

Sushi 参考实现（在池子详情里管理 Add/Remove）：
- `sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/pool/v2/[address]/(manage)/_common/ui/manage-v2-liquidity-card.tsx`
  - Manage card：Tabs（Add / Remove）
  - 每个 tab 跳到对应路由

---

## 4. 需要用到的链上合约与最小 ABI（白话版）

### 4.1 Router（UniswapV2Router）

我们要调用它做两件事：
- `addLiquidity(tokenA, tokenB, amountADesired, amountBDesired, amountAMin, amountBMin, to, deadline)`
- `removeLiquidity(tokenA, tokenB, liquidity, amountAMin, amountBMin, to, deadline)`

Router 地址来源：
- `getChainConfig(chainId)?.router`（见 `apps/frontend/src/contracts/index.ts`）

### 4.2 Pair（UniswapV2Pair，同时也是 LP token）

我们要从它读这些数据：
- `token0()` / `token1()`：这个池子的两种 token 是谁
- `getReserves()`：池子的储备（决定兑换比例、决定你该存多少另一边）
- `totalSupply()`：LP 总量（用来算你大概能拿多少 LP）
- `balanceOf(user)`：你持有多少 LP（用于 Remove）
- （Remove 前还要）`allowance(user, router)`：LP 授权给 Router 多少

Pair 地址来源（两种方式）：
- 推荐：页面参数直接给 `pair`
- 备选：通过 Factory 链上查 `getPair(tokenA, tokenB)` 得到

### 4.3 ERC20（tokenA/tokenB/LP）

我们要用它做三件事：
- `balanceOf(user)`：用户余额够不够
- `allowance(user, router)`：是否需要先 Approve
- `approve(router, amount)`：授权 Router 花你的币

---

## 5. Add Liquidity（加流动性）——用户在页面上看到什么 & 我们在后台做什么

### 5.1 用户操作流程（页面行为）

1) 用户打开 Add Liquidity 页面
- 如果是从 Pool Details 进来，页面自动知道 pair/token0/token1
- 如果不是从详情页进来：用户需要选择 token0/token1（MVP 可只允许从配置 token list 里选）

2) 页面展示两种 token 的输入框
- 用户可以只填一个数量，另一个数量由页面“自动按比例算出来”

3) 页面显示两类提示（都来自链上）
- 你的余额够不够
- 你是否已经授权 Router 花你的 token（没授权就提示先 Approve）

4) 用户点击 “Approve token0 / Approve token1”（如需要）

5) 用户点击 “Add Liquidity”
- 钱包弹窗确认
- 提交交易
- 等待链上确认
- 成功后提示：拿到了 LP（并给区块浏览器链接）

### 5.2 页面必须做的链上读取（每一步要读什么）

进入页面后（或 token/pair 改变后）要读：
- 当前链的 Router 地址（来自 config）
- Pair 地址（如果是 token0/token1 进入：factory.getPair 读链上）
- Pair.token0 / Pair.token1（确保 token 顺序正确）
- Pair.getReserves（算比例）
- token0.decimals / token1.decimals（正确 parseUnits/formatUnits）
- 用户余额：token0.balanceOf、token1.balanceOf（或 wagmi useBalance）
- 用户授权：token0.allowance(user, router)、token1.allowance(user, router)

### 5.3 “自动按比例算另一个输入框”怎么做（白话）

如果池子已经有流动性（reserve0>0 且 reserve1>0）：
- 假设用户先输入 token0 的 amount0Desired
- 那么页面自动给出：
  - amount1Desired ≈ amount0Desired * reserve1 / reserve0

如果池子是空池（刚创建、reserve 为 0）：
- 不强制比例，用户两边都可以随便填
- 但要提示：首次加池子会决定初始价格（风险提示）

Sushi 参考实现（“输入一边自动算另一边”）：
- `sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/pool/v2/[address]/(manage)/_common/ui/add-section-legacy.tsx`
  - `onChangeToken0TypedAmount` / `onChangeToken1TypedAmount`：NOT_EXISTS 时允许自由输入；有池子时按 pool.priceOf().getQuote() 自动计算另一边

### 5.4 Slippage（滑点）与 deadline（过期时间）

Add Liquidity 最容易失败的原因之一是：你签名那一刻到链上打包时，池子比例变了。

所以我们必须提供：
- slippage：例如默认 0.5%
- deadline：例如默认 20 分钟

计算方式（白话）：
- amountAMin = amountADesired * (1 - slippage)
- amountBMin = amountBDesired * (1 - slippage)
- deadline = 当前时间 + deadlineMinutes

设置来源：
- MVP-4 可以复用 Swap 页已有的设置逻辑（或做一个简单弹窗设置，存 localStorage）

Sushi 参考实现（slippage/deadline 的存储与读取）：
- `sushiswap/packages/hooks/src/useSlippageTolerance.ts`
- `sushiswap/packages/hooks/src/useTTL.ts`
- `sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/pool/v2/[address]/(manage)/_common/ui/add-section-legacy.tsx`

### 5.5 Add Liquidity 的“写链上”步骤（严格顺序）

1) Approve（如果 allowance 不够）
- 分别对 token0、token1 检查 allowance
- 不够就让用户先 approve（可以两个按钮，也可以串行自动触发）

2) 调用 Router.addLiquidity
- 参数：token0/token1/amount0Desired/amount1Desired/amount0Min/amount1Min/to/deadline
- to：用户地址

3) 等待交易回执，成功后提示：
- toast：成功
- 展示 txHash + explorer 链接
- UI 把输入框清空（或保留）

---

## 6. Remove Liquidity（移除流动性）——用户在页面上看到什么 & 我们在后台做什么

### 6.1 用户操作流程（页面行为）

1) 用户打开 Remove Liquidity 页面（通常从 Pool Details 进入）
- 页面拿到 pairAddress

2) 页面展示：
- 你当前持有多少 LP（来自链上）
- 你选择要移除多少（百分比 slider + 手输）

3) 页面实时展示“预计拿回多少 token0/token1”（来自链上计算）

4) 如果 LP 没授权给 Router，先让用户 Approve LP

5) 用户点击 “Remove Liquidity”
- 钱包确认
- 提交交易
- 成功后提示：拿回了两种 token（给 tx 链接）

### 6.2 页面必须做的链上读取

进入页面后要读：
- Pair.token0 / Pair.token1
- Pair.getReserves
- Pair.totalSupply
- Pair.balanceOf(user)（你的 LP 余额）
- Pair.allowance(user, router)（LP 给 Router 的授权）

以及 token0/token1 的 decimals（显示用）。

### 6.3 “预计拿回多少币”怎么计算（白话）

你拿回的两种币 = 你烧掉的 LP 份额 * 池子储备

公式（只要理解就行）：
- liquidityToBurn = lpBalance * percent
- amount0Out ≈ liquidityToBurn * reserve0 / totalSupply
- amount1Out ≈ liquidityToBurn * reserve1 / totalSupply

然后再考虑 slippage：
- amount0Min = amount0Out * (1 - slippage)
- amount1Min = amount1Out * (1 - slippage)

Sushi 参考实现（用 LP/totalSupply/reserve 计算 underlying + 再减滑点）：
- `sushiswap/apps/web/src/app/(networks)/(evm)/[chainId]/pool/v2/[address]/(manage)/_common/ui/remove-section-legacy.tsx`
  - `useUnderlyingTokenBalanceFromPool`：把 LP 份额换算成 underlying
  - `subtractSlippage`：得到 minAmount0/minAmount1

### 6.4 Remove Liquidity 的“写链上”步骤（严格顺序）

1) Approve LP（如果 allowance 不够）
- LP token 就是 pairAddress 这个合约本身
- approve 的 spender 是 Router

2) 调用 Router.removeLiquidity
- 参数：token0/token1/liquidityToBurn/amount0Min/amount1Min/to/deadline

3) 等待交易回执，成功后提示：
- toast：成功
- txHash + explorer 链接
- slider 重置 / 重新读链上余额

---

## 7. 状态机与错误处理（务必做，不然会“看起来很不稳定”）

### 7.1 Add 页需要的状态（建议最小集合）

- idle：用户还没输入
- quoting：正在读链上 reserve / 计算比例
- needApprove0 / needApprove1：提示授权
- approving：授权交易 pending
- readyToSubmit：可 Add
- submitting：addLiquidity 已发出
- success / failed

### 7.2 Remove 页需要的状态

- idle：未选择百分比
- loadingPosition：读取 LP 余额/储备
- needApproveLp
- approving
- readyToSubmit
- submitting
- success / failed

### 7.3 常见错误要有“人话”提示

- 用户没连钱包：提示 connect wallet
- 链不对：提示 switch network
- 余额不足：提示 Insufficient balance
- allowance 不够：提示先 approve
- 交易被拒绝：User rejected
- 合约 revert（典型原因）：
  - slippage 太小：提示“滑点过低，建议调大”
  - 输入金额太小：提示“金额太小”

---

## 8. 任务拆分（按模块拆到“可以直接分配给人”）

> 建议拆成 4 组：链上读、UI/交互、链上写、测试与验收。

### 8.1 链上读取层（必须）

1) 统一 ABI 与 read 封装（建议新建）
- 新建 `apps/frontend/src/lib/liquidity/abis.ts`
  - ERC20 ABI（balanceOf/allowance/approve/decimals/symbol）
  - Pair ABI（token0/token1/getReserves/totalSupply/balanceOf/allowance）
  - Router ABI（addLiquidity/removeLiquidity）
  - Factory ABI（getPair）

2) 获取 Pair（两种入口都要支持）
- 如果页面有 `pairAddress`：直接用
- 如果页面只有 token0/token1：通过 Factory.getPair 读链上得到 pairAddress
- getPair=0 时：提示“池子未创建”

3) 读取并缓存页面必需数据
- reserves、totalSupply、lpBalance、token decimals、allowance
- 轮询策略（建议）：用户停留在页面时每 15~30s refresh 一次（可选）

### 8.2 Add Liquidity 页面改造（必须）

目标：把 `apps/frontend/src/app/routes/pools-add.tsx` 从占位变成可交易。

任务拆分：
1) 路由参数与默认值
- 支持 query params（chainId/pair/token0/token1）
- 如果 chainId 与钱包当前链不一致：提示切链

2) 输入框逻辑（“输入一边自动算另一边”）
- 当 reserves>0：按比例自动补另一边
- 当 reserves=0：允许两边自由输入，并提示“首次定价”

3) allowance & approve UI
- token0/token1 分别显示是否已授权
- 未授权显示 “Approve xxx”
- 授权中显示 pending 状态

4) slippage/deadline 设置
- 默认值：slippage 0.5%，deadline 20 分钟
- UI：可以复用 Swap 页设置弹窗（或单独做一个简单设置区域）

5) 调用 addLiquidity（写链上）
- 先检查输入合法性（>0、余额足够）
- 再检查 allowance
- 最后发 addLiquidity 交易

6) 成功/失败反馈
- toast + explorer 链接
- 成功后刷新余额/LP（重新读链上）

### 8.3 Remove Liquidity 页面改造（必须）

目标：把 `apps/frontend/src/app/routes/pools-remove.tsx` 从占位变成可交易。

任务拆分：
1) 路由参数
- 必须支持 `pairAddress`（否则无法知道 LP 是哪个）
- 没有 pairAddress 时：提示从 Pool Details 进入（或输入 pairAddress）

2) 读取 position（链上）
- lpBalance、reserves、totalSupply
- 计算“最多可移除”

3) slider + 手输百分比（保持现有 UI，但换成真实计算）
- percent 改变时：
  - liquidityToBurn 改变
  - amount0Out/amount1Out 重新计算

4) LP allowance & approve UI
- 检查 pair.allowance(user, router)
- 不够则提示 Approve LP

5) 调用 removeLiquidity（写链上）
- 生成 min amounts（slippage）
- 发交易
- 成功后刷新余额/LP

### 8.4 入口联动与地址展示规范（必须）

1) 从 Pool Details 进入 Add/Remove
- `apps/frontend/src/app/routes/pool-details.tsx`：
  - Add：跳 `/pools/add?...`
  - Remove：新增按钮跳 `/pools/remove?...`

2) 地址展示不要直接显示完整地址（延续 MVP-3 规范）
- Add/Remove 页所有地址都要简写（如 `0x1234…abcd`）
- 如需核对，给 explorer 链接（address/tx）

### 8.5 测试与验收（必须）

1) 本地逻辑单测（推荐，至少测计算函数）
- “按比例补另一边”
- “移除时根据 LP 计算可拿回 amount0/amount1”
- “slippage minAmount 计算”

2) 手工验收脚本（必须写在文档里，方便每次回归）
- 在 Sepolia：
  - 选一个已有池子（vETH/vUSDC）
  - Add：approve 两个 token -> addLiquidity 成功 -> 钱包余额变化、LP 余额增加
  - Remove：approve LP -> removeLiquidity 成功 -> 钱包余额变化、LP 余额减少
- 在 Scroll Sepolia 重复一遍

---

## 9. 验收标准（DoD）

Add Liquidity：
- 不依赖 BFF/子图，页面所有数据都来自链上读
- 能成功完成：Approve token0/token1 -> addLiquidity -> 成功拿到 LP
- 输入/余额/授权/切链/用户拒绝等场景都有明确提示

Remove Liquidity：
- 能成功完成：Approve LP -> removeLiquidity -> 成功拿回两种 token
- 百分比 slider 的“预计拿回数量”随链上储备变化可刷新

---

## 10. 建议排期（按 1 名前端）

- Day 1：整理 ABI + 链上 read 封装（pair/router/factory/erc20），打通“读到 reserves/allowance/balance”
- Day 2：完成 Add Liquidity（输入联动 + approve + add）
- Day 3：完成 Remove Liquidity（LP position 读取 + approve + remove）
- Day 4：入口联动（从 Pool Details 跳转）+ 地址简写规范 + 回归测试两条链
