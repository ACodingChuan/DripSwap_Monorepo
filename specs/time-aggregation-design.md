# 时间聚合数据结构设计（Token / Pair，Uniswap 对齐版）

> **目标**：在保持 Uniswap v2 体系语义的前提下，重新设计 token/pair 的 minute/hour 聚合表，满足 Explore/Tokens、Token 详情、Explore/Pools、Pool 详情的图表展示需求。  
> **结论方向**：Token 价格曲线采用**单池参考价**（Reference Pool spot price），成交量和 TVL 采用**全池聚合**。

---

## 1. 设计原则（与 Uniswap 对齐）

1) **Token 价格曲线是“参考价”**  
   - 不是 swap 成交价，也不是全市场加权价。  
   - 通过 **Reference Pool** 的储备比率得到 spot price。  

2) **成交量/TVL 是全池聚合**  
   - Token 维度的 volume 与 TVL 都是 token 参与的所有池子累计值。  
   - Pair 维度的 volume 与 TVL 只针对该 pair。

3) **与 Uniswap v2 子图语义一致**  
   - 价格来源采用 whitelist + 最小流动性阈值（MIN_LIQUIDITY）。  
   - 价格是 `derivedETH * ETH/USD`（ETH/USD 取预言机）。  

4) **结构设计可直接驱动图表**  
   - minute/hour 表存储“桶内增量 + 快照”，避免复杂差分。

---

## 2. Reference Pool 选择规则（Single Pool Price）

### 2.1 规则（Uniswap v2 对齐）
- 如果 token 是 `REFERENCE_TOKEN`（vETH），则 `derivedETH = 1`。
- 如果 token 是稳定币（STABLECOINS），则 `derivedETH = 1 / ethPrice`。
- 否则：
  - 按 **WHITELIST 顺序**遍历，找到第一个满足：
    - `pair.exists` 且 `pair.reserveETH > MIN_LIQUIDITY_THRESHOLD`  
  - 使用该 pair 作为 Reference Pool。

> 这是 Uniswap v2 子图的做法，属于**单池参考价**。

### 2.2 参考价计算
```
tokenPriceUSD = derivedETH * ethPriceUSD
```
其中：
```
derivedETH = 
  if token == vETH -> 1
  if token in STABLECOINS -> 1 / ethPriceUSD
  else -> pair.tokenXPrice * tokenX.derivedETH (whitelist 逻辑)
```

---

## 3. 新的数据结构（建议落库）

### 3.1 TokenHourData
**用途**：token 价格 OHLC + 成交量 + TVL（小时粒度）

| 字段 | 类型 | 语义 | 数据来源 | 更新方式 |
|---|---|---|---|---|
| id | text | `{token}-{hourIndex}` | 计算 | 创建时 |
| periodStartUnix | int | 小时起点 | 计算 | 创建时 |
| token | hex | token 地址 | token.id | 创建时 |
| open/high/low/close | text | **参考价**（USD） | Reference Pool | Sync |
| priceUSD | text | close 同义 | Reference Pool | Sync |
| volumeToken | bigint | **桶内增量** | Swap | 累加 |
| volumeUSD | text | **桶内增量（tracked）** | Swap | 累加 |
| untrackedVolumeUSD | text | **桶内增量（untracked）** | Swap | 累加 |
| totalValueLockedUSD | text | **token 全池 TVL** | token.totalLiquidity | Sync |
| totalValueLockedToken | bigint | 全池 TVL raw | token.totalLiquidity | Sync |

> **注意**：volume 和 tvl 是全池聚合；open/close 来自 reference pool。

### 3.2 TokenMinuteData
**用途**：token 价格 OHLC + 成交量 + TVL（分钟粒度）

字段与 TokenHourData 一致，仅 `periodStartUnix` 为分钟起点，`id` 使用 minuteIndex。

### 3.3 PairHourData
**用途**：Pool（pair）粒度的 TVL/Volume 统计（小时）

| 字段 | 类型 | 语义 | 数据来源 | 更新方式 |
|---|---|---|---|---|
| id | text | `{pair}-{hourIndex}` | 计算 | 创建时 |
| hourStartUnix | int | 小时起点 | 计算 | 创建时 |
| pair | hex | pair 地址 | pair.id | 创建时 |
| reserve0/reserve1 | bigint | 储备快照 | Sync | 覆盖 |
| reserveUSD | text | 该池 TVL | Sync | 覆盖 |
| hourlyVolumeToken0/1 | bigint | 桶内成交量 | Swap | 累加 |
| hourlyVolumeUSD | text | 桶内成交量(USD) | Swap | 累加 |
| hourlyTxns | bigint | 桶内交易数 | Swap | 累加 |

### 3.4 Token 价格来源字段（建议新增）
为保证“单池价格”的可解释性，建议在 `tokens` 表中补充以下字段：

| 字段 | 类型 | 语义 |
|---|---|---|
| priceSource | text | `REFERENCE` / `STABLE` / `POOL` |
| priceSourcePair | hex | 参考池 pair 地址（仅 POOL 时有值） |
| priceSourceUpdatedAt | bigint | 上次更新参考池的块高 |

---

## 4. 更新流程（事件驱动）

### 4.1 PairCreated
- 创建 Token / Pair 基础实体
- **不创建**时间桶（避免空数据）

### 4.2 Sync（储备更新）
1) 更新 pair.reserve0/reserve1、token0Price/token1Price  
2) 更新 `pair.reserveETH / reserveUSD`  
3) 计算 token.derivedETH（Reference Pool 规则）  
4) 计算 token.priceUSD = derivedETH * ethPrice  
5) **TokenHour/Minute**  
   - 只更新 OHLC + priceUSD  
   - 更新 TVL 快照（token.totalLiquidity）  
6) **PairHour**  
   - 更新 reserve0/reserve1/reserveUSD 快照

### 4.3 Swap（交易）
1) 计算 amount0/amount1、trackedUSD、untrackedUSD  
2) 更新 token.tradeVolume / tradeVolumeUSD / untrackedVolumeUSD  
3) 更新 pair.volumeToken0/1 / volumeUSD  
4) **TokenHour/Minute**  
   - 只累加 volumeToken / volumeUSD / untrackedVolumeUSD  
   - 不更新 OHLC（OHLC 仅由 Sync 触发，Swap 后的 Sync 会单独更新）  
5) **PairHour**  
   - 累加 volumeToken0/1、volumeUSD  
   - txCount +1

### 4.4 桶 ID 与时间边界
- hourIndex = floor(timestamp / 3600)  
- minuteIndex = floor(timestamp / 60)  
- id = `{entityId}-{index}`  
- periodStartUnix = index * (3600 or 60)

### 4.5 open=0 的修正策略（与实际链上逻辑对齐）
若桶创建时价格为 0，但后续同桶价格 > 0，则：
```
if open == 0 and price > 0:
  open = price
  low = min(low, price)
```
这保证 “开盘价” 是该桶第一个有效价格。
---

## 5. 初始化与更新示例

### 示例 A：Token 首次流动性 + 同小时 Swap
**背景**：vBTC/vETH pair 第一次 Sync 后，在同一小时产生 Swap  

1) **Sync 初始化**  
```
tokenHourData:
  open=high=low=close=priceUSD=31000
  volumeUSD=0
  tvlUSD=1,200,000
```
2) **Swap**  
```
tokenHourData:
  volumeUSD += 50,000
  close 仅在下一次 Sync 更新
```

### 示例 B：池子 TVL 变化但无交易
**背景**：用户仅添加流动性，触发 Sync，但无 Swap  

```
pairHourData.reserveUSD 变更
tokenHourData.totalValueLockedUSD 变更
volume 仍为 0
```

---

## 6. 图表映射（Uniswap Explore 对齐）

### 6.1 Explore / Tokens
| 展示项 | 计算来源 |
|---|---|
| Price | token.priceUSD |
| 1h/24h Change | tokenHourData.close vs 1h/24h 前 close |
| Volume 24h | SUM(tokenHourData.volumeUSD, last 24h) |
| TVL | token.totalLiquidity * token.priceUSD |
| Sparkline | tokenHourData.close（按小时） |

### 6.2 Token 详情页
| 图表 | 数据来源 |
|---|---|
| Price Candlestick | tokenMinuteData / tokenHourData OHLC |
| Volume Chart | tokenMinuteData / tokenHourData volumeUSD |
| TVL Chart | tokenMinuteData / tokenHourData totalValueLockedUSD |

### 6.3 Explore / Pools
| 展示项 | 计算来源 |
|---|---|
| TVL | pair.reserveUSD |
| Volume 24h | SUM(pairHourData.hourlyVolumeUSD, last 24h) |
| Fees 24h | volumeUSD * feeRate |

### 6.4 Pool 详情页
| 图表 | 数据来源 |
|---|---|
| Volume Chart | pairHourData |
| TVL Chart | pairHourData |
| Fees Chart | volumeUSD * feeRate |

### 6.5 API 组装建议（与 Uniswap 页面行为一致）

**Token 详情页**
- `ohlc`: 直接取 tokenHour/Minute 的 OHLC
- `historicalVolume`: 取桶内 `volumeUSD`
- `historicalTvl`: 取桶内 `totalValueLockedUSD`（快照）

**Explore/Tokens 表格**
- `priceHistory`: 用 tokenHourData.close 生成 sparkline
- `volume1h/1d/1w`: sum( tokenHourData.volumeUSD )
- `pricePercentChange`: (close_now - close_then) / close_then

**Pool 详情页**
- `volume`: sum( pairHourData.hourlyVolumeUSD )
- `tvl`: pairHourData.reserveUSD（快照）

---

## 7. 关键约束与注意事项

1) **Token OHLC 不是成交价**  
   它是 Reference Pool spot price，可能与成交价不同。  

2) **单池参考价会出现跳变**  
   当 Reference Pool 改变时，价格会出现不连续。  

3) **Volume/TVL 是全池聚合**  
   与价格来源不同，需要在展示上明确“参考价 + 聚合量”。  

---

## 8. 可直接落地的开发提示

- token_hour/minute 中 volume 采用 **桶内增量**  
- token_hour/minute 中 tvl 采用 **快照**  
- token_hour/minute 的 OHLC 基于 Reference Pool 价格
