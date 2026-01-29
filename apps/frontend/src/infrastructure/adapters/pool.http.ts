import { gql } from '@/infrastructure/graphql/client';
import type {
  GetPoolDetailsInput,
  GetPoolPriceCandlesInput,
  GetPoolTransactionsInput,
  PoolDetails,
  PoolOhlc,
  PoolPort,
  PoolTransactionRow,
  PoolTransactionType,
} from '@/domain/ports/pool-port';

type PoolDetailsResponse = {
  poolDetails: {
    chainId: string;
    pairAddress: string;
    token0: { address: string; symbol: string; name: string };
    token1: { address: string; symbol: string; name: string };

    tvlUsd: string | number;
    volume24hUsd: string | number;
    fees24hUsd: string | number;
    tx24hCount?: number | null;

    apr?: string | number | null;
    reserve0?: string | number | null;
    reserve1?: string | number | null;
    token0Price?: string | number | null;
    token1Price?: string | number | null;
  } | null;
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

export class PoolHttpAdapter implements PoolPort {
  async getPoolDetails(input: GetPoolDetailsInput): Promise<PoolDetails | null> {
    const data = await gql<PoolDetailsResponse, { chainId: string; pairAddress: string }>(
      `
        query PoolDetails($chainId: String!, $pairAddress: String!) {
          poolDetails(chainId: $chainId, pairAddress: $pairAddress) {
            chainId
            pairAddress
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
            volume24hUsd
            fees24hUsd
            tx24hCount
            apr
            reserve0
            reserve1
            token0Price
            token1Price
          }
        }
      `,
      { chainId: input.chainId, pairAddress: input.pairAddress }
    );

    if (!data.poolDetails) return null;

    return {
      chainId: data.poolDetails.chainId,
      pairAddress: data.poolDetails.pairAddress,
      token0: data.poolDetails.token0,
      token1: data.poolDetails.token1,
      tvlUsd: asNumber(data.poolDetails.tvlUsd),
      volume24hUsd: asNumber(data.poolDetails.volume24hUsd),
      fees24hUsd: asNumber(data.poolDetails.fees24hUsd),
      tx24hCount: data.poolDetails.tx24hCount ?? null,
      apr: asOptionalNumber(data.poolDetails.apr),
      reserve0: asOptionalNumber(data.poolDetails.reserve0),
      reserve1: asOptionalNumber(data.poolDetails.reserve1),
      token0Price: asOptionalNumber(data.poolDetails.token0Price),
      token1Price: asOptionalNumber(data.poolDetails.token1Price),
    } satisfies PoolDetails;
  }

  async getPoolPriceCandles(input: GetPoolPriceCandlesInput): Promise<PoolOhlc[]> {
    const data = await gql<
      { poolPriceCandles: Array<{ timestamp: number; tvlUsd: string | number; volumeUsd: string | number; feesUsd: string | number }> },
      { chainId: string; pairAddress: string; interval: string; from: number; to: number }
    >(
      `
        query PoolPriceCandles(
          $chainId: String!
          $pairAddress: String!
          $interval: PoolChartInterval!
          $from: Int!
          $to: Int!
        ) {
          poolPriceCandles(
            chainId: $chainId
            pairAddress: $pairAddress
            interval: $interval
            from: $from
            to: $to
          ) {
            timestamp
            tvlUsd
            volumeUsd
            feesUsd
          }
        }
      `,
      {
        chainId: input.chainId,
        pairAddress: input.pairAddress,
        interval: input.interval,
        from: input.from,
        to: input.to,
      }
    );

    return data.poolPriceCandles.map((row) => ({
      timestamp: row.timestamp,
      tvlUsd: asNumber(row.tvlUsd),
      volumeUsd: asNumber(row.volumeUsd),
      feesUsd: asNumber(row.feesUsd),
    }));
  }

  async getPoolTransactions(input: GetPoolTransactionsInput): Promise<PoolTransactionRow[]> {
    const limit = input.limit ?? 25;
    const data = await gql<
      {
        poolTransactions: Array<{
          type: PoolTransactionType;
          timestamp: number;
          txHash: string;
          amountUsd?: string | number | null;
          token0Amount?: string | number | null;
          token1Amount?: string | number | null;
          account?: string | null;
        }>;
      },
      { chainId: string; pairAddress: string; limit: number; types?: PoolTransactionType[] }
    >(
      `
        query PoolTransactions($chainId: String!, $pairAddress: String!, $limit: Int, $types: [ExploreTxType!]) {
          poolTransactions(chainId: $chainId, pairAddress: $pairAddress, limit: $limit, types: $types) {
            type
            timestamp
            txHash
            amountUsd
            token0Amount
            token1Amount
            account
          }
        }
      `,
      {
        chainId: input.chainId,
        pairAddress: input.pairAddress,
        limit,
        types: input.types,
      }
    );

    return data.poolTransactions.map((row) => ({
      type: row.type,
      timestamp: Number(row.timestamp),
      txHash: row.txHash,
      amountUsd: asOptionalNumber(row.amountUsd),
      token0Amount: asOptionalNumber(row.token0Amount),
      token1Amount: asOptionalNumber(row.token1Amount),
      account: row.account ?? null,
    }));
  }
}
