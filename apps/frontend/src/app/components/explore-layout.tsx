import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';

import { cn } from '@/shared/utils';
import { ExploreProtocolStats } from '@/app/components/explore-protocol-stats';

type ExploreTab = 'tokens' | 'pools' | 'transactions';

const TABS: Array<{ key: ExploreTab; label: string; path: string }> = [
  { key: 'tokens', label: 'Tokens', path: '/explore/tokens' },
  { key: 'pools', label: 'Pools', path: '/explore/pools' },
  { key: 'transactions', label: 'Transactions', path: '/explore/transactions' },
];

export function ExploreLayout({
  activeTab,
  title,
  description,
  children,
}: {
  activeTab: ExploreTab;
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[1200px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-sm)]">
        <div className="flex flex-col gap-[var(--space-xs)]">
          <h1 className="text-3xl font-semibold tracking-tight text-foreground sm:text-4xl">
            {title}
          </h1>
          <p className="max-w-3xl text-base text-muted-foreground">{description}</p>
        </div>

        <ExploreProtocolStats />

        <nav aria-label="Explore tabs" className="flex flex-wrap items-center gap-2">
          {TABS.map((tab) => (
            <Link
              key={tab.key}
              to={tab.path}
              className={cn(
                'inline-flex h-9 items-center justify-center rounded-md border px-3 text-sm font-medium transition-colors',
                tab.key === activeTab
                  ? 'border-border bg-secondary text-foreground shadow-sm'
                  : 'border-border/60 bg-background text-muted-foreground hover:text-foreground'
              )}
            >
              {tab.label}
            </Link>
          ))}
        </nav>
      </header>

      {children}
    </main>
  );
}
