import { createRouter } from '@tanstack/react-router';

import { bridgeRoute } from './bridge';
import { faucetRoute } from './faucet';
import { exploreRoute } from './explore';
import { explorePoolsRoute } from './explore-pools';
import { exploreTokensRoute } from './explore-tokens';
import { exploreTransactionsRoute } from './explore-transactions';
import { indexRoute } from './index';
import { poolDetailsRoute } from './pool-details';
import { poolsRoute } from './pools';
import { poolsAddRoute } from './pools-add';
import { poolsMineRoute } from './pools-mine';
import { poolsRemoveRoute } from './pools-remove';
import { rootRoute } from './root';
import { swapRoute } from './swap';
import { swapTestRoute } from './swap-test';
import { tokenDetailsRoute } from './token-details';
import { walletRoute } from './wallet';

const routeTree = rootRoute.addChildren([
  indexRoute,
  swapRoute,
  swapTestRoute,
  exploreRoute,
  exploreTokensRoute,
  explorePoolsRoute,
  exploreTransactionsRoute,
  tokenDetailsRoute,
  poolDetailsRoute,
  poolsRoute,
  poolsMineRoute,
  poolsAddRoute,
  poolsRemoveRoute,
  faucetRoute,
  bridgeRoute,
  walletRoute,
]);

export const router = createRouter({ routeTree });

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router;
  }
}
