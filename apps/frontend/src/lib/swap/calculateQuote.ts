// Swap Quote 计算逻辑 - 纯前端实现
// 基于 specs/4.1-SWAP.md Story 1

import { formatUnits, parseUnits } from 'viem';
import type { PublicClient } from 'viem';
import { getChainConfig, getPairAddress, getTokenConfig, getIntermediaryTokens } from '@/contracts';

// Uniswap V2 Pair ABI
const PAIR_ABI = [
  {
    inputs: [],
    name: 'getReserves',
    outputs: [
      { internalType: 'uint112', name: '_reserve0', type: 'uint112' },
      { internalType: 'uint112', name: '_reserve1', type: 'uint112' },
      { internalType: 'uint32', name: '_blockTimestampLast', type: 'uint32' },
    ],
    stateMutability: 'view',
    type: 'function',
  },
  {
    inputs: [],
    name: 'token0',
    outputs: [{ internalType: 'address', name: '', type: 'address' }],
    stateMutability: 'view',
    type: 'function',
  },
  {
    inputs: [],
    name: 'token1',
    outputs: [{ internalType: 'address', name: '', type: 'address' }],
    stateMutability: 'view',
    type: 'function',
  },
] as const;

// ChainlinkOracle ABI
const ORACLE_ABI = [
  {
    inputs: [{ internalType: 'address', name: 'token', type: 'address' }],
    name: 'getUSDPrice',
    outputs: [
      { internalType: 'uint256', name: 'pxE18', type: 'uint256' },
      { internalType: 'uint256', name: 'updatedAt', type: 'uint256' },
    ],
    stateMutability: 'view',
    type: 'function',
  },
] as const;

// Uniswap V2 Router ABI (getAmountsOut)
const ROUTER_ABI = [
  {
    inputs: [
      { internalType: 'uint256', name: 'amountIn', type: 'uint256' },
      { internalType: 'address[]', name: 'path', type: 'address[]' },
    ],
    name: 'getAmountsOut',
    outputs: [{ internalType: 'uint256[]', name: 'amounts', type: 'uint256[]' }],
    stateMutability: 'view',
    type: 'function',
  },
] as const;

export interface QuoteParams {
  chainId: number;
  tokenIn: `0x${string}`;
  tokenOut: `0x${string}`;
  amountIn: string;
  slippageBps?: number;
}

export interface QuoteResult {
  amountOut: string;
  amountOutFormatted: string;
  priceImpactBps: number;
  feeBps: number;
  feeAmount: string;
  feeUsd?: string;
  maxReceived: string;
  minReceived: string;
  slippageBps: number;
  route: string; // e.g., 'Direct' or 'via vETH'
  routePath: `0x${string}`[]; // Full address path for execution
  pair: {
    address: string; // First hop pair address
    reserve0: string;
    reserve1: string;
    token0: string;
    token1: string;
  };
}

// Logger helper
const Logger = {
  info: (step: string, data?: any) => {
    const time = new Date().toLocaleTimeString();
    console.log(`%c[${time}] [INFO] ${step}`, 'color: #3b82f6; font-weight: bold', data || '');
  },
  warn: (step: string, data?: any) => {
    const time = new Date().toLocaleTimeString();
    console.warn(`%c[${time}] [WARN] ${step}`, 'color: #f59e0b; font-weight: bold', data || '');
  },
  success: (step: string, data?: any) => {
    const time = new Date().toLocaleTimeString();
    console.log(`%c[${time}] [SUCCESS] ${step}`, 'color: #10b981; font-weight: bold', data || '');
  },
  start: (action: string) => {
    const time = new Date().toLocaleTimeString();
    console.group(`%c[${time}] 🚀 ${action}`, 'color: #8b5cf6; font-weight: bold; font-size: 11px');
  },
  end: () => {
    console.groupEnd();
  },
};

export async function calculateQuote(
  publicClient: PublicClient,
  params: QuoteParams
): Promise<QuoteResult> {
  const startTime = performance.now();
  Logger.start(`Starting Quote Calculation`);

  const { chainId, tokenIn, tokenOut, amountIn, slippageBps = 50 } = params;

  try {
    Logger.info(`Step 1: Config & Params`, { chainId, tokenIn, tokenOut, amountIn, slippageBps });

    const chainConfig = getChainConfig(chainId);
    if (!chainConfig) throw new Error(`Chain ${chainId} not supported`);

    const tokenInConfig = getTokenConfig(chainId, tokenIn);
    const tokenOutConfig = getTokenConfig(chainId, tokenOut);
    if (!tokenInConfig || !tokenOutConfig) throw new Error('Token not found');

    const amountInBigInt = parseUnits(amountIn, tokenInConfig.decimals);
    if (amountInBigInt === 0n) throw new Error('Amount must be greater than 0');

    // --- 1. 尝试直连 (Fast Path) ---
    const directPairAddress = getPairAddress(chainId, tokenIn, tokenOut);
    if (directPairAddress) {
      const path = [tokenIn, tokenOut];
      Logger.info(`Checking Direct Path`, { path });

      try {
        // 并行获取所有数据 (1 RTT)
        const [amounts, reservesResult, token0Result, oraclePriceResult] = await Promise.all([
          // 1. getAmountsOut
          publicClient.readContract({
            address: chainConfig.router,
            abi: ROUTER_ABI,
            functionName: 'getAmountsOut',
            args: [amountInBigInt, path],
          }),
          // 2. getReserves
          publicClient.readContract({
            address: directPairAddress as `0x${string}`,
            abi: PAIR_ABI,
            functionName: 'getReserves',
          }),
          // 3. token0
          publicClient.readContract({
            address: directPairAddress as `0x${string}`,
            abi: PAIR_ABI,
            functionName: 'token0',
          }),
          // 4. Oracle (Optional)
          publicClient
            .readContract({
              address: chainConfig.chainlinkOracle,
              abi: ORACLE_ABI,
              functionName: 'getUSDPrice',
              args: [tokenIn],
            })
            .catch(() => [0n, 0n] as const),
        ]);

        // 如果到这里没有报错，说明直连成功且有流动性
        const bestAmountOut = amounts[amounts.length - 1];
        const [reserve0, reserve1] = reservesResult;
        const token0 = token0Result;

        Logger.info(`Direct Path Found & Data Fetched`, {
          amountOut: formatUnits(bestAmountOut, tokenOutConfig.decimals),
        });

        // --- 计算 Direct 的 Impact & Fee (Inline) ---

        const isToken0 = token0.toLowerCase() === tokenIn.toLowerCase();
        const reserveIn = isToken0 ? reserve0 : reserve1;
        const reserveOut = isToken0 ? reserve1 : reserve0;

        // Mid Price
        const E18 = 10n ** 18n;
        const decIn = BigInt(tokenInConfig.decimals);
        const decOut = BigInt(tokenOutConfig.decimals);

        if (reserveIn === 0n) throw new Error('Reserve is zero');

        const midPriceNumerator = BigInt(reserveOut) * 10n ** decIn * E18;
        const midPriceDenominator = BigInt(reserveIn) * 10n ** decOut;
        const midPriceE18 = midPriceNumerator / midPriceDenominator;

        // Exec Price
        const execPriceNumerator = bestAmountOut * 10n ** decIn * E18;
        const execPriceDenominator = amountInBigInt * 10n ** decOut;
        const execPriceE18 = execPriceNumerator / execPriceDenominator;

        // Impact
        const priceImpactE18 = ((execPriceE18 - midPriceE18) * E18) / midPriceE18;
        const priceImpactBps = Number((priceImpactE18 * 10000n) / E18);

        // Fee
        const feeBps = 30;
        const feeAmount = (amountInBigInt * 3n) / 1000n;
        let feeUsd: string | undefined;
        const tokenInPriceE18 = oraclePriceResult[0];

        if (tokenInPriceE18 > 0n) {
          const feeUsdBigInt = (feeAmount * tokenInPriceE18) / 10n ** decIn;
          feeUsd = formatUnits(feeUsdBigInt, 18);
        }

        const result: QuoteResult = {
          amountOut: bestAmountOut.toString(),
          amountOutFormatted: formatUnits(bestAmountOut, tokenOutConfig.decimals),
          priceImpactBps,
          feeBps,
          feeAmount: feeAmount.toString(),
          feeUsd,
          maxReceived: bestAmountOut.toString(),
          minReceived: ((bestAmountOut * BigInt(10000 - slippageBps)) / 10000n).toString(),
          slippageBps,
          route: 'Direct',
          routePath: path,
          pair: {
            address: directPairAddress,
            reserve0: reserve0.toString(),
            reserve1: reserve1.toString(),
            token0: token0,
            token1: isToken0 ? tokenOut : tokenIn,
          },
        };

        const duration = Math.round(performance.now() - startTime);
        Logger.success(`Quote Calculation (Direct Fast Path) Completed in ${duration}ms`, result);
        Logger.end();
        return result;
      } catch (e) {
        Logger.info('Direct path failed or no liquidity, falling back to multi-hop', e);
      }
    }

    // --- 2. 多跳 (Fallback) ---

    let bestPath: `0x${string}`[] | undefined;
    let bestAmountOut = 0n;

    const bases = getIntermediaryTokens(chainId);
    const promises = bases.map(async (baseSymbol) => {
      const baseToken = Object.values(chainConfig.tokens).find((t) => t.symbol === baseSymbol);
      if (!baseToken || baseToken.address === tokenIn || baseToken.address === tokenOut)
        return null;

      // 检查两段 Pair 是否存在
      if (
        !getPairAddress(chainId, tokenIn, baseToken.address) ||
        !getPairAddress(chainId, baseToken.address, tokenOut)
      )
        return null;

      const path = [tokenIn, baseToken.address, tokenOut];
      try {
        const amounts = await publicClient.readContract({
          address: chainConfig.router,
          abi: ROUTER_ABI,
          functionName: 'getAmountsOut',
          args: [amountInBigInt, path],
        });
        return { path, amountOut: amounts[amounts.length - 1], via: baseSymbol };
      } catch {
        return null;
      }
    });

    const results = await Promise.all(promises);
    const validResults = results.filter((r) => r !== null) as {
      path: `0x${string}`[];
      amountOut: bigint;
      via: string;
    }[];

    if (validResults.length > 0) {
      // 找出 AmountOut 最大的路径
      const best = validResults.reduce((prev, current) =>
        current.amountOut > prev.amountOut ? current : prev
      );
      bestPath = best.path;
      bestAmountOut = best.amountOut;
      Logger.info(`Multi-hop path found via ${best.via}`, {
        path: best.path,
        amountOut: formatUnits(bestAmountOut, tokenOutConfig.decimals),
      });
    }

    if (!bestPath || bestAmountOut === 0n) {
      throw new Error('No route found');
    }

    // --- 详细数据获取 & Price Impact 计算 (For Multi-hop) ---
    // 我们需要获取路径上每一跳的 Reserves 来计算 Price Impact

    const E18 = 10n ** 18n;
    let cumulativeMidPriceE18 = E18; // 初始为 1.0 (18 decimals)
    let firstHopPairAddress: string | undefined;
    let firstHopReserve0: string | undefined;
    let firstHopReserve1: string | undefined;
    let firstHopToken0: string | undefined;
    let firstHopToken1: string | undefined;

    Logger.info(`Fetching reserves for path`, bestPath);

    for (let i = 0; i < bestPath.length - 1; i++) {
      const tIn = bestPath[i];
      const tOut = bestPath[i + 1];
      const tInConfig = getTokenConfig(chainId, tIn)!;
      const tOutConfig = getTokenConfig(chainId, tOut)!;

      const pairAddr = getPairAddress(chainId, tIn, tOut)! as `0x${string}`;

      const [reserves, token0] = await Promise.all([
        publicClient.readContract({
          address: pairAddr,
          abi: PAIR_ABI,
          functionName: 'getReserves',
        }),
        publicClient.readContract({
          address: pairAddr,
          abi: PAIR_ABI,
          functionName: 'token0',
        }),
      ]);

      const [reserve0, reserve1] = reserves;
      const isToken0 = token0.toLowerCase() === tIn.toLowerCase();
      const reserveIn = isToken0 ? reserve0 : reserve1;
      const reserveOut = isToken0 ? reserve1 : reserve0;

      if (i === 0) {
        firstHopPairAddress = pairAddr;
        firstHopReserve0 = reserve0.toString();
        firstHopReserve1 = reserve1.toString();
        firstHopToken0 = token0;
        firstHopToken1 = isToken0 ? tOut : tIn;
      }

      // Mid Price
      const decIn = BigInt(tInConfig.decimals);
      const decOut = BigInt(tOutConfig.decimals);

      if (reserveIn === 0n)
        throw new Error(`Reserve is zero for ${tInConfig.symbol}->${tOutConfig.symbol}`);

      const hopMidPriceNumerator = BigInt(reserveOut) * 10n ** decIn * E18;
      const hopMidPriceDenominator = BigInt(reserveIn) * 10n ** decOut;
      const hopMidPriceE18 = hopMidPriceNumerator / hopMidPriceDenominator;

      // 累乘: TotalPrice = TotalPrice * HopPrice / 10^18
      cumulativeMidPriceE18 = (cumulativeMidPriceE18 * hopMidPriceE18) / E18;
    }

    // Exec Price
    const totalDecIn = BigInt(tokenInConfig.decimals);
    const totalDecOut = BigInt(tokenOutConfig.decimals);

    const execPriceNumerator = bestAmountOut * 10n ** totalDecIn * E18;
    const execPriceDenominator = amountInBigInt * 10n ** totalDecOut;
    const execPriceE18 = execPriceNumerator / execPriceDenominator;

    // Impact
    const priceImpactE18 = ((execPriceE18 - cumulativeMidPriceE18) * E18) / cumulativeMidPriceE18;
    const priceImpactBps = Number((priceImpactE18 * 10000n) / E18);

    Logger.info(`Price Impact Calc (Multi-hop)`, {
      midPrice: formatUnits(cumulativeMidPriceE18, 18),
      execPrice: formatUnits(execPriceE18, 18),
      impactBps: priceImpactBps,
    });

    // Fee (Estimate 0.3% * hops)
    const feeBps = 30 * (bestPath.length - 1);

    // Oracle Price for USD Fee (Optional)
    let feeUsd: string | undefined;
    const feeAmount = (amountInBigInt * BigInt(feeBps)) / 10000n; // Approximate fee amount in Input Token
    try {
      const [px] = await publicClient.readContract({
        address: chainConfig.chainlinkOracle,
        abi: ORACLE_ABI,
        functionName: 'getUSDPrice',
        args: [tokenIn],
      });
      if (px > 0n) {
        const feeUsdBigInt = (feeAmount * px) / 10n ** totalDecIn;
        feeUsd = formatUnits(feeUsdBigInt, 18);
      }
    } catch (error) {
      Logger.warn('Failed to fetch oracle price for fee estimation', error);
    }

    const amountOutFormatted = formatUnits(bestAmountOut, tokenOutConfig.decimals);
    const minReceived = (bestAmountOut * BigInt(10000 - slippageBps)) / 10000n;

    const routeLabel =
      bestPath.length === 2
        ? 'Direct'
        : `via ${getTokenConfig(chainId, bestPath[1])?.symbol || 'Unknown'}`;

    const result: QuoteResult = {
      amountOut: bestAmountOut.toString(),
      amountOutFormatted,
      priceImpactBps,
      feeBps,
      feeAmount: feeAmount.toString(),
      feeUsd,
      maxReceived: bestAmountOut.toString(),
      minReceived: minReceived.toString(),
      slippageBps,
      route: routeLabel,
      routePath: bestPath,
      pair: {
        address: firstHopPairAddress!,
        reserve0: firstHopReserve0!,
        reserve1: firstHopReserve1!,
        token0: firstHopToken0!,
        token1: firstHopToken1!,
      },
    };

    const duration = Math.round(performance.now() - startTime);
    Logger.success(`Quote Calculation Completed in ${duration}ms`, result);
    Logger.end();

    return result;
  } catch (error) {
    console.error('Quote calculation failed', error);
    Logger.end();
    throw error;
  }
}
