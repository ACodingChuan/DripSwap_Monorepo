import { createRoute } from '@tanstack/react-router';

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
import { ArrowLeftRight, ArrowRight, FlaskConical, ShieldAlert, Waves } from '@/shared/icons';

const FEATURE_CARDS = [
  {
    title: 'Swap',
    description: 'Swap tokens seamlessly on Sepolia and Scroll.',
    icon: <ArrowLeftRight className="size-6" aria-hidden="true" />,
  },
  {
    title: 'Liquidity Provision',
    description: 'Provide liquidity and earn simulated rewards.',
    icon: <Waves className="size-6" aria-hidden="true" />,
  },
  {
    title: 'Test Token Faucet',
    description: 'Claim test tokens to explore workflows.',
    icon: <FlaskConical className="size-6" aria-hidden="true" />,
  },
  {
    title: 'Cross-chain Bridge',
    description: 'Move assets between mock networks.',
    icon: <ArrowRight className="size-6" aria-hidden="true" />,
  },
] as const;

const IndexPage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();

  return (
    <div className="flex flex-col gap-[var(--space-2xl)]">
      <section className="relative isolate overflow-hidden bg-[radial-gradient(circle_at_top,_rgba(30,102,245,0.18),_transparent_55%)] pb-[var(--space-2xl)] pt-[var(--space-xl)]">
        <div
          className="pointer-events-none absolute inset-x-0 top-0 h-1/2 bg-gradient-to-b from-primary/12 to-transparent"
          aria-hidden="true"
        />
        <div className="mx-auto flex w-full max-w-[1200px] flex-col items-center gap-[var(--space-lg)] px-6 text-center">
          <Badge
            variant="outline"
            className="rounded-[var(--radius-pill)] bg-primary/10 text-primary"
          >
            Live environment
          </Badge>
          <div className="flex flex-col gap-[var(--space-sm)]">
            <h1
              ref={headingRef}
              tabIndex={-1}
              className="text-4xl font-semibold tracking-tight text-foreground sm:text-5xl"
            >
              Learn and Experiment with DripSwap
            </h1>
            <p className="mx-auto max-w-2xl text-base text-muted-foreground sm:text-lg">
              A DEX for Sepolia and Scroll. Explore swaps, liquidity, and bridges with live data.
            </p>
          </div>
          <Button size="lg" className="px-[var(--space-xl)]" asChild>
            <a href="/swap" aria-label="Start exploring swaps">
              Get Started
            </a>
          </Button>
        </div>
      </section>

      <section className="mx-auto w-full max-w-[1200px] px-6">
        <header className="mb-[var(--space-lg)] text-center">
          <h2 className="text-2xl font-semibold tracking-tight text-foreground">Features</h2>
          <p className="mt-[var(--space-xs)] text-base text-muted-foreground">
            UI flows reflect live API/chain state.
          </p>
        </header>
        <div className="grid gap-[var(--space-md)] sm:grid-cols-2 xl:grid-cols-4">
          {FEATURE_CARDS.map((feature) => (
            <Card key={feature.title} className="h-full border-border/70 bg-surface-elevated/80">
              <CardHeader className="gap-[var(--space-md)] text-left">
                <span className="flex size-12 items-center justify-center rounded-[var(--radius-pill)] bg-primary/12 text-primary">
                  {feature.icon}
                </span>
                <CardTitle className="text-lg font-semibold text-foreground">
                  {feature.title}
                </CardTitle>
              </CardHeader>
              <CardContent className="pt-0 text-sm text-muted-foreground">
                {feature.description}
              </CardContent>
            </Card>
          ))}
        </div>
      </section>

      <section className="mx-auto w-full max-w-[900px] px-6">
        <Card className="border-warning/30 bg-warning/10">
          <CardHeader className="flex flex-row items-start gap-[var(--space-sm)]">
            <span
              className="rounded-[var(--radius-pill)] bg-warning/25 p-[var(--space-sm)] text-warning"
              aria-hidden="true"
            >
              <ShieldAlert className="size-5" />
            </span>
            <div className="flex flex-col gap-[var(--space-xs)] text-left">
              <CardTitle className="text-lg text-foreground">Safety Disclaimer</CardTitle>
              <CardDescription className="text-sm text-muted-foreground">
                This sandbox is for educational purposes only. Please avoid using production wallets
                or real assets; DripSwap is not responsible for any losses incurred.
              </CardDescription>
            </div>
          </CardHeader>
        </Card>
      </section>

      <footer className="border-t border-border/60 bg-background/80">
        <div className="mx-auto flex w-full max-w-[1200px] flex-col items-center justify-between gap-[var(--space-sm)] px-6 py-[var(--space-lg)] text-sm text-muted-foreground md:flex-row">
          <p>© {new Date().getFullYear()} DripSwap. All rights reserved.</p>
          <div className="flex items-center gap-[var(--space-sm)]">
            <a href="/terms" className="hover:text-foreground focus-visible:text-foreground">
              Terms of Service
            </a>
            <span aria-hidden="true">•</span>
            <a href="/privacy" className="hover:text-foreground focus-visible:text-foreground">
              Privacy Policy
            </a>
          </div>
        </div>
      </footer>
    </div>
  );
};

export const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  component: IndexPage,
});
