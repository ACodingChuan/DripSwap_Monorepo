import type { SwapPort } from '@/domain/ports/swap-port';

import { SwapBffAdapter } from './swap.bff';

import type { PoolsPort } from '@/domain/ports/pools-port';
import { PoolsBffAdapter } from './pools.bff';

import type { FaucetPort } from '@/domain/ports/faucet-port';
import { FaucetBffAdapter } from './faucet.bff';
import type { BridgePort } from '@/domain/ports/bridge-port';
import { BridgeBffAdapter } from './bridge.bff';
import type { ExplorePort } from '@/domain/ports/explore-port';
import { ExploreBffAdapter } from './explore.bff';
import type { TokenPort } from '@/domain/ports/token-port';
import { TokenBffAdapter } from './token.bff';

export function resolveSwapAdapter(): SwapPort {
  return new SwapBffAdapter();
}

export function resolvePoolsAdapter(): PoolsPort {
  // 也可以按 VITE_API_IMPL 做分支，这里固定走 BFF
  return new PoolsBffAdapter();
}

export function resolveFaucetAdapter(): FaucetPort {
  return new FaucetBffAdapter();
}

export function resolveBridgeAdapter(): BridgePort {
  return new BridgeBffAdapter();
}

export function resolveExploreAdapter(): ExplorePort {
  return new ExploreBffAdapter();
}

export function resolveTokenAdapter(): TokenPort {
  return new TokenBffAdapter();
}
