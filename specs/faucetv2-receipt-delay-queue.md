# FaucetV2 回执延迟处理方案（无轮询版）

## 目标
- 发送交易后不做链上模拟（不调用 `eth_call` / `eth_estimateGas`）。
- 仅依赖交易回执确认成功/失败。
- 采用**延迟队列**方式：入队后固定延迟（默认 100s）再处理。
- 不使用“每 5~10 秒扫表”的轮询方式。

## 总体流程
1. **发送交易**：后端构造并发送交易，获得 `txHash`。
2. **入队**：写入数据库 `faucet_claim_tx`（或扩展表）并放入内存延迟队列（DelayQueue）。
3. **延迟触发**：延迟 100 秒后自动出队（阻塞等待，不轮询）。
4. **回执查询**：执行 `eth_getTransactionReceipt(txHash)`。
5. **结果处理**：
   - `receipt == null`：标记 `DROPPED`（只查一次，直接丢弃）。
   - `receipt.status == 1`：标记 `CONFIRMED`，更新历史与余额。
   - `receipt.status == 0`：标记 `FAILED`，不更新余额。

## 延迟队列实现（推荐）
### 1) 内存 DelayQueue + DB 持久化
- **写入时**：
  - `status = SUBMITTED`
  - `check_at = now + 100s`
  - 同时将任务放入内存 DelayQueue
- **启动时恢复**：
  - 服务启动时只执行一次 DB 加载（非轮询）
  - 将 `status = SUBMITTED` 的记录重新入队，延迟为 `check_at - now`
- **消费线程**：
  - 阻塞等待 `DelayQueue.take()`
  - 到期即执行一次回执查询

### 优点
- 无轮询
- 固定延迟触发
- 服务重启不丢任务（可恢复）

### 限制
- 多实例部署需要额外的分布式锁或将任务分片
- 如果节点宕机，未入队但已写入 DB 的任务需要在启动时恢复

## 关键参数
- `receiptDelaySeconds = 100`
- `receiptQueryOnce = true`
- `receiptMissingAction = DROPPED`

## 状态机
- `SUBMITTED`：已发交易，等待回执查询
- `CONFIRMED`：回执成功
- `FAILED`：回执失败（revert）
- `DROPPED`：回执为空（只查一次，直接丢弃）

## 注意事项
- 无链上模拟后，失败交易仍会消耗 relayer gas。
- 若 `DROPPED` 但交易后来上链，会产生数据不一致；此方案接受该风险。
- 如需更高一致性，可提高延迟时间或增加一次重试。

## 后续讨论方向
- 数据库风控表结构优化：如何一次查询获取所有判断信息。
- 是否建立聚合表（每日次数/IP 计数/用户额度）减少统计查询。
