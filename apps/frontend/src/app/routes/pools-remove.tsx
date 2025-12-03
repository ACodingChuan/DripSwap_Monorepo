import { createRoute } from '@tanstack/react-router';
import { useState } from 'react';

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
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
  Input,
  toast,
} from '@/shared/ui';
import { AlertTriangle } from '@/shared/icons';

const PoolsRemovePage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();
  const [percentage, setPercentage] = useState(25);
  const [manualPercentage, setManualPercentage] = useState('25');
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);

  const simulatedReceive = (((parseFloat(manualPercentage || '0') || 0) / 100) * 1680.96).toFixed(
    2
  );

  const handleSliderChange = (value: number) => {
    setPercentage(value);
    setManualPercentage(String(value));
  };

  const handleManualChange = (event: React.ChangeEvent<HTMLInputElement>) => {
    const next = event.target.value;
    setManualPercentage(next);
    const parsed = parseFloat(next);
    if (!Number.isNaN(parsed)) {
      setPercentage(Math.min(100, Math.max(0, parsed)));
    }
  };

  const handleRemove = () => {
    toast('Liquidity removal requested', {
      description: 'On-chain transactions will be submitted by the connected wallet/backend.',
    });
  };

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[720px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-xs)] text-center">
        <Badge variant="outline" className="self-center">
          Exit position
        </Badge>
        <h1
          ref={headingRef}
          tabIndex={-1}
          className="text-3xl font-semibold tracking-tight text-foreground focus:outline-none sm:text-4xl"
        >
          Remove liquidity
        </h1>
        <p className="mx-auto max-w-2xl text-base text-muted-foreground">
          Adjust the percentage to estimate returns. Numbers reflect live backend calculations.
        </p>
      </header>

      <Card>
        <CardHeader className="flex flex-col gap-[var(--space-sm)]">
          <CardTitle className="text-xl">Position summary</CardTitle>
          <CardDescription>WETH / USDC · Deposit $12,400.00 · Share 1.92%</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-[var(--space-lg)]">
          <div className="flex flex-col gap-[var(--space-sm)]">
            <label htmlFor="liquidity-range" className="text-sm font-medium text-muted-foreground">
              Percentage to remove
            </label>
            <Input
              id="liquidity-range"
              type="range"
              min="0"
              max="100"
              step="1"
              value={percentage}
              onChange={(event) => handleSliderChange(Number(event.target.value))}
              className="h-2 cursor-pointer rounded-full border-none bg-gradient-to-r from-primary to-primary"
              aria-valuetext={`${percentage}%`}
            />
            <div className="flex items-center justify-between text-sm text-muted-foreground">
              <span>0%</span>
              <span>100%</span>
            </div>
          </div>

          <div className="grid gap-[var(--space-sm)] sm:grid-cols-2">
            <label
              className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground"
              htmlFor="manual-percentage"
            >
              Manual percentage
              <div className="relative">
                <Input
                  id="manual-percentage"
                  type="number"
                  min="0"
                  max="100"
                  step="1"
                  value={manualPercentage}
                  onChange={handleManualChange}
                  className="pr-10"
                />
                <span className="pointer-events-none absolute right-3 top-1/2 -translate-y-1/2 text-sm text-muted-foreground">
                  %
                </span>
              </div>
            </label>
            <div className="flex flex-col gap-[var(--space-xs)]">
              <span className="text-sm font-medium text-muted-foreground">You will receive</span>
              <div className="rounded-[var(--radius-card)] border border-border/60 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-foreground">
                {simulatedReceive} USDC
              </div>
              <span className="text-xs text-muted-foreground">
                Includes protocol fees and estimated network fee.
              </span>
            </div>
          </div>

          <div className="flex flex-col gap-[var(--space-sm)]">
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-muted-foreground">Gas fee</span>
              <span className="text-sm text-foreground">0.00054 ETH (~$1.81)</span>
            </div>
            <div className="flex items-center justify-between">
              <span className="text-sm font-medium text-muted-foreground">Slippage</span>
              <div className="flex items-center gap-[var(--space-xs)]">
                <span className="text-sm text-foreground">0.5%</span>
                <Dialog open={isSettingsOpen} onOpenChange={setIsSettingsOpen}>
                  <DialogTrigger asChild>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="px-[var(--space-sm)]"
                      aria-haspopup="dialog"
                    >
                      Edit
                    </Button>
                  </DialogTrigger>
                  <DialogContent className="gap-[var(--space-md)]">
                    <DialogHeader>
                      <DialogTitle className="text-xl">Adjust slippage</DialogTitle>
                    </DialogHeader>
                    <p className="text-sm text-muted-foreground">
                      Configure slippage in swap preferences.
                    </p>
                    <div className="flex items-center justify-between rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] text-sm text-muted-foreground">
                      <span>Current setting</span>
                      <span className="text-foreground">0.5%</span>
                    </div>
                  </DialogContent>
                </Dialog>
              </div>
            </div>
          </div>

          <div className="rounded-[var(--radius-card)] border border-warning/40 bg-warning/15 p-[var(--space-md)] text-sm text-muted-foreground">
            <span className="flex items-center gap-[var(--space-xs)] font-medium text-warning">
              <AlertTriangle className="size-4" aria-hidden="true" /> Important
            </span>
            <p className="mt-[var(--space-xs)]">
              Removing liquidity exits the position and resets unclaimed rewards.
            </p>
          </div>

          <Button type="button" size="lg" className="justify-center" onClick={handleRemove}>
            Remove liquidity
          </Button>
        </CardContent>
      </Card>
    </main>
  );
};

export const poolsRemoveRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/pools/remove',
  component: PoolsRemovePage,
});
