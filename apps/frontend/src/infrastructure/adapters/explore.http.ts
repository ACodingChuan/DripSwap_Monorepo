import { gql } from '@/infrastructure/graphql/client';
import type {
  ExplorePort,
  ExploreRecentTransaction,
  ExploreStats,
  ExploreTokenRow,
  GetExploreStatsInput,
  GetExploreTokensInput,
  GetRecentTransactionsInput,
} from '@/domain/ports/explore-port';

type RecentTransactionsResponse = {
  recentTransactions: ExploreRecentTransaction[];
};

type ExploreStatsResponse = {
  exploreStats: ExploreStats;
};

type ExploreTokensResponse = {
  exploreTokens: ExploreTokenRow[];
};

export class ExploreHttpAdapter implements ExplorePort {
  async getRecentTransactions(input: GetRecentTransactionsInput): Promise<ExploreRecentTransaction[]> {
    const limit = input.limit ?? 25;
    const data = await gql<RecentTransactionsResponse, { chainId: string; limit: number }>(
      `
        query RecentTransactions($chainId: String!, $limit: Int) {
          recentTransactions(chainId: $chainId, limit: $limit) {
            id
            chainId
            blockNumber
            txHash
            eventSig
            decodedName
            decodedData
            status
            createdAt
          }
        }
      `,
      { chainId: input.chainId, limit }
    );

    return data.recentTransactions;
  }

  async getExploreStats(input: GetExploreStatsInput): Promise<ExploreStats> {
    const days = input.days ?? 30;
    const data = await gql<ExploreStatsResponse, { chainId: string; days: number }>(
      `
        query ExploreStats($chainId: String!, $days: Int) {
          exploreStats(chainId: $chainId, days: $days) {
            chainId
            tvlUsd
            volume24hUsd
            fees24hUsd
            tvlSeries {
              date
              valueUsd
            }
            volumeSeries {
              date
              valueUsd
            }
          }
        }
      `,
      { chainId: input.chainId, days }
    );

    return data.exploreStats;
  }

  async getExploreTokens(input: GetExploreTokensInput): Promise<ExploreTokenRow[]> {
    const limit = input.limit ?? 50;
    const search = input.search ?? null;

    const data = await gql<ExploreTokensResponse, { chainId: string; limit: number; search: string | null }>(
      `
        query ExploreTokens($chainId: String!, $limit: Int, $search: String) {
          exploreTokens(chainId: $chainId, limit: $limit, search: $search) {
            id
            symbol
            name
            priceUsd
            change1h
            change1d
            fdvUsd
            volume24hUsd
          }
        }
      `,
      { chainId: input.chainId, limit, search }
    );

    // 后端已经计算好所有派生字段,前端直接使用
    return data.exploreTokens.map(token => ({
      id: token.id,
      symbol: token.symbol,
      name: token.name,
      priceUsd: token.priceUsd ? Number(token.priceUsd) : 0,
      change1h: token.change1h ? Number(token.change1h) : null,
      change1d: token.change1d ? Number(token.change1d) : null,
      fdvUsd: token.fdvUsd ? Number(token.fdvUsd) : null,
    }));
  }
}
