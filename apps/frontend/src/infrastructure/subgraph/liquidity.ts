import { subgraphGql } from './client';

export type SubgraphLiquidityPosition = {
  pairAddress: `0x${string}`;
  token0: { address: `0x${string}`; symbol: string };
  token1: { address: `0x${string}`; symbol: string };
  liquidityTokenBalance: string; // human string (18 decimals, derived from mint-burn)
};

type MintBurnPositionsResponse = {
  mints: Array<{
    liquidity: string;
    pair: {
      id: string;
      token0: { id: string; symbol: string };
      token1: { id: string; symbol: string };
    };
  }>;
  burns: Array<{
    liquidity: string;
    pair: {
      id: string;
      token0: { id: string; symbol: string };
      token1: { id: string; symbol: string };
    };
  }>;
};

function bigDecimalToScaledBigInt(value: string, decimals = 18): bigint {
  const v = value.trim();
  if (!v) return 0n;
  const negative = v.startsWith('-');
  const raw = negative ? v.slice(1) : v;
  const [iRaw, fRaw = ''] = raw.split('.');
  const i = iRaw.replace(/^0+/, '') || '0';
  const f = (fRaw + '0'.repeat(decimals)).slice(0, decimals);
  const asBigInt = BigInt(i) * 10n ** BigInt(decimals) + BigInt(f || '0');
  return negative ? -asBigInt : asBigInt;
}

function scaledBigIntToDecimalString(value: bigint, decimals = 18): string {
  const negative = value < 0n;
  const v = negative ? -value : value;
  const base = 10n ** BigInt(decimals);
  const i = v / base;
  const f = v % base;
  if (f === 0n) return `${negative ? '-' : ''}${i.toString()}`;

  // For values < 1, keep more precision so tiny LP balances don't appear as "0".
  // For values >= 1, keep it readable with fewer decimals.
  const maxDp = i > 0n ? 6 : decimals;

  const fStr = f
    .toString()
    .padStart(decimals, '0')
    .slice(0, maxDp)
    .replace(/0+$/, '');
  return `${negative ? '-' : ''}${i.toString()}${fStr ? `.${fStr}` : ''}`;
}

export async function fetchUserLiquidityPositions(
  chainId: number,
  userAddress: `0x${string}`
): Promise<SubgraphLiquidityPosition[]> {
  const user = userAddress.toLowerCase();

  // NOTE:
  // Our deployed subgraph does not store LiquidityPosition / LP balances.
  // To avoid on-chain scanning, we approximate positions as:
  //   sum(mints.to=user).liquidity - sum(burns.sender=user).liquidity
  // This matches typical router flows (no LP transfers between EOAs).
  const data = await subgraphGql<MintBurnPositionsResponse, { user: string }>(
    chainId,
    `
      query UserLiquidityPositions($user: Bytes!) {
        mints(first: 1000, where: { to: $user }) {
          liquidity
          pair {
            id
            token0 { id symbol }
            token1 { id symbol }
          }
        }
        burns(first: 1000, where: { sender: $user }) {
          liquidity
          pair {
            id
            token0 { id symbol }
            token1 { id symbol }
          }
        }
      }
    `,
    { user }
  );

  const map = new Map<
    string,
    { pair: `0x${string}`; token0: { address: `0x${string}`; symbol: string }; token1: { address: `0x${string}`; symbol: string }; net: bigint }
  >();

  const upsert = (pairId: string, token0: { id: string; symbol: string }, token1: { id: string; symbol: string }) => {
    const pair = pairId.toLowerCase() as `0x${string}`;
    let row = map.get(pair);
    if (!row) {
      row = {
        pair,
        token0: { address: token0.id.toLowerCase() as `0x${string}`, symbol: token0.symbol },
        token1: { address: token1.id.toLowerCase() as `0x${string}`, symbol: token1.symbol },
        net: 0n,
      };
      map.set(pair, row);
    }
    return row;
  };

  for (const m of data.mints ?? []) {
    const row = upsert(m.pair.id, m.pair.token0, m.pair.token1);
    row.net += bigDecimalToScaledBigInt(m.liquidity, 18);
  }

  for (const b of data.burns ?? []) {
    const row = upsert(b.pair.id, b.pair.token0, b.pair.token1);
    row.net -= bigDecimalToScaledBigInt(b.liquidity, 18);
  }

  return Array.from(map.values())
    .filter((row) => row.net > 0n)
    .sort((a, b) => (a.net > b.net ? -1 : 1))
    .map((row) => ({
      pairAddress: row.pair,
      token0: row.token0,
      token1: row.token1,
      liquidityTokenBalance: scaledBigIntToDecimalString(row.net, 18),
    }));
}
