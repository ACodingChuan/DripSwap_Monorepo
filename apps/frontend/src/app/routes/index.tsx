import { Link, createRoute } from '@tanstack/react-router';

import { rootRoute } from './root';
import { usePageFocus } from '@/shared/hooks';
import { Badge, Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui';
import { ArrowLeftRight, ArrowRight, Download, FlaskConical, Mail, MessageCircle, ShieldAlert, Waves } from '@/shared/icons';

const FLOW_STEPS = [
  {
    step: '01',
    title: 'Claim Faucet Test Tokens',
    description: 'Claim vUSDC / vETH (and more) to try swaps, liquidity, and bridging.',
    icon: <FlaskConical className="size-6" aria-hidden="true" />,
    cta: { label: 'Go to Faucet', to: '/faucet' },
  },
  {
    step: '02',
    title: 'Use Uniswap V2 (Swap / Pool)',
    description: 'Swap on Sepolia / Scroll Sepolia, or add/remove liquidity like a real V2 DEX.',
    icon: <ArrowLeftRight className="size-6" aria-hidden="true" />,
    cta: { label: 'Go to Swap', to: '/swap' },
  },
  {
    step: '03',
    title: 'Bridge Cross-chain (CCIP)',
    description: 'Bridge via Chainlink CCIP, then track progress by messageId and open the official explorer.',
    icon: <ArrowRight className="size-6" aria-hidden="true" />,
    cta: { label: 'Go to Bridge', to: '/bridge' },
  },
] as const;

const FEATURE_CARDS = [
  {
    title: 'Swap & Pool (V2)',
    description: 'Front-end talks to the chain directly for Swap / Add / Remove Liquidity.',
    icon: <Waves className="size-6" aria-hidden="true" />,
  },
  {
    title: 'FaucetV2 (Risk Control + Relayer)',
    description: 'Backend signer + relayer-sponsored gas, with captcha/limits/inventory + receipt confirmation.',
    icon: <FlaskConical className="size-6" aria-hidden="true" />,
  },
  {
    title: 'CCIP messageId Tracking',
    description: 'Extract messageId from the tx receipt, store it in BFF, search it, and open CCIP explorer.',
    icon: <ArrowRight className="size-6" aria-hidden="true" />,
  },
  {
    title: 'Subgraph + BFF Aggregation',
    description: 'Goldsky/The Graph indexing + BFF multi-chain query/caching for Explore dashboards.',
    icon: <ArrowLeftRight className="size-6" aria-hidden="true" />,
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
          <Badge variant="outline" className="rounded-[var(--radius-pill)] bg-primary/10 text-primary">
            Demo / Testnet
          </Badge>
          <div className="flex flex-col gap-[var(--space-sm)]">
            <h1
              ref={headingRef}
              tabIndex={-1}
              className="text-4xl font-semibold tracking-tight text-foreground sm:text-5xl"
            >
              DripSwap: a demo-ready multi-chain Uniswap V2 DEX
            </h1>
            <p className="mx-auto max-w-3xl text-base text-muted-foreground sm:text-lg">
              Recommended flow: claim Faucet tokens → swap / provide liquidity → bridge via CCIP. This project is also a job-search demo (feel free to reach out).
            </p>
          </div>

          <div className="flex w-full max-w-xl flex-col gap-[var(--space-sm)] sm:flex-row sm:justify-center">
            <Button size="lg" className="px-[var(--space-xl)]" asChild>
              <Link to="/faucet" aria-label="Start from faucet">
                Start from Faucet
              </Link>
            </Button>
            <Button size="lg" variant="outline" className="px-[var(--space-xl)]" asChild>
              <Link to="/bridge" aria-label="Jump to bridge">
                Jump to Bridge
              </Link>
            </Button>
          </div>
        </div>
      </section>

      <section className="mx-auto w-full max-w-[1200px] px-6">
        <header className="mb-[var(--space-lg)] text-center">
          <h2 className="text-2xl font-semibold tracking-tight text-foreground">End-to-end Flow</h2>
          <p className="mt-[var(--space-xs)] text-base text-muted-foreground">
            Complete: Faucet → Swap/Pool → Bridge, and track CCIP messageId on the Bridge page.
          </p>
        </header>

        <div className="grid gap-[var(--space-md)] md:grid-cols-3">
          {FLOW_STEPS.map((s) => (
            <Card key={s.step} className="h-full border-border/70 bg-surface-elevated/80">
              <CardHeader className="gap-[var(--space-md)] text-left">
                <div className="flex items-center justify-between">
                  <span className="flex size-12 items-center justify-center rounded-[var(--radius-pill)] bg-primary/12 text-primary">
                    {s.icon}
                  </span>
                  <span className="text-xs font-medium tracking-widest text-muted-foreground">STEP {s.step}</span>
                </div>
                <CardTitle className="text-lg font-semibold text-foreground">{s.title}</CardTitle>
                <CardDescription className="text-sm text-muted-foreground">{s.description}</CardDescription>
              </CardHeader>
              <CardContent className="pt-0">
                <Button className="w-full" asChild>
                  <Link to={s.cta.to}>{s.cta.label}</Link>
                </Button>
              </CardContent>
            </Card>
          ))}
        </div>
      </section>

      <section className="mx-auto w-full max-w-[1200px] px-6">
        <div className="grid gap-[var(--space-md)] md:grid-cols-[1.25fr_0.75fr]">
          <Card className="h-full border-border/70">
            <CardHeader className="text-left">
              <CardTitle className="text-xl">What This Demo Shows (Job Portfolio)</CardTitle>
              <CardDescription className="text-sm text-muted-foreground">
                A full-stack Web3 demo: front-end wallet flows, BFF + risk controls, contracts + subgraph indexing, and cross-chain tracking.
              </CardDescription>
            </CardHeader>
            <CardContent className="grid gap-[var(--space-md)] sm:grid-cols-2">
              {FEATURE_CARDS.map((feature) => (
                <Card key={feature.title} className="h-full border-border/60 bg-surface-elevated/60">
                  <CardHeader className="gap-[var(--space-md)] text-left">
                    <span className="flex size-10 items-center justify-center rounded-[var(--radius-pill)] bg-primary/12 text-primary">
                      {feature.icon}
                    </span>
                    <CardTitle className="text-base font-semibold text-foreground">{feature.title}</CardTitle>
                  </CardHeader>
                  <CardContent className="pt-0 text-sm text-muted-foreground">{feature.description}</CardContent>
                </Card>
              ))}
            </CardContent>
          </Card>

          <Card className="h-full border-border/70">
            <CardHeader className="text-left">
              <CardTitle className="text-xl">Contact</CardTitle>
              <CardDescription className="text-sm text-muted-foreground">
                I’m currently looking for a Web3 role (full-stack / smart contracts / backend). If you’d like to offer me an interview opportunity, please contact me.
              </CardDescription>
            </CardHeader>
            <CardContent className="flex flex-col gap-[var(--space-md)] text-sm">
              <div className="flex items-center gap-[var(--space-sm)]">
                <span className="flex size-9 items-center justify-center rounded-[var(--radius-pill)] bg-primary/12 text-primary">
                  <Mail className="size-4" aria-hidden="true" />
                </span>
                <div className="flex flex-col">
                  <span className="text-muted-foreground">Email</span>
                  <a
                    href="mailto:yznt7381@hotmail.com"
                    className="font-medium text-foreground hover:text-primary focus-visible:text-primary"
                  >
                    yznt7381@hotmail.com
                  </a>
                </div>
              </div>

              <div className="flex items-center gap-[var(--space-sm)]">
                <span className="flex size-9 items-center justify-center rounded-[var(--radius-pill)] bg-primary/12 text-primary">
                  <MessageCircle className="size-4" aria-hidden="true" />
                </span>
                <div className="flex flex-col">
                  <span className="text-muted-foreground">Telegram</span>
                  <a
                    href="https://t.me/KopChuan"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="font-medium text-foreground hover:text-primary focus-visible:text-primary"
                  >
                    t.me/KopChuan
                  </a>
                </div>
              </div>

              <div className="pt-1">
                <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Resume</div>
                <div className="mt-2 grid gap-2">
                  <Button variant="outline" className="w-full justify-start gap-2" asChild>
                    <a href="/resume/resumezh-CN.pdf" download>
                      <Download className="size-4" aria-hidden="true" />
                      Download Resume (中文)
                    </a>
                  </Button>
                  <Button variant="outline" className="w-full justify-start gap-2" asChild>
                    <a href="/resume/resume-en.pdf" download>
                      <Download className="size-4" aria-hidden="true" />
                      Download Resume (EN)
                    </a>
                  </Button>
                </div>
              </div>

              <div className="pt-[var(--space-sm)]">
                <Button variant="outline" className="w-full" asChild>
                  <Link to="/explore">Open Explore Dashboard</Link>
                </Button>
              </div>
            </CardContent>
          </Card>
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
                This demo runs on testnets for learning and showcasing only. Do not use production wallets or real assets.
              </CardDescription>
            </div>
          </CardHeader>
        </Card>
      </section>

      <footer className="border-t border-border/60 bg-background/80">
        <div className="mx-auto flex w-full max-w-[1200px] flex-col items-center justify-between gap-[var(--space-sm)] px-6 py-[var(--space-lg)] text-sm text-muted-foreground md:flex-row">
          <p>© {new Date().getFullYear()} DripSwap（Testnet Demo）</p>
          <p>
            Contact:{' '}
            <a className="hover:text-foreground" href="mailto:yznt7381@hotmail.com">
              yznt7381@hotmail.com
            </a>
          </p>
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
