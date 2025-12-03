import { createRoute } from '@tanstack/react-router';

import { usePageFocus } from '@/shared/hooks';
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
  Input,
} from '@/shared/ui';
import { rootRoute } from './root';

const WalletPage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-3xl flex-col gap-[var(--space-lg)] px-6 py-10 lg:px-10">
      <header className="flex flex-col gap-[var(--space-xs)]">
        <Badge variant="outline" className="self-start" aria-live="polite">
          Mock data only
        </Badge>
        <h1
          ref={headingRef}
          tabIndex={-1}
          className="text-3xl font-semibold tracking-tight text-foreground focus:outline-none sm:text-4xl"
        >
          Wallet connection
        </h1>
        <p className="max-w-2xl text-base text-muted-foreground">
          This page previews how wallet selection will look. No real wallet SDKs are loaded in Phase
          1.
        </p>
      </header>

      <Card>
        <CardHeader>
          <CardTitle className="text-xl">Select wallet provider</CardTitle>
          <CardDescription>Populate the form to simulate a wallet connection flow.</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-[var(--space-md)]">
          <label
            className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground"
            htmlFor="wallet-alias"
          >
            Session alias
            <Input id="wallet-alias" name="alias" placeholder="My wallet" />
          </label>
          <fieldset
            className="flex flex-col gap-[var(--space-xs)]"
            aria-label="Wallet provider options"
          >
            <legend className="text-sm font-medium text-muted-foreground">Choose a provider</legend>
            <label className="flex items-center gap-[var(--space-sm)] text-sm text-foreground">
              <input
                type="radio"
                name="provider"
                value="injected"
                disabled
                aria-disabled
                className="accent-primary"
              />
              Injected wallet (coming soon)
            </label>
            <label className="flex items-center gap-[var(--space-sm)] text-sm text-foreground">
              <input
                type="radio"
                name="provider"
                value="hardware"
                disabled
                aria-disabled
                className="accent-primary"
              />
              Hardware wallet (coming soon)
            </label>
            <label className="flex items-center gap-[var(--space-sm)] text-sm text-foreground">
              <input
                type="radio"
                name="provider"
                value="mobile"
                disabled
                aria-disabled
                className="accent-primary"
              />
              Mobile wallet (coming soon)
            </label>
          </fieldset>
        </CardContent>
        <CardFooter>
          <Button
            type="button"
            disabled
            aria-disabled
            className="w-full justify-center"
            aria-label="Connect wallet"
          >
            Connect wallet (coming soon)
          </Button>
        </CardFooter>
      </Card>
    </main>
  );
};

export const walletRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/wallet',
  component: WalletPage,
});
