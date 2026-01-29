import { createRoute, useRouterState } from '@tanstack/react-router';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { ConnectButton } from '@rainbow-me/rainbowkit';
import {
  useAccount,
  useChainId,
  usePublicClient,
  useReadContract,
  useWaitForTransactionReceipt,
  useWriteContract,
} from 'wagmi';
import { formatUnits, maxUint256 } from 'viem';

import { rootRoute } from './root';
import { usePageFocus } from '@/shared/hooks';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  Input,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Skeleton,
  toast,
} from '@/shared/ui';
import { AlertTriangle } from '@/shared/icons';
import { getChainConfig } from '@/contracts';
import { shortenHex } from '@/shared/utils';
import { ERC20_ABI, UNISWAP_V2_ROUTER_ABI } from '@/lib/liquidity/abis';
import { fetchUserLiquidityPositions } from '@/infrastructure/subgraph/liquidity';
import { readErc20Decimals, readErc20Symbol, readPairTokens, readReserves, readTotalSupply } from '@/lib/liquidity/reads';

function isProbablyAddress(value: string | null): value is `0x${string}` {
  if (!value) return false;
  const v = value.trim();
  return /^0x[a-fA-F0-9]{40}$/.test(v);
}

function formatTokenAmount(value: bigint | undefined, decimals: number | undefined) {
  if (value === undefined || decimals === undefined) return '—';
  const s = formatUnits(value, decimals);
  const [i, f] = s.split('.');
  if (!f) return i;
  return `${i}.${f.slice(0, 6)}`.replace(/\.$/, '');
}

function clampPercent(value: number) {
  if (!Number.isFinite(value)) return 0;
  return Math.min(100, Math.max(0, value));
}

const DEFAULT_SLIPPAGE_BPS = 50; // 0.5%
const DEFAULT_DEADLINE_SECONDS = 60 * 20; // 20m

const PoolsRemovePage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();
  const queryClient = useQueryClient();
  const walletChainId = useChainId();
  const { address: userAddress } = useAccount();
  const routerState = useRouterState();

  const params = useMemo(
    () => new URLSearchParams(routerState.location.search),
    [routerState.location.search]
  );

  const requestedChainIdRaw = params.get('chainId');
  const requestedPairRaw = params.get('pair');

  const requestedChainId = useMemo(() => {
    if (!requestedChainIdRaw) return walletChainId;
    const asNumber = Number(requestedChainIdRaw);
    return Number.isFinite(asNumber) ? asNumber : walletChainId;
  }, [requestedChainIdRaw, walletChainId]);

  const chainConfig = useMemo(() => getChainConfig(requestedChainId), [requestedChainId]);
  const routerAddress = chainConfig?.router;
  const publicClient = usePublicClient({ chainId: requestedChainId });

  const pairAddressFromUrl = isProbablyAddress(requestedPairRaw)
    ? (requestedPairRaw.toLowerCase() as `0x${string}`)
    : null;

  const isWrongNetwork = !!userAddress && walletChainId !== requestedChainId;

  const { data: myPositions, isLoading: isPositionsLoading } = useQuery({
    queryKey: ['liquidity', 'remove', 'positions', requestedChainId, userAddress],
    enabled: !!userAddress,
    queryFn: async () => fetchUserLiquidityPositions(requestedChainId, userAddress!),
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });

  const [selectedPair, setSelectedPair] = useState<`0x${string}` | undefined>();

  useEffect(() => {
    if (pairAddressFromUrl) {
      setSelectedPair(pairAddressFromUrl);
      return;
    }
    if (!myPositions?.length) return;
    if (selectedPair) return;
    setSelectedPair(myPositions[0].pairAddress);
  }, [myPositions, pairAddressFromUrl, selectedPair]);

  const effectivePair = pairAddressFromUrl ?? selectedPair ?? null;

  const { data: poolInfo, isLoading: isPoolLoading, isError: isPoolError } = useQuery({
    queryKey: ['liquidity', 'remove', 'poolInfo', requestedChainId, effectivePair],
    enabled: !!publicClient && !!effectivePair,
    queryFn: async () => {
      const { token0, token1 } = await readPairTokens(publicClient!, effectivePair!);
      const [reserves, totalSupply] = await Promise.all([
        readReserves(publicClient!, effectivePair!),
        readTotalSupply(publicClient!, effectivePair!),
      ]);
      return { token0, token1, reserves, totalSupply };
    },
  });

  const { data: tokenMeta, isLoading: isTokenMetaLoading } = useQuery({
    queryKey: ['liquidity', 'remove', 'tokenMeta', requestedChainId, poolInfo?.token0, poolInfo?.token1],
    enabled: !!publicClient && !!poolInfo?.token0 && !!poolInfo?.token1,
    queryFn: async () => {
      const t0 = poolInfo!.token0;
      const t1 = poolInfo!.token1;
      const [symbol0, decimals0, symbol1, decimals1] = await Promise.all([
        readErc20Symbol(publicClient!, t0).catch(() => shortenHex(t0)),
        readErc20Decimals(publicClient!, t0),
        readErc20Symbol(publicClient!, t1).catch(() => shortenHex(t1)),
        readErc20Decimals(publicClient!, t1),
      ]);
      return { symbol0, decimals0, symbol1, decimals1 };
    },
  });

  const { data: lpBalance, isLoading: isLpBalanceLoading } = useReadContract({
    address: effectivePair ?? undefined,
    abi: ERC20_ABI,
    functionName: 'balanceOf',
    args: userAddress ? [userAddress] : undefined,
    chainId: requestedChainId,
    query: { enabled: !!effectivePair && !!userAddress },
  });

  const [percentage, setPercentage] = useState(25);
  const [manualPercentage, setManualPercentage] = useState('25');

  useEffect(() => {
    const parsed = Number(manualPercentage);
    if (Number.isFinite(parsed)) setPercentage(clampPercent(parsed));
  }, [manualPercentage]);

  const liquidityToRemove = useMemo(() => {
    if (!lpBalance) return 0n;
    return (lpBalance * BigInt(percentage)) / 100n;
  }, [lpBalance, percentage]);

  const expectedAmounts = useMemo(() => {
    if (!poolInfo) return null;
    if (poolInfo.totalSupply === 0n) return null;
    if (liquidityToRemove === 0n) return { amount0: 0n, amount1: 0n };
    const amount0 = (liquidityToRemove * poolInfo.reserves.reserve0) / poolInfo.totalSupply;
    const amount1 = (liquidityToRemove * poolInfo.reserves.reserve1) / poolInfo.totalSupply;
    return { amount0, amount1 };
  }, [liquidityToRemove, poolInfo]);

  const minAmounts = useMemo(() => {
    if (!expectedAmounts) return null;
    const min0 = (expectedAmounts.amount0 * BigInt(10_000 - DEFAULT_SLIPPAGE_BPS)) / 10_000n;
    const min1 = (expectedAmounts.amount1 * BigInt(10_000 - DEFAULT_SLIPPAGE_BPS)) / 10_000n;
    return { min0, min1 };
  }, [expectedAmounts]);

  const { data: lpAllowance, refetch: refetchLpAllowance } = useReadContract({
    address: effectivePair ?? undefined,
    abi: ERC20_ABI,
    functionName: 'allowance',
    args: userAddress && routerAddress ? [userAddress, routerAddress] : undefined,
    chainId: requestedChainId,
    query: { enabled: !!effectivePair && !!userAddress && !!routerAddress },
  });

  const needApproveLp = useMemo(() => {
    if (!effectivePair || !routerAddress || !userAddress) return false;
    if (!lpBalance || lpBalance === 0n) return false;
    if (liquidityToRemove === 0n) return false;
    return !lpAllowance || lpAllowance < liquidityToRemove;
  }, [effectivePair, liquidityToRemove, lpAllowance, lpBalance, routerAddress, userAddress]);

  const { writeContractAsync, isPending: isWriting } = useWriteContract();
  const [txHash, setTxHash] = useState<`0x${string}` | undefined>();
  const [phase, setPhase] = useState<'idle' | 'approving' | 'removing'>('idle');

  const { isLoading: isConfirming, isSuccess: isConfirmed, isError: isConfirmError } =
    useWaitForTransactionReceipt({ hash: txHash });

  useEffect(() => {
    if (!txHash) return;
    if (isConfirmed) {
      toast('Transaction confirmed', { description: shortenHex(txHash) });
      setTxHash(undefined);
      setPhase('idle');
      refetchLpAllowance();
      queryClient.invalidateQueries({ queryKey: ['liquidity'] });
    } else if (isConfirmError) {
      toast('Transaction failed', { description: 'The transaction reverted or was not confirmed.' });
      setTxHash(undefined);
      setPhase('idle');
      refetchLpAllowance();
    }
  }, [isConfirmed, isConfirmError, queryClient, refetchLpAllowance, txHash]);

  const handlePrimaryAction = async () => {
    if (!chainConfig || !routerAddress) return;
    if (!userAddress) {
      toast('Connect wallet', { description: 'Please connect your wallet to continue.' });
      return;
    }
    if (isWrongNetwork) {
      toast('Wrong network', { description: `Please switch your wallet to chainId ${requestedChainId}.` });
      return;
    }
    if (!effectivePair || !poolInfo || !minAmounts) {
      toast('Select a pool', { description: 'Please pick a pool to remove liquidity from.' });
      return;
    }
    if (!lpBalance || lpBalance === 0n) {
      toast('No liquidity', { description: 'You do not have LP tokens for this pool.' });
      return;
    }
    if (liquidityToRemove === 0n) {
      toast('Choose percentage', { description: 'Please select a percentage greater than 0%.' });
      return;
    }

    try {
      if (needApproveLp) {
        setPhase('approving');
        const hash = await writeContractAsync({
          address: effectivePair,
          abi: ERC20_ABI,
          functionName: 'approve',
          args: [routerAddress, maxUint256],
          chainId: requestedChainId,
        });
        toast('Approval submitted', { description: 'Approve LP token' });
        setTxHash(hash);
        return;
      }

      setPhase('removing');
      const deadline = BigInt(Math.floor(Date.now() / 1000) + DEFAULT_DEADLINE_SECONDS);
      const hash = await writeContractAsync({
        address: routerAddress,
        abi: UNISWAP_V2_ROUTER_ABI,
        functionName: 'removeLiquidity',
        args: [
          poolInfo.token0,
          poolInfo.token1,
          liquidityToRemove,
          minAmounts.min0,
          minAmounts.min1,
          userAddress,
          deadline,
        ],
        chainId: requestedChainId,
      });
      toast('Remove liquidity submitted', { description: shortenHex(hash) });
      setTxHash(hash);
    } catch (e: any) {
      setPhase('idle');
      toast('Transaction rejected', { description: e?.shortMessage ?? e?.message ?? 'User rejected or tx failed.' });
    }
  };

  const primaryLabel = useMemo(() => {
    if (!userAddress) return 'Connect wallet';
    if (phase === 'approving') return 'Approving...';
    if (phase === 'removing') return 'Removing...';
    if (needApproveLp) return 'Approve LP token';
    return 'Remove liquidity';
  }, [needApproveLp, phase, userAddress]);

  const disablePrimary =
    !chainConfig ||
    !routerAddress ||
    !userAddress ||
    isWrongNetwork ||
    !effectivePair ||
    !poolInfo ||
    !minAmounts ||
    isWriting ||
    isConfirming ||
    !lpBalance ||
    lpBalance === 0n ||
    liquidityToRemove === 0n;

  const title = useMemo(() => {
    if (!tokenMeta) return 'Remove liquidity';
    return `${tokenMeta.symbol0}/${tokenMeta.symbol1}`;
  }, [tokenMeta]);

  const selectablePairs = myPositions ?? [];

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[720px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-xs)] text-center">
        <Badge variant="outline" className="self-center">
          Exit position
        </Badge>
        <h1
          ref={headingRef}
          tabIndex={-1}
          className="text-3xl font-semibold tracking-tight text-foreground focus:outline-none sm:text-4xl"
        >
          {title}
        </h1>
        <p className="mx-auto max-w-2xl text-base text-muted-foreground">
          Remove liquidity by burning LP tokens. (In Uniswap V2 style pools, “claim” and “remove” are effectively the same action.)
        </p>
      </header>

      {!chainConfig ? (
        <Card className="border-border/70 bg-surface-elevated/60">
          <CardHeader>
            <CardTitle className="text-lg">Unsupported network</CardTitle>
            <CardDescription>chainId {requestedChainId} is not configured in the frontend.</CardDescription>
          </CardHeader>
        </Card>
      ) : null}

      {!userAddress ? (
        <Card className="border-border/70 bg-surface-elevated/60">
          <CardHeader>
            <CardTitle className="text-lg">Connect wallet</CardTitle>
            <CardDescription>Connect your wallet to see your LP positions and remove liquidity.</CardDescription>
          </CardHeader>
          <CardContent className="flex justify-center">
            <ConnectButton />
          </CardContent>
        </Card>
      ) : null}

      {userAddress && isWrongNetwork ? (
        <Card className="border-warning/40 bg-warning/15">
          <CardHeader className="flex flex-row items-start gap-[var(--space-sm)]">
            <span className="rounded-[var(--radius-pill)] bg-warning/30 p-[var(--space-sm)] text-warning" aria-hidden="true">
              <AlertTriangle className="size-5" />
            </span>
            <div className="flex flex-col gap-[var(--space-xs)] text-left">
              <CardTitle className="text-lg">Switch network</CardTitle>
              <CardDescription>
                Wallet is on chainId {walletChainId}. Switch to chainId {requestedChainId} to remove liquidity.
              </CardDescription>
            </div>
          </CardHeader>
        </Card>
      ) : null}

      <Card>
        <CardHeader className="flex flex-col gap-[var(--space-sm)]">
          <CardTitle className="text-xl">Remove liquidity</CardTitle>
          <CardDescription>Router: {shortenHex(routerAddress ?? '')}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-[var(--space-md)]">
          {!pairAddressFromUrl ? (
            <div className="flex flex-col gap-[var(--space-xs)]">
              <div className="text-xs text-muted-foreground">Select a position</div>
              {!userAddress ? (
                <div className="rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
                  Connect wallet to see your positions.
                </div>
              ) : isPositionsLoading ? (
                <Skeleton className="h-11 w-full rounded-[var(--radius-control)]" />
              ) : selectablePairs.length === 0 ? (
                <div className="rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
                  You don’t have any LP tokens yet.
                </div>
              ) : (
                <Select value={selectedPair} onValueChange={(v) => setSelectedPair(v as `0x${string}`)}>
                  <SelectTrigger>
                    <SelectValue placeholder="Select a pool" />
                  </SelectTrigger>
                  <SelectContent>
                    {selectablePairs.map((p) => {
                      const s0 = p.token0.symbol ?? shortenHex(p.token0.address);
                      const s1 = p.token1.symbol ?? shortenHex(p.token1.address);
                      return (
                        <SelectItem key={p.pairAddress} value={p.pairAddress}>
                          {s0}/{s1} ({shortenHex(p.pairAddress)})
                        </SelectItem>
                      );
                    })}
                  </SelectContent>
                </Select>
              )}
            </div>
          ) : (
            <div className="rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
              Pool: {shortenHex(pairAddressFromUrl)}
            </div>
          )}

          {effectivePair && (isPoolLoading || isTokenMetaLoading) ? (
            <Skeleton className="h-20 w-full rounded-[var(--radius-card)]" />
          ) : effectivePair && isPoolError ? (
            <div className="rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
              Failed to load pool data from chain.
            </div>
          ) : null}

          {effectivePair && poolInfo && tokenMeta && !isPoolLoading && !isTokenMetaLoading ? (
            <div className="rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
              LP balance:{' '}
              {isLpBalanceLoading ? '...' : lpBalance ? formatTokenAmount(lpBalance, 18) : '0'} · Removing:{' '}
              {formatTokenAmount(liquidityToRemove, 18)}
              <br />
              Expected receive (approx): {formatTokenAmount(expectedAmounts?.amount0, tokenMeta.decimals0)} {tokenMeta.symbol0} ·{' '}
              {formatTokenAmount(expectedAmounts?.amount1, tokenMeta.decimals1)} {tokenMeta.symbol1}
            </div>
          ) : null}

          <div className="flex flex-col gap-[var(--space-sm)]">
            <label htmlFor="liquidity-range" className="text-sm font-medium text-muted-foreground">
              Percentage to remove
            </label>
            <Input
              id="liquidity-range"
              type="range"
              min="0"
              max="100"
              step="1"
              value={percentage}
              onChange={(event) => {
                const next = clampPercent(Number(event.target.value));
                setPercentage(next);
                setManualPercentage(String(next));
              }}
              className="h-2 cursor-pointer rounded-full border-none bg-gradient-to-r from-primary to-primary"
              aria-valuetext={`${percentage}%`}
            />
            <div className="flex items-center justify-between text-sm text-muted-foreground">
              <span>0%</span>
              <span>100%</span>
            </div>
          </div>

          <label className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground" htmlFor="manual-percentage">
            Manual percentage
            <div className="relative">
              <Input
                id="manual-percentage"
                type="number"
                min="0"
                max="100"
                step="1"
                value={manualPercentage}
                onChange={(event) => setManualPercentage(event.target.value)}
                className="pr-10"
              />
              <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">
                %
              </span>
            </div>
          </label>

          <div className="rounded-[var(--radius-card)] border border-dashed border-border/60 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
            Slippage: {(DEFAULT_SLIPPAGE_BPS / 100).toFixed(2)}% · Deadline: {Math.floor(DEFAULT_DEADLINE_SECONDS / 60)} minutes
          </div>

          <Button type="button" size="lg" className="justify-center" onClick={handlePrimaryAction} disabled={disablePrimary}>
            {primaryLabel}
          </Button>

          {txHash ? (
            <div className="text-xs text-muted-foreground">
              Tx: <span className="font-mono">{shortenHex(txHash)}</span> {isConfirming ? '(confirming...)' : ''}
            </div>
          ) : null}
        </CardContent>
      </Card>
    </main>
  );
};

export const poolsRemoveRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/pools/remove',
  component: PoolsRemovePage,
});
