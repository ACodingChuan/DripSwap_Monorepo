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
  Input,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  toast,
} from '@/shared/ui';
import { requestFaucet } from '@/app/services/faucet-service';

const NETWORKS = ['Sepolia', 'Scroll'] as const;
const TOKENS = ['USDC', 'DAI', 'WETH'] as const;

const FaucetPage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();
  const [network, setNetwork] = useState<(typeof NETWORKS)[number]>('Sepolia');
  const [token, setToken] = useState<(typeof TOKENS)[number]>('USDC');

  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const formData = new FormData(event.currentTarget as HTMLFormElement);
    const recipient = String(formData.get('recipient') || '');
    const amount = String(formData.get('amount') || '');
    setSubmitting(true);
    try {
      const res = await requestFaucet({ network, token, amount, recipient });
      toast('Faucet request sent', {
        description: `Request ${res.id} status: ${res.status}`,
      });
    } catch (e: any) {
      toast('Faucet request failed', { description: e?.message ?? 'Unknown error' });
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[720px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-xs)] text-center">
        <Badge variant="outline" className="self-center" aria-live="polite">
          Request tokens
        </Badge>
        <h1
          ref={headingRef}
          tabIndex={-1}
          className="text-3xl font-semibold tracking-tight text-foreground focus:outline-none sm:text-4xl"
        >
          Faucet
        </h1>
        <p className="mx-auto max-w-2xl text-base text-muted-foreground">
          Claim configured token balances on Sepolia and Scroll.
        </p>
      </header>

      <Card>
        <form
          className="flex flex-col gap-[var(--space-lg)]"
          onSubmit={handleSubmit}
          aria-labelledby="faucet-section-heading"
        >
          <CardHeader className="flex flex-col gap-[var(--space-sm)]">
            <div className="flex flex-col gap-[var(--space-xs)]">
              <CardTitle id="faucet-section-heading" className="text-xl">
                Request details
              </CardTitle>
              <CardDescription>
                Select a network and token, then enter a recipient address.
              </CardDescription>
            </div>
          </CardHeader>
          <CardContent className="flex flex-col gap-[var(--space-md)]">
            <label
              className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground"
              htmlFor="faucet-network"
            >
              Network
              <Select
                value={network}
                onValueChange={(value) => setNetwork(value as (typeof NETWORKS)[number])}
              >
                <SelectTrigger id="faucet-network">
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
              htmlFor="faucet-token"
            >
              Token
              <Select
                value={token}
                onValueChange={(value) => setToken(value as (typeof TOKENS)[number])}
              >
                <SelectTrigger id="faucet-token">
                  <SelectValue placeholder="Select token" />
                </SelectTrigger>
                <SelectContent>
                  {TOKENS.map((option) => (
                    <SelectItem key={option} value={option}>
                      {option}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </label>
            <label
              className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground"
              htmlFor="faucet-address"
            >
              Recipient address
              <Input
                id="faucet-address"
                name="recipient"
                placeholder="0x0000..."
                aria-describedby="faucet-helper"
                required
              />
              <span id="faucet-helper" className="text-xs text-muted-foreground">
                Enter a valid address.
              </span>
            </label>
            <label
              className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground"
              htmlFor="faucet-amount"
            >
              Amount
              <Input
                id="faucet-amount"
                name="amount"
                type="number"
                min="0"
                step="0.1"
                placeholder="100"
              />
            </label>
          </CardContent>
          <div className="px-[var(--space-xl)] pb-[var(--space-xl)]">
            <Button type="submit" size="lg" className="w-full justify-center" disabled={submitting}>
              {submitting ? 'Submitting…' : 'Claim tokens'}
            </Button>
          </div>
        </form>
      </Card>

      <Card className="border-border/70 bg-surface-elevated/60">
        <CardHeader>
          <CardTitle className="text-lg">Recent requests</CardTitle>
          <CardDescription>Recent transactions appear here when available.</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-[var(--space-sm)] text-sm text-muted-foreground">
          <div className="flex items-center justify-between rounded-[var(--radius-card)] border border-border/60 bg-background px-[var(--space-md)] py-[var(--space-sm)]">
            <span>
              {network} · {token}
            </span>
            <span className="text-foreground">Tx #0xFAUCET123</span>
          </div>
          <p className="text-xs">History persists only for the current session.</p>
        </CardContent>
      </Card>
    </main>
  );
};

export const faucetRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/faucet',
  component: FaucetPage,
});
