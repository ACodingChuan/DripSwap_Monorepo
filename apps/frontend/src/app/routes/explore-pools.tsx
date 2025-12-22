import { Link, createRoute } from '@tanstack/react-router';

import { ExploreLayout } from '@/app/components/explore-layout';
import { Badge, Card, Input, Skeleton, Button } from '@/shared/ui';
import { Search } from '@/shared/icons';

import { rootRoute } from './root';

const ExplorePoolsPage = () => {
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
              <Input placeholder="Search pools" className="w-full pl-10" aria-label="Search pools" />
              <Search
                className="absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
                aria-hidden="true"
              />
            </div>
            <Badge variant="outline">Coming soon</Badge>
          </div>
        </div>

        <Card className="overflow-hidden">
          <div className="grid grid-cols-[2fr_repeat(5,minmax(0,1fr))_auto] border-b border-border/70 bg-muted/60 px-[var(--space-md)] py-[var(--space-sm)] text-xs font-medium uppercase tracking-wide text-muted-foreground">
            <span>Pool</span>
            <span className="text-right">TVL</span>
            <span className="text-right">Volume (24h)</span>
            <span className="text-right">Fees (24h)</span>
            <span className="text-right">Tx</span>
            <span className="text-right">APR</span>
            <span className="sr-only">Actions</span>
          </div>
          <div className="divide-y divide-border/60">
            {Array.from({ length: 8 }, (_, index) => `pool-skeleton-${index}`).map((key) => (
              <div
                key={key}
                className="grid grid-cols-[2fr_repeat(5,minmax(0,1fr))_auto] items-center gap-[var(--space-sm)] px-[var(--space-md)] py-[var(--space-sm)] text-sm"
              >
                <div className="flex flex-col">
                  <Skeleton className="h-4 w-40" />
                  <Skeleton className="mt-2 h-3 w-24" />
                </div>
                <Skeleton className="ml-auto h-4 w-16" />
                <Skeleton className="ml-auto h-4 w-16" />
                <Skeleton className="ml-auto h-4 w-16" />
                <Skeleton className="ml-auto h-4 w-16" />
                <Skeleton className="ml-auto h-4 w-16" />
                <div className="flex items-center justify-end">
                  <Button variant="outline" size="sm" asChild>
                    <Link to="/pools" aria-label="Go to liquidity page">
                      Manage
                    </Link>
                  </Button>
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

