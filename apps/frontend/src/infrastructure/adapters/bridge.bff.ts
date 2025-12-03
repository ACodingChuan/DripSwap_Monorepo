import { BridgeHttpAdapter } from './bridge.http';

export class BridgeBffAdapter extends BridgeHttpAdapter {
  constructor() {
    const base = import.meta.env.VITE_API_BASE_URL;
    super(base && base.length > 0 ? base : undefined);
  }
}
