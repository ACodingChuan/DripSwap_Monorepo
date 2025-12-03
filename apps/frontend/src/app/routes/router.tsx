import { createRouter } from '@tanstack/react-router';

import { bridgeRoute } from './bridge';
import { faucetRoute } from './faucet';
import { indexRoute } from './index';
import { poolsRoute } from './pools';
import { poolsAddRoute } from './pools-add';
import { poolsMineRoute } from './pools-mine';
import { poolsRemoveRoute } from './pools-remove';
import { rootRoute } from './root';
import { swapRoute } from './swap';
import { swapTestRoute } from './swap-test';
import { walletRoute } from './wallet';

const routeTree = rootRoute.addChildren([
  indexRoute,
  swapRoute,
  swapTestRoute,
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
