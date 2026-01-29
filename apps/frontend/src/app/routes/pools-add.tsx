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
import { formatUnits, maxUint256, parseUnits, zeroAddress } from 'viem';

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
import { getAllTokens, getChainConfig } from '@/contracts';
import { shortenHex } from '@/shared/utils';
import { ERC20_ABI, UNISWAP_V2_ROUTER_ABI } from '@/lib/liquidity/abis';
import {
  readErc20Balance,
  readErc20Decimals,
  readErc20Symbol,
  readPairAddress,
  readPairTokens,
  readReserves,
} from '@/lib/liquidity/reads';

function isProbablyAddress(value: string | null): value is `0x${string}` {
  if (!value) return false;
  const v = value.trim();
  return /^0x[a-fA-F0-9]{40}$/.test(v);
}

function formatBalance(value: bigint | undefined, decimals: number | undefined) {
  if (value === undefined || decimals === undefined) return '—';
  const s = formatUnits(value, decimals);
  const [i, f] = s.split('.');
  if (!f) return i;
  return `${i}.${f.slice(0, 6)}`.replace(/\.$/, '');
}

function truncateHumanAmount(value: string, maxDp = 6) {
  const v = value.trim();
  if (!v.includes('.')) return v;
  const [i, f] = v.split('.');
  return `${i}.${f.slice(0, maxDp)}`.replace(/\.$/, '');
}

const DEFAULT_SLIPPAGE_BPS = 50; // 0.5%
const DEFAULT_DEADLINE_SECONDS = 60 * 20; // 20m

const PoolsAddPage = () => {
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
  const requestedTokenARaw = params.get('tokenA');
  const requestedTokenBRaw = params.get('tokenB');

  const requestedChainId = useMemo(() => {
    if (!requestedChainIdRaw) return walletChainId;
    const asNumber = Number(requestedChainIdRaw);
    return Number.isFinite(asNumber) ? asNumber : walletChainId;
  }, [requestedChainIdRaw, walletChainId]);

  const pairAddressFromUrl = isProbablyAddress(requestedPairRaw)
    ? (requestedPairRaw.toLowerCase() as `0x${string}`)
    : null;

  const chainConfig = useMemo(() => getChainConfig(requestedChainId), [requestedChainId]);
  const routerAddress = chainConfig?.router;
  const factoryAddress = chainConfig?.factory;
  const publicClient = usePublicClient({ chainId: requestedChainId });

  const tokenOptions = useMemo(() => getAllTokens(requestedChainId), [requestedChainId]);
  const tokenAddressSet = useMemo(
    () => new Set(tokenOptions.map((t) => t.address.toLowerCase())),
    [tokenOptions]
  );

  const [tokenA, setTokenA] = useState<`0x${string}` | undefined>();
  const [tokenB, setTokenB] = useState<`0x${string}` | undefined>();

  useEffect(() => {
    if (pairAddressFromUrl) return;
    if (!tokenOptions.length) return;

    const maybeA =
      isProbablyAddress(requestedTokenARaw) && tokenAddressSet.has(requestedTokenARaw.toLowerCase())
        ? (requestedTokenARaw.toLowerCase() as `0x${string}`)
        : undefined;
    const maybeB =
      isProbablyAddress(requestedTokenBRaw) && tokenAddressSet.has(requestedTokenBRaw.toLowerCase())
        ? (requestedTokenBRaw.toLowerCase() as `0x${string}`)
        : undefined;

    const nextA = maybeA ?? tokenOptions[0].address;
    const nextB = maybeB ?? tokenOptions.find((t) => t.address !== nextA)?.address ?? nextA;

    const aValid = tokenA && tokenAddressSet.has(tokenA.toLowerCase());
    const bValid = tokenB && tokenAddressSet.has(tokenB.toLowerCase());
    if (aValid && bValid) return;

    setTokenA(nextA);
    setTokenB(nextB);
  }, [
    pairAddressFromUrl,
    requestedTokenARaw,
    requestedTokenBRaw,
    tokenA,
    tokenB,
    tokenAddressSet,
    tokenOptions,
  ]);

  const handleTokenAChange = (value: string) => {
    const next = value as `0x${string}`;
    if (next === tokenB) setTokenB(tokenA);
    setTokenA(next);
  };

  const handleTokenBChange = (value: string) => {
    const next = value as `0x${string}`;
    if (next === tokenA) setTokenA(tokenB);
    setTokenB(next);
  };

  const isWrongNetwork = !!userAddress && walletChainId !== requestedChainId;

  const { data: pairAddressFromFactory, isLoading: isPairLookupLoading } = useQuery({
    queryKey: ['liquidity', 'add', 'pairByTokens', requestedChainId, tokenA, tokenB],
    enabled: !!publicClient && !!factoryAddress && !!tokenA && !!tokenB && !pairAddressFromUrl,
    queryFn: async () => readPairAddress(publicClient!, factoryAddress!, tokenA!, tokenB!),
  });

  const pairAddress = useMemo(() => {
    if (pairAddressFromUrl) return pairAddressFromUrl;
    if (!pairAddressFromFactory) return null;
    if (pairAddressFromFactory === zeroAddress) return null;
    return pairAddressFromFactory;
  }, [pairAddressFromFactory, pairAddressFromUrl]);

  const { data: poolState, isLoading: isPoolLoading, isError: isPoolError } = useQuery({
    queryKey: ['liquidity', 'add', 'poolState', requestedChainId, pairAddress],
    enabled: !!publicClient && !!pairAddress,
    queryFn: async () => {
      const { token0, token1 } = await readPairTokens(publicClient!, pairAddress!);
      const reserves = await readReserves(publicClient!, pairAddress!);
      return { token0, token1, reserves };
    },
  });

  useEffect(() => {
    if (!pairAddressFromUrl) return;
    if (!poolState?.token0 || !poolState?.token1) return;
    setTokenA(poolState.token0);
    setTokenB(poolState.token1);
  }, [pairAddressFromUrl, poolState?.token0, poolState?.token1]);

  const { data: tokenMeta, isLoading: isTokenMetaLoading } = useQuery({
    queryKey: ['liquidity', 'add', 'tokenMeta', requestedChainId, tokenA, tokenB],
    enabled: !!publicClient && !!tokenA && !!tokenB,
    queryFn: async () => {
      const [symbolA, decimalsA, symbolB, decimalsB] = await Promise.all([
        readErc20Symbol(publicClient!, tokenA!).catch(() => shortenHex(tokenA)),
        readErc20Decimals(publicClient!, tokenA!),
        readErc20Symbol(publicClient!, tokenB!).catch(() => shortenHex(tokenB)),
        readErc20Decimals(publicClient!, tokenB!),
      ]);
      return { symbolA, decimalsA, symbolB, decimalsB };
    },
  });

  const decimalsA = tokenMeta?.decimalsA;
  const decimalsB = tokenMeta?.decimalsB;
  const symbolA = tokenMeta?.symbolA ?? (tokenA ? shortenHex(tokenA) : 'Token A');
  const symbolB = tokenMeta?.symbolB ?? (tokenB ? shortenHex(tokenB) : 'Token B');

  const reservesForSelected = useMemo(() => {
    if (!poolState || !tokenA) return null;
    const a = tokenA.toLowerCase();
    const token0 = poolState.token0.toLowerCase();
    const token1 = poolState.token1.toLowerCase();
    if (a === token0) {
      return { reserveA: poolState.reserves.reserve0, reserveB: poolState.reserves.reserve1 };
    }
    if (a === token1) {
      return { reserveA: poolState.reserves.reserve1, reserveB: poolState.reserves.reserve0 };
    }
    return null;
  }, [poolState, tokenA]);

  const hasLiquidity =
    !!reservesForSelected && reservesForSelected.reserveA > 0n && reservesForSelected.reserveB > 0n;

  const { data: balances, isLoading: isBalancesLoading } = useQuery({
    queryKey: ['liquidity', 'add', 'balances', requestedChainId, tokenA, tokenB, userAddress],
    enabled: !!publicClient && !!tokenA && !!tokenB && !!userAddress,
    queryFn: async () => {
      const [bA, bB] = await Promise.all([
        readErc20Balance(publicClient!, tokenA!, userAddress!),
        readErc20Balance(publicClient!, tokenB!, userAddress!),
      ]);
      return { bA, bB };
    },
  });

  const [amountA, setAmountA] = useState('');
  const [amountB, setAmountB] = useState('');
  const [lastEdited, setLastEdited] = useState<'A' | 'B'>('A');

  useEffect(() => {
    if (!hasLiquidity) return;
    if (!decimalsA || !decimalsB) return;
    if (!reservesForSelected) return;

    try {
      if (lastEdited === 'A') {
        if (!amountA.trim()) {
          if (amountB !== '') setAmountB('');
          return;
        }
        const rawA = parseUnits(amountA, decimalsA);
        const rawB = (rawA * reservesForSelected.reserveB) / reservesForSelected.reserveA;
        const next = truncateHumanAmount(formatUnits(rawB, decimalsB));
        if (next !== amountB) setAmountB(next);
      } else {
        if (!amountB.trim()) {
          if (amountA !== '') setAmountA('');
          return;
        }
        const rawB = parseUnits(amountB, decimalsB);
        const rawA = (rawB * reservesForSelected.reserveA) / reservesForSelected.reserveB;
        const next = truncateHumanAmount(formatUnits(rawA, decimalsA));
        if (next !== amountA) setAmountA(next);
      }
    } catch {
      // ignore parse errors while user types
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [
    amountA,
    amountB,
    decimalsA,
    decimalsB,
    hasLiquidity,
    lastEdited,
    reservesForSelected?.reserveA,
    reservesForSelected?.reserveB,
  ]);

  const parsedA = useMemo(() => {
    if (!decimalsA) return null;
    if (!amountA.trim()) return 0n;
    try {
      return parseUnits(amountA, decimalsA);
    } catch {
      return null;
    }
  }, [amountA, decimalsA]);

  const parsedB = useMemo(() => {
    if (!decimalsB) return null;
    if (!amountB.trim()) return 0n;
    try {
      return parseUnits(amountB, decimalsB);
    } catch {
      return null;
    }
  }, [amountB, decimalsB]);

  const { data: allowanceA, refetch: refetchAllowanceA } = useReadContract({
    address: tokenA,
    abi: ERC20_ABI,
    functionName: 'allowance',
    args: userAddress && routerAddress ? [userAddress, routerAddress] : undefined,
    chainId: requestedChainId,
    query: { enabled: !!tokenA && !!userAddress && !!routerAddress },
  });

  const { data: allowanceB, refetch: refetchAllowanceB } = useReadContract({
    address: tokenB,
    abi: ERC20_ABI,
    functionName: 'allowance',
    args: userAddress && routerAddress ? [userAddress, routerAddress] : undefined,
    chainId: requestedChainId,
    query: { enabled: !!tokenB && !!userAddress && !!routerAddress },
  });

  const needApproveA = useMemo(() => {
    if (!tokenA || !routerAddress || !userAddress) return false;
    if (parsedA === null) return false;
    if (parsedA === 0n) return false;
    return !allowanceA || allowanceA < parsedA;
  }, [allowanceA, parsedA, routerAddress, tokenA, userAddress]);

  const needApproveB = useMemo(() => {
    if (!tokenB || !routerAddress || !userAddress) return false;
    if (parsedB === null) return false;
    if (parsedB === 0n) return false;
    return !allowanceB || allowanceB < parsedB;
  }, [allowanceB, parsedB, routerAddress, tokenB, userAddress]);

  const { writeContractAsync, isPending: isWriting } = useWriteContract();
  const [txHash, setTxHash] = useState<`0x${string}` | undefined>();
  const [phase, setPhase] = useState<'idle' | 'approving' | 'adding'>('idle');

  const { isLoading: isConfirming, isSuccess: isConfirmed, isError: isConfirmError } =
    useWaitForTransactionReceipt({
      hash: txHash,
    });

  useEffect(() => {
    if (!txHash) return;

    if (isConfirmed) {
      toast('Transaction confirmed', { description: shortenHex(txHash) });
      setTxHash(undefined);
      setPhase('idle');
      refetchAllowanceA();
      refetchAllowanceB();
      queryClient.invalidateQueries({ queryKey: ['liquidity'] });
    } else if (isConfirmError) {
      toast('Transaction failed', { description: 'The transaction reverted or was not confirmed.' });
      setTxHash(undefined);
      setPhase('idle');
      refetchAllowanceA();
      refetchAllowanceB();
    }
  }, [isConfirmed, isConfirmError, queryClient, refetchAllowanceA, refetchAllowanceB, txHash]);

  const handlePrimaryAction = async () => {
    if (!tokenA || !tokenB || !routerAddress) return;
    if (!userAddress) {
      toast('Connect wallet', { description: 'Please connect your wallet to continue.' });
      return;
    }
    if (isWrongNetwork) {
      toast('Wrong network', { description: `Please switch your wallet to chainId ${requestedChainId}.` });
      return;
    }
    if (parsedA === null || parsedB === null) {
      toast('Invalid amount', { description: 'Please enter valid amounts.' });
      return;
    }
    if (parsedA === 0n || parsedB === 0n) {
      toast('Enter amounts', { description: 'Both token amounts must be greater than 0.' });
      return;
    }

    const bA = balances?.bA;
    const bB = balances?.bB;
    if (bA !== undefined && bA < parsedA) {
      toast('Insufficient balance', { description: `Not enough ${symbolA}.` });
      return;
    }
    if (bB !== undefined && bB < parsedB) {
      toast('Insufficient balance', { description: `Not enough ${symbolB}.` });
      return;
    }

    try {
      if (needApproveA) {
        setPhase('approving');
        const hash = await writeContractAsync({
          address: tokenA,
          abi: ERC20_ABI,
          functionName: 'approve',
          args: [routerAddress, maxUint256],
          chainId: requestedChainId,
        });
        toast('Approval submitted', { description: `Approve ${symbolA}` });
        setTxHash(hash);
        return;
      }

      if (needApproveB) {
        setPhase('approving');
        const hash = await writeContractAsync({
          address: tokenB,
          abi: ERC20_ABI,
          functionName: 'approve',
          args: [routerAddress, maxUint256],
          chainId: requestedChainId,
        });
        toast('Approval submitted', { description: `Approve ${symbolB}` });
        setTxHash(hash);
        return;
      }

      setPhase('adding');
      const minA = (parsedA * BigInt(10_000 - DEFAULT_SLIPPAGE_BPS)) / 10_000n;
      const minB = (parsedB * BigInt(10_000 - DEFAULT_SLIPPAGE_BPS)) / 10_000n;
      const deadline = BigInt(Math.floor(Date.now() / 1000) + DEFAULT_DEADLINE_SECONDS);

      const hash = await writeContractAsync({
        address: routerAddress,
        abi: UNISWAP_V2_ROUTER_ABI,
        functionName: 'addLiquidity',
        args: [tokenA, tokenB, parsedA, parsedB, minA, minB, userAddress, deadline],
        chainId: requestedChainId,
      });
      toast('Add liquidity submitted', { description: shortenHex(hash) });
      setTxHash(hash);
    } catch (e: any) {
      setPhase('idle');
      toast('Transaction rejected', {
        description: e?.shortMessage ?? e?.message ?? 'User rejected or tx failed.',
      });
    }
  };

  const primaryLabel = useMemo(() => {
    if (!userAddress) return 'Connect wallet';
    if (phase === 'approving') return 'Approving...';
    if (phase === 'adding') return 'Adding...';
    if (needApproveA) return `Approve ${symbolA}`;
    if (needApproveB) return `Approve ${symbolB}`;
    return 'Add liquidity';
  }, [needApproveA, needApproveB, phase, symbolA, symbolB, userAddress]);

  const disablePrimary =
    !tokenA ||
    !tokenB ||
    !routerAddress ||
    !chainConfig ||
    !userAddress ||
    isWrongNetwork ||
    isWriting ||
    isConfirming ||
    parsedA === null ||
    parsedB === null ||
    parsedA === 0n ||
    parsedB === 0n;

  const title = tokenA && tokenB ? `${symbolA}/${symbolB}` : 'Add liquidity';

  if (!chainConfig) {
    return (
      <main className="mx-auto flex min-h-screen w-full max-w-[720px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
        <Card className="border-border/70 bg-surface-elevated/60">
          <CardHeader>
            <CardTitle className="text-lg">Unsupported network</CardTitle>
            <CardDescription>chainId {requestedChainId} is not configured in the frontend.</CardDescription>
          </CardHeader>
        </Card>
      </main>
    );
  }

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[720px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-xs)] text-center">
        <Badge variant="outline" className="self-center">
          Provide liquidity
        </Badge>
        <h1
          ref={headingRef}
          tabIndex={-1}
          className="text-3xl font-semibold tracking-tight text-foreground focus:outline-none sm:text-4xl"
        >
          {title}
        </h1>
        <p className="mx-auto max-w-2xl text-base text-muted-foreground">
          This page reads balances/allowances/reserves directly from the chain and submits transactions via your wallet.
        </p>
      </header>

      {!userAddress ? (
        <Card className="border-border/70 bg-surface-elevated/60">
          <CardHeader>
            <CardTitle className="text-lg">Connect wallet</CardTitle>
            <CardDescription>Connect your wallet to see balances and add liquidity.</CardDescription>
          </CardHeader>
          <CardContent className="flex justify-center">
            <ConnectButton />
          </CardContent>
        </Card>
      ) : null}

      {isWrongNetwork ? (
        <Card className="border-warning/40 bg-warning/15">
          <CardHeader className="flex flex-row items-start gap-[var(--space-sm)]">
            <span
              className="rounded-[var(--radius-pill)] bg-warning/30 p-[var(--space-sm)] text-warning"
              aria-hidden="true"
            >
              <AlertTriangle className="size-5" />
            </span>
            <div className="flex flex-col gap-[var(--space-xs)] text-left">
              <CardTitle className="text-lg">Switch network</CardTitle>
              <CardDescription>
                Wallet is on chainId {walletChainId}. Switch to chainId {requestedChainId} to add liquidity.
              </CardDescription>
            </div>
          </CardHeader>
        </Card>
      ) : null}

      <Card>
        <CardHeader className="flex flex-col gap-[var(--space-sm)]">
          <CardTitle className="text-xl">Add liquidity</CardTitle>
          <CardDescription>Router: {shortenHex(routerAddress ?? '')}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-[var(--space-md)]">
          {!tokenOptions.length ? (
            <div className="rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
              No tokens configured for this chain yet.
            </div>
          ) : (
            <div className="grid gap-[var(--space-sm)] sm:grid-cols-2">
              <div className="flex flex-col gap-[var(--space-xs)]">
                <div className="text-xs text-muted-foreground">Token A</div>
                <Select
                  value={tokenA}
                  onValueChange={handleTokenAChange}
                  disabled={!!pairAddressFromUrl}
                >
                  <SelectTrigger>
                    <SelectValue placeholder="Select a token" />
                  </SelectTrigger>
                  <SelectContent>
                    {tokenOptions.map((t) => (
                      <SelectItem key={t.address} value={t.address}>
                        {t.symbol} ({shortenHex(t.address)})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div className="flex flex-col gap-[var(--space-xs)]">
                <div className="text-xs text-muted-foreground">Token B</div>
                <Select
                  value={tokenB}
                  onValueChange={handleTokenBChange}
                  disabled={!!pairAddressFromUrl}
                >
                  <SelectTrigger>
                    <SelectValue placeholder="Select a token" />
                  </SelectTrigger>
                  <SelectContent>
                    {tokenOptions.map((t) => (
                      <SelectItem key={t.address} value={t.address}>
                        {t.symbol} ({shortenHex(t.address)})
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
          )}

          <div className="rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
            {pairAddressFromUrl ? (
              <>Pool: {shortenHex(pairAddressFromUrl)}</>
            ) : !tokenA || !tokenB ? (
              'Select tokens to continue.'
            ) : !factoryAddress ? (
              'Factory address is not configured for this chain.'
            ) : isPairLookupLoading ? (
              'Checking pool address...'
            ) : !pairAddress ? (
              'Pool not created yet (it will be created automatically when you add liquidity).'
            ) : (
              <>Pool: {shortenHex(pairAddress)}</>
            )}
            {pairAddress && !isPoolLoading && !isPoolError && reservesForSelected && hasLiquidity ? (
              <>
                <br />
                Reserves: {formatBalance(reservesForSelected.reserveA, decimalsA)} {symbolA} ·{' '}
                {formatBalance(reservesForSelected.reserveB, decimalsB)} {symbolB}
              </>
            ) : null}
          </div>

          {pairAddress && isPoolLoading ? (
            <Skeleton className="h-16 w-full rounded-[var(--radius-card)]" />
          ) : pairAddress && isPoolError ? (
            <div className="rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
              Failed to load pool reserves from chain.
            </div>
          ) : null}

          <div className="grid gap-[var(--space-sm)] sm:grid-cols-2">
            <div className="flex flex-col gap-[var(--space-xs)]">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>{isTokenMetaLoading ? 'Token A' : symbolA}</span>
                <span>
                  Balance:{' '}
                  {!userAddress
                    ? '—'
                    : isBalancesLoading
                      ? '...'
                      : formatBalance(balances?.bA, decimalsA)}
                </span>
              </div>
              <Input
                type="number"
                min="0"
                step="any"
                placeholder="0.0"
                value={amountA}
                onChange={(e) => {
                  setLastEdited('A');
                  setAmountA(e.target.value);
                }}
              />
            </div>

            <div className="flex flex-col gap-[var(--space-xs)]">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>{isTokenMetaLoading ? 'Token B' : symbolB}</span>
                <span>
                  Balance:{' '}
                  {!userAddress
                    ? '—'
                    : isBalancesLoading
                      ? '...'
                      : formatBalance(balances?.bB, decimalsB)}
                </span>
              </div>
              <Input
                type="number"
                min="0"
                step="any"
                placeholder="0.0"
                value={amountB}
                onChange={(e) => {
                  setLastEdited('B');
                  setAmountB(e.target.value);
                }}
              />
            </div>
          </div>

          <div className="rounded-[var(--radius-card)] border border-dashed border-border/60 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
            Slippage: {(DEFAULT_SLIPPAGE_BPS / 100).toFixed(2)}% · Deadline: {Math.floor(DEFAULT_DEADLINE_SECONDS / 60)} minutes
          </div>

          <Button
            type="button"
            size="lg"
            className="justify-center"
            onClick={handlePrimaryAction}
            disabled={disablePrimary}
          >
            {primaryLabel}
          </Button>

          {txHash ? (
            <div className="text-xs text-muted-foreground">
              Tx: <span className="font-mono">{shortenHex(txHash)}</span>{' '}
              {isConfirming ? '(confirming...)' : ''}
            </div>
          ) : null}
        </CardContent>
      </Card>
    </main>
  );
};

export const poolsAddRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/pools/add',
  component: PoolsAddPage,
});
