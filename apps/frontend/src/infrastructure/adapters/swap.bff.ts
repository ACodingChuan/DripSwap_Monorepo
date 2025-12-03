import { SwapHttpAdapter } from './swap.http';

export class SwapBffAdapter extends SwapHttpAdapter {
  constructor() {
    const baseUrl = import.meta.env.VITE_API_BASE_URL;
    super(baseUrl && baseUrl.length > 0 ? baseUrl : undefined);
  }
}
