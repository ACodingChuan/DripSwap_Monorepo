import { FaucetHttpAdapter } from './faucet.http';

export class FaucetBffAdapter extends FaucetHttpAdapter {
  constructor() {
    const base = import.meta.env.VITE_API_BASE_URL;
    super(base && base.length > 0 ? base : undefined);
  }
}
