import { gql } from '@/infrastructure/graphql/client';
import type {
  ExplorePort,
  ExplorePoolRow,
  ExplorePoolSort,
  ExploreRecentTransaction,
  ExploreStats,
  ExploreTokenRow,
  GetExplorePoolsInput,
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

type ExplorePoolsResponse = {
  explorePools: Array<{
    pairAddress: string;
    chainId: string;
    token0: { address: string; symbol: string; name: string };
    token1: { address: string; symbol: string; name: string };
    tvlUsd: string | number;
    tvlChange1d?: string | number | null;
    volume24hUsd: string | number;
    volume1wUsd?: string | number | null;
    fees24hUsd: string | number;
    tx24hCount?: number | null;
    apr?: string | number | null;
  }>;
};

function asNumber(value: string | number | null | undefined): number {
  if (value === null || value === undefined) return 0;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : 0;
}

function asOptionalNumber(value: string | number | null | undefined): number | null {
  if (value === null || value === undefined) return null;
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}

export class ExploreHttpAdapter implements ExplorePort {
  async getRecentTransactions(input: GetRecentTransactionsInput): Promise<ExploreRecentTransaction[]> {
    const limit = input.limit ?? 25;
    const data = await gql<
      RecentTransactionsResponse,
      { chainId: string; limit: number }
    >(
      `
        query RecentTransactions($chainId: String!, $limit: Int) {
          recentTransactions(chainId: $chainId, limit: $limit) {
            id
            chainId
            blockNumber
            txHash
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

  async getExplorePools(input: GetExplorePoolsInput): Promise<ExplorePoolRow[]> {
    const limit = input.limit ?? 50;
    const search = input.search ?? null;
    const sort: ExplorePoolSort = input.sort ?? 'TVL_DESC';

    const data = await gql<
      ExplorePoolsResponse,
      { chainId: string; limit: number; search: string | null; sort: ExplorePoolSort }
    >(
      `
        query ExplorePools($chainId: String!, $limit: Int, $search: String, $sort: ExplorePoolSort) {
          explorePools(chainId: $chainId, limit: $limit, search: $search, sort: $sort) {
            pairAddress
            chainId
            token0 {
              address
              symbol
              name
            }
            token1 {
              address
              symbol
              name
            }
            tvlUsd
            tvlChange1d
            volume24hUsd
            volume1wUsd
            fees24hUsd
            tx24hCount
            apr
          }
        }
      `,
      { chainId: input.chainId, limit, search, sort }
    );

    return data.explorePools.map(
      (row) =>
        ({
          pairAddress: row.pairAddress,
          chainId: row.chainId,
          token0: row.token0,
          token1: row.token1,
          tvlUsd: asNumber(row.tvlUsd),
          tvlChange1d: asOptionalNumber(row.tvlChange1d),
          volume24hUsd: asNumber(row.volume24hUsd),
          volume1wUsd: asOptionalNumber(row.volume1wUsd),
          fees24hUsd: asNumber(row.fees24hUsd),
          tx24hCount: row.tx24hCount ?? null,
          apr: asOptionalNumber(row.apr),
        }) satisfies ExplorePoolRow
    );
  }
}
