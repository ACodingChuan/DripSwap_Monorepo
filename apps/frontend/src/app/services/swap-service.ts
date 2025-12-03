import type { SwapQuoteInput } from '@/domain/ports/swap-port';

import { resolveSwapAdapter } from '@/infrastructure/adapters';

const swapAdapter = resolveSwapAdapter();

export function fetchSwapTokens() {
  return swapAdapter.getTokens();
}

export function fetchSwapQuote(input: SwapQuoteInput) {
  return swapAdapter.getQuote(input);
}
