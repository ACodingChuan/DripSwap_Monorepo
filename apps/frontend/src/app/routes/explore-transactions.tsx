import { createRoute } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useChainId } from 'wagmi';
import { useMemo, useState } from 'react';

import { ExploreLayout } from '@/app/components/explore-layout';
import { fetchRecentTransactions } from '@/app/services/explore-service';
import type { ExploreRecentTransaction } from '@/domain/ports/explore-port';
import {
  Badge,
  Card,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
  Skeleton,
} from '@/shared/ui';
import { Check, ChevronDown } from '@/shared/icons';
import { getExplorerAddressUrl, getExplorerTxUrl, shortenHex } from '@/shared/utils';

import { rootRoute } from './root';

function formatChainLabel(chainId: number) {
  if (chainId === 11155111) return 'Sepolia';
  if (chainId === 534351) return 'Scroll Sepolia';
  return String(chainId);
}

function safeParseJson(value: string | null | undefined): unknown {
  if (!value) return null;
  try {
    return JSON.parse(value);
  } catch {
    return null;
  }
}

function formatRelativeTime(timestampSeconds: number) {
  const nowSeconds = Math.floor(Date.now() / 1000);
  const diff = Math.max(0, nowSeconds - timestampSeconds);
  if (diff < 60) return `${diff}s`;
  const minutes = Math.floor(diff / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  return `${days}d`;
}

function formatUsd(value: string | null | undefined) {
  if (!value) return '—';
  const asNumber = Number(value);
  if (!Number.isFinite(asNumber)) return '—';
  return `$${asNumber.toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
}

function formatTokenAmount(value: string | null | undefined) {
  if (!value) return '—';
  const asNumber = Number(value);
  if (!Number.isFinite(asNumber)) return value;
  return asNumber.toLocaleString(undefined, { maximumFractionDigits: 6 });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

type SwapDecodedData = {
  type?: 'SWAP' | 'MINT' | 'BURN';
  pair?: string;
  sender?: string;
  from?: string;
  to?: string;
  amountUsd?: string;
  amountIn?: string;
  amountOut?: string;
  account?: string;
  timestamp?: number;
  tokenIn?: { id?: string; symbol?: string; name?: string; decimals?: number };
  tokenOut?: { id?: string; symbol?: string; name?: string; decimals?: number };
  token0?: { id?: string; symbol?: string; name?: string; decimals?: number };
  token1?: { id?: string; symbol?: string; name?: string; decimals?: number };
  amount0?: string;
  amount1?: string;
};

function parseSwapDecodedData(value: string | null | undefined): SwapDecodedData | null {
  const decoded = safeParseJson(value);
  if (!isRecord(decoded)) return null;
  return decoded as SwapDecodedData;
}

function getWalletAddress(decoded: SwapDecodedData | null) {
  if (!decoded) return null;
  if (typeof decoded.from === 'string' && decoded.from.length > 0) return decoded.from;
  if (typeof decoded.account === 'string' && decoded.account.length > 0) return decoded.account;
  if (typeof decoded.to === 'string' && decoded.to.length > 0) return decoded.to;
  return null;
}

function getTimestampSeconds(tx: ExploreRecentTransaction, decoded: SwapDecodedData | null) {
  if (decoded && typeof decoded.timestamp === 'number' && Number.isFinite(decoded.timestamp)) {
    return decoded.timestamp;
  }
  const createdAtSeconds = Number(tx.createdAt);
  return Number.isFinite(createdAtSeconds) ? createdAtSeconds : null;
}

function getTokenSymbol(token: SwapDecodedData['tokenIn'] | SwapDecodedData['tokenOut'] | undefined) {
  if (!token) return '—';
  if (typeof token.symbol === 'string' && token.symbol.length > 0) return token.symbol;
  return '—';
}

function getTokenSymbolLite(token: SwapDecodedData['token0'] | SwapDecodedData['token1'] | undefined) {
  if (!token) return '—';
  if (typeof token.symbol === 'string' && token.symbol.length > 0) return token.symbol;
  return '—';
}

function buildTxSummary(tx: ExploreRecentTransaction) {
  const decoded = parseSwapDecodedData(tx.decodedData);
  const txType = decoded?.type ?? 'SWAP';

  const tokenInSymbol = getTokenSymbol(decoded?.tokenIn);
  const tokenOutSymbol = getTokenSymbol(decoded?.tokenOut);
  const token0Symbol = getTokenSymbolLite(decoded?.token0);
  const token1Symbol = getTokenSymbolLite(decoded?.token1);

  const amountIn = decoded && typeof decoded.amountIn === 'string' ? decoded.amountIn : null;
  const amountOut = decoded && typeof decoded.amountOut === 'string' ? decoded.amountOut : null;
  const amount0 = decoded && typeof decoded.amount0 === 'string' ? decoded.amount0 : null;
  const amount1 = decoded && typeof decoded.amount1 === 'string' ? decoded.amount1 : null;

  const amountUsd = decoded && typeof decoded.amountUsd === 'string' ? decoded.amountUsd : null;
  const walletAddress = getWalletAddress(decoded);
  const timestampSeconds = getTimestampSeconds(tx, decoded);

  return {
    decoded,
    txType,
    tokenInSymbol,
    tokenOutSymbol,
    token0Symbol,
    token1Symbol,
    amountIn,
    amountOut,
    amount0,
    amount1,
    amountUsd,
    walletAddress,
    timestampSeconds,
  };
}

const ExploreTransactionsPage = () => {
  const chainId = useChainId();
  const [selectedTxId, setSelectedTxId] = useState<string | null>(null);
  const [selectedTypes, setSelectedTypes] = useState<Array<'SWAP' | 'MINT' | 'BURN'>>([
    'SWAP',
    'MINT',
    'BURN',
  ]);

  const { data, isLoading, isError } = useQuery({
    queryKey: ['explore', 'recentTransactions', chainId],
    queryFn: () =>
      fetchRecentTransactions({
        chainId: String(chainId),
        limit: 50,
      }),
  });

  const filtered = useMemo(() => {
    const rows = data ?? [];
    if (selectedTypes.length === 0) return rows;
    return rows.filter((tx) => {
      const decoded = parseSwapDecodedData(tx.decodedData);
      const t = decoded?.type;
      if (t === 'SWAP' || t === 'MINT' || t === 'BURN') {
        return selectedTypes.includes(t);
      }
      // Back-compat: if missing type, treat as SWAP.
      return selectedTypes.includes('SWAP');
    });
  }, [data, selectedTypes]);

  const chainLabel = formatChainLabel(chainId);
  const selectedTx = useMemo(
    () => (selectedTxId ? (filtered ?? []).find((tx) => tx.id === selectedTxId) ?? null : null),
    [filtered, selectedTxId]
  );
  const selectedSummary = useMemo(() => (selectedTx ? buildTxSummary(selectedTx) : null), [selectedTx]);

  return (
    <ExploreLayout
      activeTab="transactions"
      title="Explore"
      description="Track tokens, pools, and recent swaps across Sepolia and Scroll."
    >
      <section className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex items-center justify-between">
          <h2 className="text-xl font-semibold text-foreground">Transactions</h2>
          <Badge variant="outline">{chainLabel}</Badge>
        </div>

        <Card className="overflow-hidden">
          <div className="w-full overflow-x-auto">
            <table className="w-full min-w-[760px] text-sm">
              <thead className="border-b border-border/70 bg-muted/60 text-xs font-medium tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-[var(--space-md)] py-[var(--space-sm)] text-left">Time</th>
                  <th className="px-[var(--space-md)] py-[var(--space-sm)] text-left">
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <button
                          type="button"
                          className="inline-flex cursor-pointer select-none items-center gap-1 rounded-md px-2 py-1 text-xs font-medium uppercase tracking-wide text-muted-foreground hover:bg-muted/60 hover:text-foreground"
                        >
                          TYPE
                          <ChevronDown className="size-4 text-muted-foreground" aria-hidden="true" />
                        </button>
                      </DropdownMenuTrigger>
                      {/* Uniswap-like overlay: larger surface, clear selection state */}
                      <DropdownMenuContent
                        align="start"
                        sideOffset={8}
                        className="w-60 rounded-xl border border-border/70 bg-background p-1.5 shadow-lg"
                      >
                        {(
                          [
                            { key: 'SWAP', label: 'Swap' },
                            { key: 'MINT', label: 'Mint' },
                            { key: 'BURN', label: 'Burn' },
                          ] as const
                        ).map((opt) => {
                          const checked = selectedTypes.includes(opt.key);
                          return (
                            <DropdownMenuItem
                              key={opt.key}
                              onSelect={(event) => {
                                event.preventDefault(); // keep menu open for multi-select
                                setSelectedTypes((prev) => {
                                  const has = prev.includes(opt.key);
                                  const next = has ? prev.filter((t) => t !== opt.key) : [...prev, opt.key];
                                  // Never allow empty selection; fallback to all.
                                  return next.length > 0 ? next : ['SWAP', 'MINT', 'BURN'];
                                });
                              }}
                              className="flex cursor-pointer items-center justify-between gap-3 rounded-lg px-3 py-2.5 text-sm font-medium hover:bg-muted/50 focus:bg-muted/50"
                            >
                              <span className="text-foreground">{opt.label}</span>
                              <span
                                className={
                                  checked
                                    ? 'flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground'
                                    : 'flex size-8 items-center justify-center rounded-lg border border-border/70 bg-background'
                                }
                              >
                                <Check className={checked ? 'size-4' : 'size-4 opacity-0'} aria-hidden="true" />
                              </span>
                            </DropdownMenuItem>
                          );
                        })}
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </th>
                  <th className="px-[var(--space-md)] py-[var(--space-sm)] text-right">USD</th>
                  <th className="px-[var(--space-md)] py-[var(--space-sm)] text-right">Token amount</th>
                  <th className="px-[var(--space-md)] py-[var(--space-sm)] text-right">Token amount</th>
                  <th className="px-[var(--space-md)] py-[var(--space-sm)] text-right">Wallet</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/60">
                {isLoading
                  ? Array.from({ length: 12 }, (_, index) => `tx-skeleton-${index}`).map((key) => (
                      <tr key={key} className="h-14">
                        <td className="px-[var(--space-md)] py-[var(--space-sm)]">
                          <Skeleton className="h-4 w-14" />
                        </td>
                        <td className="px-[var(--space-md)] py-[var(--space-sm)]">
                          <Skeleton className="h-4 w-56" />
                        </td>
                        <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right">
                          <Skeleton className="ml-auto h-4 w-20" />
                        </td>
                        <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right">
                          <Skeleton className="ml-auto h-4 w-28" />
                        </td>
                        <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right">
                          <Skeleton className="ml-auto h-4 w-28" />
                        </td>
                        <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right">
                          <Skeleton className="ml-auto h-4 w-24" />
                        </td>
                      </tr>
                    ))
                  : isError
                    ? (
                        <tr>
                          <td
                            colSpan={6}
                            className="px-[var(--space-md)] py-[var(--space-md)] text-sm text-muted-foreground"
                          >
                            Failed to load transactions. Please try again later.
                          </td>
                        </tr>
                      )
                    : (filtered ?? []).length === 0
                      ? (
                          <tr>
                            <td
                              colSpan={6}
                              className="px-[var(--space-md)] py-[var(--space-md)] text-sm text-muted-foreground"
                            >
                              No transactions yet.
                            </td>
                          </tr>
                        )
                      : (filtered ?? []).map((tx) => {
                          const summary = buildTxSummary(tx);
                          const timeLabel =
                            summary.timestampSeconds && summary.timestampSeconds > 0
                              ? formatRelativeTime(summary.timestampSeconds)
                              : '—';

                          return (
                            <tr
                              key={tx.id}
                              className="h-14 cursor-pointer text-foreground hover:bg-muted/40"
                              role="button"
                              tabIndex={0}
                              onClick={() => setSelectedTxId(tx.id)}
                              onKeyDown={(event) => {
                                if (event.key === 'Enter' || event.key === ' ') {
                                  event.preventDefault();
                                  setSelectedTxId(tx.id);
                                }
                              }}
                            >
                              <td className="px-[var(--space-md)] py-[var(--space-sm)] text-muted-foreground">
                                {timeLabel}
                              </td>
                              <td className="px-[var(--space-md)] py-[var(--space-sm)]">
                                <div className="flex flex-wrap items-center gap-2">
                                  <span className="text-muted-foreground">{summary.txType}</span>
                                  {summary.txType === 'SWAP' ? (
                                    <>
                                      <span className="font-medium">{summary.tokenInSymbol}</span>
                                      <span className="text-muted-foreground">for</span>
                                      <span className="font-medium">{summary.tokenOutSymbol}</span>
                                    </>
                                  ) : (
                                    <>
                                      <span className="font-medium">{summary.token0Symbol}</span>
                                      <span className="text-muted-foreground">/</span>
                                      <span className="font-medium">{summary.token1Symbol}</span>
                                    </>
                                  )}
                                </div>
                              </td>
                              <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right text-muted-foreground">
                                {formatUsd(summary.amountUsd)}
                              </td>
                              <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right text-muted-foreground">
                                {summary.txType === 'SWAP'
                                  ? summary.amountIn
                                    ? `${formatTokenAmount(summary.amountIn)} ${summary.tokenInSymbol}`
                                    : '—'
                                  : summary.amount0
                                    ? `${formatTokenAmount(summary.amount0)} ${summary.token0Symbol}`
                                    : '—'}
                              </td>
                              <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right text-muted-foreground">
                                {summary.txType === 'SWAP'
                                  ? summary.amountOut
                                    ? `${formatTokenAmount(summary.amountOut)} ${summary.tokenOutSymbol}`
                                    : '—'
                                  : summary.amount1
                                    ? `${formatTokenAmount(summary.amount1)} ${summary.token1Symbol}`
                                    : '—'}
                              </td>
                              <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right text-muted-foreground">
                              {summary.walletAddress ? shortenHex(summary.walletAddress) : '—'}
                            </td>
                          </tr>
                        );
                      })}
              </tbody>
            </table>
          </div>
        </Card>
      </section>

      <Dialog
        open={Boolean(selectedTx)}
        onOpenChange={(open) => {
          if (!open) setSelectedTxId(null);
        }}
      >
        <DialogContent className="w-[min(42rem,92vw)]">
          <DialogHeader>
            <DialogTitle>Transaction details</DialogTitle>
            <DialogDescription>
              {!selectedSummary
                ? 'Transaction'
                : selectedSummary.txType === 'SWAP'
                  ? `Swap ${selectedSummary.tokenInSymbol} for ${selectedSummary.tokenOutSymbol}`
                  : `${selectedSummary.txType} ${selectedSummary.token0Symbol}/${selectedSummary.token1Symbol}`}
            </DialogDescription>
          </DialogHeader>

          {!selectedTx || !selectedSummary ? (
            <div className="text-sm text-muted-foreground">No transaction selected.</div>
          ) : (
            <div className="grid gap-[var(--space-md)]">
              <div className="grid grid-cols-2 gap-[var(--space-md)]">
                <div className="rounded-lg border border-border/70 p-[var(--space-md)]">
                  <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                    USD value
                  </div>
                  <div className="mt-1 text-base font-semibold text-foreground">
                    {formatUsd(selectedSummary.amountUsd)}
                  </div>
                </div>

                <div className="rounded-lg border border-border/70 p-[var(--space-md)]">
                  <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                    Wallet
                  </div>
                  <div className="mt-1 flex items-center justify-between gap-3 font-mono text-sm text-foreground">
                    <span>{selectedSummary.walletAddress ? shortenHex(selectedSummary.walletAddress) : '—'}</span>
                    {selectedSummary.walletAddress && getExplorerAddressUrl(chainId, selectedSummary.walletAddress) ? (
                      <a
                        className="text-xs font-medium text-primary hover:underline"
                        href={getExplorerAddressUrl(chainId, selectedSummary.walletAddress) ?? '#'}
                        target="_blank"
                        rel="noreferrer"
                      >
                        View
                      </a>
                    ) : null}
                  </div>
                </div>
              </div>

              <div className="rounded-lg border border-border/70 p-[var(--space-md)]">
                <div className="grid gap-3 text-sm">
                  <div className="grid grid-cols-[10rem_minmax(0,1fr)] items-start gap-6">
                    <span className="text-muted-foreground">Transaction hash</span>
                    <span className="break-all font-mono text-right text-foreground">
                      {selectedTx.txHash}
                    </span>
                  </div>
                  <div className="grid grid-cols-[10rem_minmax(0,1fr)] items-center gap-6">
                    <span className="text-muted-foreground">Block</span>
                    <span className="text-right text-foreground">
                      {selectedTx.blockNumber && selectedTx.blockNumber > 0
                        ? selectedTx.blockNumber.toLocaleString()
                        : '—'}
                    </span>
                  </div>
                  <div className="grid grid-cols-[10rem_minmax(0,1fr)] items-center gap-6">
                    <span className="text-muted-foreground">Timestamp</span>
                    <span className="text-right text-foreground">
                      {selectedSummary.timestampSeconds
                        ? new Date(selectedSummary.timestampSeconds * 1000).toLocaleString()
                        : '—'}
                    </span>
                  </div>
                  <div className="grid grid-cols-[10rem_minmax(0,1fr)] items-center gap-6">
                    <span className="text-muted-foreground">Amount in</span>
                    <span className="text-right text-foreground">
                      {selectedSummary.amountIn
                        ? `${formatTokenAmount(selectedSummary.amountIn)} ${selectedSummary.tokenInSymbol}`
                        : '—'}
                    </span>
                  </div>
                  <div className="grid grid-cols-[10rem_minmax(0,1fr)] items-center gap-6">
                    <span className="text-muted-foreground">Amount out</span>
                    <span className="text-right text-foreground">
                      {selectedSummary.amountOut
                        ? `${formatTokenAmount(selectedSummary.amountOut)} ${selectedSummary.tokenOutSymbol}`
                        : '—'}
                    </span>
                  </div>
                </div>

                {getExplorerTxUrl(chainId, selectedTx.txHash) ? (
                  <div className="mt-[var(--space-md)] border-t border-border/60 pt-[var(--space-md)]">
                    <a
                      className="text-sm font-medium text-primary hover:underline"
                      href={getExplorerTxUrl(chainId, selectedTx.txHash) ?? '#'}
                      target="_blank"
                      rel="noreferrer"
                    >
                      View on explorer
                    </a>
                  </div>
                ) : null}
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </ExploreLayout>
  );
};

export const exploreTransactionsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/explore/transactions',
  component: ExploreTransactionsPage,
});
