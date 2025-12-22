import { createRoute, redirect } from '@tanstack/react-router';

import { rootRoute } from './root';

export const exploreRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/explore',
  beforeLoad: () => {
    throw redirect({ to: '/explore/tokens' });
  },
});

