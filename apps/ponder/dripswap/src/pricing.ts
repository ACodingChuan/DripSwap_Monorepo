import Decimal from "decimal.js";
import { encodeFunctionData, toHex } from "viem";
import { OracleAbi } from "../abis/OracleAbi";
import {
  pair as pairTable,
  pairTokenLookup as pairTokenLookupTable,
  token as tokenTable,
} from "ponder:schema";
import {
  MINIMUM_LIQUIDITY_THRESHOLD_ETH,
  MINIMUM_USD_THRESHOLD_NEW_PAIRS,
  ORACLE_ETH_USD_SCROLL,
  ORACLE_ETH_USD_SEPOLIA,
  REFERENCE_TOKEN,
  STABLECOINS,
  WHITELIST,
} from "./constants";
import { divWad, mulWad, parseWad, scaleToWad } from "./fixedPoint";

const ALMOST_ZERO = new Decimal("0.000001");
const MINIMUM_USD_THRESHOLD_NEW_PAIRS_DECIMAL = new Decimal(
  MINIMUM_USD_THRESHOLD_NEW_PAIRS
);
const MINIMUM_LIQUIDITY_THRESHOLD_ETH_DECIMAL = new Decimal(
  MINIMUM_LIQUIDITY_THRESHOLD_ETH
);
const DECIMAL_ONE = new Decimal(1);
const ORACLE_DECIMALS_CACHE = new Map<string, number>();

function logOracleCall(params: {
  chainId: number;
  address: string;
  functionName: "latestRoundData" | "decimals";
  blockNumber?: bigint;
}) {
  const data = encodeFunctionData({
    abi: OracleAbi,
    functionName: params.functionName,
  });
  const blockTag = params.blockNumber ? toHex(params.blockNumber) : "latest";
  console.info("[ponder][oracle] eth_call", {
    chainId: params.chainId,
    address: params.address,
    functionName: params.functionName,
    blockTag,
    data,
    params: [{ to: params.address, data }, blockTag],
  });
}

function normalizeAddress(address: string): string {
  return address.toLowerCase();
}

export function getOracleAddress(chainId: number): string | null {
  if (chainId === 11155111) return ORACLE_ETH_USD_SEPOLIA;
  if (chainId === 534351) return ORACLE_ETH_USD_SCROLL;
  return null;
}

/**
 * ✅ 问题7：显式按 blockNumber 读取历史 oracle 状态
 */
export async function getEthPriceInUSD(
  client: any,
  chainId: number,
  blockNumber?: bigint
): Promise<{ price: Decimal; roundId: bigint; updatedAt: bigint }> {
  const oracleAddress = getOracleAddress(chainId);
  if (!oracleAddress) return { price: new Decimal(0), roundId: 0n, updatedAt: 0n };

  try {
    logOracleCall({
      chainId,
      address: oracleAddress,
      functionName: "latestRoundData",
      blockNumber,
    });
    const roundData = await client.readContract({
      address: oracleAddress,
      abi: OracleAbi,
      functionName: "latestRoundData",
      ...(blockNumber ? { blockNumber } : {}),
    });
    let decimalsValue = ORACLE_DECIMALS_CACHE.get(oracleAddress);
    if (decimalsValue === undefined) {
      logOracleCall({
        chainId,
        address: oracleAddress,
        functionName: "decimals",
        blockNumber,
      });
      const decimalsResult = await client.readContract({
        address: oracleAddress,
        abi: OracleAbi,
        functionName: "decimals",
      });
      decimalsValue = Number(decimalsResult);
      ORACLE_DECIMALS_CACHE.set(oracleAddress, decimalsValue);
    }

    const roundId = roundData[0] as bigint;
    const answer = roundData[1] as bigint;
    const updatedAt = roundData[3] as bigint;
    let decimals = Number(decimalsValue);
    if (decimals === 0) decimals = 8;
    if (answer <= 0n) return { price: new Decimal(0), roundId: 0n, updatedAt: 0n };

    const scale = new Decimal(10).pow(decimals);
    const price = new Decimal(answer.toString()).div(scale);
    return { price, roundId, updatedAt };
  } catch {
    return { price: new Decimal(0), roundId: 0n, updatedAt: 0n };
  }
}

export function getTrackedVolumeUSD(
  tokenAmount0: Decimal,
  token0: { id: string; derivedETH: string },
  tokenAmount1: Decimal,
  token1: { id: string; derivedETH: string },
  pairRow: { reserve0: bigint; reserve1: bigint; liquidityProviderCount: bigint },
  ethPrice: Decimal,
  decimals0: bigint,
  decimals1: bigint
): Decimal {
  const token0Id = normalizeAddress(token0.id);
  const token1Id = normalizeAddress(token1.id);
  const price0 = mulWad(parseWad(token0.derivedETH), ethPrice);
  const price1 = mulWad(parseWad(token1.derivedETH), ethPrice);

  if (pairRow.liquidityProviderCount < 5n) {
    const reserve0Dec = scaleToWad(pairRow.reserve0, decimals0);
    const reserve1Dec = scaleToWad(pairRow.reserve1, decimals1);
    const reserve0USD = mulWad(reserve0Dec, price0);
    const reserve1USD = mulWad(reserve1Dec, price1);

    if (WHITELIST.includes(token0Id) && WHITELIST.includes(token1Id)) {
      if (reserve0USD.add(reserve1USD).lt(MINIMUM_USD_THRESHOLD_NEW_PAIRS_DECIMAL)) {
        return new Decimal(0);
      }
    }
    if (WHITELIST.includes(token0Id) && !WHITELIST.includes(token1Id)) {
      if (reserve0USD.mul(2).lt(MINIMUM_USD_THRESHOLD_NEW_PAIRS_DECIMAL)) {
        return new Decimal(0);
      }
    }
    if (!WHITELIST.includes(token0Id) && WHITELIST.includes(token1Id)) {
      if (reserve1USD.mul(2).lt(MINIMUM_USD_THRESHOLD_NEW_PAIRS_DECIMAL)) {
        return new Decimal(0);
      }
    }
  }

  if (WHITELIST.includes(token0Id) && WHITELIST.includes(token1Id)) {
    return mulWad(tokenAmount0, price0)
      .add(mulWad(tokenAmount1, price1))
      .div(2);
  }
  if (WHITELIST.includes(token0Id) && !WHITELIST.includes(token1Id)) {
    return mulWad(tokenAmount0, price0);
  }
  if (!WHITELIST.includes(token0Id) && WHITELIST.includes(token1Id)) {
    return mulWad(tokenAmount1, price1);
  }
  return new Decimal(0);
}

export function getTrackedLiquidityUSD(
  tokenAmount0: Decimal,
  token0: { id: string; derivedETH: string },
  tokenAmount1: Decimal,
  token1: { id: string; derivedETH: string },
  ethPrice: Decimal
): Decimal {
  const token0Id = normalizeAddress(token0.id);
  const token1Id = normalizeAddress(token1.id);
  const price0 = mulWad(parseWad(token0.derivedETH), ethPrice);
  const price1 = mulWad(parseWad(token1.derivedETH), ethPrice);

  if (WHITELIST.includes(token0Id) && WHITELIST.includes(token1Id)) {
    return mulWad(tokenAmount0, price0).add(mulWad(tokenAmount1, price1));
  }
  if (WHITELIST.includes(token0Id) && !WHITELIST.includes(token1Id)) {
    return mulWad(tokenAmount0, price0).mul(2);
  }
  if (!WHITELIST.includes(token0Id) && WHITELIST.includes(token1Id)) {
    return mulWad(tokenAmount1, price1).mul(2);
  }
  return new Decimal(0);
}

export async function findEthPerToken(
  db: any,
  chainId: number,
  tokenRow: { id: string },
  ethPrice: Decimal
): Promise<Decimal> {
  const tokenId = normalizeAddress(tokenRow.id);
  if (tokenId === REFERENCE_TOKEN) return DECIMAL_ONE;
  if (STABLECOINS.includes(tokenId)) return divWad(DECIMAL_ONE, ethPrice);

  for (const whitelistToken of WHITELIST) {
    const lookupId = `${tokenId}-${normalizeAddress(whitelistToken)}`;
    const lookup = await db.find(pairTokenLookupTable, { chainId, id: lookupId });
    if (!lookup) continue;

    const pairRow = await db.find(pairTable, { chainId, id: normalizeAddress(lookup.pair) });
    if (!pairRow) continue;

    const reserveEth = parseWad(pairRow.reserveETH);
    if (reserveEth.lte(MINIMUM_LIQUIDITY_THRESHOLD_ETH_DECIMAL)) continue;

    if (pairRow.token0 === tokenId && WHITELIST.includes(normalizeAddress(pairRow.token1))) {
      const token1 = await db.find(tokenTable, { chainId, id: pairRow.token1 });
      if (token1) return mulWad(parseWad(pairRow.token1Price), parseWad(token1.derivedETH));
    }
    if (pairRow.token1 === tokenId && WHITELIST.includes(normalizeAddress(pairRow.token0))) {
      const token0 = await db.find(tokenTable, { chainId, id: pairRow.token0 });
      if (token0) return mulWad(parseWad(pairRow.token0Price), parseWad(token0.derivedETH));
    }
  }

  return new Decimal(0);
}

export function calcDerivedAmountEth(
  token0DerivedEth: Decimal,
  token1DerivedEth: Decimal,
  amount0: Decimal,
  amount1: Decimal
): Decimal {
  const derived0 = mulWad(token0DerivedEth, amount0);
  const derived1 = mulWad(token1DerivedEth, amount1);
  if (derived0.lte(ALMOST_ZERO) || derived1.lte(ALMOST_ZERO)) return derived0.add(derived1);
  return derived0.add(derived1).div(2);
}
