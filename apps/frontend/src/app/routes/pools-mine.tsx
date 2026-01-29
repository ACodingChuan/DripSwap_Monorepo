import { Link, createRoute } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { ConnectButton } from '@rainbow-me/rainbowkit';
import { useAccount, useChainId } from 'wagmi';

import { rootRoute } from './root';
import { usePageFocus } from '@/shared/hooks';
import { Badge, Button, Card, CardContent, CardDescription, CardHeader, CardTitle, Input, Skeleton } from '@/shared/ui';
import { Search } from '@/shared/icons';
import { shortenHex } from '@/shared/utils';
import { fetchUserLiquidityPositions } from '@/infrastructure/subgraph/liquidity';

const PoolsMinePage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();
  const chainId = useChainId();
  const { address: userAddress } = useAccount();
  const [search, setSearch] = useState('');

  const { data: positions, isLoading: isPositionsLoading } = useQuery({
    queryKey: ['liquidity', 'mine', chainId, userAddress],
    enabled: !!userAddress,
    queryFn: async () => fetchUserLiquidityPositions(chainId, userAddress!),
    staleTime: 60_000,
    refetchOnWindowFocus: false,
  });

  const filteredPositions = useMemo(() => {
    const list = positions ?? [];
    const q = search.trim().toLowerCase();
    if (!q) return list;
    return list.filter((p) => {
      const name = `${p.token0.symbol}/${p.token1.symbol}`.toLowerCase();
      return name.includes(q) || p.pairAddress.toLowerCase().includes(q);
    });
  }, [positions, search]);

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[960px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex flex-col gap-[var(--space-xs)] sm:flex-row sm:items-start sm:justify-between">
          <div className="flex flex-col gap-[var(--space-xs)]">
            <h1
              ref={headingRef}
              tabIndex={-1}
              className="text-3xl font-semibold tracking-tight text-foreground focus:outline-none sm:text-4xl"
            >
              Manage Liquidity Positions
            </h1>
            <p className="max-w-2xl text-base text-muted-foreground">
              You can adjust your liquidity positions on the connected network.
            </p>
          </div>
          <div className="flex items-center gap-[var(--space-sm)]">
            {!userAddress ? <ConnectButton /> : null}
            <Button variant="primary" size="md" asChild>
              <Link to="/pools/add">Add liquidity</Link>
            </Button>
          </div>
        </div>
      </header>

      {userAddress && isPositionsLoading ? (
        <Card className="border-border/70">
          <CardHeader>
            <CardTitle className="text-lg">Loading positions</CardTitle>
            <CardDescription>Loading positions from the subgraph...</CardDescription>
          </CardHeader>
          <CardContent>
            <Skeleton className="h-28 w-full rounded-[var(--radius-card)]" />
          </CardContent>
        </Card>
      ) : null}

      <Card className="overflow-hidden border-border/70">
        <CardHeader className="flex flex-col gap-[var(--space-md)]">
          <div className="flex flex-col gap-[var(--space-xs)] sm:flex-row sm:items-center sm:justify-between">
            <CardTitle className="text-lg">My Positions ({filteredPositions.length})</CardTitle>
            <div className="relative w-full sm:w-80">
              <Input
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                placeholder="Search"
                className="w-full pl-10"
                aria-label="Search positions"
              />
              <Search
                className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
            </div>
          </div>
          <CardDescription className="text-sm text-muted-foreground">
            This list is loaded from The Graph subgraph. If you have no positions, it will be empty.
          </CardDescription>
        </CardHeader>

        <CardContent className="p-0">
          <div className="grid grid-cols-[2fr_1.2fr_1fr] border-b border-border/70 bg-muted/60 px-[var(--space-md)] py-[var(--space-sm)] text-xs font-medium uppercase tracking-wide text-muted-foreground">
            <span>Name</span>
            <span>Pool</span>
            <span className="text-right">Actions</span>
          </div>
          <div className="divide-y divide-border/60">
            {!userAddress ? (
              <div className="px-[var(--space-md)] py-[var(--space-lg)] text-sm text-muted-foreground">
                Connect wallet to view your positions.
              </div>
            ) : isPositionsLoading ? (
              <div className="px-[var(--space-md)] py-[var(--space-lg)]">
                <Skeleton className="h-16 w-full rounded-[var(--radius-card)]" />
              </div>
            ) : filteredPositions.length === 0 ? (
              <div className="px-[var(--space-md)] py-[var(--space-lg)] text-sm text-muted-foreground">
                No results.
              </div>
            ) : (
              filteredPositions.map((p) => {
                const name = `${p.token0.symbol} / ${p.token1.symbol}`;
                return (
                  <div
                    key={p.pairAddress}
                    className="grid grid-cols-[2fr_1.2fr_1fr] items-center gap-[var(--space-sm)] px-[var(--space-md)] py-[var(--space-sm)] text-sm text-foreground"
                  >
                    <div className="flex flex-col">
                      <Link
                        to="/explore/pools/$chain/$poolAddress"
                        params={{ chain: String(chainId), poolAddress: p.pairAddress }}
                        className="font-medium hover:text-primary focus-visible:text-primary"
                      >
                        {name}
                      </Link>
                      <span className="text-xs text-muted-foreground">
                        LP balance: {p.liquidityTokenBalance}
                      </span>
                    </div>
                    <Link
                      to="/explore/pools/$chain/$poolAddress"
                      params={{ chain: String(chainId), poolAddress: p.pairAddress }}
                      className="text-xs text-muted-foreground font-mono hover:text-foreground focus-visible:text-foreground"
                    >
                      {shortenHex(p.pairAddress)}
                    </Link>
                    <div className="flex items-center justify-end gap-[var(--space-xs)]">
                      <Button variant="outline" size="sm" asChild>
                        <Link to="/pools/add" search={{ chainId, pair: p.pairAddress }}>
                          Add
                        </Link>
                      </Button>
                      <Button variant="primary" size="sm" asChild>
                        <Link to="/pools/remove" search={{ chainId, pair: p.pairAddress }}>
                          Remove
                        </Link>
                      </Button>
                    </div>
                  </div>
                );
              })
            )}
          </div>
        </CardContent>
      </Card>
    </main>
  );
};

export const poolsMineRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/pools/mine',
  component: PoolsMinePage,
});
