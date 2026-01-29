import { createRoute, useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useChainId } from 'wagmi';

import { ExploreLayout } from '@/app/components/explore-layout';
import { fetchExplorePools } from '@/app/services/explore-service';
import {
  Badge,
  Card,
  Input,
  Skeleton,
  Button,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/shared/ui';
import { ArrowDown, ArrowUp, ChevronsUpDown, Minus, MoreHorizontal, Plus, Search } from '@/shared/icons';
import { cn } from '@/shared/utils';

import { rootRoute } from './root';

function formatChainLabel(chainId: number) {
  if (chainId === 11155111) return 'Sepolia';
  if (chainId === 534351) return 'Scroll Sepolia';
  return String(chainId);
}

function formatCompactUsd(value: number | null | undefined) {
  if (value === null || value === undefined) return '$0.00';
  if (!Number.isFinite(value)) return '$0.00';
  if (value >= 1_000_000_000) return `$${(value / 1_000_000_000).toFixed(2)}B`;
  if (value >= 1_000_000) return `$${(value / 1_000_000).toFixed(2)}M`;
  if (value >= 1_000) return `$${(value / 1_000).toFixed(2)}K`;
  return `$${value.toFixed(2)}`;
}

function formatPercent(value: number | null | undefined) {
  if (value === null || value === undefined) return '0.00%';
  if (!Number.isFinite(value)) return '0.00%';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toLocaleString(undefined, { maximumFractionDigits: 2 })}%`;
}

function percentColor(value: number | null | undefined) {
  if (value === null || value === undefined) return 'text-muted-foreground';
  if (!Number.isFinite(value) || value === 0) return 'text-muted-foreground';
  return value > 0 ? 'text-emerald-600' : 'text-red-600';
}

function formatApr(value: number | null | undefined) {
  if (value === null || value === undefined) return '0.00%';
  if (!Number.isFinite(value)) return '0.00%';
  // Backend returns APR as a ratio (e.g. 0.12 => 12%).
  const pct = value * 100;
  if (pct > 0 && pct < 0.01) return '<0.01%';
  return `${pct.toLocaleString(undefined, { maximumFractionDigits: 2 })}%`;
}

type SortKey = 'tvlUsd' | 'volume24hUsd' | 'volume1wUsd' | 'fees24hUsd' | 'tx24hCount' | 'apr';
type SortDir = 'asc' | 'desc';

const ExplorePoolsPage = () => {
  const chainId = useChainId();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [sortKey, setSortKey] = useState<SortKey>('tvlUsd');
  const [sortDir, setSortDir] = useState<SortDir>('desc');
  const deferredSearch = useMemo(() => search.trim(), [search]);

  const { data, isLoading, isError } = useQuery({
    queryKey: ['explore', 'pools', chainId, deferredSearch],
    queryFn: () =>
      fetchExplorePools({
        chainId: String(chainId),
        limit: 50,
        search: deferredSearch.length > 0 ? deferredSearch : undefined,
      }),
  });

  const chainLabel = formatChainLabel(chainId);

  const sorted = useMemo(() => {
    const rows = [...(data ?? [])];

    const getValue = (row: (typeof rows)[number]) => {
      switch (sortKey) {
        case 'tvlUsd':
          return row.tvlUsd;
        case 'volume24hUsd':
          return row.volume24hUsd;
        case 'volume1wUsd':
          return row.volume1wUsd ?? null;
        case 'fees24hUsd':
          return row.fees24hUsd;
        case 'tx24hCount':
          return row.tx24hCount ?? null;
        case 'apr':
          return row.apr ?? null;
      }
    };

    rows.sort((a, b) => {
      const av = getValue(a);
      const bv = getValue(b);
      const aa = av === null || av === undefined || !Number.isFinite(av) ? null : av;
      const bb = bv === null || bv === undefined || !Number.isFinite(bv) ? null : bv;

      // Keep nulls at the end for both asc/desc.
      if (aa === null && bb === null) return 0;
      if (aa === null) return 1;
      if (bb === null) return -1;
      if (aa === bb) return 0;

      if (sortDir === 'asc') return aa > bb ? 1 : -1;
      return aa > bb ? -1 : 1;
    });
    return rows;
  }, [data, sortKey, sortDir]);

  const toggleSort = (key: SortKey) => {
    if (key === sortKey) {
      setSortDir((prev) => (prev === 'asc' ? 'desc' : 'asc'));
      return;
    }
    setSortKey(key);
    setSortDir('asc');
  };

  const sortIcon = (key: SortKey) => {
    if (key !== sortKey) return <ChevronsUpDown className="ml-1 size-3 text-muted-foreground" aria-hidden="true" />;
    return sortDir === 'asc'
      ? <ArrowUp className="ml-1 size-3 text-muted-foreground" aria-hidden="true" />
      : <ArrowDown className="ml-1 size-3 text-muted-foreground" aria-hidden="true" />;
  };

  return (
    <ExploreLayout
      activeTab="pools"
      title="Explore"
      description="Browse top pools and drill into pool details."
    >
      <section className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex flex-col gap-[var(--space-sm)] sm:flex-row sm:items-center sm:justify-between">
          <h2 className="text-xl font-semibold text-foreground">Pools</h2>
          <div className="flex flex-wrap items-center gap-[var(--space-sm)]">
            <div className="relative w-full sm:w-auto">
              <Input
                placeholder="Search pools"
                className="w-full pl-10"
                aria-label="Search pools"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
              <Search
                className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
            </div>
            <Badge variant="outline">{chainLabel}</Badge>
          </div>
        </div>

        <Card className="overflow-hidden">
          {/* Keep header/body column widths identical; the last column fits the row actions menu. */}
          <div className="grid grid-cols-[2fr_repeat(6,minmax(0,1fr))_3rem] items-center gap-[var(--space-sm)] border-b border-border/70 bg-muted/60 px-[var(--space-md)] py-[var(--space-sm)] text-xs font-medium uppercase tracking-wide text-muted-foreground">
            <span>Pool</span>
            <button
              type="button"
              className="inline-flex items-center justify-end text-right"
              onClick={() => toggleSort('tvlUsd')}
            >
              TVL {sortIcon('tvlUsd')}
            </button>
            <button
              type="button"
              className="inline-flex items-center justify-end text-right"
              onClick={() => toggleSort('volume24hUsd')}
            >
              Volume (24h) {sortIcon('volume24hUsd')}
            </button>
            <button
              type="button"
              className="inline-flex items-center justify-end text-right"
              onClick={() => toggleSort('volume1wUsd')}
            >
              Volume (1w) {sortIcon('volume1wUsd')}
            </button>
            <button
              type="button"
              className="inline-flex items-center justify-end text-right"
              onClick={() => toggleSort('fees24hUsd')}
            >
              Fees (24h) {sortIcon('fees24hUsd')}
            </button>
            <button
              type="button"
              className="inline-flex items-center justify-end text-right"
              onClick={() => toggleSort('tx24hCount')}
            >
              Tx (24h) {sortIcon('tx24hCount')}
            </button>
            <button
              type="button"
              className="inline-flex items-center justify-end text-right"
              onClick={() => toggleSort('apr')}
            >
              APR {sortIcon('apr')}
            </button>
            <span className="sr-only">Actions</span>
          </div>
          <div className="divide-y divide-border/60">
            {isLoading
              ? Array.from({ length: 8 }, (_, index) => `pool-skeleton-${index}`).map((key) => (
                  <div
                    key={key}
                    className="grid grid-cols-[2fr_repeat(6,minmax(0,1fr))_3rem] items-center gap-[var(--space-sm)] px-[var(--space-md)] py-[var(--space-sm)] text-sm"
                  >
                    <div className="flex flex-col">
                      <Skeleton className="h-4 w-40" />
                    </div>
                    <Skeleton className="ml-auto h-4 w-16" />
                    <Skeleton className="ml-auto h-4 w-16" />
                    <Skeleton className="ml-auto h-4 w-16" />
                    <Skeleton className="ml-auto h-4 w-16" />
                    <Skeleton className="ml-auto h-4 w-16" />
                    <Skeleton className="ml-auto h-4 w-16" />
                    <div className="flex items-center justify-end">
                      <Button variant="ghost" size="icon" disabled aria-label="Pool actions">
                        <MoreHorizontal className="size-4" aria-hidden="true" />
                      </Button>
                    </div>
                  </div>
                ))
              : isError
                ? (
                    <div className="px-[var(--space-md)] py-[var(--space-md)] text-sm text-muted-foreground">
                      Failed to load pools. Please try again later.
                    </div>
                  )
                : (data ?? []).length === 0
                  ? (
                      <div className="px-[var(--space-md)] py-[var(--space-md)] text-sm text-muted-foreground">
                        No pools found.
                      </div>
                    )
                  : sorted.map((pool) => (
                      <div
                        key={pool.pairAddress}
                        role="link"
                        tabIndex={0}
                        className="grid grid-cols-[2fr_repeat(6,minmax(0,1fr))_3rem] items-center gap-[var(--space-sm)] px-[var(--space-md)] py-[var(--space-sm)] text-sm text-foreground hover:bg-muted/40 focus-visible:bg-muted/40 focus-visible:outline-none"
                        onClick={() =>
                          navigate({
                            to: '/explore/pools/$chain/$poolAddress',
                            params: { chain: String(chainId), poolAddress: pool.pairAddress },
                          })
                        }
                        onKeyDown={(event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault();
                            navigate({
                              to: '/explore/pools/$chain/$poolAddress',
                              params: { chain: String(chainId), poolAddress: pool.pairAddress },
                            });
                          }
                        }}
                      >
                        <div className="min-w-0">
                          <div className="truncate font-medium">
                            {pool.token0.symbol}/{pool.token1.symbol}
                          </div>
                        </div>
                        <div className="text-right">
                          <div className="text-muted-foreground">{formatCompactUsd(pool.tvlUsd)}</div>
                          <div className={cn('text-xs', percentColor(pool.tvlChange1d ?? null))}>
                            {formatPercent(pool.tvlChange1d ?? 0)}
                          </div>
                        </div>
                        <span className="text-right text-muted-foreground">{formatCompactUsd(pool.volume24hUsd)}</span>
                        <span className="text-right text-muted-foreground">{formatCompactUsd(pool.volume1wUsd ?? 0)}</span>
                        <span className="text-right text-muted-foreground">{formatCompactUsd(pool.fees24hUsd)}</span>
                        <span className="text-right text-muted-foreground">{pool.tx24hCount ?? 0}</span>
                        <span className="text-right text-muted-foreground">{formatApr(pool.apr ?? 0)}</span>
                        <div className="flex items-center justify-end">
                          <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                              <Button
                                variant="ghost"
                                size="icon"
                                aria-label="Pool actions"
                                onClick={(e) => e.stopPropagation()}
                              >
                                <MoreHorizontal className="size-4" aria-hidden="true" />
                              </Button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent
                              align="end"
                              className="w-56 rounded-xl border border-border/70 bg-background p-1 shadow-lg"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <div className="px-2 py-1.5 text-sm font-medium">
                                {pool.token0.symbol}/{pool.token1.symbol}
                              </div>
                              <DropdownMenuSeparator />
                              <DropdownMenuItem
                                onSelect={(event) => {
                                  event.preventDefault();
                                  navigate({
                                    to: '/pools/add',
                                    search: { chainId: String(chainId), pair: pool.pairAddress },
                                  });
                                }}
                                className="flex items-center gap-2"
                              >
                                <Plus className="size-4" aria-hidden="true" />
                                Add liquidity
                              </DropdownMenuItem>
                              <DropdownMenuItem
                                onSelect={(event) => {
                                  event.preventDefault();
                                  navigate({
                                    to: '/pools/remove',
                                    search: { chainId: String(chainId), pair: pool.pairAddress },
                                  });
                                }}
                                className="flex items-center gap-2"
                              >
                                <Minus className="size-4" aria-hidden="true" />
                                Remove liquidity
                              </DropdownMenuItem>
                            </DropdownMenuContent>
                          </DropdownMenu>
                        </div>
                      </div>
                    ))}
          </div>
        </Card>
      </section>
    </ExploreLayout>
  );
};

export const explorePoolsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/explore/pools',
  component: ExplorePoolsPage,
});
