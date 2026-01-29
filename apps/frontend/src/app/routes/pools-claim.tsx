import { Link, createRoute } from '@tanstack/react-router';
import { useState } from 'react';
import { ConnectButton } from '@rainbow-me/rainbowkit';
import { useAccount, useChainId } from 'wagmi';

import { rootRoute } from './root';
import { usePageFocus } from '@/shared/hooks';
import { Badge, Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui';

type ClaimTab = 'fees' | 'rewards';

const PoolsClaimPage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();
  const { address: userAddress } = useAccount();
  const chainId = useChainId();
  const [tab, setTab] = useState<ClaimTab>('fees');

  const isFees = tab === 'fees';

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[1100px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex flex-col gap-[var(--space-xs)] sm:flex-row sm:items-start sm:justify-between">
          <div className="flex flex-col gap-[var(--space-xs)]">
            <h1
              ref={headingRef}
              tabIndex={-1}
              className="text-4xl font-semibold tracking-tight text-foreground focus:outline-none"
            >
              Claim
            </h1>
            <p className="max-w-3xl text-base text-muted-foreground">
              For V2-style pools, fees are realized when you remove liquidity (burn LP tokens). This page tracks claimable items and history (MVP).
            </p>
          </div>
          <div className="flex items-center gap-[var(--space-sm)]">
            {!userAddress ? <ConnectButton /> : null}
            <Button variant="primary" size="md" asChild>
              <Link to="/pools/mine">Manage</Link>
            </Button>
          </div>
        </div>
      </header>

      <section className="grid gap-[var(--space-md)] sm:grid-cols-2">
        <Card className="border-border/70">
          <CardHeader>
            <CardDescription>Claimable V2 Fees</CardDescription>
            <CardTitle className="text-2xl">$0.00</CardTitle>
          </CardHeader>
        </Card>
        <Card className="border-border/70">
          <CardHeader>
            <CardDescription>Claimable Rewards</CardDescription>
            <CardTitle className="text-2xl">$0.00</CardTitle>
          </CardHeader>
        </Card>
      </section>

      <section className="flex items-center gap-[var(--space-sm)]">
        <Button
          type="button"
          variant={isFees ? 'secondary' : 'ghost'}
          size="sm"
          onClick={() => setTab('fees')}
        >
          Fees <Badge variant="outline" className="ml-2">v2</Badge>
        </Button>
        <Button
          type="button"
          variant={!isFees ? 'secondary' : 'ghost'}
          size="sm"
          onClick={() => setTab('rewards')}
        >
          Rewards <Badge variant="outline" className="ml-2">v2</Badge>
        </Button>
      </section>

      <Card className="overflow-hidden border-border/70">
        <CardHeader>
          <CardTitle className="text-lg">
            {isFees ? 'Claimable Fees' : 'Claimable Rewards'} (0)
          </CardTitle>
          <CardDescription>Network: chainId {chainId}</CardDescription>
        </CardHeader>
        <CardContent className="p-0">
          <div className="grid grid-cols-[1fr_1fr_1fr] border-b border-border/70 bg-muted/60 px-[var(--space-md)] py-[var(--space-sm)] text-xs font-medium uppercase tracking-wide text-muted-foreground">
            <span>Chain</span>
            <span>{isFees ? 'Fees amount' : 'Rewards amount'}</span>
            <span className="text-right">Action</span>
          </div>
          <div className="px-[var(--space-md)] py-[var(--space-xl)] text-center text-sm text-muted-foreground">
            No results.
          </div>
        </CardContent>
      </Card>
    </main>
  );
};

export const poolsClaimRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/pools/claim',
  component: PoolsClaimPage,
});
