import { createRoute } from '@tanstack/react-router';
import { useEffect, useMemo, useState } from 'react';
import { useAccount, useBalance, useChainId } from 'wagmi';
import { formatUnits, parseUnits } from 'viem';

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
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  toast,
} from '@/shared/ui';
import { ArrowDownUp, ChevronDown, Settings2 } from '@/shared/icons';
import { rootRoute } from './root';
import { getAllTokens, TokenConfig, getChainConfig } from '@/contracts';
import { useSwapQuote } from '@/lib/swap/useSwapQuote';
import { useSwapExecution } from '@/lib/swap/useSwapExecution';
import { cn } from '@/shared/utils';

const DEFAULT_AMOUNT = '';

type DetailRow = {
  label: string;
  value: string;
  accent?: string;
};

function formatBps(value: number) {
  // BPS is usually positive for fee, but price impact can be negative
  // Let's show absolute % for fee
  return `${(value / 100).toFixed(2)}%`;
}

const SwapPage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();
  const chainId = useChainId();
  const { address: userAddress } = useAccount();

  // Tokens - Sync load from config
  const tokens = useMemo(() => getAllTokens(chainId), [chainId]);

  const [tokenIn, setTokenIn] = useState<`0x${string}` | undefined>();
  const [tokenOut, setTokenOut] = useState<`0x${string}` | undefined>();

  // Initialize tokens
  useEffect(() => {
    if (tokens.length > 0) {
      if (!tokenIn) setTokenIn(tokens[0].address);
      if (!tokenOut) setTokenOut(tokens[1]?.address || tokens[0].address);
    }
  }, [tokens, tokenIn, tokenOut]);

  const [amountIn, setAmountIn] = useState(DEFAULT_AMOUNT);
  const [isDetailsOpen, setIsDetailsOpen] = useState(false);
  const [isSettingsOpen, setIsSettingsOpen] = useState(false);
  const [isAutoSlippage, setIsAutoSlippage] = useState(true);
  const [manualSlippage, setManualSlippage] = useState<'0.1' | '0.5' | '1.0' | 'custom'>('0.5');
  const [customSlippageValue, setCustomSlippageValue] = useState('');

  // Balance for Token In
  const { data: balanceIn } = useBalance({
    address: userAddress,
    token: tokenIn,
  });

  const { data: balanceOut } = useBalance({
    address: userAddress,
    token: tokenOut,
  });

  // Quote Hook
  const {
    quote,
    isLoading: isQuoteLoading,
    error: quoteError,
  } = useSwapQuote({
    tokenIn,
    tokenOut,
    amountIn,
    slippageBps: isAutoSlippage
      ? 50
      : manualSlippage === 'custom'
        ? (Number(customSlippageValue) >= 0.01 ? Number(customSlippageValue) : 0.1) * 100
        : Number(manualSlippage) * 100,
    debounceMs: 300,
  });

  // Derived Token Meta
  const tokenInMeta = useMemo(() => tokens.find((t) => t.address === tokenIn), [tokens, tokenIn]);
  const tokenOutMeta = useMemo(
    () => tokens.find((t) => t.address === tokenOut),
    [tokens, tokenOut]
  );

  // Router & Pair
  const routerAddress = useMemo(() => getChainConfig(chainId)?.router, [chainId]);

  // Execution Hook
  const { approvalState, swapState, handleApprove, handleSwap } = useSwapExecution({
    chainId,
    tokenIn: tokenIn!,
    tokenOut: tokenOut!,
    amountIn,
    minAmountOut: quote?.minReceived || '0',
    routerAddress: routerAddress!,
    tokenInDecimals: tokenInMeta?.decimals || 18,
    routePath: quote?.routePath,
  });

  // Toast for Swap Result
  useEffect(() => {
    if (swapState === 'success') {
      toast('Swap Successful', {
        description: `Successfully swapped ${amountIn} ${tokenInMeta?.symbol} for ${quote?.amountOutFormatted} ${tokenOutMeta?.symbol}`,
      });
      setAmountIn('');
    } else if (swapState === 'failed') {
      toast('Swap Failed', { description: 'The transaction failed on-chain. Please try again.' });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [swapState]);

  const handleToggleTokens = () => {
    if (!tokenIn || !tokenOut) return;
    setTokenIn(tokenOut);
    setTokenOut(tokenIn);
    // If we have a quote, use the output amount as the new input amount
    if (quote) {
      setAmountIn(quote.amountOutFormatted);
    }
  };

  // Balance Check
  const isInsufficientBalance = useMemo(() => {
    if (!balanceIn || !amountIn || !tokenInMeta) return false;
    try {
      const required = parseUnits(amountIn, tokenInMeta.decimals);
      return balanceIn.value < required;
    } catch {
      return false;
    }
  }, [balanceIn, amountIn, tokenInMeta]);

  const detailRows = useMemo<DetailRow[]>(() => {
    if (!quote) return [];
    return [
      {
        label: 'Price Impact',
        value: formatBps(quote.priceImpactBps),
        accent: quote.priceImpactBps < -500 ? 'text-destructive' : undefined, // High impact warning
      },
      {
        label: 'Slippage Tolerance',
        value: formatBps(quote.slippageBps),
      },
      {
        label: 'Protocol Fee',
        value: quote.feeUsd
          ? `${formatBps(quote.feeBps)} ($${Number(quote.feeUsd).toFixed(2)})`
          : formatBps(quote.feeBps),
      },
      {
        label: 'Min Received',
        value: `${formatUnits(BigInt(quote.minReceived), tokenOutMeta?.decimals || 18)} ${tokenOutMeta?.symbol}`,
      },
      {
        label: 'Route',
        value: quote.route,
      },
    ];
  }, [quote, tokenOutMeta]);

  // Unit Price
  const unitPrice = useMemo(() => {
    if (!quote || !amountIn) return '--';
    const inVal = parseFloat(amountIn);
    const outVal = parseFloat(quote.amountOutFormatted);
    if (inVal === 0) return '--';
    return (outVal / inVal).toLocaleString(undefined, { maximumFractionDigits: 6 });
  }, [quote, amountIn]);

  const handleTokenInChange = (value: string) => {
    const newTokenIn = value as `0x${string}`;
    if (newTokenIn === tokenOut) {
      setTokenOut(tokenIn); // Swap if same
    }
    setTokenIn(newTokenIn);
  };

  const handleTokenOutChange = (value: string) => {
    const newTokenOut = value as `0x${string}`;
    if (newTokenOut === tokenIn) {
      setTokenIn(tokenOut); // Swap if same
    }
    setTokenOut(newTokenOut);
  };

  const handleSwapSubmit = async (e: React.FormEvent) => {
    e.preventDefault();

    if (approvalState !== 'approved') {
      try {
        await handleApprove();
        toast('Approval Submitted', { description: 'Please wait for confirmation...' });
      } catch (error) {
        console.error(error);
        toast('Approval Failed', { description: 'User rejected or transaction failed.' });
      }
    } else {
      try {
        await handleSwap();
        toast('Swap Submitted', { description: 'Transaction sent to the network.' });
      } catch (error) {
        console.error(error);
        toast('Swap Failed', { description: 'User rejected or transaction failed.' });
      }
    }
  };

  // Disable logic separated for better UX
  const isApproveNeeded = approvalState === 'not_approved' || approvalState === 'unknown';

  // Common disable reasons
  const isInputInvalid =
    !tokenIn || !tokenOut || !amountIn || Number(amountIn) <= 0 || isInsufficientBalance;
  const isPending = approvalState === 'pending' || swapState === 'pending';

  // Specific disable reasons
  // For Approve: We only need valid input and no pending tx. Quote error shouldn't strictly block approve,
  // but usually implies swap isn't possible. Let's allow approve even if quote fails, to unblock users.
  const isApproveDisabled = isInputInvalid || isPending;

  // For Swap: We need quote success
  const isSwapActionDisabled =
    isInputInvalid || isPending || isQuoteLoading || !!quoteError || isApproveNeeded;

  // Final Button State
  const isButtonDisabled = isApproveNeeded ? isApproveDisabled : isSwapActionDisabled;

  let buttonText = 'Swap';
  if (isInsufficientBalance) buttonText = 'Insufficient Balance';
  else if (isQuoteLoading) buttonText = 'Fetching quote...';
  else if (isApproveNeeded) buttonText = `Approve ${tokenInMeta?.symbol}`;
  else if (approvalState === 'pending') buttonText = 'Approving...';
  else if (swapState === 'pending') buttonText = 'Swapping...';

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[720px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-xs)] text-center">
        <Badge variant="outline" className="self-center" aria-live="polite">
          Sepolia / Scroll Testnet
        </Badge>
        <h1
          ref={headingRef}
          tabIndex={-1}
          className="text-3xl font-semibold tracking-tight text-foreground focus:outline-none sm:text-4xl"
        >
          Swap assets
        </h1>
        <p className="mx-auto max-w-2xl text-base text-muted-foreground">
          Trade tokens instantly on DripSwap.
        </p>
      </header>

      <Card className="mx-auto w-full max-w-[440px]">
        <form
          className="flex flex-col"
          aria-labelledby="swap-card-heading"
          onSubmit={handleSwapSubmit}
        >
          <CardHeader className="flex flex-row items-start justify-between gap-[var(--space-sm)]">
            <div className="flex flex-col gap-[var(--space-xs)]">
              <CardTitle id="swap-card-heading" className="text-xl">
                Swap
              </CardTitle>
              <CardDescription className="text-sm text-muted-foreground">
                {chainId === 11155111
                  ? 'Sepolia'
                  : chainId === 534351
                    ? 'Scroll Sepolia'
                    : 'Unsupported Network'}
              </CardDescription>
            </div>
            <Dialog open={isSettingsOpen} onOpenChange={setIsSettingsOpen}>
              <DialogTrigger asChild>
                <Button
                  type="button"
                  variant="ghost"
                  size="icon"
                  aria-haspopup="dialog"
                  aria-controls="swap-settings"
                >
                  <Settings2 className="size-5" aria-hidden="true" />
                  <span className="sr-only">Open swap settings</span>
                </Button>
              </DialogTrigger>
              <DialogContent id="swap-settings" className="gap-[var(--space-lg)]">
                <DialogHeader>
                  <DialogTitle className="text-xl">Swap settings</DialogTitle>
                </DialogHeader>
                <div className="flex flex-col gap-[var(--space-md)] text-sm text-muted-foreground">
                  <div className="flex items-center justify-between gap-[var(--space-sm)]">
                    <div className="flex flex-col gap-[var(--space-xs)]">
                      <span className="text-sm font-medium text-foreground">Auto slippage</span>
                      <span>Automatically adjusts based on market volatility.</span>
                    </div>
                    <Button
                      type="button"
                      variant={isAutoSlippage ? 'primary' : 'outline'}
                      size="sm"
                      onClick={() => setIsAutoSlippage((prev) => !prev)}
                      aria-pressed={isAutoSlippage}
                    >
                      {isAutoSlippage ? 'Enabled' : 'Disabled'}
                    </Button>
                  </div>

                  <fieldset
                    className="flex flex-col gap-[var(--space-sm)]"
                    disabled={isAutoSlippage}
                    aria-disabled={isAutoSlippage}
                  >
                    <legend className="text-sm font-medium text-foreground">Manual slippage</legend>
                    <div className="grid grid-cols-2 gap-[var(--space-sm)]">
                      {['0.1', '0.5', '1.0'].map((value) => (
                        <Button
                          key={value}
                          type="button"
                          variant={
                            !isAutoSlippage && manualSlippage === value ? 'primary' : 'outline'
                          }
                          size="sm"
                          onClick={() => setManualSlippage(value as typeof manualSlippage)}
                          disabled={isAutoSlippage}
                        >
                          {value}%
                        </Button>
                      ))}
                      {manualSlippage === 'custom' && !isAutoSlippage ? (
                        <div className="relative h-9">
                          <Input
                            type="number"
                            className={cn(
                              'h-full w-full pr-6 text-center transition-colors',
                              customSlippageValue && Number(customSlippageValue) < 0.01
                                ? 'border-red-500 text-red-600 focus-visible:ring-red-500'
                                : ''
                            )}
                            placeholder="0.5"
                            value={customSlippageValue}
                            onChange={(e) => setCustomSlippageValue(e.target.value)}
                            autoFocus
                          />
                          <span
                            className={cn(
                              'absolute right-3 top-1/2 -translate-y-1/2 text-sm',
                              customSlippageValue && Number(customSlippageValue) < 0.01
                                ? 'text-red-600'
                                : 'text-muted-foreground'
                            )}
                          >
                            %
                          </span>
                        </div>
                      ) : (
                        <Button
                          type="button"
                          variant="outline"
                          size="sm"
                          onClick={() => setManualSlippage('custom')}
                          disabled={isAutoSlippage}
                        >
                          Custom
                        </Button>
                      )}
                    </div>
                  </fieldset>

                  <div className="flex flex-col gap-[var(--space-xs)]">
                    <label htmlFor="deadline" className="text-sm font-medium text-foreground">
                      Transaction deadline (minutes)
                    </label>
                    <Input id="deadline" type="number" min="1" max="60" step="1" placeholder="20" />
                  </div>
                </div>
              </DialogContent>
            </Dialog>
          </CardHeader>

          <CardContent className="flex flex-col gap-[var(--space-lg)]">
            <div className="flex flex-col gap-[var(--space-sm)]">
              <SwapAssetField
                id="sell-amount"
                mode="Sell"
                tokens={tokens}
                token={tokenInMeta}
                onTokenChange={handleTokenInChange}
                amount={amountIn}
                onAmountChange={setAmountIn}
                balance={balanceIn}
                isError={isInsufficientBalance}
              />

              <div className="-my-1 flex justify-center">
                <Button
                  type="button"
                  variant="secondary"
                  size="icon"
                  aria-label="Switch tokens"
                  onClick={handleToggleTokens}
                >
                  <ArrowDownUp className="size-4" aria-hidden="true" />
                </Button>
              </div>

              <SwapAssetField
                id="buy-amount"
                mode="Buy"
                tokens={tokens}
                token={tokenOutMeta}
                onTokenChange={handleTokenOutChange}
                amount={quote?.amountOutFormatted || ''}
                onAmountChange={() => {}} // Output is read-only based on quote
                balance={balanceOut}
                disabled={true}
              />
            </div>

            <div className="flex flex-col gap-[var(--space-sm)]">
              <Button
                type="button"
                variant="ghost"
                className="justify-between px-0 text-sm font-medium text-foreground"
                onClick={() => setIsDetailsOpen((prev) => !prev)}
                aria-expanded={isDetailsOpen}
                aria-controls="swap-details"
                disabled={!quote}
              >
                <span>
                  {unitPrice !== '--'
                    ? `1 ${tokenInMeta?.symbol} ≈ ${unitPrice} ${tokenOutMeta?.symbol}`
                    : 'Exchange Rate'}
                </span>
                <ChevronDown
                  className={`size-4 transition-transform ${isDetailsOpen ? 'rotate-180' : ''}`}
                  aria-hidden="true"
                />
              </Button>
              <div
                id="swap-details"
                className={`grid overflow-hidden text-sm text-muted-foreground transition-[grid-template-rows,opacity] duration-200 ${isDetailsOpen && quote ? 'grid-rows-[1fr] opacity-100' : 'grid-rows-[0fr] opacity-0'}`}
                aria-hidden={!isDetailsOpen}
              >
                <div className="flex flex-col gap-[var(--space-xs)] overflow-hidden border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] rounded-md">
                  {detailRows.map((row) => (
                    <div
                      key={row.label}
                      className="flex items-center justify-between gap-[var(--space-sm)]"
                    >
                      <span>{row.label}</span>
                      <span className={row.accent}>{row.value}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </CardContent>

          <div className="px-[var(--space-xl)] pb-[var(--space-xl)]">
            <Button
              type="submit"
              size="lg"
              className="w-full justify-center"
              disabled={isButtonDisabled}
            >
              {buttonText}
            </Button>
          </div>
        </form>
      </Card>
    </main>
  );
};

type SwapAssetFieldProps = {
  id: string;
  mode: 'Sell' | 'Buy';
  tokens: TokenConfig[];
  token?: TokenConfig;
  onTokenChange: (value: string) => void;
  amount: string;
  onAmountChange: (value: string) => void;
  balance?: { formatted: string; symbol: string };
  disabled?: boolean;
  isError?: boolean;
};

function SwapAssetField({
  id,
  mode,
  tokens,
  token,
  onTokenChange,
  amount,
  onAmountChange,
  balance,
  disabled,
  isError,
}: SwapAssetFieldProps) {
  return (
    <div
      className={cn(
        'rounded-[var(--radius-card)] border border-border/70 bg-surface-elevated/60 p-[var(--space-md)] shadow-[inset_0_1px_0_rgba(255,255,255,0.45)]',
        isError && 'border-red-500 bg-red-50'
      )}
    >
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        <span>{mode}</span>
        <span className="flex items-center gap-[var(--space-xs)]" aria-live="polite">
          <span className="font-medium text-foreground">Balance:</span>
          {balance ? `${parseFloat(balance.formatted).toFixed(4)}` : '--'}
        </span>
      </div>
      <div className="mt-[var(--space-sm)] flex items-center justify-between gap-[var(--space-sm)]">
        <div className="flex flex-col gap-[var(--space-xs)]">
          <Input
            id={id}
            name={id}
            type="number"
            min="0"
            step="any"
            value={amount}
            onChange={(event) => onAmountChange(event.target.value)}
            className={cn(
              'h-auto border-none bg-transparent px-0 text-2xl font-semibold text-foreground shadow-none focus-visible:ring-0 focus-visible:ring-offset-0',
              isError && 'text-red-600'
            )}
            placeholder="0.00"
            aria-label={`${mode} amount`}
            disabled={disabled}
          />
          <span className="text-xs text-muted-foreground">
            {/* USD approximation could go here */}
            &nbsp;
          </span>
        </div>
        <Select value={token?.address} onValueChange={onTokenChange} disabled={tokens.length === 0}>
          <SelectTrigger className="w-[8rem] justify-between bg-background/60 text-base font-semibold">
            <SelectValue aria-label={`${mode} token`} placeholder="Select" />
          </SelectTrigger>
          <SelectContent>
            {tokens.map((option) => (
              <SelectItem key={option.address} value={option.address}>
                <div className="flex items-center gap-2">
                  {/* If we had images, <img src={option.logo} ... /> */}
                  <span>{option.symbol}</span>
                </div>
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
      </div>
    </div>
  );
}

export const swapRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/swap',
  component: SwapPage,
});
