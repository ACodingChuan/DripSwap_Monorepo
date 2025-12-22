import { Link, createRoute } from '@tanstack/react-router';

import { rootRoute } from './root';
import { Badge, Card, CardContent, CardHeader, CardTitle, Skeleton } from '@/shared/ui';
import { ArrowLeft } from '@/shared/icons';

const PoolDetailsPage = () => {
  const { chain, poolAddress } = poolDetailsRoute.useParams();

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[1200px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex items-center gap-[var(--space-sm)] text-sm text-muted-foreground">
          <ArrowLeft className="size-4" aria-hidden="true" />
          <Link to="/explore/pools" className="hover:text-foreground focus-visible:text-foreground">
            Back to pools
          </Link>
        </div>

        <div className="flex flex-wrap items-start justify-between gap-[var(--space-md)]">
          <div className="flex flex-col gap-[var(--space-xs)]">
            <h1 className="text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
              Pool details
            </h1>
            <p className="text-sm text-muted-foreground">
              {chain} · {poolAddress}
            </p>
          </div>
          <Badge variant="outline">SSE-ready</Badge>
        </div>
      </header>

      <section className="grid gap-[var(--space-md)] lg:grid-cols-[2fr,1fr]">
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Volume</CardTitle>
          </CardHeader>
          <CardContent>
            <Skeleton className="h-64 w-full rounded-[var(--radius-card)]" />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Snapshot</CardTitle>
          </CardHeader>
          <CardContent className="grid gap-[var(--space-sm)] sm:grid-cols-2 lg:grid-cols-1">
            {['TVL', 'Volume (24h)', 'Fees (24h)', 'APR'].map((label) => (
              <div
                key={label}
                className="rounded-[var(--radius-card)] border border-border/60 bg-surface-elevated/60 p-[var(--space-md)]"
              >
                <div className="text-xs font-medium uppercase text-muted-foreground">{label}</div>
                <div className="mt-1 text-lg font-semibold text-foreground">—</div>
              </div>
            ))}
          </CardContent>
        </Card>
      </section>

      <section className="grid gap-[var(--space-md)] lg:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Price</CardTitle>
          </CardHeader>
          <CardContent>
            <Skeleton className="h-64 w-full rounded-[var(--radius-card)]" />
          </CardContent>
        </Card>
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">Transactions</CardTitle>
          </CardHeader>
          <CardContent>
            <Skeleton className="h-64 w-full rounded-[var(--radius-card)]" />
          </CardContent>
        </Card>
      </section>
    </main>
  );
};

export const poolDetailsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/explore/pools/$chain/$poolAddress',
  component: PoolDetailsPage,
});

