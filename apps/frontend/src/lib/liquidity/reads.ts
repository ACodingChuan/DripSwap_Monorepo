import type { PublicClient } from 'viem';

import {
  ERC20_ABI,
  UNISWAP_V2_FACTORY_ABI,
  UNISWAP_V2_PAIR_ABI,
} from './abis';

export async function readPairAddress(
  publicClient: PublicClient,
  factory: `0x${string}`,
  tokenA: `0x${string}`,
  tokenB: `0x${string}`
): Promise<`0x${string}`> {
  return publicClient.readContract({
    address: factory,
    abi: UNISWAP_V2_FACTORY_ABI,
    functionName: 'getPair',
    args: [tokenA, tokenB],
  }) as Promise<`0x${string}`>;
}

export async function readPairTokens(
  publicClient: PublicClient,
  pair: `0x${string}`
): Promise<{ token0: `0x${string}`; token1: `0x${string}` }> {
  const [token0, token1] = await Promise.all([
    publicClient.readContract({ address: pair, abi: UNISWAP_V2_PAIR_ABI, functionName: 'token0' }),
    publicClient.readContract({ address: pair, abi: UNISWAP_V2_PAIR_ABI, functionName: 'token1' }),
  ]);
  return { token0: token0 as `0x${string}`, token1: token1 as `0x${string}` };
}

export async function readReserves(
  publicClient: PublicClient,
  pair: `0x${string}`
): Promise<{ reserve0: bigint; reserve1: bigint; blockTimestampLast: number }> {
  const [reserve0, reserve1, blockTimestampLast] = (await publicClient.readContract({
    address: pair,
    abi: UNISWAP_V2_PAIR_ABI,
    functionName: 'getReserves',
  })) as readonly [bigint, bigint, number];
  return { reserve0, reserve1, blockTimestampLast };
}

export async function readTotalSupply(
  publicClient: PublicClient,
  pair: `0x${string}`
): Promise<bigint> {
  return publicClient.readContract({
    address: pair,
    abi: UNISWAP_V2_PAIR_ABI,
    functionName: 'totalSupply',
  }) as Promise<bigint>;
}

export async function readErc20Decimals(
  publicClient: PublicClient,
  token: `0x${string}`
): Promise<number> {
  return publicClient.readContract({
    address: token,
    abi: ERC20_ABI,
    functionName: 'decimals',
  }) as Promise<number>;
}

export async function readErc20Symbol(
  publicClient: PublicClient,
  token: `0x${string}`
): Promise<string> {
  return publicClient.readContract({
    address: token,
    abi: ERC20_ABI,
    functionName: 'symbol',
  }) as Promise<string>;
}

export async function readErc20Balance(
  publicClient: PublicClient,
  token: `0x${string}`,
  owner: `0x${string}`
): Promise<bigint> {
  return publicClient.readContract({
    address: token,
    abi: ERC20_ABI,
    functionName: 'balanceOf',
    args: [owner],
  }) as Promise<bigint>;
}

export async function readErc20Allowance(
  publicClient: PublicClient,
  token: `0x${string}`,
  owner: `0x${string}`,
  spender: `0x${string}`
): Promise<bigint> {
  return publicClient.readContract({
    address: token,
    abi: ERC20_ABI,
    functionName: 'allowance',
    args: [owner, spender],
  }) as Promise<bigint>;
}
