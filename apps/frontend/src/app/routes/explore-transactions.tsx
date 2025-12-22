import { createRoute } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useChainId } from 'wagmi';
import { useMemo, useState } from 'react';

import { ExploreLayout } from '@/app/components/explore-layout';
import { fetchRecentTransactions } from '@/app/services/explore-service';
import type { ExploreRecentTransaction } from '@/domain/ports/explore-port';
import { Badge, Card, Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, Skeleton } from '@/shared/ui';

import { rootRoute } from './root';

function formatChainLabel(chainId: number) {
  if (chainId === 11155111) return 'Sepolia';
  if (chainId === 534351) return 'Scroll Sepolia';
  return String(chainId);
}

function getTxExplorerUrl(chainId: number, txHash: string) {
  if (chainId === 11155111) return `https://sepolia.etherscan.io/tx/${txHash}`;
  if (chainId === 534351) return `https://sepolia.scrollscan.com/tx/${txHash}`;
  return null;
}

function shortenAddress(value: string) {
  if (value.length <= 12) return value;
  return `${value.slice(0, 6)}…${value.slice(-4)}`;
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

function buildTxSummary(tx: ExploreRecentTransaction) {
  const decoded = parseSwapDecodedData(tx.decodedData);
  const tokenInSymbol = getTokenSymbol(decoded?.tokenIn);
  const tokenOutSymbol = getTokenSymbol(decoded?.tokenOut);
  const amountIn = decoded && typeof decoded.amountIn === 'string' ? decoded.amountIn : null;
  const amountOut = decoded && typeof decoded.amountOut === 'string' ? decoded.amountOut : null;
  const amountUsd = decoded && typeof decoded.amountUsd === 'string' ? decoded.amountUsd : null;
  const walletAddress = getWalletAddress(decoded);
  const timestampSeconds = getTimestampSeconds(tx, decoded);

  return {
    decoded,
    tokenInSymbol,
    tokenOutSymbol,
    amountIn,
    amountOut,
    amountUsd,
    walletAddress,
    timestampSeconds,
  };
}

const ExploreTransactionsPage = () => {
  const chainId = useChainId();
  const [selectedTxId, setSelectedTxId] = useState<string | null>(null);

  const { data, isLoading, isError } = useQuery({
    queryKey: ['explore', 'recentTransactions', chainId],
    queryFn: () => fetchRecentTransactions({ chainId: String(chainId), limit: 50 }),
  });

  const chainLabel = formatChainLabel(chainId);
  const selectedTx = useMemo(
    () => (selectedTxId ? (data ?? []).find((tx) => tx.id === selectedTxId) ?? null : null),
    [data, selectedTxId]
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
                  <th className="px-[var(--space-md)] py-[var(--space-sm)] text-left">Type</th>
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
                    : (data ?? []).length === 0
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
                      : (data ?? []).map((tx) => {
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
                                  <span className="text-muted-foreground">Swap</span>
                                  <span className="font-medium">{summary.tokenInSymbol}</span>
                                  <span className="text-muted-foreground">for</span>
                                  <span className="font-medium">{summary.tokenOutSymbol}</span>
                                </div>
                              </td>
                              <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right text-muted-foreground">
                                {formatUsd(summary.amountUsd)}
                              </td>
                              <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right text-muted-foreground">
                                {summary.amountIn
                                  ? `${formatTokenAmount(summary.amountIn)} ${summary.tokenInSymbol}`
                                  : '—'}
                              </td>
                              <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right text-muted-foreground">
                                {summary.amountOut
                                  ? `${formatTokenAmount(summary.amountOut)} ${summary.tokenOutSymbol}`
                                  : '—'}
                              </td>
                              <td className="px-[var(--space-md)] py-[var(--space-sm)] text-right text-muted-foreground">
                                {summary.walletAddress ? shortenAddress(summary.walletAddress) : '—'}
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
              {selectedSummary
                ? `Swap ${selectedSummary.tokenInSymbol} for ${selectedSummary.tokenOutSymbol}`
                : 'Swap'}
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
                  <div className="mt-1 break-all font-mono text-sm text-foreground">
                    {selectedSummary.walletAddress ?? '—'}
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

                {getTxExplorerUrl(chainId, selectedTx.txHash) ? (
                  <div className="mt-[var(--space-md)] border-t border-border/60 pt-[var(--space-md)]">
                    <a
                      className="text-sm font-medium text-primary hover:underline"
                      href={getTxExplorerUrl(chainId, selectedTx.txHash) ?? '#'}
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
