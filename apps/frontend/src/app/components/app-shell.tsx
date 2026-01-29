import {
  useState,
  useRef,
  useEffect,
  type PropsWithChildren,
  type FocusEvent,
  type KeyboardEvent,
  type MouseEvent,
} from 'react';
import { Link, useRouterState } from '@tanstack/react-router';
import { useAccount, useSignMessage, useDisconnect, useSwitchChain, useConfig } from 'wagmi';
import { ConnectButton } from '@rainbow-me/rainbowkit';
import { sepolia, scrollSepolia } from 'wagmi/chains';

import {
  Button,
  Badge,
  Dialog,
  DialogTrigger,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogClose,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  toast,
} from '@/shared/ui';
import { cn } from '@/shared/utils';
import { Droplet, ChevronDown, Menu, LogOut, Network } from '@/shared/icons';
import { useAuthStore } from '@/shared/store/authStore';
import { authService } from '@/app/services/auth-service';

const SUPPORTED_CHAINS = [sepolia, scrollSepolia];

const NAV_ITEMS = [
  { label: 'Home', path: '/' },
  { label: 'Swap', path: '/swap' },
  { label: 'Explore', path: '/explore' },
  {
    label: 'Liquidity',
    path: '/pools/mine',
    submenu: [
      { label: 'Manage', path: '/pools/mine' },
    ],
  },
  { label: 'Faucet', path: '/faucet' },
  { label: 'Bridge', path: '/bridge' },
] as const;

export function AppShell({ children }: PropsWithChildren) {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:left-[var(--space-lg)] focus:top-[var(--space-lg)] focus:z-50 focus:rounded-full focus:bg-primary focus:px-[var(--space-md)] focus:py-[var(--space-sm)] focus:text-primary-foreground focus:shadow-lg"
      >
        Skip to main content
      </a>
      <SiteHeader />
      <main id="main-content" className="pb-[var(--space-xl)] pt-20">
        {children}
      </main>
    </div>
  );
}

function SiteHeader() {
  return (
    <header className="sticky top-0 z-40 border-b border-border/60 bg-background/85 backdrop-blur">
      <div className="flex h-16 w-full items-center justify-between gap-[var(--space-md)] px-6">
        <Brand />
        <PrimaryNav />
        <div className="flex items-center gap-[var(--space-sm)]">
          <MobileMenu />
          <RightActions />
        </div>
      </div>
    </header>
  );
}

function Brand() {
  return (
    <Button variant="ghost" size="sm" className="-ml-2 px-[var(--space-sm)]" asChild>
      <Link to="/" className="flex items-center gap-[var(--space-sm)]" aria-label="Go to home">
        <span className="flex size-9 items-center justify-center rounded-full bg-primary text-primary-foreground shadow-sm">
          <Droplet className="size-4" aria-hidden="true" />
        </span>
        <span className="text-base font-semibold tracking-tight">DripSwap</span>
      </Link>
    </Button>
  );
}

function PrimaryNav() {
  const pathname = useRouterState({ select: (state) => state.location.pathname });

  return (
    <nav aria-label="Primary" className="hidden items-center gap-[var(--space-sm)] lg:flex">
      {NAV_ITEMS.map((item) =>
        'submenu' in item ? (
          <LiquidityMenu key={item.label} pathname={pathname} />
        ) : (
          <NavLink key={item.path} to={item.path} pathname={pathname} label={item.label} />
        )
      )}
    </nav>
  );
}

function MobileMenu() {
  return (
    <Dialog>
      <DialogTrigger asChild>
        <Button variant="ghost" size="sm" className="lg:hidden px-2" aria-label="Open navigation">
          <Menu className="size-5" aria-hidden="true" />
        </Button>
      </DialogTrigger>
      <DialogContent className="w-[min(22rem,90vw)] gap-[var(--space-md)] px-0 py-[var(--space-lg)]">
        <DialogHeader className="px-[var(--space-lg)]">
          <DialogTitle>Navigate</DialogTitle>
        </DialogHeader>
        <nav
          className="flex flex-col gap-[var(--space-xs)] px-[var(--space-lg)]"
          aria-label="Mobile"
        >
          {NAV_ITEMS.map((item) =>
            'submenu' in item ? (
              <div key={item.label} className="flex flex-col gap-[var(--space-xs)]">
                <DialogClose asChild>
                  <Button variant="ghost" size="md" className="justify-start" asChild>
                    <Link to={item.path}>{item.label}</Link>
                  </Button>
                </DialogClose>
                {item.submenu.map((subItem) => (
                  <DialogClose asChild key={subItem.path}>
                    <Button
                      variant="ghost"
                      size="sm"
                      className="justify-start pl-[calc(var(--space-lg)+var(--space-sm))] text-sm text-muted-foreground"
                      asChild
                    >
                      <Link to={subItem.path}>{subItem.label}</Link>
                    </Button>
                  </DialogClose>
                ))}
              </div>
            ) : (
              <DialogClose asChild key={item.path}>
                <Button variant="ghost" size="md" className="justify-start" asChild>
                  <Link to={item.path}>{item.label}</Link>
                </Button>
              </DialogClose>
            )
          )}
        </nav>
      </DialogContent>
    </Dialog>
  );
}

function RightActions() {
  return (
    <div className="flex items-center gap-[var(--space-sm)]">
      <NetworkSwitcher />
      <WalletButton />
    </div>
  );
}

function NavLink({ to, label, pathname }: { to: string; label: string; pathname: string }) {
  const isActive = pathname === to || (to !== '/' && pathname.startsWith(`${to}/`));

  return (
    <Button
      variant={isActive ? 'secondary' : 'ghost'}
      size="sm"
      asChild
      className={cn('px-[var(--space-md)] text-sm font-medium h-9', isActive && 'shadow-sm')}
    >
      <Link to={to} aria-current={isActive ? 'page' : undefined}>
        {label}
      </Link>
    </Button>
  );
}

function LiquidityMenu({ pathname }: { pathname: string }) {
  const [open, setOpen] = useState(false);
  const triggerRef = useRef<HTMLAnchorElement | null>(null);
  const menuId = 'nav-liquidity-menu';
  const closeTimer = useRef<number | null>(null);

  const isActive =
    pathname === '/pools' ||
    pathname.startsWith('/pools/');

  useEffect(() => {
    setOpen(false);
  }, [pathname]);

  const openNow = () => {
    if (closeTimer.current) window.clearTimeout(closeTimer.current);
    setOpen(true);
  };
  const scheduleClose = () => {
    if (closeTimer.current) window.clearTimeout(closeTimer.current);
    closeTimer.current = window.setTimeout(() => setOpen(false), 120);
  };

  const handleBlur = (event: FocusEvent<HTMLDivElement>) => {
    if (event.currentTarget.contains(event.relatedTarget)) return;
    scheduleClose();
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      setOpen(false);
      triggerRef.current?.focus();
    }
  };

  const handleMouseLeave = (event: MouseEvent<HTMLDivElement>) => {
    const next = event.relatedTarget as Node | null;
    if (next && event.currentTarget.contains(next)) return;
    scheduleClose();
  };

  return (
    <div
      className="relative"
      onMouseEnter={openNow}
      onMouseLeave={handleMouseLeave}
      onFocus={openNow}
      onBlur={handleBlur}
      onKeyDown={handleKeyDown}
    >
      <Button
        variant={isActive ? 'secondary' : 'ghost'}
        size="sm"
        className={cn('px-[var(--space-md)] text-sm font-medium h-9', isActive && 'shadow-sm')}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={menuId}
        onClick={() => (open ? setOpen(false) : setOpen(true))}
        asChild
      >
        <Link ref={triggerRef} to="/pools/mine">
          <span className="flex items-center gap-[var(--space-xs)]">
            Liquidity
            <ChevronDown className="size-3" aria-hidden="true" />
          </span>
        </Link>
      </Button>

      <div
        id={menuId}
        role="menu"
        aria-label="Liquidity pool submenu"
        className={cn(
          'absolute left-0 top-full mt-1 w-48 rounded-md border border-border bg-card p-1 shadow-lg z-50',
          open
            ? 'opacity-100 translate-y-0 transition-all duration-200'
            : 'opacity-0 -translate-y-1 pointer-events-none transition-all duration-200'
        )}
      >
        <Button variant="ghost" size="sm" className="w-full justify-start px-2" asChild>
          <Link to="/pools/mine" role="menuitem" onClick={() => setOpen(false)}>
            Manage
          </Link>
        </Button>
      </div>
    </div>
  );
}

function NetworkSwitcher() {
  const { address, chain: connectedChain, connector } = useAccount();
  const { disconnectAsync } = useDisconnect();
  const { switchChainAsync } = useSwitchChain();
  const { logout } = useAuthStore();
  const config = useConfig();

  const forceWagmiDisconnect = () => {
    // Some RainbowKit connectors can return a minimal "wallet details" connector
    // without a functional `disconnect()` implementation. In that case, we still
    // want to clear local connection state so UI updates and the app "disconnects".
    try {
      if (typeof window !== 'undefined' && window.localStorage) {
        window.localStorage.removeItem('wagmi.store');
        window.localStorage.removeItem('wagmi.recentConnectorId');
        if (connector?.id) window.localStorage.setItem(`wagmi.${connector.id}.disconnected`, 'true');
      }
    } catch {
      // ignore storage errors
    }

    config.setState((x) => ({
      ...x,
      connections: new Map(),
      current: null,
      status: 'disconnected',
    }));
  };

  const handleDisconnect = async () => {
    try {
      // Prefer wagmi disconnect, but fall back to clearing local state if connector is misconfigured.
      await disconnectAsync();
    } catch (e: any) {
      const message = e?.shortMessage ?? e?.message ?? 'Unknown error';
      if (String(message).includes('disconnect is not a function')) {
        forceWagmiDisconnect();
      } else {
        toast('Disconnect failed', { description: message });
      }
    } finally {
      logout();
    }
  };

  const handleSwitchChain = async (targetChainId: number) => {
    const target = SUPPORTED_CHAINS.find((c) => c.id === targetChainId);
    const chainIdHex = `0x${targetChainId.toString(16)}`;
    let provider: any = undefined;
    try {
      provider = await connector?.getProvider?.();
    } catch {
      provider = undefined;
    }

    // If connector provider is unavailable, try to pick a reasonable global provider.
    if (!provider?.request && typeof window !== 'undefined') {
      const eth: any = (window as any).ethereum;
      if (eth?.providers?.length) {
        if (connector?.name?.toLowerCase?.().includes('metamask')) {
          provider = eth.providers.find((p: any) => p?.isMetaMask) ?? eth;
        } else {
          provider = eth.providers[0];
        }
      } else if (eth?.request) {
        provider = eth;
      }
    }

    // Prefer sending EIP-1193 requests to the *active connector provider* (not window.ethereum),
    // otherwise multi-wallet environments can route the request to a different wallet.
    if (provider?.request) {
      try {
        await provider.request({
          method: 'wallet_switchEthereumChain',
          params: [{ chainId: chainIdHex }],
        });
        return;
      } catch (e: any) {
        const code = e?.code;
        // MetaMask & compatible: 4902 means the chain is not added yet.
        if (code === 4902 && target) {
          try {
            await provider.request({
              method: 'wallet_addEthereumChain',
              params: [
                {
                  chainId: chainIdHex,
                  chainName: target.name,
                  rpcUrls: target.rpcUrls?.default?.http ?? [],
                  nativeCurrency: target.nativeCurrency,
                  blockExplorerUrls: target.blockExplorers?.default?.url
                    ? [target.blockExplorers.default.url]
                    : undefined,
                },
              ],
            });
            await provider.request({
              method: 'wallet_switchEthereumChain',
              params: [{ chainId: chainIdHex }],
            });
            return;
          } catch (addErr: any) {
            toast('Add network failed', {
              description: addErr?.shortMessage ?? addErr?.message ?? 'Unknown error',
            });
            return;
          }
        }
        // If wallet rejects or errors, surface it directly instead of falling back to wagmi
        // (which may throw SwitchChainNotSupportedError for some connectors).
        toast('Switch network failed', {
          description: e?.shortMessage ?? e?.message ?? 'Unknown error',
        });
        return;
      }
    }

    try {
      await switchChainAsync({ chainId: targetChainId });
    } catch (e: any) {
      const code = e?.cause?.code ?? e?.code;
      // MetaMask: 4902 means the chain is not added yet.
      if (
        code === 4902 &&
        target &&
        (provider?.request || (typeof window !== 'undefined' && (window as any).ethereum?.request))
      ) {
        const fallbackProvider: any = provider?.request ? provider : (window as any).ethereum;
        try {
          await fallbackProvider.request({
            method: 'wallet_addEthereumChain',
            params: [
              {
                chainId: `0x${target.id.toString(16)}`,
                chainName: target.name,
                rpcUrls: target.rpcUrls?.default?.http ?? [],
                nativeCurrency: target.nativeCurrency,
                blockExplorerUrls: target.blockExplorers?.default?.url
                  ? [target.blockExplorers.default.url]
                  : undefined,
              },
            ],
          });
          await switchChainAsync({ chainId: targetChainId });
          return;
        } catch (addErr: any) {
          toast('Add network failed', {
            description: addErr?.shortMessage ?? addErr?.message ?? 'Unknown error',
          });
          return;
        }
      }

      // Common errors:
      // - chain not added to wallet (MetaMask 4902)
      // - user rejected request (4001)
      toast('Switch network failed', {
        description:
          e?.shortMessage ??
          e?.message ??
          'This wallet does not support programmatic network switching. Please switch in your wallet.',
      });
    }
  };

  if (!connectedChain || !address) {
    return null; // 未连接钱包时不显示
  }

  const isSupported = SUPPORTED_CHAINS.some((c) => c.id === connectedChain.id);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Badge
          className={cn(
            'px-3 py-1.5 gap-1.5 flex items-center cursor-pointer hover:opacity-80 transition-opacity',
            isSupported
              ? 'bg-primary text-primary-foreground'
              : 'bg-destructive text-destructive-foreground'
          )}
        >
          <Network className="size-3" aria-hidden="true" />
          <span className="text-xs font-medium">
            {isSupported ? connectedChain.name : 'Unsupported Network'}
          </span>
          <ChevronDown className="size-3 ml-1" aria-hidden="true" />
        </Badge>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <div className="px-2 py-1.5 text-sm font-semibold">Switch Network</div>
        {SUPPORTED_CHAINS.map((chain) => (
          <DropdownMenuItem
            key={chain.id}
            onSelect={(_e) => {
              void handleSwitchChain(chain.id);
            }}
            className="cursor-pointer"
          >
            <Network className="mr-2 size-4" />
            <span>{chain.name}</span>
            {connectedChain?.id === chain.id && (
              <span className="ml-auto text-xs text-muted-foreground">✓</span>
            )}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem
          onSelect={(_e) => {
            void handleDisconnect();
          }}
          className="cursor-pointer text-destructive"
        >
          <LogOut className="mr-2 size-4" />
          <span>Disconnect</span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function WalletButton() {
  const { address } = useAccount();
  const { signMessageAsync } = useSignMessage();
  const { isAuthenticated, setSession, logout } = useAuthStore();
  const [isLoggingIn, setIsLoggingIn] = useState(false);

  // 自动登录：当钱包连接且未认证时，自动触发登录
  useEffect(() => {
    const autoLogin = async () => {
      if (address && !isAuthenticated && !isLoggingIn) {
        setIsLoggingIn(true);
        try {
          const nonce = await authService.getNonce(address);
          const signature = await signMessageAsync({ message: nonce });
          const { sessionId } = await authService.login(address, nonce, signature);
          setSession(sessionId, address);
        } catch (error: any) {
          console.error('Auto-login failed:', error);
        } finally {
          setIsLoggingIn(false);
        }
      }
    };

    autoLogin();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [address, isAuthenticated]);

  // 当钱包断开时，自动清除 session
  useEffect(() => {
    if (!address && isAuthenticated) {
      logout();
    }
  }, [address, isAuthenticated, logout]);

  return (
    <ConnectButton.Custom>
      {({ account, chain, openConnectModal, mounted }) => {
        const ready = mounted;
        const connected = ready && account && chain;

        return (
          <div
            {...(!ready && {
              'aria-hidden': true,
              style: {
                opacity: 0,
                pointerEvents: 'none',
                userSelect: 'none',
              },
            })}
          >
            {(() => {
              if (!connected) {
                return (
                  <Button size="sm" onClick={openConnectModal} className="shadow-sm">
                    Connect Wallet
                  </Button>
                );
              }

              return <Badge className="px-3 py-1.5">{account.displayName}</Badge>;
            })()}
          </div>
        );
      }}
    </ConnectButton.Custom>
  );
}
