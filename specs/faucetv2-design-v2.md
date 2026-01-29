# FaucetV2 方案（DB 风控 + 延迟回执确认）

## 目标
- 发送交易前不做链上模拟（不调用 `eth_call` / `eth_estimateGas`）。
- 使用本地数据库风控 + 配置缓存，降低链上依赖。
- 交易发送后只做一次回执查询（固定延迟 100s）。
- 状态只保留 `SUBMITTED`（不再使用 `RECEIVED`）。

---

## 一、关键约束与取舍
- **发交易成功只代表广播成功**，不代表执行成功。
- **风控判断基于 DB**，链上只在最终广播时执行。
- **回执查询只做一次**：100s 后查 `eth_getTransactionReceipt`，无回执即丢弃。
- **接受偶发不一致**（如：交易晚到但已标记 `DROPPED`）。
- **DB 计数与库存更新统一放到“交易发送成功之后”**。
- **交易失败或丢弃必须回滚计数与库存**。

---

## 二、数据表结构（最终版）

### 1) faucet_claim_request（请求记录）
- 作用：幂等、追踪、延迟队列调度
- 字段：
  - `id`
  - `idempotency_key`
  - `status`（仅使用 `SUBMITTED` / `CONFIRMED` / `FAILED` / `DROPPED`）
  - `tx_hash`
  - `check_at`
  - `user_address`
  - `ip_hash`
  - `device_id`
  - `created_at`

### 2) faucet_chain_config（链级配置）
- 字段：
  - `chain_id (PK)`
  - `enabled`
  - `paused`
  - `user_daily_max`
  - `user_cooldown_seconds`
  - `claim_deadline_seconds`
  - `ip_daily_max`
  - `eip712_name`
  - `eip712_version`
  - `signer_address`
  - `relayer_address`
- 缓存策略：Redis TTL = **72h**

### 3) faucet_token_config（token 配置）
- 字段：
  - `chain_id (PK)`
  - `token_address (PK)`
  - `symbol`
  - `single_amount_raw`
  - `whitelist (boolean)`
  - `vault_balance_raw`

### 4) faucet_token_daily_issued（token 全局日额度）
- 字段：
  - `chain_id (PK)`
  - `token_address (PK)`
  - `day (PK)`
  - `issued_raw`

### 5) faucet_user_daily（用户日次数 + 冷却）
- 字段：
  - `chain_id (PK)`
  - `user_address (PK)`
  - `day (PK)`
  - `claim_count`
  - `last_claim_at`

### 6) faucet_ip_daily（IP 日限制）
- 字段：
  - `chain_id (PK)`
  - `ip_hash (PK)`
  - `day (PK)`
  - `claim_count`

### 7) faucet_blacklist（黑名单）
- 字段：
  - `chain_id (PK)`
  - `user_address`
  - `ip_hash`
  - `active`

---

## 三、Nonce 设计
- Nonce 使用 **用户维度的 claim_count** 生成。
- 格式建议：`YYYYMMDD + claim_count`（例如：202501231, 202501232）。
- 不单独存 nonce 字段。
- 关键点：`claim_count` 必须在数据库中 **原子递增**，避免并发重复。

---

## 四、风控查询策略（减少 SQL 次数，不用大 SQL）

- `faucet_chain_config`：Redis 读取（TTL 72h），缓存 miss 才查 DB。
- 合并查询 #1：`faucet_token_config + faucet_token_daily_issued`（token 维度）。
- 合并查询 #2：`faucet_claim_request + faucet_user_daily + faucet_ip_daily + faucet_blacklist`（请求/用户/IP 维度）。
- 计数行缺失时：用 `INSERT ... ON CONFLICT DO NOTHING` 初始化后再查询（一次性补齐）。

说明：
- 查询顺序以“最便宜 → 最早拒绝”为原则。
- 不进行链上读取/模拟。

---

## 五、风控规则（发送交易前）

1) idempotency_key 已存在 → 直接返回历史结果
2) chain_config.enabled=false → 拒绝
3) chain_config.paused=true → 拒绝
4) captcha 失败 → 拒绝
5) 黑名单命中 → 拒绝
6) token whitelist=false → 拒绝
7) 用户冷却时间未到 → 拒绝
8) 用户日次数超限（user_daily_max） → 拒绝
9) IP 日次数超限（ip_daily_max = 6） → 拒绝
10) token 全局日额度超限（issued_raw + single_amount_raw > daily_cap_raw） → 拒绝
11) vault_balance_raw < single_amount_raw → 拒绝
12) 通过 → 执行 DB 更新 + 发交易

---

## 六、DB 更新规则（通过风控后）

**发送交易成功后（有 txHash 才更新）：**
- 写 `faucet_claim_request`：
  - `status = SUBMITTED`
  - `tx_hash`
  - `check_at = now + 100s`
  - `user_address / ip_hash / device_id`
- `faucet_user_daily`：`claim_count += 1`, `last_claim_at = now`
- `faucet_ip_daily`：`claim_count += 1`
- `faucet_token_daily_issued`：`issued_raw += single_amount_raw`
- `faucet_token_config`：`vault_balance_raw -= single_amount_raw`

---

## 七、延迟回执队列（无轮询）

### 7.1 入队信息（交易广播成功后）
- `request_id`
- `chain_id`
- `tx_hash`
- `check_at`（用于恢复）

### 7.2 出队处理（仅执行一次）
1) 调用 `eth_getTransactionReceipt(txHash)`\n
2) 根据结果更新 `faucet_claim_request.status`：\n
   - `status = 1` → `CONFIRMED`\n
   - `status = 0` → `FAILED`\n
   - `null` → `DROPPED`

### 7.3 状态更新规则
- 成功（CONFIRMED）：\n
  - 仅更新 `faucet_claim_request.status`\n
- 失败（FAILED）或无回执（DROPPED）：\n
  - 更新 `faucet_claim_request.status`\n
  - 回滚以下数据（与发送后写入相反）：\n
    - `faucet_user_daily.claim_count -= 1`\n
    - `faucet_ip_daily.claim_count -= 1`\n
    - `faucet_token_daily_issued.issued_raw -= single_amount_raw`\n
    - `faucet_token_config.vault_balance_raw += single_amount_raw`

### 7.4 服务重启恢复
- 启动时从 DB 读取 `status=SUBMITTED` 的记录\n
- 计算 `delay = check_at - now` 后重新入队\n
- 非轮询，仅启动时执行一次

---

## 八、回滚策略（当前默认）
- 交易失败或丢弃 **必须回滚** 计数与库存（上面第 7.3 已定义）。

---

## 九、说明
- 该方案刻意减少链上 RPC，适合快速交付与降低复杂度。
- 若后续追求更高一致性，可加回执重试或事件监听。
