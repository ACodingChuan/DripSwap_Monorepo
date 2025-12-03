// React Hook for Swap Quote calculation
// 集成 calculateQuote 到 React 组件

import { useState, useEffect, useCallback, useRef } from 'react';
import { usePublicClient, useChainId } from 'wagmi';
import { calculateQuote, type QuoteParams, type QuoteResult } from './calculateQuote';

export interface UseSwapQuoteParams {
  tokenIn?: `0x${string}`;
  tokenOut?: `0x${string}`;
  amountIn?: string;
  slippageBps?: number;
  debounceMs?: number; // 报价计算的防抖时间
}

export interface UseSwapQuoteResult {
  quote: QuoteResult | null;
  isLoading: boolean;
  error: Error | null;
  refetch: () => void;
}

/**
 * React Hook for Swap Quote calculation
 * 自动防抖、自动重试、支持多链
 */
export function useSwapQuote(params: UseSwapQuoteParams): UseSwapQuoteResult {
  const { tokenIn, tokenOut, amountIn, slippageBps, debounceMs = 300 } = params;

  const publicClient = usePublicClient();
  const chainId = useChainId();

  const [quote, setQuote] = useState<QuoteResult | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  // 使用 ref 来跟踪上次查询的参数，避免重复查询
  const lastParamsRef = useRef<string>('');

  const fetchQuote = useCallback(async () => {
    // 验证参数
    if (!publicClient || !tokenIn || !tokenOut || !amountIn || Number(amountIn) <= 0) {
      setQuote(null);
      setError(null);
      return;
    }

    // 生成参数指纹，避免相同参数重复查询
    const paramsKey = `${chainId}-${tokenIn}-${tokenOut}-${amountIn}-${slippageBps}`;
    if (lastParamsRef.current === paramsKey) {
      console.log('Same params, skipping query');
      return;
    }

    lastParamsRef.current = paramsKey;
    setIsLoading(true);
    setError(null);

    try {
      const quoteParams: QuoteParams = {
        chainId,
        tokenIn,
        tokenOut,
        amountIn,
        slippageBps,
      };

      console.log('Fetching quote with params:', quoteParams);
      const result = await calculateQuote(publicClient, quoteParams);
      console.log('Quote result:', result);

      setQuote(result);
      setError(null);
    } catch (err) {
      console.error('Quote error:', err);
      const error = err instanceof Error ? err : new Error('Failed to calculate quote');
      setError(error);
      setQuote(null);
    } finally {
      setIsLoading(false);
    }
  }, [publicClient, chainId, tokenIn, tokenOut, amountIn, slippageBps]);

  // Quote 防抖效果
  useEffect(() => {
    const timer = setTimeout(() => {
      fetchQuote();
    }, debounceMs);

    return () => {
      clearTimeout(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tokenIn, tokenOut, amountIn, slippageBps, debounceMs]);

  // 当链或钱包地址变化时，清空缓存
  useEffect(() => {
    lastParamsRef.current = '';
    setQuote(null);
  }, [chainId]);

  return {
    quote,
    isLoading,
    error,
    refetch: fetchQuote,
  };
}
