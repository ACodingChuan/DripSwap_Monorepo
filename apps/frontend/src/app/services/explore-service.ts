import { resolveExploreAdapter } from '@/infrastructure/adapters';
import type { GetExploreStatsInput, GetExploreTokensInput, GetRecentTransactionsInput } from '@/domain/ports/explore-port';

const adapter = resolveExploreAdapter();

export function fetchRecentTransactions(input: GetRecentTransactionsInput) {
  return adapter.getRecentTransactions(input);
}

export function fetchExploreStats(input: GetExploreStatsInput) {
  return adapter.getExploreStats(input);
}

export function fetchExploreTokens(input: GetExploreTokensInput) {
  return adapter.getExploreTokens(input);
}
