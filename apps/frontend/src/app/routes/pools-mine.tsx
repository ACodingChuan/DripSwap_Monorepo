import { Link, createRoute } from '@tanstack/react-router';

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
} from '@/shared/ui';
import { ArrowLeft, Coins, Percent } from '@/shared/icons';

const POSITIONS = [
  {
    name: 'WETH / USDC',
    deposited: '$12,400.00',
    rewards: '48.20 USDC',
    apr: '4.12%',
    share: '1.92% of pool',
  },
  {
    name: 'DAI / ETH',
    deposited: '$6,150.00',
    rewards: '0.12 ETH',
    apr: '3.45%',
    share: '0.88% of pool',
  },
] as const;

const PoolsMinePage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[960px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex items-center gap-[var(--space-sm)] text-sm text-muted-foreground">
          <ArrowLeft className="size-4" aria-hidden="true" />
          <Link
            to="/pools"
            className="text-foreground hover:text-primary focus-visible:text-primary"
          >
            Back to pools
          </Link>
        </div>
        <div className="flex flex-col gap-[var(--space-xs)] sm:flex-row sm:items-center sm:justify-between">
          <div className="flex flex-col gap-[var(--space-xs)]">
            <h1
              ref={headingRef}
              tabIndex={-1}
              className="text-3xl font-semibold tracking-tight text-foreground focus:outline-none sm:text-4xl"
            >
              My liquidity
            </h1>
            <p className="max-w-2xl text-base text-muted-foreground">
              Review positions, rewards, and performance based on live network state.
            </p>
          </div>
          <Button variant="primary" size="md" asChild>
            <Link to="/pools/add">New position</Link>
          </Button>
        </div>
      </header>

      <section className="grid gap-[var(--space-md)]">
        {POSITIONS.map((position) => (
          <Card key={position.name} className="border-border/70">
            <CardHeader className="flex flex-col gap-[var(--space-sm)]">
              <div className="flex flex-wrap items-center justify-between gap-[var(--space-sm)]">
                <div className="flex flex-col gap-[var(--space-xs)]">
                  <CardTitle className="text-xl">{position.name}</CardTitle>
                  <CardDescription className="text-sm text-muted-foreground">
                    Deposit value {position.deposited} · {position.share}
                  </CardDescription>
                </div>
                <div className="flex items-center gap-[var(--space-sm)]">
                  <Badge variant="outline" className="gap-[var(--space-xs)]">
                    <Percent className="size-3" aria-hidden="true" /> {position.apr} APR
                  </Badge>
                  <Badge variant="success">Active</Badge>
                </div>
              </div>
            </CardHeader>
            <CardContent className="flex flex-col gap-[var(--space-sm)]">
              <div className="flex flex-col gap-[var(--space-xs)]">
                <span className="text-xs font-medium uppercase text-muted-foreground">
                  Unclaimed rewards
                </span>
                <span className="text-lg font-semibold text-foreground">{position.rewards}</span>
              </div>
              <div className="flex flex-wrap items-center gap-[var(--space-sm)]">
                <Button variant="outline" size="sm" asChild>
                  <Link to="/pools/add">Add liquidity</Link>
                </Button>
                <Button variant="primary" size="sm" asChild>
                  <Link to="/pools/remove">Claim reward</Link>
                </Button>
              </div>
            </CardContent>
          </Card>
        ))}

        <Card className="border-dashed border-border/60 bg-surface-elevated/60">
          <CardHeader className="flex flex-col items-start gap-[var(--space-sm)]">
            <Badge variant="outline" className="gap-[var(--space-xs)]">
              <Coins className="size-3" aria-hidden="true" /> Tip
            </Badge>
            <CardTitle className="text-lg">Liquidity mining</CardTitle>
            <CardDescription>
              Reward accrual and APR calculations reflect current market activity.
            </CardDescription>
          </CardHeader>
        </Card>
      </section>
    </main>
  );
};

export const poolsMineRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/pools/mine',
  component: PoolsMinePage,
});
