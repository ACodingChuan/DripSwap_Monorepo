import { resolveTokenAdapter } from '@/infrastructure/adapters';
import type {
  GetTokenDetailsInput,
  GetTokenPoolsInput,
  GetTokenPriceCandlesInput,
  GetTokenTransactionsInput,
} from '@/domain/ports/token-port';

const adapter = resolveTokenAdapter();

export function fetchTokenDetails(input: GetTokenDetailsInput) {
  return adapter.getTokenDetails(input);
}

export function fetchTokenPriceCandles(input: GetTokenPriceCandlesInput) {
  return adapter.getTokenPriceCandles(input);
}

export function fetchTokenPools(input: GetTokenPoolsInput) {
  return adapter.getTokenPools(input);
}

export function fetchTokenTransactions(input: GetTokenTransactionsInput) {
  return adapter.getTokenTransactions(input);
}

