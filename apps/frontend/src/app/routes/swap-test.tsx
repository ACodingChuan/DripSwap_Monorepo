// Swap Quote 测试页面 - 验证 calculateQuote 功能

import { createRoute } from '@tanstack/react-router';
import { useState, useEffect } from 'react';
import { useChainId, useAccount, useBalance } from 'wagmi';
import { parseUnits } from 'viem';
import { rootRoute } from './root';
import { getAllTokens } from '@/contracts';
import { useSwapQuote } from '@/lib/swap/useSwapQuote';
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  Button,
  Input,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/ui';
import { cn } from '@/shared/utils';

export const swapTestRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/swap-test',
  component: SwapTestPage,
});

function SwapTestPage() {
  const chainId = useChainId();
  const { address: userAddress } = useAccount();
  const tokens = getAllTokens(chainId);

  const [tokenIn, setTokenIn] = useState<`0x${string}`>(tokens[0]?.address as `0x${string}`);
  const [tokenOut, setTokenOut] = useState<`0x${string}`>(tokens[1]?.address as `0x${string}`);
  const [amountIn, setAmountIn] = useState('1');
  const [isBalanceError, setIsBalanceError] = useState(false);

  // 获取余额
  const { data: balance } = useBalance({
    address: userAddress,
    token: tokenIn,
  });

  // 余额校验
  useEffect(() => {
    if (!balance || !amountIn) {
      setIsBalanceError(false);
      return;
    }
    try {
      const tokenConfig = tokens.find((t) => t.address === tokenIn);
      const decimals = tokenConfig?.decimals || 18;
      const amountInBigInt = parseUnits(amountIn, decimals);
      if (balance.value < amountInBigInt) {
        setIsBalanceError(true);
      } else {
        setIsBalanceError(false);
      }
    } catch {
      setIsBalanceError(false);
    }
  }, [amountIn, balance, tokenIn, tokens]);

  const { quote, isLoading, error } = useSwapQuote({
    tokenIn,
    tokenOut,
    amountIn,
    debounceMs: 0, // 改回 0ms
  });

  return (
    <div className="container mx-auto p-4 max-w-2xl">
      <Card>
        <CardHeader>
          <CardTitle>Swap Quote Test (Chain ID: {chainId})</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {/* Token In */}
          <div>
            <label className="block text-sm font-medium mb-2">Token In</label>
            <Select value={tokenIn} onValueChange={(v) => setTokenIn(v as `0x${string}`)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {tokens.map((token) => (
                  <SelectItem key={token.address} value={token.address}>
                    {token.symbol} ({token.decimals} decimals)
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
            {balance && (
              <div className="text-xs text-muted-foreground mt-1">
                Balance: {balance.formatted} {balance.symbol}
              </div>
            )}
          </div>

          {/* Amount In */}
          <div>
            <label className="block text-sm font-medium mb-2">Amount In</label>
            <div className="relative">
              <Input
                type="number"
                value={amountIn}
                onChange={(e) => setAmountIn(e.target.value)}
                placeholder="Enter amount"
                className={cn(
                  isBalanceError && 'bg-red-50 border-red-500 focus-visible:ring-red-500'
                )}
              />
              {isBalanceError && (
                <div className="absolute right-3 top-1/2 -translate-y-1/2 text-xs font-medium text-red-600">
                  Exceeds Balance
                </div>
              )}
            </div>
          </div>

          {/* Token Out */}
          <div>
            <label className="block text-sm font-medium mb-2">Token Out</label>
            <Select value={tokenOut} onValueChange={(v) => setTokenOut(v as `0x${string}`)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {tokens.map((token) => (
                  <SelectItem key={token.address} value={token.address}>
                    {token.symbol} ({token.decimals} decimals)
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          {/* Swap Button */}
          <Button onClick={() => setTokenIn(tokenOut)} className="w-full" disabled={isBalanceError}>
            {isBalanceError ? 'Insufficient Balance' : 'Swap Tokens ↕️'}
          </Button>

          {/* Loading State */}
          {isLoading && (
            <div className="p-4 bg-blue-50 rounded">
              <p className="text-blue-700">Calculating quote...</p>
            </div>
          )}

          {/* Error State */}
          {error && (
            <div className="p-4 bg-red-50 rounded">
              <p className="text-red-700 font-medium">Error:</p>
              <p className="text-red-600 text-sm">{error.message}</p>
            </div>
          )}

          {/* Quote Result */}
          {quote && !isLoading && (
            <div className="p-4 bg-green-50 rounded space-y-2">
              <h3 className="font-bold text-green-900">Quote Result:</h3>
              <div className="grid grid-cols-2 gap-2 text-sm">
                <div className="font-medium">Amount Out:</div>
                <div>{quote.amountOutFormatted}</div>

                <div className="font-medium">Price Impact:</div>
                <div className={quote.priceImpactBps < -50 ? 'text-red-600' : ''}>
                  {(quote.priceImpactBps / 100).toFixed(2)}%
                </div>

                <div className="font-medium">Protocol Fee:</div>
                <div>{(quote.feeBps / 100).toFixed(2)}%</div>

                <div className="font-medium">Fee USD:</div>
                <div>${quote.feeUsd || 'N/A'}</div>

                <div className="font-medium">Min Received:</div>
                <div>{(Number(quote.minReceived) / 10 ** 6).toFixed(6)}</div>

                <div className="font-medium">Slippage:</div>
                <div>{(quote.slippageBps / 100).toFixed(2)}%</div>

                <div className="font-medium">Route:</div>
                <div>{quote.route}</div>

                <div className="font-medium">Pair:</div>
                <div className="text-xs break-all">{quote.pair.address}</div>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
