import { createRoute } from '@tanstack/react-router';
import { useEffect, useMemo, useState } from 'react';

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
import { fetchFaucetV2Captcha, postFaucetV2ClaimV2 } from '@/app/services/faucetv2-service';

const CHAIN_OPTIONS = [
  { chainId: 11155111, label: 'Sepolia' },
  { chainId: 534351, label: 'Scroll Sepolia' },
] as const;

// Fixed calibration (P0-0): frontend stores the "what/amount" config; backend enforces all risk checks.
const SINGLE_OPTIONS: Record<number, Array<{ symbol: string; amountHuman: string }>> = {
  11155111: [
    { symbol: 'vBTC', amountHuman: '0.008' },
    { symbol: 'vDAI', amountHuman: '200' },
    { symbol: 'vETH', amountHuman: '0.05' },
    { symbol: 'vLINK', amountHuman: '20' },
    { symbol: 'vUSDC', amountHuman: '200' },
    { symbol: 'vUSDT', amountHuman: '200' },
  ],
  534351: [
    { symbol: 'vBTC', amountHuman: '0.008' },
    { symbol: 'vDAI', amountHuman: '200' },
    { symbol: 'vETH', amountHuman: '0.05' },
    { symbol: 'vLINK', amountHuman: '20' },
    { symbol: 'vSCR', amountHuman: '1' },
    { symbol: 'vUSDC', amountHuman: '200' },
    { symbol: 'vUSDT', amountHuman: '200' },
  ],
};

const FaucetPage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();
  const [submitting, setSubmitting] = useState(false);

  const [selectedChainId, setSelectedChainId] = useState<number>(11155111);
  const isSupportedChain = selectedChainId === 11155111 || selectedChainId === 534351;

  // Allow claiming to any address (wallet connection is optional).
  const [targetAddress, setTargetAddress] = useState<string>('');

  const [singleToken, setSingleToken] = useState<string>('');
  const [captchaId, setCaptchaId] = useState<string>('');
  const [captchaImage, setCaptchaImage] = useState<string>('');
  const [captchaEnabled, setCaptchaEnabled] = useState<boolean>(false);
  const [captchaAnswer, setCaptchaAnswer] = useState<string>('');

  const [recent, setRecent] = useState<Array<{ requestId: string; txHash?: string | null }>>([]);

  const deviceId = useMemo(() => {
    try {
      const key = 'ds:faucetv2:deviceId';
      const existing = localStorage.getItem(key);
      if (existing && existing.length > 0) return existing;
      const v = crypto.randomUUID();
      localStorage.setItem(key, v);
      return v;
    } catch {
      return undefined;
    }
  }, []);

  const normalizedTarget = useMemo(() => targetAddress.trim(), [targetAddress]);
  const isValidAddress = useMemo(() => /^0x[a-fA-F0-9]{40}$/.test(normalizedTarget), [normalizedTarget]);

  useEffect(() => {
    // Set sensible defaults for tokens based on selected chain.
    const opts = SINGLE_OPTIONS[selectedChainId] ?? [];
    if (!singleToken && opts.length > 0) setSingleToken(opts[0].symbol);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedChainId]);

  const loadCaptcha = async () => {
    try {
      const res = await fetchFaucetV2Captcha();
      setCaptchaEnabled(res.enabled);
      setCaptchaId(res.captchaId);
      setCaptchaImage(res.imageData);
      setCaptchaAnswer('');
    } catch (e: any) {
      toast('Captcha load failed', { description: e?.message ?? 'Unknown error' });
    }
  };

  useEffect(() => {
    void loadCaptcha();
  }, []);

  const explorerBase = useMemo(() => {
    if (selectedChainId === 11155111) return 'https://sepolia.etherscan.io/tx/';
    if (selectedChainId === 534351) return 'https://sepolia.scrollscan.com/tx/';
    return null;
  }, [selectedChainId]);

  const canClaim = useMemo(() => {
    if (!isSupportedChain) return false;
    if (!isValidAddress) return false;
    if (!singleToken) return false;
    if (captchaEnabled && !captchaAnswer.trim()) return false;
    return true;
  }, [isSupportedChain, isValidAddress, singleToken, captchaEnabled, captchaAnswer]);

  const handleClaim = async () => {
    if (!isValidAddress) return;
    setSubmitting(true);
    try {
      const idempotencyKey = crypto.randomUUID();
      const body = {
        chainId: selectedChainId,
        user: normalizedTarget,
        idempotencyKey,
        deviceId,
        captchaId: captchaId || undefined,
        captchaAnswer: captchaAnswer.trim() || undefined,
        token: singleToken,
      };

      const res = await postFaucetV2ClaimV2(body);

      if (res.status === 'REJECTED') {
        toast('Faucet claim rejected', {
          description: `${res.message ?? ''}`.trim(),
        });
        return;
      }

      toast('Faucet claim submitted', {
        description: `Request ${res.requestId}`,
      });
      setRecent((prev) => [{ requestId: res.requestId, txHash: res.txHash }, ...prev].slice(0, 5));
    } catch (e: any) {
      toast('Faucet claim failed', { description: e?.message ?? 'Unknown error' });
    } finally {
      setSubmitting(false);
      void loadCaptcha();
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
          Claim configured test tokens (single only, up to 3 per day). The backend enforces cooldown, daily limits, and vault checks.
        </p>
      </header>

      <Card>
        <CardHeader className="flex flex-col gap-[var(--space-sm)]">
          <div className="flex flex-col gap-[var(--space-xs)]">
            <CardTitle id="faucet-section-heading" className="text-xl">
              Claim
            </CardTitle>
            <CardDescription>
              Wallet connection is optional. Enter a recipient address and choose a chain to claim.
            </CardDescription>
          </div>
        </CardHeader>
        <CardContent className="flex flex-col gap-[var(--space-md)]">
          <label className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground">
            Network
            <Select
              value={String(selectedChainId)}
              onValueChange={(v) => setSelectedChainId(Number(v))}
            >
              <SelectTrigger>
                <SelectValue placeholder="Select network" />
              </SelectTrigger>
              <SelectContent>
                {CHAIN_OPTIONS.map((c) => (
                  <SelectItem key={c.chainId} value={String(c.chainId)}>
                    {c.label}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>

          <label className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground">
            Recipient address
            <Input
              value={targetAddress}
              onChange={(e) => setTargetAddress(e.target.value)}
              placeholder="0x..."
              spellCheck={false}
            />
            {!targetAddress.trim() ? (
              <span className="text-xs text-muted-foreground">Enter a valid EVM address.</span>
            ) : isValidAddress ? (
              <span className="text-xs text-muted-foreground">Address looks valid.</span>
            ) : (
              <span className="text-xs text-destructive">Invalid address.</span>
            )}
          </label>

          <label className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground">
            Token
            <Select value={singleToken} onValueChange={setSingleToken}>
              <SelectTrigger>
                <SelectValue placeholder="Select token" />
              </SelectTrigger>
              <SelectContent>
                {(SINGLE_OPTIONS[selectedChainId] ?? []).map((option) => (
                  <SelectItem key={option.symbol} value={option.symbol}>
                    {option.symbol} · {option.amountHuman}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </label>

          {captchaEnabled && (
            <div className="flex flex-col gap-[var(--space-xs)] text-sm font-medium text-muted-foreground">
              <span>Captcha</span>
              <div className="flex items-center gap-[var(--space-sm)]">
                {captchaImage ? (
                  <img
                    src={captchaImage}
                    alt="captcha"
                    className="h-12 w-[180px] rounded-md border border-border/60 bg-white"
                  />
                ) : (
                  <div className="h-12 w-[180px] rounded-md border border-border/60 bg-muted" />
                )}
                <Button type="button" variant="outline" onClick={loadCaptcha} disabled={submitting}>
                  Refresh
                </Button>
              </div>
              <Input
                value={captchaAnswer}
                onChange={(e) => setCaptchaAnswer(e.target.value)}
                placeholder="Enter the code"
                spellCheck={false}
              />
            </div>
          )}

          <Button
            type="button"
            size="lg"
            className="w-full justify-center"
            disabled={submitting || !canClaim}
            onClick={handleClaim}
          >
            {submitting ? 'Submitting…' : 'Claim'}
          </Button>
        </CardContent>
      </Card>

      <Card className="border-border/70 bg-surface-elevated/60">
        <CardHeader>
          <CardTitle className="text-lg">Recent requests</CardTitle>
          <CardDescription>Recent transactions appear here for this session.</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-[var(--space-sm)] text-sm text-muted-foreground">
          {recent.length === 0 && <p className="text-xs">No requests yet.</p>}
          {recent.map((r) => (
            <div
              key={r.requestId}
              className="flex flex-col gap-1 rounded-[var(--radius-card)] border border-border/60 bg-background px-[var(--space-md)] py-[var(--space-sm)]"
            >
              <span className="text-xs">Request {r.requestId}</span>
              <span className="text-foreground">
                {r.txHash ? (
                  explorerBase ? (
                    <a className="underline" href={`${explorerBase}${r.txHash}`} target="_blank" rel="noreferrer">
                      {r.txHash}
                    </a>
                  ) : (
                    r.txHash
                  )
                ) : (
                  'pending'
                )}
              </span>
            </div>
          ))}
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
