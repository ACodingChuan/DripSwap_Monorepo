import { createRoute } from '@tanstack/react-router';
import { useEffect, useMemo, useState } from 'react';
import { useAccount, useChainId, useSwitchChain } from 'wagmi';
import { parseUnits, formatUnits, Address } from 'viem';

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
  Label,
  RadioGroup,
  RadioGroupItem,
  toast,
} from '@/shared/ui';
import { ArrowRight } from '@/shared/icons';
import { rootRoute } from './root';
import { getAllTokens, getChainConfig, CCIP_CHAINS } from '@/contracts';
import { BRIDGE_CONFIG } from '@/domain/bridge/config';

// Import Domain Hooks
import { useBridgeQuote } from '@/domain/bridge/hooks/useBridgeQuote';
import { usePermit2Allowance, useApprovePermit2 } from '@/domain/bridge/hooks/useApprove';
import { usePermit2Sign } from '@/domain/bridge/hooks/usePermit2Sign';
import { useBridgeTransaction } from '@/domain/bridge/hooks/useBridgeTransaction';

const BridgePage = () => {
  const headingRef = usePageFocus<HTMLHeadingElement>();
  const { address } = useAccount();
  const walletChainId = useChainId();
  const { switchChain } = useSwitchChain();

  // --- 1. Independent UI Network State ---
  const [fromNetworkId, setFromNetworkId] = useState<number>(CCIP_CHAINS.SEPOLIA);
  const [toNetworkId, setToNetworkId] = useState<number>(CCIP_CHAINS.SCROLL_SEPOLIA);

  // Auto-switch "To" network when "From" changes
  useEffect(() => {
    if (fromNetworkId === CCIP_CHAINS.SEPOLIA) {
      setToNetworkId(CCIP_CHAINS.SCROLL_SEPOLIA);
    } else if (fromNetworkId === CCIP_CHAINS.SCROLL_SEPOLIA) {
      setToNetworkId(CCIP_CHAINS.SEPOLIA);
    }
  }, [fromNetworkId]);

  // --- 2. Token & Amount State ---
  const tokens = useMemo(
    () => getAllTokens(fromNetworkId).filter((t) => t.symbol !== 'vLINK'),
    [fromNetworkId]
  );
  const [selectedTokenAddress, setSelectedTokenAddress] = useState<string>('');
  const [amount, setAmount] = useState('');
  const [feeToken, setFeeToken] = useState<'NATIVE' | 'LINK'>('NATIVE');

  // Select first token by default
  useEffect(() => {
    if (tokens.length > 0 && !selectedTokenAddress) {
      setSelectedTokenAddress(tokens[0].address);
    } else if (tokens.length > 0 && !tokens.find((t) => t.address === selectedTokenAddress)) {
      setSelectedTokenAddress(tokens[0].address);
    }
  }, [tokens, selectedTokenAddress]);

  const selectedToken = useMemo(
    () => tokens.find((t) => t.address === selectedTokenAddress),
    [tokens, selectedTokenAddress]
  );

  // --- 3. Hooks Integration ---
  const fromChainConfig = getChainConfig(fromNetworkId);
  const toChainConfig = getChainConfig(toNetworkId);

  // Amount Parsing
  const amountBigInt = useMemo(() => {
    try {
      return amount && selectedToken ? parseUnits(amount, selectedToken.decimals) : 0n;
    } catch {
      return 0n;
    }
  }, [amount, selectedToken]);

  // Quote Fee
  const {
    data: quotedFee,
    isLoading: isQuoteLoading,
    error: quoteError,
  } = useBridgeQuote({
    chainId: fromNetworkId,
    token: selectedToken?.address as Address,
    dstChainId: toNetworkId,
    receiver: address as Address,
    amount: amountBigInt,
    payInLink: feeToken === 'LINK',
    enabled: !!selectedToken && !!address && amountBigInt > 0n,
  });

  // Approval (Permit2) - Bridge Token
  const { data: allowance, refetch: refetchAllowance } = usePermit2Allowance(
    selectedToken?.address as Address,
    address,
    fromNetworkId
  );

  // Approval (Permit2) - LINK Token (if paying in LINK)
  const linkTokenAddress = BRIDGE_CONFIG.LINK_TOKEN[fromNetworkId];
  const { data: linkAllowance, refetch: refetchLinkAllowance } = usePermit2Allowance(
    linkTokenAddress,
    address,
    fromNetworkId
  );

  const {
    approve,
    isPending: isApprovePending,
    isConfirming: isApproveConfirming,
  } = useApprovePermit2();

  // Signature & Transaction
  const { signPermit } = usePermit2Sign();
  const {
    sendToken,
    isPending: isBridgePending,
    isConfirming: isBridgeConfirming,
  } = useBridgeTransaction();

  // --- 4. Actions ---
  const handleSwitchNetwork = () => {
    switchChain({ chainId: fromNetworkId });
  };

  const handleBridge = async (e: React.FormEvent) => {
    e.preventDefault();

    // STRICT GUARD
    if (walletChainId !== fromNetworkId) {
      toast('Network Mismatch', { description: 'Please switch network first.' });
      return;
    }

    if (!amountBigInt || !selectedToken || !address || !quotedFee) return;

    try {
      // 1. Sign Permit2
      // Generate a large unique nonce (timestamp-based + random)
      // Permit2 nonces must be unique to prevent replay attacks
      const nonce = BigInt(Date.now() * 1000000 + Math.floor(Math.random() * 1000000));

      // Prepare Fee Config for LINK payment (Real Chainlink LINK, not vLINK)
      let feeConfig = undefined;
      if (feeToken === 'LINK') {
        const linkTokenAddress = BRIDGE_CONFIG.LINK_TOKEN[fromNetworkId];
        if (linkTokenAddress) {
          // Apply 3% buffer for LINK permit
          const feeWithBuffer = (quotedFee * 103n) / 100n;
          feeConfig = {
            token: linkTokenAddress,
            amount: feeWithBuffer,
          };
        }
      }

      const { permit, signature } = await signPermit(
        fromNetworkId,
        selectedToken.address as Address,
        amountBigInt,
        BRIDGE_CONFIG.ADDRESSES[fromNetworkId], // Spender = Bridge
        nonce,
        feeConfig
      );

      // 2. Send Transaction
      await sendToken(
        fromNetworkId,
        toNetworkId,
        selectedToken.address as Address,
        amountBigInt,
        address, // Receiver = Self
        feeToken === 'LINK',
        quotedFee,
        { permit, signature }
      );

      toast('Bridge Transaction Sent');
      setAmount('');
    } catch (e) {
      console.error(e);
      toast('Bridge Failed', { description: (e as Error).message });
    }
  };

  // --- 5. Render Helpers ---
  const isBridgeTokenApprovalNeeded = useMemo(() => {
    // Optimize: Check allowance > 0 immediately, don't wait for amount input
    if (!selectedToken || allowance === undefined) return false;
    if (selectedToken.symbol === 'ETH' || selectedToken.symbol === 'SEP') return false;
    return allowance === 0n;
  }, [allowance, selectedToken]);

  const isLinkApprovalNeeded = useMemo(() => {
    // Optimize: Check allowance > 0 immediately, don't wait for fee quote
    if (feeToken !== 'LINK' || linkAllowance === undefined) return false;

    return linkAllowance === 0n;
  }, [feeToken, linkAllowance]);

  const handleApproveToken = async () => {
    if (!selectedToken) return;
    try {
      await approve(selectedToken.address as Address, fromNetworkId);
      toast(`Approving ${selectedToken.symbol}...`);
      setTimeout(refetchAllowance, 5000);
    } catch (e) {
      console.error(e);
      toast('Approval Failed');
    }
  };

  const handleApproveLink = async () => {
    const linkTokenAddress = BRIDGE_CONFIG.LINK_TOKEN[fromNetworkId];
    if (!linkTokenAddress) return;
    try {
      await approve(linkTokenAddress, fromNetworkId);
      toast('Approving LINK...');
      setTimeout(refetchLinkAllowance, 5000);
    } catch (e) {
      console.error(e);
      toast('Approval Failed');
    }
  };

  const renderActionButton = () => {
    if (!address)
      return (
        <Button disabled className="w-full">
          Connect Wallet
        </Button>
      );

    if (walletChainId !== fromNetworkId) {
      return (
        <Button onClick={handleSwitchNetwork} className="w-full" variant="default">
          Switch to {fromNetworkId === CCIP_CHAINS.SEPOLIA ? 'Sepolia' : 'Scroll'}
        </Button>
      );
    }

    const isProcessing =
      isBridgePending || isApprovePending || isBridgeConfirming || isApproveConfirming;
    if (isProcessing) {
      return (
        <Button disabled className="w-full">
          Processing...
        </Button>
      );
    }

    if (isBridgeTokenApprovalNeeded) {
      return (
        <Button onClick={handleApproveToken} className="w-full">
          Approve {selectedToken?.symbol}
        </Button>
      );
    }

    if (isLinkApprovalNeeded) {
      return (
        <Button onClick={handleApproveLink} className="w-full">
          Approve LINK for Fee
        </Button>
      );
    }

    if (amountBigInt > 0n && !quotedFee) {
      return (
        <Button disabled className="w-full">
          Fetching Fee...
        </Button>
      );
    }

    if (amountBigInt > 0n && quoteError) {
      return (
        <Button disabled className="w-full">
          Fee Error
        </Button>
      );
    }

    return (
      <Button type="submit" disabled={amountBigInt <= 0n} className="w-full">
        Bridge Assets
      </Button>
    );
  };

  return (
    <main className="mx-auto flex min-h-screen w-full max-w-[720px] flex-col gap-[var(--space-xl)] px-6 py-[var(--space-2xl)]">
      <header className="flex flex-col gap-[var(--space-xs)] text-center">
        <Badge variant="outline" className="self-center">
          Cross-Chain Bridge
        </Badge>
        <h1 ref={headingRef} className="text-3xl font-semibold tracking-tight">
          Bridge Assets
        </h1>
        <p className="text-muted-foreground">Transfer tokens securely via Chainlink CCIP.</p>
      </header>

      <Card className="mx-auto w-full max-w-[480px]">
        <form onSubmit={handleBridge}>
          <CardHeader>
            <CardTitle>Bridge</CardTitle>
            <CardDescription>Select source and destination chains.</CardDescription>
          </CardHeader>

          <CardContent className="flex flex-col gap-6">
            {/* Network Selection */}
            <div className="grid grid-cols-[1fr_auto_1fr] items-end gap-2">
              <div className="space-y-2">
                <Label>From</Label>
                <Select
                  value={String(fromNetworkId)}
                  onValueChange={(v) => setFromNetworkId(Number(v))}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={String(CCIP_CHAINS.SEPOLIA)}>Sepolia</SelectItem>
                    <SelectItem value={String(CCIP_CHAINS.SCROLL_SEPOLIA)}>Scroll</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              <div className="pb-2 text-muted-foreground">
                <ArrowRight className="size-5" />
              </div>

              <div className="space-y-2">
                <Label>To</Label>
                <Select value={String(toNetworkId)} disabled>
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value={String(CCIP_CHAINS.SEPOLIA)}>Sepolia</SelectItem>
                    <SelectItem value={String(CCIP_CHAINS.SCROLL_SEPOLIA)}>Scroll</SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            {/* Asset Selection */}
            <div className="rounded-lg border p-4 bg-muted/40 space-y-4">
              <div className="flex justify-between">
                <Label>Send Amount</Label>
              </div>

              <div className="flex gap-2">
                <Input
                  type="number"
                  placeholder="0.00"
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  className="text-lg border-none bg-transparent shadow-none p-0 focus-visible:ring-0"
                  step="any"
                />
                <Select value={selectedTokenAddress} onValueChange={setSelectedTokenAddress}>
                  <SelectTrigger className="w-[120px]">
                    <SelectValue placeholder="Token" />
                  </SelectTrigger>
                  <SelectContent>
                    {tokens.map((t) => (
                      <SelectItem key={t.address} value={t.address}>
                        <div className="flex items-center gap-2">
                          <span>{t.symbol}</span>
                        </div>
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>

            {/* Fee Selection */}
            <div className="space-y-3">
              <Label>Pay Fees In</Label>
              <RadioGroup
                value={feeToken}
                onValueChange={(v) => setFeeToken(v as 'NATIVE' | 'LINK')}
                className="flex gap-4"
              >
                <div className="flex items-center space-x-2">
                  <RadioGroupItem value="NATIVE" id="fee-native" />
                  <Label htmlFor="fee-native">
                    Native ({fromNetworkId === CCIP_CHAINS.SEPOLIA ? 'ETH' : 'ETH'})
                  </Label>
                </div>
                <div className="flex items-center space-x-2">
                  <RadioGroupItem value="LINK" id="fee-link" />
                  <Label htmlFor="fee-link">LINK</Label>
                </div>
              </RadioGroup>
              <p className="text-xs text-muted-foreground min-h-[1.5em]">
                {isQuoteLoading
                  ? 'Estimating fee...'
                  : quotedFee
                    ? `Max Fee (incl. 3% buffer): ${formatUnits((quotedFee * 103n) / 100n, 18).slice(0, 8)} ${feeToken === 'LINK' ? 'LINK' : 'ETH'}`
                    : quoteError
                      ? 'Fee estimation failed'
                      : 'Enter amount to estimate fee'}
              </p>
              {feeToken === 'LINK' && (
                <p className="text-xs text-blue-600 dark:text-blue-400">
                  LINK is the real Chainlink LINK token used for CCIP fees. Need LINK? Get testnet LINK from{' '}
                  <a
                    href="https://faucets.chain.link/"
                    target="_blank"
                    rel="noopener noreferrer"
                    className="underline hover:text-blue-700 dark:hover:text-blue-300"
                  >
                    Chainlink Faucet
                  </a>
                </p>
              )}
            </div>

            {/* Action Button */}
            <div className="pt-4">{renderActionButton()}</div>
          </CardContent>
        </form>
      </Card>
    </main>
  );
};

export const bridgeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/bridge',
  component: BridgePage,
});
