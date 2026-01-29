import { resolvePoolAdapter } from '@/infrastructure/adapters';
import type {
  GetPoolDetailsInput,
  GetPoolPriceCandlesInput,
  GetPoolTransactionsInput,
  PoolDetails,
  PoolOhlc,
  PoolTransactionRow,
} from '@/domain/ports/pool-port';

const adapter = resolvePoolAdapter();

export function fetchPoolDetails(input: GetPoolDetailsInput): Promise<PoolDetails | null> {
  return adapter.getPoolDetails(input);
}

export function fetchPoolPriceCandles(input: GetPoolPriceCandlesInput): Promise<PoolOhlc[]> {
  return adapter.getPoolPriceCandles(input);
}

export function fetchPoolTransactions(input: GetPoolTransactionsInput): Promise<PoolTransactionRow[]> {
  return adapter.getPoolTransactions(input);
}
