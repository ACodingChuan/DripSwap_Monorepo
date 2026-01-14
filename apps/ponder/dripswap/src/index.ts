import Decimal from "decimal.js";
import { createWriteStream } from "node:fs";
import { fileURLToPath } from "node:url";
import { ponder } from "ponder:registry";
import {
  burn,
  bundle,
  mint,
  pair,
  pairTokenLookup,
  pairDayData,
  pairHourData,
  swap,
  token,
  tokenDayData,
  tokenHourData,
  tokenMinuteData,
  uniswapDayData,
  uniswapFactory,
  user,
} from "ponder:schema";

import { ADDRESS_ZERO, FACTORY_ADDRESS } from "./constants";
import { divWad, formatWad, mulWad, parseWad, scaleToWad } from "./fixedPoint";
import {
  calcDerivedAmountEth,
  findEthPerToken,
  getEthPriceInUSD,
  getTrackedLiquidityUSD,
  getTrackedVolumeUSD,
} from "./pricing";
import { fetchTokenMetadata } from "./token";

type PendingMint = { to: string; liquidity: bigint };
type PendingBurn = { sender: string; liquidity: bigint };

// ✅ 问题7：缓存里带 blockNumber + blockTimestamp + updatedAt
type EthPriceCache = {
  price: Decimal;
  roundId: bigint;
  updatedAt: bigint;
  blockNumber: bigint;
  blockTimestamp: bigint;
};

const pendingMints = new Map<string, PendingMint[]>();
const pendingBurns = new Map<string, PendingBurn[]>();
const latestEthPrice = new Map<number, EthPriceCache>();

const FEE_RATE = new Decimal("0.003");
const ORACLE_SEPOLIA_CACHE_SECONDS = 3600n;
const CHAIN_ID_SEPOLIA = 11155111;
const CHAIN_ID_SCROLL_SEPOLIA = 534351;

const logKey = "__ponder_log_redirect__";
if (!(globalThis as Record<string, unknown>)[logKey]) {
  (globalThis as Record<string, unknown>)[logKey] = true;
  const logPath = fileURLToPath(new URL("../ponder.log", import.meta.url));
  const logStream = createWriteStream(logPath, { flags: "a" });
  const stdoutWrite = process.stdout.write.bind(process.stdout);
  const stderrWrite = process.stderr.write.bind(process.stderr);

  process.stdout.write = ((chunk, encoding, cb) => {
    try {
      logStream.write(chunk);
    } catch {
      // ignore
    }
    return stdoutWrite(chunk, encoding as never, cb as never);
  }) as typeof process.stdout.write;

  process.stderr.write = ((chunk, encoding, cb) => {
    try {
      logStream.write(chunk);
    } catch {
      // ignore
    }
    return stderrWrite(chunk, encoding as never, cb as never);
  }) as typeof process.stderr.write;
}
function logEvent(
  chainName: string,
  message: string,
  details?: Record<string, unknown>
) {
  if (details) {
    console.info(`[ponder][${chainName}] ${message}`, details);
  } else {
    console.info(`[ponder][${chainName}] ${message}`);
  }
}

function logOracle(message: string, details?: Record<string, unknown>) {
  if (details) {
    console.info(`[ponder][oracle] ${message}`, details);
  } else {
    console.info(`[ponder][oracle] ${message}`);
  }
}

function normalizeAddress(address: string): `0x${string}` {
  return address.toLowerCase() as `0x${string}`;
}

/**
 * ✅ 问题1：txKey 必须包含 pairAddress
 */
function txKey(chainId: number, txHash: string, pairAddress: string): string {
  return `${chainId}:${txHash.toLowerCase()}:${pairAddress.toLowerCase()}`;
}

function makeEventId(txHash: string, logIndex: number): string {
  return `${txHash}-${logIndex}`;
}

function addWadString(value: string, delta: Decimal): string {
  return formatWad(parseWad(value).add(delta));
}

async function upsert(db: any, table: any, values: any) {
  const { chainId, id, ...rest } = values;
  await db.insert(table).values(values).onConflictDoUpdate(rest);
}

async function ensureUser(db: any, chainId: number, address: string) {
  await db
    .insert(user)
    .values({ chainId, id: normalizeAddress(address) })
    .onConflictDoNothing();
}

async function getOrCreateFactory(db: any, chainId: number) {
  const factoryId = normalizeAddress(FACTORY_ADDRESS);
  let existing = await db.find(uniswapFactory, { chainId, id: factoryId });
  if (!existing) {
    existing = {
      chainId,
      id: factoryId,
      pairCount: 0n,
      totalVolumeUSD: "0",
      totalVolumeETH: "0",
      untrackedVolumeUSD: "0",
      totalLiquidityUSD: "0",
      totalLiquidityETH: "0",
      txCount: 0n,
    };
    await db.insert(uniswapFactory).values(existing);
  }
  return existing;
}

async function getOrCreateToken(db: any, chainId: number, client: any, id: string) {
  const tokenId = normalizeAddress(id);
  let existing = await db.find(token, { chainId, id: tokenId });
  if (!existing) {
    const meta = await fetchTokenMetadata(client, tokenId);
    existing = {
      chainId,
      id: tokenId,
      symbol: meta.symbol,
      name: meta.name,
      decimals: meta.decimals,
      totalSupply: meta.totalSupply,
      tradeVolume: 0n,
      tradeVolumeUSD: "0",
      untrackedVolumeUSD: "0",
      txCount: 0n,
      totalLiquidity: 0n,
      derivedETH: "0",
    };
    await db.insert(token).values(existing);
  }
  return existing;
}

/**
 * ✅ Oracle price cache
 * - 同一块最多 fetch 一次，且 readContract 按 blockNumber
 * - Scroll: 仅同 blockNumber 复用缓存
 * - Sepolia: 当 blockTimestamp - updatedAt < 3600s 时复用缓存
 * - 仅当 roundId 变化时写入 bundle（多行，按 roundId 去重）
 */
async function getEthPriceCached(
  db: any,
  client: any,
  chainId: number,
  blockNumber: bigint,
  blockTimestamp: bigint
): Promise<EthPriceCache> {
  const cached = latestEthPrice.get(chainId);
  if (cached) {
    if (cached.blockNumber === blockNumber) {
      logOracle("cache hit (same block)", {
        chainId,
        blockNumber: blockNumber.toString(),
        roundId: cached.roundId.toString(),
        price: cached.price.toString(),
      });
      return cached;
    }

    if (
      chainId === CHAIN_ID_SEPOLIA &&
      cached.updatedAt > 0n &&
      blockTimestamp >= cached.updatedAt &&
      blockTimestamp - cached.updatedAt < ORACLE_SEPOLIA_CACHE_SECONDS
    ) {
      logOracle("cache hit (sepolia updatedAt window)", {
        chainId,
        blockTimestamp: blockTimestamp.toString(),
        updatedAt: cached.updatedAt.toString(),
        roundId: cached.roundId.toString(),
        price: cached.price.toString(),
      });
      return cached;
    }
  }
  logOracle("cache miss", {
    chainId,
    blockNumber: blockNumber.toString(),
    prevBlockNumber: cached?.blockNumber?.toString(),
  });

  const fetched = await getEthPriceInUSD(client, chainId, blockNumber);
  const next: EthPriceCache = { ...fetched, blockNumber, blockTimestamp };
  latestEthPrice.set(chainId, next);
  logOracle("cache set", {
    chainId,
    blockNumber: blockNumber.toString(),
    blockTimestamp: blockTimestamp.toString(),
    roundId: next.roundId.toString(),
    updatedAt: next.updatedAt.toString(),
    price: next.price.toString(),
  });

  if (cached && cached.roundId === next.roundId) {
    logOracle("round unchanged, skip bundle insert", {
      chainId,
      blockNumber: blockNumber.toString(),
      roundId: next.roundId.toString(),
    });
    return next;
  }

  // 记录历史 bundle（按 roundId 去重）
  const id = next.roundId.toString();
  await upsert(db, bundle, {
    chainId,
    id,
    ethPrice: formatWad(next.price),
    oracleRoundId: next.roundId,
    updatedAt: next.updatedAt,
  });

  return next;
}

async function updateUniswapDayData(
  db: any,
  chainId: number,
  timestamp: bigint,
  factoryRow: any
) {
  const dayId = Math.floor(Number(timestamp) / 86400);
  const dayStart = dayId * 86400;
  const id = dayId.toString();
  let row = await db.find(uniswapDayData, { chainId, id });
  if (!row) {
    row = {
      chainId,
      id,
      date: dayStart,
      dailyVolumeETH: "0",
      dailyVolumeUSD: "0",
      dailyVolumeUntracked: "0",
      totalVolumeETH: "0",
      totalLiquidityETH: "0",
      totalVolumeUSD: "0",
      totalLiquidityUSD: "0",
      txCount: 0n,
    };
  }
  row.totalLiquidityUSD = factoryRow.totalLiquidityUSD;
  row.totalLiquidityETH = factoryRow.totalLiquidityETH;
  row.txCount = factoryRow.txCount;
  await upsert(db, uniswapDayData, row);
  return row;
}

async function updatePairDayData(db: any, chainId: number, timestamp: bigint, pairRow: any) {
  const dayId = Math.floor(Number(timestamp) / 86400);
  const dayStart = dayId * 86400;
  const id = `${pairRow.id}-${dayId}`;
  let row = await db.find(pairDayData, { chainId, id });
  if (!row) {
    row = {
      chainId,
      id,
      date: dayStart,
      pairAddress: pairRow.id,
      token0: pairRow.token0,
      token1: pairRow.token1,
      reserve0: 0n,
      reserve1: 0n,
      totalSupply: 0n,
      reserveUSD: "0",
      dailyVolumeToken0: 0n,
      dailyVolumeToken1: 0n,
      dailyVolumeUSD: "0",
      dailyTxns: 0n,
    };
  }
  row.totalSupply = pairRow.totalSupply;
  row.reserve0 = pairRow.reserve0;
  row.reserve1 = pairRow.reserve1;
  row.reserveUSD = pairRow.reserveUSD;
  row.dailyTxns = row.dailyTxns + 1n;
  await upsert(db, pairDayData, row);
  return row;
}

async function updatePairHourData(db: any, chainId: number, timestamp: bigint, pairRow: any) {
  const hourIndex = Math.floor(Number(timestamp) / 3600);
  const hourStart = hourIndex * 3600;
  const id = `${pairRow.id}-${hourIndex}`;
  let row = await db.find(pairHourData, { chainId, id });
  if (!row) {
    row = {
      chainId,
      id,
      hourStartUnix: hourStart,
      pair: pairRow.id,
      reserve0: 0n,
      reserve1: 0n,
      totalSupply: 0n,
      reserveUSD: "0",
      hourlyVolumeToken0: 0n,
      hourlyVolumeToken1: 0n,
      hourlyVolumeUSD: "0",
      hourlyTxns: 0n,
    };
  }
  row.totalSupply = pairRow.totalSupply;
  row.reserve0 = pairRow.reserve0;
  row.reserve1 = pairRow.reserve1;
  row.reserveUSD = pairRow.reserveUSD;
  row.hourlyTxns = row.hourlyTxns + 1n;
  await upsert(db, pairHourData, row);
  return row;
}

async function updateTokenDayData(
  db: any,
  chainId: number,
  timestamp: bigint,
  tokenRow: any,
  ethPrice: Decimal
) {
  const dayId = Math.floor(Number(timestamp) / 86400);
  const dayStart = dayId * 86400;
  const id = `${tokenRow.id}-${dayId}`;
  let row = await db.find(tokenDayData, { chainId, id });
  if (!row) {
    row = {
      chainId,
      id,
      date: dayStart,
      token: tokenRow.id,
      dailyVolumeToken: 0n,
      dailyVolumeETH: "0",
      dailyVolumeUSD: "0",
      dailyTxns: 0n,
      totalLiquidityToken: 0n,
      totalLiquidityETH: "0",
      totalLiquidityUSD: "0",
      priceUSD: "0",
    };
  }

  const derivedEth = parseWad(tokenRow.derivedETH);
  const totalLiquidityDec = scaleToWad(tokenRow.totalLiquidity, tokenRow.decimals);
  const totalLiquidityEth = mulWad(totalLiquidityDec, derivedEth);
  const totalLiquidityUsd = mulWad(totalLiquidityEth, ethPrice);

  row.priceUSD = formatWad(mulWad(derivedEth, ethPrice));
  row.totalLiquidityToken = tokenRow.totalLiquidity;
  row.totalLiquidityETH = formatWad(totalLiquidityEth);
  row.totalLiquidityUSD = formatWad(totalLiquidityUsd);
  row.dailyTxns = row.dailyTxns + 1n;

  await upsert(db, tokenDayData, row);
  return row;
}

/**
 * TokenHour/Minute（Uniswap 对齐 + 单池参考价）：
 * - Sync：更新 OHLC/price + TVL 快照
 * - Swap：只更新 volume（在 addTokenBucketSwapVolume 中处理）
 */
async function touchTokenBucket(
  db: any,
  chainId: number,
  timestamp: bigint,
  tokenRow: any,
  priceUsd: Decimal,
  kind: "hour" | "minute"
) {
  const seconds = kind === "hour" ? 3600 : 60;
  const index = Math.floor(Number(timestamp) / seconds);
  const start = index * seconds;
  const id = `${tokenRow.id}-${index}`;

  const table = kind === "hour" ? tokenHourData : tokenMinuteData;
  let row = await db.find(table, { chainId, id });

  const priceUsdStr = formatWad(priceUsd);
  const totalLiquidityDec = scaleToWad(tokenRow.totalLiquidity ?? 0n, tokenRow.decimals);
  const totalValueLockedUsd = mulWad(totalLiquidityDec, priceUsd);

  if (!row) {
    row = {
      chainId,
      id,
      periodStartUnix: start,
      token: tokenRow.id,
      volumeToken: 0n,
      volumeUSD: "0",
      untrackedVolumeUSD: "0",
      totalValueLockedToken: tokenRow.totalLiquidity ?? 0n,
      totalValueLockedUSD: formatWad(totalValueLockedUsd),
      priceUSD: priceUsdStr,
      feesUSD: "0",
      open: priceUsdStr,
      high: priceUsdStr,
      low: priceUsdStr,
      close: priceUsdStr,
    };
  } else {
    const high = parseWad(row.high);
    const low = parseWad(row.low);
    const open = parseWad(row.open);

    const pricePositive = priceUsd.gt(0);
    const nextHigh = high.gt(priceUsd) ? high : priceUsd;
    const nextLow = low.isZero() && pricePositive ? priceUsd : low.lt(priceUsd) ? low : priceUsd;

    if (open.isZero() && pricePositive) {
      row.open = priceUsdStr;
    }

    row.high = formatWad(nextHigh);
    row.low = formatWad(nextLow);
    row.close = priceUsdStr;
    row.priceUSD = priceUsdStr;

    row.totalValueLockedToken = tokenRow.totalLiquidity ?? 0n;
    row.totalValueLockedUSD = formatWad(totalValueLockedUsd);
  }

  await upsert(db, table, row);
  return row;
}

async function addTokenBucketSwapVolume(
  db: any,
  chainId: number,
  timestamp: bigint,
  tokenRow: any,
  amountRaw: bigint,
  tokenDerivedUsdDelta: Decimal,
  isTracked: boolean,
  kind: "hour" | "minute"
) {
  const seconds = kind === "hour" ? 3600 : 60;
  const index = Math.floor(Number(timestamp) / seconds);
  const start = index * seconds;
  const id = `${tokenRow.id}-${index}`;
  const table = kind === "hour" ? tokenHourData : tokenMinuteData;

  let row = await db.find(table, { chainId, id });
  if (!row) {
    // 如果 swap 先于 sync 触发（极少见），兜底创建，OHLC/TVL 由后续 Sync 填充
    row = {
      chainId,
      id,
      periodStartUnix: start,
      token: tokenRow.id,
      volumeToken: 0n,
      volumeUSD: "0",
      untrackedVolumeUSD: "0",
      totalValueLockedToken: 0n,
      totalValueLockedUSD: "0",
      priceUSD: "0",
      feesUSD: "0",
      open: "0",
      high: "0",
      low: "0",
      close: "0",
    };
  }

  row.volumeToken = (row.volumeToken ?? 0n) + amountRaw;

  // ✅ untracked：永远累加 token 自己的 derived USD（方案A）
  row.untrackedVolumeUSD = addWadString(row.untrackedVolumeUSD, tokenDerivedUsdDelta);

  // ✅ tracked：仅当该 swap trackedAmountUSD > 0 才累加
  if (isTracked) {
    row.volumeUSD = addWadString(row.volumeUSD, tokenDerivedUsdDelta);
  }

  // feesUSD：按 token 自己的 derived USD 估算
  row.feesUSD = addWadString(row.feesUSD, tokenDerivedUsdDelta.mul(FEE_RATE));

  await upsert(db, table, row);
  return row;
}

ponder.on("Factory:PairCreated", async ({ event, context }) => {
  const chainId = context.chain.id;
  const chainName = context.chain.name;
  const [token0Raw, token1Raw, pairRaw] = event.args as readonly [
    `0x${string}`,
    `0x${string}`,
    `0x${string}`,
    bigint
  ];
  const token0Address = normalizeAddress(token0Raw);
  const token1Address = normalizeAddress(token1Raw);
  const pairAddress = normalizeAddress(pairRaw);
  logEvent(chainName, "Factory:PairCreated", {
    chainId,
    pair: pairAddress,
    token0: token0Address,
    token1: token1Address,
    block: event.block.number,
    tx: event.transaction.hash,
  });

  const factoryRow = await getOrCreateFactory(context.db, chainId);
  factoryRow.pairCount = factoryRow.pairCount + 1n;
  await upsert(context.db, uniswapFactory, factoryRow);

  const token0 = await getOrCreateToken(context.db, chainId, context.client, token0Address);
  const token1 = await getOrCreateToken(context.db, chainId, context.client, token1Address);

  const pairRow = {
    chainId,
    id: pairAddress,
    token0: token0.id,
    token1: token1.id,
    reserve0: 0n,
    reserve1: 0n,
    totalSupply: 0n,
    reserveETH: "0",
    reserveUSD: "0",
    trackedReserveETH: "0",
    token0Price: "0",
    token1Price: "0",
    volumeToken0: 0n,
    volumeToken1: 0n,
    volumeUSD: "0",
    untrackedVolumeUSD: "0",
    txCount: 0n,
    createdAtTimestamp: event.block.timestamp,
    createdAtBlockNumber: event.block.number,
    liquidityProviderCount: 0n, // ✅ 问题6：永远 0
  };
  await upsert(context.db, pair, pairRow);

  await context.db
    .insert(pairTokenLookup)
    .values({ chainId, id: `${token0.id}-${token1.id}`, pair: pairAddress })
    .onConflictDoNothing();

  await context.db
    .insert(pairTokenLookup)
    .values({ chainId, id: `${token1.id}-${token0.id}`, pair: pairAddress })
    .onConflictDoNothing();
});

ponder.on("Pair:Transfer", async ({ event, context }) => {
  const chainId = context.chain.id;
  const chainName = context.chain.name;
  const pairId = normalizeAddress(event.log.address);
  logEvent(chainName, "Pair:Transfer", {
    chainId,
    pair: pairId,
    from: event.args.from,
    to: event.args.to,
    value: event.args.value.toString(),
    block: event.block.number,
    tx: event.transaction.hash,
  });
  const pairRow = await context.db.find(pair, { chainId, id: pairId });
  if (!pairRow) {
    logEvent(chainName, "Pair:Transfer missing pairRow", { chainId, pair: pairId });
    return;
  }

  const from = normalizeAddress(event.args.from);
  const to = normalizeAddress(event.args.to);
  const value = event.args.value;

  // ignore initial transfers for first adds
  if (to === ADDRESS_ZERO && value === 1000n) return;

  await ensureUser(context.db, chainId, from);
  await ensureUser(context.db, chainId, to);

  // ✅ 问题1：key 包含 pairId
  const key = txKey(chainId, event.transaction.hash, pairId);
  const pendingMintList = pendingMints.get(key) ?? [];
  const pendingBurnList = pendingBurns.get(key) ?? [];

  // mint: from == 0x0
  if (from === ADDRESS_ZERO) {
    pairRow.totalSupply = pairRow.totalSupply + value;
    pendingMintList.push({ to, liquidity: value });
    pendingMints.set(key, pendingMintList);
  }

  // burn: first transfer from LP to pair (to == pair)
  if (to === pairId) {
    pendingBurnList.push({ sender: from, liquidity: value });
    pendingBurns.set(key, pendingBurnList);
  }

  // burn: second transfer from pair to 0x0 (burn lp token)
  if (to === ADDRESS_ZERO && from === pairId) {
    pairRow.totalSupply = pairRow.totalSupply - value;
  }

  await upsert(context.db, pair, pairRow);
});

ponder.on("Pair:Sync", async ({ event, context }) => {
  const chainId = context.chain.id;
  const chainName = context.chain.name;
  const pairId = normalizeAddress(event.log.address);
  logEvent(chainName, "Pair:Sync", {
    chainId,
    pair: pairId,
    reserve0: event.args.reserve0.toString(),
    reserve1: event.args.reserve1.toString(),
    block: event.block.number,
    tx: event.transaction.hash,
  });
  const pairRow = await context.db.find(pair, { chainId, id: pairId });
  if (!pairRow) {
    logEvent(chainName, "Pair:Sync missing pairRow", { chainId, pair: pairId });
    return;
  }

  const token0 = await context.db.find(token, { chainId, id: pairRow.token0 });
  const token1 = await context.db.find(token, { chainId, id: pairRow.token1 });
  if (!token0 || !token1) {
    logEvent(chainName, "Pair:Sync missing token row", {
      chainId,
      pair: pairId,
      token0: pairRow.token0,
      token1: pairRow.token1,
    });
    return;
  }

  const factoryRow = await getOrCreateFactory(context.db, chainId);

  // old values
  const oldTotalLiquidityEth = parseWad(factoryRow.totalLiquidityETH);
  const oldPairTrackedEth = parseWad(pairRow.trackedReserveETH);

  const reserve0Raw = event.args.reserve0;
  const reserve1Raw = event.args.reserve1;

  // update token totalLiquidity raw
  const token0Liquidity = token0.totalLiquidity - pairRow.reserve0 + reserve0Raw;
  const token1Liquidity = token1.totalLiquidity - pairRow.reserve1 + reserve1Raw;

  // ✅ 问题7：按 blockNumber 读 oracle，并同块缓存
  const ethPriceCache = await getEthPriceCached(
    context.db,
    context.client,
    chainId,
    event.block.number,
    event.block.timestamp
  );

  // set reserves
  pairRow.reserve0 = reserve0Raw;
  pairRow.reserve1 = reserve1Raw;

  // prices
  if (reserve1Raw !== 0n) {
    const reserve0Wad = scaleToWad(reserve0Raw, token0.decimals);
    const reserve1Wad = scaleToWad(reserve1Raw, token1.decimals);
    pairRow.token0Price = formatWad(divWad(reserve0Wad, reserve1Wad));
  } else {
    pairRow.token0Price = "0";
  }

  if (reserve0Raw !== 0n) {
    const reserve0Wad = scaleToWad(reserve0Raw, token0.decimals);
    const reserve1Wad = scaleToWad(reserve1Raw, token1.decimals);
    pairRow.token1Price = formatWad(divWad(reserve1Wad, reserve0Wad));
  } else {
    pairRow.token1Price = "0";
  }

  await upsert(context.db, pair, pairRow);

  // update derivedETH (uses db state, like subgraph)
  const token0DerivedEth = await findEthPerToken(context.db, chainId, token0, ethPriceCache.price);
  const token1DerivedEth = await findEthPerToken(context.db, chainId, token1, ethPriceCache.price);
  token0.derivedETH = formatWad(token0DerivedEth);
  token1.derivedETH = formatWad(token1DerivedEth);

  // tracked liquidity (USD -> ETH)
  const reserve0Dec = scaleToWad(reserve0Raw, token0.decimals);
  const reserve1Dec = scaleToWad(reserve1Raw, token1.decimals);

  const trackedLiquidityUsd = getTrackedLiquidityUSD(
    reserve0Dec,
    token0,
    reserve1Dec,
    token1,
    ethPriceCache.price
  );
  const trackedLiquidityEth = divWad(trackedLiquidityUsd, ethPriceCache.price);

  // reserve ETH/USD
  const reserveEth = mulWad(reserve0Dec, token0DerivedEth).add(mulWad(reserve1Dec, token1DerivedEth));
  const reserveUsd = mulWad(reserveEth, ethPriceCache.price);

  pairRow.trackedReserveETH = formatWad(trackedLiquidityEth);
  pairRow.reserveETH = formatWad(reserveEth);
  pairRow.reserveUSD = formatWad(reserveUsd);

  logEvent(chainName, "Pair:Sync computed", {
    chainId,
    pair: pairId,
    token0: pairRow.token0,
    token1: pairRow.token1,
    token0Price: pairRow.token0Price,
    token1Price: pairRow.token1Price,
    token0DerivedETH: formatWad(token0DerivedEth),
    token1DerivedETH: formatWad(token1DerivedEth),
    reserveETH: pairRow.reserveETH,
    reserveUSD: pairRow.reserveUSD,
    trackedReserveETH: pairRow.trackedReserveETH,
  });

  // ✅ 问题2：不做 clamp，完全对齐 subgraph
  const nextTotalLiquidityEth = oldTotalLiquidityEth.sub(oldPairTrackedEth).add(trackedLiquidityEth);
  factoryRow.totalLiquidityETH = formatWad(nextTotalLiquidityEth);
  factoryRow.totalLiquidityUSD = formatWad(mulWad(nextTotalLiquidityEth, ethPriceCache.price));

  // token total liquidity raw
  token0.totalLiquidity = token0Liquidity >= 0n ? token0Liquidity : 0n;
  token1.totalLiquidity = token1Liquidity >= 0n ? token1Liquidity : 0n;

  await upsert(context.db, pair, pairRow);
  await upsert(context.db, token, token0);
  await upsert(context.db, token, token1);
  await upsert(context.db, uniswapFactory, factoryRow);

  // ✅ 问题8：Sync touch hour/minute（OHLC/price/TVL）
  const token0PriceUsd = mulWad(token0DerivedEth, ethPriceCache.price);
  const token1PriceUsd = mulWad(token1DerivedEth, ethPriceCache.price);

  await touchTokenBucket(context.db, chainId, event.block.timestamp, token0, token0PriceUsd, "hour");
  await touchTokenBucket(context.db, chainId, event.block.timestamp, token1, token1PriceUsd, "hour");
  await touchTokenBucket(context.db, chainId, event.block.timestamp, token0, token0PriceUsd, "minute");
  await touchTokenBucket(context.db, chainId, event.block.timestamp, token1, token1PriceUsd, "minute");
});

ponder.on("Pair:Mint", async ({ event, context }) => {
  const chainId = context.chain.id;
  const chainName = context.chain.name;
  const pairId = normalizeAddress(event.log.address);
  logEvent(chainName, "Pair:Mint", {
    chainId,
    pair: pairId,
    sender: event.args.sender,
    amount0: event.args.amount0.toString(),
    amount1: event.args.amount1.toString(),
    block: event.block.number,
    tx: event.transaction.hash,
  });
  const pairRow = await context.db.find(pair, { chainId, id: pairId });
  if (!pairRow) {
    logEvent(chainName, "Pair:Mint missing pairRow", { chainId, pair: pairId });
    return;
  }

  const token0 = await context.db.find(token, { chainId, id: pairRow.token0 });
  const token1 = await context.db.find(token, { chainId, id: pairRow.token1 });
  if (!token0 || !token1) {
    logEvent(chainName, "Pair:Mint missing token row", {
      chainId,
      pair: pairId,
      token0: pairRow.token0,
      token1: pairRow.token1,
    });
    return;
  }

  // ✅ 问题1：key 包含 pairId；✅ 问题3：pending 不存在则直接 return
  const key = txKey(chainId, event.transaction.hash, pairId);
  const pending = pendingMints.get(key) ?? [];
  const transferMint = pending.shift();
  if (!transferMint) {
    // ✅ 问题3：不 fallback，不记录错误，直接不处理
    logEvent(chainName, "Pair:Mint missing pending transfer", {
      chainId,
      pair: pairId,
      tx: event.transaction.hash,
    });
    return;
  }
  if (pending.length === 0) pendingMints.delete(key);
  else pendingMints.set(key, pending);

  const liquidity = transferMint.liquidity;
  const to = transferMint.to;

  const amount0Raw = event.args.amount0;
  const amount1Raw = event.args.amount1;
  const amount0Wad = scaleToWad(amount0Raw, token0.decimals);
  const amount1Wad = scaleToWad(amount1Raw, token1.decimals);

  const ethPriceCache = await getEthPriceCached(
    context.db,
    context.client,
    chainId,
    event.block.number,
    event.block.timestamp
  );

  const token0DerivedEth = parseWad(token0.derivedETH);
  const token1DerivedEth = parseWad(token1.derivedETH);

  const amountUsd = mulWad(
    mulWad(amount0Wad, token0DerivedEth).add(mulWad(amount1Wad, token1DerivedEth)),
    ethPriceCache.price
  );

  token0.txCount = token0.txCount + 1n;
  token1.txCount = token1.txCount + 1n;
  pairRow.txCount = pairRow.txCount + 1n;

  const factoryRow = await getOrCreateFactory(context.db, chainId);
  factoryRow.txCount = factoryRow.txCount + 1n;

  await upsert(context.db, token, token0);
  await upsert(context.db, token, token1);
  await upsert(context.db, pair, pairRow);
  await upsert(context.db, uniswapFactory, factoryRow);

  const logIndex = Number(event.log.logIndex ?? 0);
  const transactionIndex = Number(event.transaction.transactionIndex ?? 0);

  await upsert(context.db, mint, {
    chainId,
    id: makeEventId(event.transaction.hash, logIndex),
    timestamp: event.block.timestamp,
    blockNumber: event.block.number,
    blockTimestamp: event.block.timestamp,
    transactionHash: normalizeAddress(event.transaction.hash),
    transactionIndex,
    logIndex,
    pair: pairId,
    to,
    liquidity,
    sender: normalizeAddress(event.args.sender),
    amount0: amount0Raw,
    amount1: amount1Raw,
    amountUSD: formatWad(amountUsd),
    // ✅ 问题4：feeOff，不写 fee 字段
    feeTo: null,
    feeLiquidity: null,
  });

  logEvent(chainName, "Pair:Mint recorded", {
    chainId,
    pair: pairId,
    liquidity: liquidity.toString(),
    to,
    amountUSD: formatWad(amountUsd),
    block: event.block.number,
    tx: event.transaction.hash,
  });

  await updatePairDayData(context.db, chainId, event.block.timestamp, pairRow);
  await updatePairHourData(context.db, chainId, event.block.timestamp, pairRow);
  await updateUniswapDayData(context.db, chainId, event.block.timestamp, factoryRow);
  await updateTokenDayData(context.db, chainId, event.block.timestamp, token0, ethPriceCache.price);
  await updateTokenDayData(context.db, chainId, event.block.timestamp, token1, ethPriceCache.price);
});

ponder.on("Pair:Burn", async ({ event, context }) => {
  const chainId = context.chain.id;
  const chainName = context.chain.name;
  const pairId = normalizeAddress(event.log.address);
  logEvent(chainName, "Pair:Burn", {
    chainId,
    pair: pairId,
    sender: event.args.sender,
    amount0: event.args.amount0.toString(),
    amount1: event.args.amount1.toString(),
    to: event.args.to,
    block: event.block.number,
    tx: event.transaction.hash,
  });
  const pairRow = await context.db.find(pair, { chainId, id: pairId });
  if (!pairRow) {
    logEvent(chainName, "Pair:Burn missing pairRow", { chainId, pair: pairId });
    return;
  }

  const token0 = await context.db.find(token, { chainId, id: pairRow.token0 });
  const token1 = await context.db.find(token, { chainId, id: pairRow.token1 });
  if (!token0 || !token1) {
    logEvent(chainName, "Pair:Burn missing token row", {
      chainId,
      pair: pairId,
      token0: pairRow.token0,
      token1: pairRow.token1,
    });
    return;
  }

  // ✅ 问题1：key 包含 pairId
  const key = txKey(chainId, event.transaction.hash, pairId);
  const pendingBurnList = pendingBurns.get(key) ?? [];
  const pendingBurn = pendingBurnList.shift();

  if (!pendingBurn) {
    // 没有对应 transfer（上下文不完整）→ 不处理
    logEvent(chainName, "Pair:Burn missing pending transfer", {
      chainId,
      pair: pairId,
      tx: event.transaction.hash,
    });
    return;
  }
  if (pendingBurnList.length === 0) pendingBurns.delete(key);
  else pendingBurns.set(key, pendingBurnList);

  const liquidity = pendingBurn.liquidity;

  const amount0Raw = event.args.amount0;
  const amount1Raw = event.args.amount1;
  const amount0Wad = scaleToWad(amount0Raw, token0.decimals);
  const amount1Wad = scaleToWad(amount1Raw, token1.decimals);

  const ethPriceCache = await getEthPriceCached(
    context.db,
    context.client,
    chainId,
    event.block.number,
    event.block.timestamp
  );

  const token0DerivedEth = parseWad(token0.derivedETH);
  const token1DerivedEth = parseWad(token1.derivedETH);

  const amountUsd = mulWad(
    mulWad(amount0Wad, token0DerivedEth).add(mulWad(amount1Wad, token1DerivedEth)),
    ethPriceCache.price
  );

  token0.txCount = token0.txCount + 1n;
  token1.txCount = token1.txCount + 1n;
  pairRow.txCount = pairRow.txCount + 1n;

  const factoryRow = await getOrCreateFactory(context.db, chainId);
  factoryRow.txCount = factoryRow.txCount + 1n;

  await upsert(context.db, token, token0);
  await upsert(context.db, token, token1);
  await upsert(context.db, pair, pairRow);
  await upsert(context.db, uniswapFactory, factoryRow);

  const logIndex = Number(event.log.logIndex ?? 0);
  const transactionIndex = Number(event.transaction.transactionIndex ?? 0);

  await upsert(context.db, burn, {
    chainId,
    id: makeEventId(event.transaction.hash, logIndex),
    timestamp: event.block.timestamp,
    blockNumber: event.block.number,
    blockTimestamp: event.block.timestamp,
    transactionHash: normalizeAddress(event.transaction.hash),
    transactionIndex,
    logIndex,
    pair: pairId,
    liquidity,
    sender: normalizeAddress(event.args.sender),
    amount0: amount0Raw,
    amount1: amount1Raw,
    // ✅ 问题5：Burn.to 按链上语义 event.args.to
    to: normalizeAddress(event.args.to),
    amountUSD: formatWad(amountUsd),
    // ✅ 问题4：feeOff
    feeTo: null,
    feeLiquidity: null,
  });

  logEvent(chainName, "Pair:Burn recorded", {
    chainId,
    pair: pairId,
    liquidity: liquidity.toString(),
    to: normalizeAddress(event.args.to),
    amountUSD: formatWad(amountUsd),
    block: event.block.number,
    tx: event.transaction.hash,
  });

  await updatePairDayData(context.db, chainId, event.block.timestamp, pairRow);
  await updatePairHourData(context.db, chainId, event.block.timestamp, pairRow);
  await updateUniswapDayData(context.db, chainId, event.block.timestamp, factoryRow);
  await updateTokenDayData(context.db, chainId, event.block.timestamp, token0, ethPriceCache.price);
  await updateTokenDayData(context.db, chainId, event.block.timestamp, token1, ethPriceCache.price);
});

ponder.on("Pair:Swap", async ({ event, context }) => {
  const chainId = context.chain.id;
  const chainName = context.chain.name;
  const pairId = normalizeAddress(event.log.address);
  logEvent(chainName, "Pair:Swap", {
    chainId,
    pair: pairId,
    sender: event.args.sender,
    amount0In: event.args.amount0In.toString(),
    amount1In: event.args.amount1In.toString(),
    amount0Out: event.args.amount0Out.toString(),
    amount1Out: event.args.amount1Out.toString(),
    to: event.args.to,
    block: event.block.number,
    tx: event.transaction.hash,
  });
  const pairRow = await context.db.find(pair, { chainId, id: pairId });
  if (!pairRow) {
    logEvent(chainName, "Pair:Swap missing pairRow", { chainId, pair: pairId });
    return;
  }

  const token0 = await context.db.find(token, { chainId, id: pairRow.token0 });
  const token1 = await context.db.find(token, { chainId, id: pairRow.token1 });
  if (!token0 || !token1) {
    logEvent(chainName, "Pair:Swap missing token row", {
      chainId,
      pair: pairId,
      token0: pairRow.token0,
      token1: pairRow.token1,
    });
    return;
  }

  const amount0In = event.args.amount0In;
  const amount1In = event.args.amount1In;
  const amount0Out = event.args.amount0Out;
  const amount1Out = event.args.amount1Out;

  const amount0TotalRaw = amount0In + amount0Out;
  const amount1TotalRaw = amount1In + amount1Out;

  const amount0Wad = scaleToWad(amount0TotalRaw, token0.decimals);
  const amount1Wad = scaleToWad(amount1TotalRaw, token1.decimals);

  const ethPriceCache = await getEthPriceCached(
    context.db,
    context.client,
    chainId,
    event.block.number,
    event.block.timestamp
  );

  const token0DerivedEth = parseWad(token0.derivedETH);
  const token1DerivedEth = parseWad(token1.derivedETH);

  // pair-level derived (for Pair.untrackedVolumeUSD etc)
  const derivedAmountEth = calcDerivedAmountEth(token0DerivedEth, token1DerivedEth, amount0Wad, amount1Wad);
  const derivedAmountUsd_pairLevel = mulWad(derivedAmountEth, ethPriceCache.price);

  const trackedAmountUsd = getTrackedVolumeUSD(
    amount0Wad,
    token0,
    amount1Wad,
    token1,
    pairRow,
    ethPriceCache.price,
    token0.decimals,
    token1.decimals
  );
  const trackedAmountEth = divWad(trackedAmountUsd, ethPriceCache.price);

  // ✅ Token basic entity：方案B（与 subgraph 一致）
  token0.tradeVolume = token0.tradeVolume + amount0In + amount0Out;
  token1.tradeVolume = token1.tradeVolume + amount1In + amount1Out;
  token0.tradeVolumeUSD = addWadString(token0.tradeVolumeUSD, trackedAmountUsd);
  token1.tradeVolumeUSD = addWadString(token1.tradeVolumeUSD, trackedAmountUsd);
  token0.untrackedVolumeUSD = addWadString(token0.untrackedVolumeUSD, derivedAmountUsd_pairLevel);
  token1.untrackedVolumeUSD = addWadString(token1.untrackedVolumeUSD, derivedAmountUsd_pairLevel);

  token0.txCount = token0.txCount + 1n;
  token1.txCount = token1.txCount + 1n;

  // ✅ Pair：tracked/untracked 与 subgraph 一致
  pairRow.volumeUSD = addWadString(pairRow.volumeUSD, trackedAmountUsd);
  pairRow.volumeToken0 = pairRow.volumeToken0 + amount0TotalRaw;
  pairRow.volumeToken1 = pairRow.volumeToken1 + amount1TotalRaw;
  pairRow.untrackedVolumeUSD = addWadString(pairRow.untrackedVolumeUSD, derivedAmountUsd_pairLevel);
  pairRow.txCount = pairRow.txCount + 1n;

  const factoryRow = await getOrCreateFactory(context.db, chainId);
  factoryRow.totalVolumeUSD = addWadString(factoryRow.totalVolumeUSD, trackedAmountUsd);
  factoryRow.totalVolumeETH = addWadString(factoryRow.totalVolumeETH, trackedAmountEth);
  factoryRow.untrackedVolumeUSD = addWadString(factoryRow.untrackedVolumeUSD, derivedAmountUsd_pairLevel);
  factoryRow.txCount = factoryRow.txCount + 1n;

  await upsert(context.db, token, token0);
  await upsert(context.db, token, token1);
  await upsert(context.db, pair, pairRow);
  await upsert(context.db, uniswapFactory, factoryRow);

  // write swap row
  const logIndex = Number(event.log.logIndex ?? 0);
  const transactionIndex = Number(event.transaction.transactionIndex ?? 0);

  await upsert(context.db, swap, {
    chainId,
    id: makeEventId(event.transaction.hash, logIndex),
    timestamp: event.block.timestamp,
    blockNumber: event.block.number,
    blockTimestamp: event.block.timestamp,
    transactionHash: normalizeAddress(event.transaction.hash),
    transactionIndex,
    logIndex,
    pair: pairId,
    sender: normalizeAddress(event.args.sender),
    from: normalizeAddress(event.transaction.from),
    amount0In,
    amount1In,
    amount0Out,
    amount1Out,
    to: normalizeAddress(event.args.to),
    amountUSD: formatWad(trackedAmountUsd.isZero() ? derivedAmountUsd_pairLevel : trackedAmountUsd),
  });

  logEvent(chainName, "Pair:Swap recorded", {
    chainId,
    pair: pairId,
    trackedUSD: formatWad(trackedAmountUsd),
    untrackedUSD: formatWad(derivedAmountUsd_pairLevel),
    amount0TotalRaw: amount0TotalRaw.toString(),
    amount1TotalRaw: amount1TotalRaw.toString(),
    block: event.block.number,
    tx: event.transaction.hash,
  });

  // Day/Hour updates (same pattern as subgraph)
  const uniswapDay = await updateUniswapDayData(context.db, chainId, event.block.timestamp, factoryRow);
  uniswapDay.dailyVolumeUSD = addWadString(uniswapDay.dailyVolumeUSD, trackedAmountUsd);
  uniswapDay.dailyVolumeETH = addWadString(uniswapDay.dailyVolumeETH, trackedAmountEth);
  uniswapDay.dailyVolumeUntracked = addWadString(uniswapDay.dailyVolumeUntracked, derivedAmountUsd_pairLevel);
  await upsert(context.db, uniswapDayData, uniswapDay);

  const pairDay = await updatePairDayData(context.db, chainId, event.block.timestamp, pairRow);
  pairDay.dailyVolumeToken0 = pairDay.dailyVolumeToken0 + amount0TotalRaw;
  pairDay.dailyVolumeToken1 = pairDay.dailyVolumeToken1 + amount1TotalRaw;
  pairDay.dailyVolumeUSD = addWadString(pairDay.dailyVolumeUSD, trackedAmountUsd);
  await upsert(context.db, pairDayData, pairDay);

  const pairHour = await updatePairHourData(context.db, chainId, event.block.timestamp, pairRow);
  pairHour.hourlyVolumeToken0 = pairHour.hourlyVolumeToken0 + amount0TotalRaw;
  pairHour.hourlyVolumeToken1 = pairHour.hourlyVolumeToken1 + amount1TotalRaw;
  pairHour.hourlyVolumeUSD = addWadString(pairHour.hourlyVolumeUSD, trackedAmountUsd);
  await upsert(context.db, pairHourData, pairHour);

  // ✅ TokenDay：方案A（你本来就对齐了）
  const token0Day = await updateTokenDayData(context.db, chainId, event.block.timestamp, token0, ethPriceCache.price);
  const token1Day = await updateTokenDayData(context.db, chainId, event.block.timestamp, token1, ethPriceCache.price);

  const token0DerivedUsdDelta = mulWad(mulWad(amount0Wad, token0DerivedEth), ethPriceCache.price);
  const token1DerivedUsdDelta = mulWad(mulWad(amount1Wad, token1DerivedEth), ethPriceCache.price);

  token0Day.dailyVolumeToken = token0Day.dailyVolumeToken + amount0TotalRaw;
  token0Day.dailyVolumeETH = addWadString(token0Day.dailyVolumeETH, mulWad(amount0Wad, token0DerivedEth));
  token0Day.dailyVolumeUSD = addWadString(token0Day.dailyVolumeUSD, token0DerivedUsdDelta);
  await upsert(context.db, tokenDayData, token0Day);

  token1Day.dailyVolumeToken = token1Day.dailyVolumeToken + amount1TotalRaw;
  token1Day.dailyVolumeETH = addWadString(token1Day.dailyVolumeETH, mulWad(amount1Wad, token1DerivedEth));
  token1Day.dailyVolumeUSD = addWadString(token1Day.dailyVolumeUSD, token1DerivedUsdDelta);
  await upsert(context.db, tokenDayData, token1Day);

  // TokenHour/Minute：仅累加 volume（OHLC/TVL 由 Sync 负责）
  const isTracked = !trackedAmountUsd.isZero();
  await addTokenBucketSwapVolume(
    context.db,
    chainId,
    event.block.timestamp,
    token0,
    amount0TotalRaw,
    token0DerivedUsdDelta,
    isTracked,
    "hour"
  );
  await addTokenBucketSwapVolume(
    context.db,
    chainId,
    event.block.timestamp,
    token1,
    amount1TotalRaw,
    token1DerivedUsdDelta,
    isTracked,
    "hour"
  );
  await addTokenBucketSwapVolume(
    context.db,
    chainId,
    event.block.timestamp,
    token0,
    amount0TotalRaw,
    token0DerivedUsdDelta,
    isTracked,
    "minute"
  );
  await addTokenBucketSwapVolume(
    context.db,
    chainId,
    event.block.timestamp,
    token1,
    amount1TotalRaw,
    token1DerivedUsdDelta,
    isTracked,
    "minute"
  );
});


//  pnpm start > ponder.log 2>&1
//  DROP SCHEMA IF EXISTS ponder_sync CASCADE;
// http://127.0.0.1:${PORT|42069}/metrics