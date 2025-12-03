import { createRoute } from '@tanstack/react-router';
import { useMemo, useState } from 'react';

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
  Input,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  toast,
} from '@/shared/ui';
import { AlertTriangle, Droplet } from '@/shared/icons';

const NETWORKS = ['Sepolia', 'Scroll'] as const;
const POOLS = ['WETH / USDC', 'DAI / ETH', 'WBTC / USDT'] as const;

const PoolsAddPage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();
  const [network, setNetwork] = useState<(typeof NETWORKS)[number]>('Sepolia');
  const [pool, setPool] = useState<(typeof POOLS)[number]>('WETH / USDC');
  const [tokenA, setTokenA] = useState('');
  const [tokenB, setTokenB] = useState('');
  const [showPreview, setShowPreview] = useState(false);

  const isMismatch = useMemo(() => network !== 'Sepolia', [network]);

  const handlePreview = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isMismatch) {
      return;
    }
    setShowPreview(true);
  };

  const handleAddLiquidity = () => {
    toast('Liquidity add requested', {
      description: 'On-chain transactions will be submitted by the connected wallet/backend.',
    });
  };

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[720px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-xs)] text-center">
        <Badge variant="outline" className="self-center">
          Provide liquidity
        </Badge>
        <h1
          ref={headingRef}
          tabIndex={-1}
          className="text-3xl font-semibold tracking-tight text-foreground focus:outline-none sm:text-4xl"
        >
          Add liquidity
        </h1>
        <p className="mx-auto max-w-2xl text-base text-muted-foreground">
          Select a pool and enter token amounts to preview share and fees based on live data.
        </p>
      </header>

      {isMismatch && (
        <Card className="border-warning/40 bg-warning/15">
          <CardHeader className="flex flex-row items-start gap-[var(--space-sm)]">
            <span
              className="rounded-[var(--radius-pill)] bg-warning/30 p-[var(--space-sm)] text-warning"
              aria-hidden="true"
            >
              <AlertTriangle className="size-5" />
            </span>
            <div className="flex flex-col gap-[var(--space-xs)] text-left">
              <CardTitle className="text-lg">Switch to Sepolia</CardTitle>
              <CardDescription>
                This pool is configured for Sepolia. Change the network to continue.
              </CardDescription>
            </div>
          </CardHeader>
        </Card>
      )}

      <Card>
        <form
          className="flex flex-col gap-[var(--space-lg)]"
          onSubmit={handlePreview}
          aria-labelledby="add-liquidity-heading"
        >
          <CardHeader className="flex flex-col gap-[var(--space-sm)]">
            <div className="flex flex-col gap-[var(--space-xs)]">
              <CardTitle id="add-liquidity-heading" className="text-xl">
                Position details
              </CardTitle>
              <CardDescription>Token ratios will follow live quotes/oracle data.</CardDescription>
            </div>
          </CardHeader>
          <CardContent className="flex flex-col gap-[var(--space-md)]">
            <label
              className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground"
              htmlFor="network"
            >
              Network
              <Select
                value={network}
                onValueChange={(value) => setNetwork(value as (typeof NETWORKS)[number])}
              >
                <SelectTrigger id="network">
                  <SelectValue placeholder="Select network" />
                </SelectTrigger>
                <SelectContent>
                  {NETWORKS.map((option) => (
                    <SelectItem key={option} value={option}>
                      {option}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </label>

            <label
              className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground"
              htmlFor="pool"
            >
              Pool
              <Select
                value={pool}
                onValueChange={(value) => setPool(value as (typeof POOLS)[number])}
              >
                <SelectTrigger id="pool">
                  <SelectValue placeholder="Select pool" />
                </SelectTrigger>
                <SelectContent>
                  {POOLS.map((option) => (
                    <SelectItem key={option} value={option}>
                      {option}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </label>

            <div className="grid gap-[var(--space-sm)] sm:grid-cols-2">
              <label
                className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground"
                htmlFor="token-a"
              >
                Amount token A
                <Input
                  id="token-a"
                  type="number"
                  min="0"
                  step="0.0001"
                  placeholder="0.00"
                  value={tokenA}
                  onChange={(event) => setTokenA(event.target.value)}
                />
              </label>
              <label
                className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground"
                htmlFor="token-b"
              >
                Amount token B
                <Input
                  id="token-b"
                  type="number"
                  min="0"
                  step="0.0001"
                  placeholder="0.00"
                  value={tokenB}
                  onChange={(event) => setTokenB(event.target.value)}
                />
              </label>
            </div>

            <div className="rounded-[var(--radius-card)] border border-dashed border-border/60 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
              Liquidity tokens are minted according to current oracle prices. Slippage settings are
              located in the swap preferences dialog.
            </div>
          </CardContent>

          <div className="flex flex-col gap-[var(--space-sm)] px-[var(--space-xl)] pb-[var(--space-xl)]">
            <Button type="submit" size="md" disabled={isMismatch}>
              Preview
            </Button>
            {showPreview && !isMismatch && (
              <div className="rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
                <ul className="flex flex-col gap-[var(--space-xs)]">
                  <li className="flex items-center justify-between">
                    <span>Pool</span>
                    <span className="font-medium text-foreground">{pool}</span>
                  </li>
                  <li className="flex items-center justify-between">
                    <span>Estimated share</span>
                    <span className="font-medium text-foreground">0.98%</span>
                  </li>
                  <li className="flex items-center justify-between">
                    <span>Price impact</span>
                    <span className="font-medium text-warning">0.15%</span>
                  </li>
                  <li className="flex items-center justify-between">
                    <span>Protocol fee</span>
                    <span className="font-medium text-foreground">$4.10</span>
                  </li>
                </ul>
              </div>
            )}
            <Button
              type="button"
              size="lg"
              className="justify-center"
              onClick={handleAddLiquidity}
              disabled={isMismatch || !showPreview}
            >
              Add liquidity
            </Button>
          </div>
        </form>
      </Card>

      <Card className="border-border/70 bg-surface-elevated/60">
        <CardHeader className="flex flex-row items-start gap-[var(--space-sm)]">
          <span
            className="rounded-[var(--radius-pill)] bg-primary/15 p-[var(--space-sm)] text-primary"
            aria-hidden="true"
          >
            <Droplet className="size-5" />
          </span>
          <div className="flex flex-col gap-[var(--space-xs)] text-left">
            <CardTitle className="text-lg">Liquidity guidance</CardTitle>
            <CardDescription>
              Deposits, rewards, and shares reflect live network state.
            </CardDescription>
          </div>
        </CardHeader>
      </Card>
    </main>
  );
};

export const poolsAddRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/pools/add',
  component: PoolsAddPage,
});
