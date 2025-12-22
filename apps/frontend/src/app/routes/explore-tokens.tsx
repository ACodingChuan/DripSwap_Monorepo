import { Link, createRoute } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useChainId } from 'wagmi';

import { ExploreLayout } from '@/app/components/explore-layout';
import { fetchExploreTokens } from '@/app/services/explore-service';
import { Badge, Card, Input, Skeleton } from '@/shared/ui';
import { Search } from '@/shared/icons';
import { cn } from '@/shared/utils';

import { rootRoute } from './root';

function formatUsd(value: number | null | undefined) {
  if (!value) return '—';
  if (!Number.isFinite(value)) return '—';
  return `$${value.toLocaleString(undefined, { maximumFractionDigits: 2 })}`;
}

function formatLargeUsd(value: number | null | undefined) {
  if (!value) return '—';
  if (!Number.isFinite(value)) return '—';
  
  if (value >= 1_000_000_000) {
    return `$${(value / 1_000_000_000).toFixed(2)}B`;
  }
  if (value >= 1_000_000) {
    return `$${(value / 1_000_000).toFixed(2)}M`;
  }
  if (value >= 1_000) {
    return `$${(value / 1_000).toFixed(2)}K`;
  }
  return `$${value.toFixed(2)}`;
}

function formatPercent(value: number | null | undefined) {
  if (!value) return '—';
  if (!Number.isFinite(value)) return '—';
  const sign = value > 0 ? '+' : '';
  return `${sign}${value.toLocaleString(undefined, { maximumFractionDigits: 2 })}%`;
}

function percentColor(value: number | null | undefined) {
  if (!value || !Number.isFinite(value) || value === 0) return 'text-muted-foreground';
  return value > 0 ? 'text-emerald-600' : 'text-red-600';
}

const ExploreTokensPage = () => {
  const chainId = useChainId();
  const [search, setSearch] = useState('');

  const deferredSearch = useMemo(() => search.trim(), [search]);

  const { data, isLoading, isError } = useQuery({
    queryKey: ['explore', 'tokens', chainId, deferredSearch],
    queryFn: () =>
      fetchExploreTokens({
        chainId: String(chainId),
        limit: 50,
        search: deferredSearch.length > 0 ? deferredSearch : undefined,
      }),
  });

  return (
    <ExploreLayout
      activeTab="tokens"
      title="Explore"
      description="Track tokens, pools, and recent transactions across Sepolia and Scroll."
    >
      <section className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex flex-col gap-[var(--space-sm)] sm:flex-row sm:items-center sm:justify-between">
          <h2 className="text-xl font-semibold text-foreground">Tokens</h2>
          <div className="flex flex-wrap items-center gap-[var(--space-sm)]">
            <div className="relative w-full sm:w-auto">
              <Input
                placeholder="Search tokens"
                className="w-full pl-10"
                aria-label="Search tokens"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
              <Search
                className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
            </div>
            <Badge variant="outline">MVP</Badge>
          </div>
        </div>

        <Card className="overflow-hidden">
          <div className="grid grid-cols-[3rem_2fr_repeat(4,minmax(0,1fr))] border-b border-border/70 bg-muted/60 px-[var(--space-md)] py-[var(--space-sm)] text-xs font-medium uppercase tracking-wide text-muted-foreground">
            <span>#</span>
            <span>Token</span>
            <span className="text-right">Price</span>
            <span className="text-right">1h</span>
            <span className="text-right">1d</span>
            <span className="text-right">FDV</span>
          </div>
          <div className="divide-y divide-border/60">
            {isLoading
              ? Array.from({ length: 10 }, (_, index) => `token-skeleton-${index}`).map((key) => (
                  <div
                    key={key}
                    className="grid grid-cols-[3rem_2fr_repeat(4,minmax(0,1fr))] items-center gap-[var(--space-sm)] px-[var(--space-md)] py-[var(--space-sm)] text-sm"
                  >
                    <Skeleton className="h-4 w-6" />
                    <div className="flex flex-col">
                      <Skeleton className="h-4 w-40" />
                      <Skeleton className="mt-2 h-3 w-24" />
                    </div>
                    <Skeleton className="ml-auto h-4 w-20" />
                    <Skeleton className="ml-auto h-4 w-14" />
                    <Skeleton className="ml-auto h-4 w-14" />
                    <Skeleton className="ml-auto h-4 w-24" />
                  </div>
                ))
              : isError
                ? (
                    <div className="px-[var(--space-md)] py-[var(--space-md)] text-sm text-muted-foreground">
                      Failed to load tokens. Please try again later.
                    </div>
                  )
                : (data ?? []).length === 0
                  ? (
                      <div className="px-[var(--space-md)] py-[var(--space-md)] text-sm text-muted-foreground">
                        No tokens found.
                      </div>
                    )
                  : (data ?? []).map((token, index) => (
                      <Link
                        key={token.id}
                        to="/explore/tokens/$chain/$tokenAddress"
                        params={{ chain: String(chainId), tokenAddress: token.id }}
                        className="grid grid-cols-[3rem_2fr_repeat(4,minmax(0,1fr))] items-center gap-[var(--space-sm)] px-[var(--space-md)] py-[var(--space-sm)] text-sm text-foreground hover:bg-muted/40 focus-visible:bg-muted/40 focus-visible:outline-none"
                      >
                        <span className="text-muted-foreground">{index + 1}</span>
                        <div className="flex min-w-0 flex-col">
                          <span className="truncate font-medium">{token.name}</span>
                          <span className="truncate text-xs text-muted-foreground">{token.symbol}</span>
                        </div>
                        <span className="text-right text-muted-foreground">{formatUsd(token.priceUsd)}</span>
                        <span className={cn('text-right', percentColor(token.change1h))}>
                          {formatPercent(token.change1h ?? null)}
                        </span>
                        <span className={cn('text-right', percentColor(token.change1d))}>
                          {formatPercent(token.change1d ?? null)}
                        </span>
                        <span className="text-right text-muted-foreground">{formatLargeUsd(token.fdvUsd)}</span>
                      </Link>
                    ))}
          </div>
        </Card>
      </section>
    </ExploreLayout>
  );
};

export const exploreTokensRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/explore/tokens',
  component: ExploreTokensPage,
});
