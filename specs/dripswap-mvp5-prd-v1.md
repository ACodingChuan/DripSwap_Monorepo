# DripSwap MVP-5 PRD（v1）：FaucetV2（后端签名机 + Relayer 代付 + 风控 + “领取量固定可控”）

> 目标：把 Faucet 做成“用户点一下就能拿到测试币”的**免 gas**发放系统，并且有基本风控与可控的发放量。
>
> 这份文档用白话写：每一步要做什么、为什么要做、做到什么程度算完成。
>
> 关联代码：
> - 合约：`apps/contracts/src/faucet/FaucetV2.sol`
> - 旧 Faucet（已废弃）：`apps/contracts/src/faucet/Faucet.sol`
> - 前端现有 Faucet 页（需要重做）：`apps/frontend/src/app/routes/faucet.tsx`
>
> 最后更新：2026-01-20

---

## 0. 一句话说清 MVP-5

用户打开 Faucet 页，连接钱包后点“领取”，后端先做风控判断，再用“签名机”签一个 EIP-712 凭证，Relayer 用自己的 gas 帮用户把 `claimWithSig()` 交易发到链上，合约把测试币转给用户。

重点：
- **用户不需要自己付 gas**（claim 由 Relayer 发交易）。
- **领取次数/额度/封控规则都在后端**，链上只做“最小可信边界”（验签、防重放、白名单、系统日上限、余额检查）。
- **领取量要“固定、可控、可计算”**：先把“每次领取多少”定成明确数字，并据此算出每天上限，避免：
  - 用户拿太多 token 去 swap，把池子价格打偏
  - FaucetV2 合约库存不够（发到一半就 `VAULT_LOW`）

---

## 1. 现状与你遇到的问题

### 1.1 现状

- `FaucetV2` 合约已经写好（但你还没部署）。
- 前端 `/faucet` 现在是“手填金额 + 调后端接口”的占位页。
- BFF 里目前没有真正的 faucet 模块（仓库里找不到 `/api/faucet` 的实现），所以 MVP-5 需要把后端整套补齐。

### 1.2 你提出的核心痛点

1) “后端签名机”模式：发放不靠用户自己调用 faucet 合约（用户不付 gas），而是后端签名 + Relayer 代付。
2) 要有封控规则：防止被刷、被薅、把 vault 领空。
3) 每次能领多少不能拍脑袋：需要一套“定量规划”（每次发多少、每天上限多少、库存要准备多少），这样你能反推“要往池子里加多深”并且不会被刷爆。

---

## 2. FaucetV2 合约到底做了什么（白话）

合约：`apps/contracts/src/faucet/FaucetV2.sol`

### 2.1 领取函数：`claimWithSig(p, sig)`

用户真正拿币的入口是 `claimWithSig(ClaimReq p, bytes sig)`。

链上会做这些“硬检查”（不通过就 revert）：
- token 必须在白名单：`tokenWhitelist[token] == true`
- `p.day` 必须是“今天”（合约用 `block.timestamp / 1 day` 算）
- `p.deadline` 不能过期
- nonce 不能用过（位图防重放）
- 签名必须来自 `SIGNER_ROLE` 的地址（后端签名机）
- token 的“系统日上限”不能超：`tokenDailyCap[token]`
- 合约里 token 余额要够（否则 `VAULT_LOW`）

链上不会做的事（全部交给后端策略）：
- 你是不是机器人、是不是同一个 IP、是不是同一个设备
- 你今天领过几次
- 你要不要先“买票”（payPass）
- 你这次该领多少（后端按“固定配置 + 风控额度”决定）

### 2.2 “买票”函数：`payPass()`

`payPass()` 只是收 ETH 并发事件 `PassPaid`，不强制任何领取条件。

意义：当你发现 vault 快被领空、或者 relayer 余额不足时，可以切到“付费期/买票期”，后端只给“已买票”的人签发凭证。

### 2.3 角色（权限）怎么用

- `DEFAULT_ADMIN_ROLE`：改参数（白名单/日上限/票价/treasury）、暂停
- `SIGNER_ROLE`：验签信任根（后端签名机）
- `FUNDER_ROLE`：给合约打入 token（fund）
- `TREASURER_ROLE`：把 ETH/token 归集到 `treasury`（sweep）

---

## 3. MVP-5 的用户体验（你打开页面会看到什么）

我们把 Liquidity 的入口跟 Sushi 一样，Faucet 单独在顶栏：

### 3.1 Faucet 页面（MVP-5）

用户只做三件事：
1) 连接钱包
2) 选链（Sepolia / Scroll Sepolia）
3) 点“领取”（不需要手填金额）

页面要明确告诉用户：
- 本次会领到哪些 token、各是多少（这是后端计算出来的）
- 如果被风控拦了，告诉原因（冷却时间/次数用完/需要买票/库存不足）
- 领取成功后给一个 txHash（可点区块浏览器）

### 3.2 我们这版 MVP-5 的领取策略（先定量，再加深池子）

你现在的目标很明确：
- 先把“每次能领多少”定成一个明确数字（可控）
- 然后你根据这个数字把池子加深（顺便测试 addLiquidity）

因此 MVP-5 不做“按池子 TVL/储备自动放大缩小”的动态发放，而是：
- **固定领取量**（写到配置里）
- **固定每日上限**（后端风控 + 链上 `tokenDailyCap` 双保险）
- **给出池子最低深度建议**（避免用户拿到 token 后一把 swap 把价格打偏）

---

## 4. 领取模式与“领取量规划”（核心设计）

你要求同时支持：
- 一次领“一对 token”（方便加流动性）
- 也要能单独领某一个 token（方便 swap/bridge/补余额）

所以 MVP-5 设计两个领取模式：

### 4.1 领取模式 A：Pair（一次领两种 token，用户自己选）

用途：用户想加流动性时，往往需要“两种币各一份”，Pair 就是为了省事。

实现方式（更灵活，按你最新要求）：
- Pair 领取时，用户在前端选择两种 token（`tokenA != tokenB`，且都在白名单里）
- 后端会签发两张凭证（两个 token），Relayer 发两笔 `claimWithSig()`
- **不绑定某一个特定池子**：用户领完后想去给哪个池子加流动性都行

配置项（后端）：
- Pair 不再单独配置“Pair Pack 数量”，直接复用 Single 的固定数值：`SINGLE_AMOUNT_RAW[chainId][token]`

### 4.2 领取模式 B：Single（一次只领一个 token）

用途：给 swap/bridge/补余额。

配置项（后端）：
- `SINGLE_AMOUNT_RAW[chainId][token]`

### 4.3 先把“每天上限”算出来（你给的假设：100 人/天）

你的假设（用于做“可计算规划”）：
- `U = 100`：每天最多 100 个独立用户
- 每个用户每天最多 3 次：
  - `2 次 Single`
  - `1 次 Pair`

我们要算两类上限：
1) **用户级上限**：单个用户一天最多能领多少（防止单人把池子打偏）
2) **系统级上限**：全站一天最多能发多少（防止 faucet vault 不够）

#### 4.3.1 用户级上限（按 token）

对每个 token，我们定义：
- `singleAmount(token)`：单独领一次给多少
- Pair 模式下，这个 token 给多少：**也等于 `singleAmount(token)`**（因为 Pair 复用 Single 数值）

则用户一天对某 token 的“理论最大领取量”是：

```
userDailyMax(token) = walletDailyMaxSingle * singleAmount(token) + walletDailyMaxPair * singleAmount(token)
                  = (walletDailyMaxSingle + walletDailyMaxPair) * singleAmount(token)
```

（为什么用最大值：用户 Single 两次可能都选同一个 token；Pair 那一次也可能把某个 token 选进去。我们用最坏情况做上限规划。）

#### 4.3.2 系统级上限（按 token）

系统每天对某 token 的“理论最大发放量”：

```
systemDailyCap(token) = U * userDailyMax(token)
```

然后再加一个 buffer（建议 20%，防并发/误差/边界）：

```
chainTokenDailyCap(token) = systemDailyCap(token) * 1.2
```

这个 `chainTokenDailyCap(token)` 适合直接写到链上的 `tokenDailyCap[token]`（链上硬闸）。

### 4.4 推荐的“初始数值”（可直接用，后续你再调）

Single（每次）：
- `vUSDC`: 200
- `vUSDT`: 200
- `vDAI`: 200
- `vETH`: 0.05
- `vBTC`: 0.008
- `vLINK`: 20

Pair（每次）：
- 用户任选两种 token（都在白名单里，且 `tokenA != tokenB`）
- 每种 token 的发放数量 = 该 token 的 `singleAmount(token)`（也就是上面的 Single 数值）

### 4.5 按“100 人/天”算出来的日上限（默认方案）

假设：
- `U = 100`
- `walletDailyMaxSingle = 2`
- `walletDailyMaxPair = 1`

所以对任意 token：
- `userDailyMax(token) = (2 + 1) * singleAmount(token) = 3 * singleAmount(token)`

按公式算出来：
- `userDailyMax(vUSDC) = 3*200 = 600`
- `userDailyMax(vUSDT) = 3*200 = 600`
- `userDailyMax(vDAI) = 3*200 = 600`
- `userDailyMax(vETH) = 3*0.05 = 0.15`
- `userDailyMax(vBTC) = 3*0.008 = 0.024`
- `userDailyMax(vLINK) = 3*20 = 60`

系统日上限（不含 buffer，U=100）：
- `vUSDC`: 60,000
- `vUSDT`: 60,000
- `vDAI`: 60,000
- `vETH`: 15
- `vBTC`: 2.4
- `vLINK`: 6,000

链上 `tokenDailyCap`（建议 +20% buffer）：
- `vUSDC`: 72,000
- `vUSDT`: 72,000
- `vDAI`: 72,000
- `vETH`: 18
- `vBTC`: 2.88
- `vLINK`: 7,200

### 4.6 这套数值怎么保护“池子价格不被打偏”

你担心“用户拿到 token 后去 swap 把价格打偏”，本质是：**单个用户手里的 token 相对池子储备太大**。

在 Uniswap V2（常量乘积）里，如果用户一次性把 `dx` 的 token0 打进池子（忽略手续费），价格变化比例近似：

```
newPrice / oldPrice = 1 / (1 + dx / reserve0)^2
```

如果我们希望“单次极端操作”价格最多偏离 5%，需要：

```
dx / reserve0 <= sqrt(1 / 0.95) - 1 ≈ 0.0259 （约 2.6%）
```

工程上更简单的建议（更保守）：
- **把用户“每日最多能拿到的池子相关 token”控制在“池子储备的 1% 以内”**

用默认数值举例：
- 用户每天最多拿 `vETH = 0.15`
- 你把某个 `vETH` 相关的池子做到 `reserve(vETH) >= 15`，那用户每天最多也就 1%
- 更稳一点：你把池子做深到 `reserve(vETH) >= 50`，那用户拿到的体量就非常小，很难把价格打偏

同理 `vUSDC`：
- 用户每天最多拿 600
- 你把池子做到 `reserve(vUSDC) >= 60,000`，也能达到 1%

### 4.7 这套数值怎么保证“faucet vault 不会不够”

你说 token 合约是“无限发放”，但 faucet 发放库存来源是 **FaucetV2 合约余额**，它依然可能不够。

因此我们要做两个层的保护：

1) **链上硬闸**：`tokenDailyCap[token]`（上面已经给出默认值）
2) **后端库存阈值**：当 FaucetV2 合约余额低于“可支撑 N 天”的库存时，进入 `PASS_REQUIRED` 或直接停发

推荐配置“库存天数”：
- `VAULT_TARGET_DAYS = 7`（至少够 7 天）
- `VAULT_LOW_DAYS = 2`（低于 2 天就强制买票/停发）

计算方式（按 token）：

```
requiredVault(token) = chainTokenDailyCap(token) * VAULT_TARGET_DAYS
lowVault(token)      = chainTokenDailyCap(token) * VAULT_LOW_DAYS
```

---

## 5. 风控/封控（后端做，链上不做）

MVP-5 的风控目标不是“完美反撸”，而是：
- 不要被同一个人/脚本无限刷
- 不要把 vault 一天内领空
- 当异常发生时能快速止血（开关 + 票价 + 黑名单）

### 5.1 MVP-5 风控要做到“可执行的细节”（不是口号）

你现在最在意两件事：
1) 别让用户拿到太多 token 去打偏池子价格
2) 别把 FaucetV2 合约库存发空

所以风控必须同时包含：
- **额度限制**：按用户/按 token/按天
- **频率限制**：冷却时间、每小时次数
- **身份限制**：IP/设备/黑名单/人机验证
- **系统保护**：库存阈值、链上 daily cap、紧急暂停

#### 5.1.1 钱包级（wallet）规则（必须）

- `walletDailyMaxSingle = 2`
- `walletDailyMaxPair = 1`
- `walletCooldownSeconds = 600`（10 分钟，防连点/脚本刷）
- `walletDailyMaxPerToken[token] = userDailyMax(token)`（第 4 章的计算结果；按 token 卡死）

##### 参数白话解释（wallet）

- `walletDailyMaxSingle`
  - 含义：同一个钱包地址“每天最多能领几次单币”（Single）。
  - 例子：=2 表示今天最多领 2 次单币，不管你两次都领 vUSDC 还是分别领 vUSDC/vETH，都算 2 次。
- `walletDailyMaxPair`
  - 含义：同一个钱包地址“每天最多能领几次一对币”（Pair）。
  - 例子：=1 表示今天最多领 1 次“两种币”的组合（由用户在白名单里自己选）。
- `walletCooldownSeconds`
  - 含义：同一个钱包两次请求之间最短间隔（防止连点/脚本狂刷）。
  - 例子：=600 表示这个钱包领完一次后，10 分钟内再次发起请求会被拒绝（返回 `COOLDOWN`）。
- `walletDailyMaxPerToken[token]`
  - 含义：同一个钱包“每天对某个 token 最多能领到多少数量”（按 token 卡死）。
  - 目的：就算用户把 Single 两次都选同一个 token，也不能把某个 token 领到过大，避免去 swap 把池子价格打偏。
  - 例子：假设 vUSDC 的 `userDailyMax(vUSDC)=600`，那这个钱包今天最多拿到 600 vUSDC，超出就拒绝（返回 `DAILY_TOKEN_QUOTA`）。

#### 5.1.2 IP 级规则（必须）

（防一个人开 100 个地址）

- `ipHourlyMaxClaims = 9`
- `ipDailyMaxClaims = 45`
- `ipDailyMaxUniqueWallets = 5`

##### 参数白话解释（IP）

- `ipHourlyMaxClaims`
  - 含义：同一个 IP（更准确是 IP hash）每小时最多允许成功发起多少次领取。
  - 目的：防止一个人用脚本开很多地址在同一个 IP 上刷。
  - 例子：=9 表示这个 IP 1 小时内最多 9 次成功领取（超过就拒绝，返回 `IP_LIMIT`）。
- `ipDailyMaxClaims`
  - 含义：同一个 IP 每天最多允许成功领取多少次。
  - 例子：=45 表示这个 IP 当天最多 45 次（超过就拒绝）。
- `ipDailyMaxUniqueWallets`
  - 含义：同一个 IP 每天最多允许“多少个不同钱包地址”成功领到币。
  - 目的：专门防“一个 IP 开 100 个地址”，即使他控制每个地址次数很少，也会被这个规则卡住。
  - 例子：=5 表示这个 IP 今天最多放行 5 个钱包地址；第 6 个新钱包再来就拒绝。

#### 5.1.3 设备级规则（建议做）

- 前端生成 `deviceId`（localStorage 存）
- `deviceDailyMaxClaims = 6`
- `deviceDailyMaxUniqueWallets = 3`

##### 参数白话解释（deviceId）

- `deviceId`
  - 含义：前端给“当前浏览器/设备”生成一个随机 ID（存 localStorage），用来粗略识别同一台设备。
  - 注意：用户清缓存/换浏览器会变；它不是强身份，只是“提高刷子的成本”。
- `deviceDailyMaxClaims`
  - 含义：同一 deviceId 每天最多成功领取多少次（所有钱包加起来）。
  - 例子：=6 表示这台设备今天最多领 6 次，超过就拒绝（返回 `DEVICE_LIMIT`）。
- `deviceDailyMaxUniqueWallets`
  - 含义：同一 deviceId 每天最多允许多少个不同钱包成功领取。
  - 例子：=3 表示这台设备最多给 3 个钱包领到币；第 4 个钱包会被拒绝。

#### 5.1.4 人机验证（强烈建议）

- 接 Cloudflare Turnstile / hCaptcha（二选一即可）
- 后端要求 `captchaToken` 验证通过才进入签名流程

#### 5.1.5 黑名单/灰名单（运营止血）

- 黑名单：直接拒绝（返回 `BLOCKED`）
- 灰名单：降低额度（比如 single 变 1/10）或强制买票（返回 `PASS_REQUIRED`）

#### 5.1.6 库存/系统保护（必须）

- 任一 token `vaultBalance(token) < lowVault(token)`：后端直接 `PASS_REQUIRED` 或 `PAUSED`
- relayer `ETH` 余额不足：后端 `PAUSED`（避免一直失败浪费时间）
- 按你最新要求：当 relayer 的原生币余额 `< 0.5 ETH` 时，直接停发（`PAUSED`），避免“连失败交易都发不起”的尴尬。
- 管理员一键止血：
  - 后端开关：停签名（不发凭证）
  - 链上开关：`pause()`

### 5.2 买票期（pass）怎么落地（符合 FaucetV2 的设计）

合约里 `payPass()` 会发事件 `PassPaid(payer, amount, day)`。

后端逻辑：
- 当系统处于 `PASS_REQUIRED` 模式时：
  - 只有“今天已经 payPass 的地址”才给签名
  - 没买票的人：前端提示“请先买票”，给一个“去买票”按钮（钱包发起 payPass tx）

---

## 6. 后端（BFF）要做什么（任务拆分）

> MVP-5 的后端是核心：签名机 + 风控 + relayer + 对账。

### 6.1 配置与密钥（先把地基搭好）

配置项（建议按 chainId 分开）：
- FaucetV2 合约地址
- Single 固定发放数量（按 token）：`SINGLE_AMOUNT_RAW[chainId][token]`
- 额度规划参数：
  - `U`（预计每日用户数，用于算 tokenDailyCap）
  - `walletDailyMaxSingle` / `walletDailyMaxPair`
  - `walletCooldownSeconds`
  - `ipHourlyMaxClaims` / `ipDailyMaxClaims` / `ipDailyMaxUniqueWallets`
  - `VAULT_TARGET_DAYS` / `VAULT_LOW_DAYS`
- relayer 保护参数：
- `RELAYER_MIN_NATIVE_BALANCE = 0.5 ETH`（低于就停签名）
- `CLAIM_DEADLINE_SECONDS`（比如 10 分钟）
- 票价 `passPriceWei`（链上配置）以及后端 `passRequired` 开关

密钥：
- Signer（有 `SIGNER_ROLE` 的私钥 / HSM key）
- Relayer（发交易的私钥 / HSM key）

MVP-5 可以先用环境变量私钥跑通；后续再接 KMS/HSM。

#### 6.1.1 你提到的“余额够不够”要拆开看（很关键）

这里其实有 3 种“余额”，它们的作用完全不同：

1) FaucetV2 合约余额（ERC20 库存，必须有）
- **这是最关键的**：`claimWithSig()` 最后是 `IERC20(token).safeTransfer(user, amount)`，钱从 FaucetV2 合约里转出去。
- 结论：你必须提前把每条链的 token 打到对应链的 FaucetV2 合约里，否则会 `VAULT_LOW`。

2) Relayer 地址余额（链上 gas，必须有）
- Relayer 才是发 `claimWithSig()` 交易的人，它需要该链的 ETH 付 gas。
- Sepolia/Scroll 是两条链：**各自需要各自链上的 gas**（Sepolia ETH、Scroll Sepolia ETH）。
- 另外按你最新要求：relayer 原生币（ETH）余额 `< 0.5 ETH` 就先停签名（避免交易一直失败）。

3) Signer（签名机）地址余额（通常不需要链上余额）
- Signer 只是在链下做 EIP-712 签名，本身不发交易的话：
  - 不需要 ETH（不付 gas）
  - 也不需要 token（不参与转账）
- 但如果你把“Signer 和 Relayer 用同一个地址”：
  - 那它作为 Relayer 会需要 ETH（gas）
  - 仍然不需要 token（token 在 FaucetV2 合约里）

#### 6.1.2 “token 无限铸造”≠“FaucetV2 自动有库存”

即使你的 vToken 是无限发放的，你还是要决定“库存怎么进 FaucetV2 合约”：

- 方案 A（推荐，最省事）：在部署/运维脚本里直接 `mint(to = faucetV2, amount)`（如果 token 有 mint 权限）
- 方案 B：先 mint 到 funder 地址，再 `approve + FaucetV2.fund(token, amount)` 转进合约

两条链都要各做一份（Sepolia 的 vUSDC 和 Scroll Sepolia 的 vUSDC 是两套不同的 token 合约地址）。

### 6.2 “报价/预览”接口（前端用来展示“这次会领到多少”）

REST：
- `GET /api/faucet/v2/quote?chainId=...`

返回（示例）：
- `pairRules`（Pair 规则：从白名单里选两种 token，且 `tokenA != tokenB`；数量复用 singleAmount）
- `singleOptions[]`（可单领 token 列表与数量）
- `passRequired`（是否需要先买票）
- `limits`（本钱包今天还剩几次 single/pair、每个 token 还剩多少额度）
- `blockReason`（被拦原因 code：`COOLDOWN` / `DAILY_LIMIT` / `DAILY_TOKEN_QUOTA` / `IP_LIMIT` / `PASS_REQUIRED` / `VAULT_LOW` / `RELAYER_ETH_LOW` / `CAPTCHA_REQUIRED` / `BLOCKED`）

### 6.3 “发放”接口（真正开始领取）

REST：
- `POST /api/faucet/v2/claim`

输入：
- `chainId`
- `userAddress`
- `deviceId`（可选）
- `type`：`SINGLE | PAIR`
- `token`（type=SINGLE 时必填）
- `tokenA` / `tokenB`（type=PAIR 时必填，且 `tokenA != tokenB`）
- `captchaToken`（如果开启了人机验证）

后端做的事（顺序）：
1) 风控校验（冷却/次数/黑名单/是否买票）+ 系统保护（vault 库存阈值、relayer ETH 是否低于 1 ETH）
2) 读配置确定这次要发的 token 与 amount（固定数值）
   - Single：`amount = singleAmount(token)`
   - Pair：`amountA = singleAmount(tokenA)`，`amountB = singleAmount(tokenB)`
3) 为每个要发的 token 生成一个 ClaimReq：
   - `user`
   - `token`
   - `amount`
   - `day`（从链上最新块 timestamp 算，避免和合约 day 不一致）
   - `nonce`（后端为 user 维护递增 nonce，必须原子递增）
   - `deadline`（now + 10m）
   - `pass`（后端要求买票则 true，否则 false）
4) Signer 对 ClaimReq 做 EIP-712 签名
5) Relayer 逐笔提交 `claimWithSig()` 到链上
6) 返回 txHash 列表给前端（并落库）

#### 6.3.1 必须实现的“防重复/并发”细节（不然一定出事故）

1) 幂等（用户连点/网络重试不会发两次）
- 前端每次点击 Claim 生成 `idempotencyKey`（uuid），放到请求头或 body
- 后端 `faucet_claim_request.idempotency_key` 做唯一索引
- 如果重复请求同一个 key：直接返回第一次的结果（包含 txHash）

2) nonce 分配必须原子
- FaucetV2 用 nonce 位图防重放，nonce 不能乱、不能重复
- 后端对每个 `(chainId, userAddress)` 维护 `nextNonce`
- 分配 nonce 时必须“加锁/事务”：
  - Pair 模式一次要两个 nonce（tokenA/tokenB），必须一次性拿到并递增
  - 任何失败都要记录，避免 nonce 空洞导致对账困难（空洞不是链上问题，但会让你排查很痛）

3) 请求状态机（必须落库）
- `REJECTED`：风控拒绝（不签名、不发交易）
- `SIGNED`：已签名（有 ClaimReq + sig）
- `SUBMITTED`：relayer 已提交 tx（有 txHash）
- `CONFIRMED`：receipt 成功 or 监听到 `Claimed`
- `FAILED`：receipt 失败/回滚

### 6.4 对账与历史记录（不做这个=线上必痛）

你需要一个“领了没领到/tx 掉了/重复点按钮”的兜底：

- DB 表（最少两张）：
  - `faucet_claim_request`：记录每次用户点“领取”的请求（状态机）
  - `faucet_claim_tx`：每个 token 一条（token/amount/nonce/txHash/receipt）

- 对账方式（MVP-5 二选一即可）：
  1) 后端轮询 tx receipt（直到 confirmed/failed）
  2) 监听 FaucetV2 的 `Claimed` 事件并回填（更稳）

#### 6.4.1 DB 表结构建议（直接照着建就能用）

1) `faucet_claim_request`
- `id`（uuid）
- `idempotency_key`（uuid，唯一）
- `chain_id`
- `user_address`
- `ip_hash`（不要存明文 IP）
- `device_id`（可空）
- `claim_type`（SINGLE/PAIR）
- `token_a` / `amount_a_raw`
- `token_b` / `amount_b_raw`（Single 时为空；Pair 时为第二个 token）
- `status`（REJECTED/SIGNED/SUBMITTED/CONFIRMED/FAILED）
- `reject_reason`（COOLDOWN/DAILY_LIMIT/IP_LIMIT/PASS_REQUIRED/VAULT_LOW/CAPTCHA_REQUIRED/BLOCKED）
- （如启用 relayer 保护）也可能是：`RELAYER_ETH_LOW`
- `created_at` / `updated_at`

2) `faucet_claim_tx`
- `id`（uuid）
- `request_id`（外键）
- `token`
- `amount_raw`
- `day`
- `nonce`
- `deadline`
- `tx_hash`
- `tx_status`（SUBMITTED/CONFIRMED/FAILED）
- `receipt_json`（可选）
- `created_at` / `updated_at`

3) `faucet_nonce`
- `chain_id`
- `user_address`
- `next_nonce`
- 唯一索引：(chain_id, user_address)

4) `faucet_blacklist`
- `subject_type`（WALLET/IP/DEVICE）
- `subject_hash_or_value`
- `reason`
- `expires_at`（可空）

#### 6.4.2 Redis 限流建议（简单但够用）

（MVP-5 用 Redis 做“高频计数”，DB 做“最终事实记录”。）

Key 示例：
- `faucet:v2:cooldown:wallet:{chainId}:{wallet}` => cooldownUntilTs
- `faucet:v2:rate:ip:{chainId}:{ipHash}:{hourKey}` => count
- `faucet:v2:rate:device:{chainId}:{deviceId}:{dayKey}` => count
- `faucet:v2:quota:walletToken:{chainId}:{wallet}:{dayKey}:{token}` => issuedAmountRaw

### 6.5 管理/止血能力（MVP-5 最少要有）

- 后端开关：
  - 全局停发（不签名）
  - 强制买票模式（passRequired=true）
- 链上开关：
  - FaucetV2 `pause()`（紧急停）
- 黑名单管理（最简单：DB 表 + admin API）

---

## 7. 前端要做什么（任务拆分，按页面）

### 7.1 重做 Faucet 页（不再手填 amount；支持 Single/Pair 两种模式）

页面：`/faucet`

必须有：
- 连接钱包提示
- 选链（或自动用当前链）
- 调 `GET /api/faucet/v2/quote` 展示：
  - Pair：提示“任选两种 token”，并在用户选择 `tokenA/tokenB` 后展示“本次会领到多少”（两个 token 的 singleAmount）
  - Single：可选 token 列表 + 各自数量
  - 如果被拦：展示原因 + 冷却时间
- 用户选择 “Pair” 或 “Single”，点“Claim”调用 `POST /api/faucet/v2/claim`
- 显示进度（至少：submitted / confirmed / failed）
- 展示 txHash（可点区块浏览器）

### 7.2 买票期的前端体验（pass required）

当 quote/claim 返回 `PASS_REQUIRED`：
- 前端展示“需要先买票”
- 给一个按钮：调用钱包执行 `FaucetV2.payPass()`（用户自己付 gas）
- 买票成功后刷新 quote，再点 claim

---

## 8. 验收标准（做到这些就算 MVP-5 完成）

### 8.1 功能闭环
- 用户不需要自己付 gas，就能领取到 Pair（自选两种 token）的两笔发放
- 用户也可以单独领取某一个 token（Single）
- 用户拿到 Pair 后能直接去 Add Liquidity（你用它来测试 addLiquidity；池子由用户自己选）

### 8.2 风控有效
- 同一钱包短时间重复点不会无限领（冷却生效）
- 同一 IP 刷不动（限流生效）
- vault 余额不足时会提示“库存不足/需要买票”，不会一直让用户点失败

### 8.3 可观测与可对账
- 后端能查到每次请求、每笔 tx、最终是否成功
- 出错能定位（签名失败/nonce 冲突/链上 revert/余额不足/网络问题）

---

## 9. 任务清单（按优先级给开发排期）

### P0（必须先做：先跑通闭环；再谈优化）

> 目标：用户在 `/faucet` 点一次，后端通过风控 + 签名 + relayer 代付，把币真实打到用户钱包里；同时你能在 DB 里查到这次发放的全过程。

#### P0-0 先把“口径”定死（不然后面一定对不上）
1) 定死 FaucetV2 的 EIP-712 域参数（部署时传入）：`name_` / `version_`
2) 定死 Single 的发放数值（两条链分别一套；Scroll 额外有 `vSCR`）
3) 定死 Pair 规则：用户任选两种 token（`tokenA != tokenB`），每种 token 的 amount = 该 token 的 singleAmount
4) 定死“停止发放”条件：relayer 原生币余额 `< 0.5 ETH` 时直接停签名（`RELAYER_ETH_LOW`）

本项目先按下面这套“硬口径”执行（后面要改也可以，但必须同步改前端/后端/部署脚本）：

> 仓库内的权威配置（机器可读）：`apps/contracts/configs/faucetv2-calibration-v1.json`

1) FaucetV2 EIP-712 Domain（部署参数）
- `name_ = "DripFaucet"`
- `version_ = "2.0"`

2) Single Amount（白话：用户每点一次，实际到账多少）
- Sepolia（chainId=11155111）：
  - `vUSDC = 200`
  - `vUSDT = 200`
  - `vDAI  = 200`
  - `vETH  = 0.05`
  - `vBTC  = 0.008`
  - `vLINK = 20`
- Scroll Sepolia（chainId=534351）：
  - 同上
  - 额外：`vSCR = 100`

3) Pair 规则（白话：一次领两种）
- 用户从白名单里任选两种 token（必须不同）
- 每种 token 的数量 = 该 token 的 Single Amount（不按池子比例，不绑定特定池子）

#### P0-1 合约侧先做“最小可信验证”（Foundry 单测 + 1 次真实链 smoke）
1) Foundry 单测覆盖这些点（不需要复杂，但必须有）：
   - `claimWithSig()`：白名单检查、day 校验、deadline 过期、nonce 防重放、验签 signer、tokenDailyCap、生效的 `VAULT_LOW`、pause/unpause
   - `payPass()`：price=0 revert、underpay revert、事件 `PassPaid` 的 day 正确
2) EIP-712 对口测试（最容易踩坑）：
   - 用测试里生成的 typedData 签名，确保链上 recover 出来的 signer 正确
   - 用“错误的 name/version/chainId/verifyingContract”签一次，确保会 `INVALID_SIGNER`
3) 真实链 smoke（Sepolia/Scroll 各 1 次）：
   - 部署后，手工 set whitelist + cap + fund 一点库存
   - 用脚本/手工签名发 1 笔 `claimWithSig()`，确认事件 `Claimed` 出来且用户余额变了

落地到仓库的对应文件/命令：
- Foundry 单测：`apps/contracts/test/FaucetV2.t.sol`
  - 本地跑：`cd apps/contracts && forge test`
- 真实链 smoke 脚本：`apps/contracts/script/SmokeFaucetV2.s.sol`
  - 运行示例（需要 RPC）：
    - 推荐用 Makefile（不需要手动 export 一堆变量）：
      - 先把配置写进 `apps/contracts/.env.sepolia` / `apps/contracts/.env.scroll`（参考示例：`apps/contracts/.env.faucetv2-smoke.*.example`）
      - 再跑：
        - `cd apps/contracts && make smoke-faucetv2 NETWORK=sepolia`
        - `cd apps/contracts && make smoke-faucetv2 NETWORK=scroll`
  - 需要的 env（最少）：`FAUCET_ADMIN_PK`、`FAUCET_SIGNER_PK`、`FAUCET_RELAYER_PK`、`FAUCET_TREASURY`、`FAUCET_TOKEN_SYMBOL`（或 `FAUCET_TOKEN`）、`FAUCET_FUND_AMOUNT_RAW`、`FAUCET_CLAIM_NONCE`（`FAUCET_CLAIM_AMOUNT_RAW` 可选，不填则按 P0-0 固定 singleAmount）

#### P0-2 部署 FaucetV2 + 基础配置（两条链都要做）
1) 部署 FaucetV2（Sepolia + Scroll Sepolia），记录：
   - 合约地址
   - 部署参数：admin / treasury / initialSigner / name_ / version_
2) 配角色：
   - `DEFAULT_ADMIN_ROLE`：你自己的管理地址
   - `SIGNER_ROLE`：签名机地址（可以先用 EOA，后面换 HSM）
   - `FUNDER_ROLE` / `TREASURER_ROLE`：用于入金/归集（先都给 admin 也行）
3) 配白名单（每条链的 token 地址都要 set）：
   - Sepolia：vETH/vBTC/vLINK/vUSDT/vUSDC/vDAI
   - Scroll：在上面基础上再加 vSCR（按你实际 token 部署为准）
4) 配链上硬闸 `tokenDailyCap[token]`（按本 PRD 计算出来的 daily cap raw 写进去）
5) 先把 `passPriceWei=0`（免费期，别引入额外变量）

#### P0-3 FaucetV2 合约库存准备（否则一定 VAULT_LOW）
1) 按“100 天库存表”把每个 token 转进 FaucetV2 合约（两条链分别操作）
2) 现场校验：
   - `balanceOfToken(token)` >= 你预计 100 天的量
   - 随便跑 1 次 claim 之后余额确实减少

#### P0-4 BFF 新增 FaucetV2 服务骨架（先能跑起来）
1) 新增 REST API 模块（不走 GraphQL）：
   - `GET /api/faucet/v2/quote`
   - `POST /api/faucet/v2/claim`
2) 新增链配置（按 chainId 分开）：
   - rpc url（HTTP）
   - FaucetV2 合约地址
   - token 地址与 decimals
   - singleAmount（human 与 raw）
   - signer 私钥（只用于签名）
   - relayer 私钥（只用于发交易）
3) 启动自检（启动时就报错，不要等用户点了才炸）：
   - rpc chainId 是否匹配配置
   - FaucetV2 合约地址是否有 code
   - relayer ETH 余额是否 >= 0.5（不足就把服务标成 `RELAYER_ETH_LOW`）

#### P0-5 数据库 + Liquibase（把“对账能力”做成默认能力）
1) 引入 Postgres/JPA/Liquibase（当前 BFF 没有 DB 依赖，需要补齐）
2) Liquibase 建表（最少这些表，别再拖到 P1）：
   - `faucet_claim_request`：用户每次点“领取”的总请求（包含 idempotencyKey、IP hash、deviceId、claimType、tokenA/tokenB、rejectReason、状态）
   - `faucet_claim_tx`：每个 token 一条 tx 记录（nonce、txHash、receipt 状态）
   - `faucet_nonce`：`(chain_id, user_address) -> next_nonce`（必须可原子递增）
   - `faucet_blacklist`：黑名单（最小止血能力）
3) 关键索引/约束（必须有，不然并发必出事）：
   - `faucet_claim_request.idempotency_key` 唯一索引
   - `faucet_nonce` 复合唯一索引 `(chain_id, user_address)`
   - 常用查询索引：`(chain_id, user_address, created_at)`、`(chain_id, status, created_at)`

#### P0-6 quote 接口（让前端“只展示固定发放”，不靠猜）
`GET /api/faucet/v2/quote?chainId=...&user=...`
1) 返回 Single 列表（token + amount）
2) 返回 Pair 规则（可选 token 列表 + “必须选两种且不同”）
3) 返回用户今天剩余次数：
   - single 剩几次
   - pair 剩几次
   - 每个 token 今天剩余多少额度（按 `walletDailyMaxPerToken[token]`）
4) 如果被拦，返回明确原因（前端照着文案提示）：
   - `COOLDOWN` / `DAILY_LIMIT` / `DAILY_TOKEN_QUOTA` / `IP_LIMIT` / `VAULT_LOW` / `RELAYER_ETH_LOW` / `CAPTCHA_REQUIRED` / `BLOCKED`

#### P0-7 claim 接口（最核心：风控 -> nonce -> 签名 -> 发交易 -> 落库）
`POST /api/faucet/v2/claim`
1) 入参校验（必做）：
   - SINGLE：`token` 必填且在白名单
   - PAIR：`tokenA/tokenB` 必填、都在白名单、且不相等
   - `idempotencyKey` 必填（前端生成 uuid）
2) 风控校验顺序（建议就按这个顺序做，出错更好排查）：
   - 黑名单（直接拒绝）
   - relayer ETH 是否 >= 0.5（不足就拒绝：`RELAYER_ETH_LOW`）
   - 冷却时间（`COOLDOWN`）
   - 钱包日次数（single/pair 分开）
   - 钱包日 token 配额（`DAILY_TOKEN_QUOTA`）
   - IP 限流（hour/day/uniqueWallets）
   - 人机校验（没带或验证失败：`CAPTCHA_REQUIRED`）
   - FaucetV2 合约库存是否够（不足：`VAULT_LOW`）
3) nonce 分配（必须原子）：
   - SINGLE 分 1 个 nonce
   - PAIR 一次分 2 个 nonce（必须同一事务里分配）
4) day/deadline 口径（必须一致）：
   - day 用链上 `currentDay()` 同口径（或用最新块 timestamp 算，保证和合约一致）
   - deadline = now + 10 分钟（跟你设置的 cooldown 不冲突）
5) EIP-712 签名（Signer）：
   - 生成 ClaimReq（user/token/amount/day/nonce/deadline/pass=false）
   - 按 FaucetV2 的 name/version/chainId/verifyingContract 签
6) 交易发送（Relayer）：
   - SINGLE：发 1 笔 `claimWithSig`
   - PAIR：发 2 笔 `claimWithSig`（先发 A 再发 B；两笔都落库）
7) 落库状态机（至少这些状态）：
   - `REJECTED`（没签名）
   - `SIGNED`（签完但还没发交易）
   - `SUBMITTED`（拿到 txHash）
   - `CONFIRMED` / `FAILED`（P0 可以先不做自动对账，但要把字段预留好）

#### P0-8 前端 /faucet 页面 + 人机校验（能用、能解释、能跳转浏览器）
1) 未连接钱包：提示连接钱包（不要展示领取按钮）
2) 连接后：
   - 先打 `quote`，把“能领什么/会领多少/为什么被拦”讲清楚
   - UI：Single 下拉选 token；Pair 两个下拉选 tokenA/tokenB（不能相同）
3) 集成人机校验（Turnstile/hCaptcha 二选一）：
   - 用户点 Claim 前先拿到 captchaToken
   - 把 captchaToken 带给 `claim` 接口
4) 进度展示：
   - 展示 txHash（两笔就两条）
   - 给区块浏览器链接

#### P0-9 一套“跑通检查清单”（你/我都能按这个验收）
1) Single：成功领到币，链上有 `Claimed` 事件
2) Pair：成功领到两种币，两笔 tx 都能在浏览器查到
3) 连点：同一个 idempotencyKey 不会发两次
4) 冷却：10 分钟内再点被拦（`COOLDOWN`）
5) 日次数：single 超 2 次 / pair 超 1 次被拦
6) IP：在同一 IP 下超过 9 次/小时会被拦
7) relayer ETH < 1：直接停发，前端能看到明确原因
8) VAULT_LOW：库存不足时不会发交易，前端能看到明确原因

### P1（上线不痛，建议马上做）
5) 后端：对账（receipt 轮询或事件监听）
6) 后端：pass 模式（库存低/异常时强制买票）
7) 前端：买票 UI（调用 payPass + 引导重试 claim）

### P2（再优化）
8) 设备指纹/更细风控
9) 管理后台/可视化：今日发了多少、剩多少、异常告警
10) batch 交易（把两笔 claim 合并）
