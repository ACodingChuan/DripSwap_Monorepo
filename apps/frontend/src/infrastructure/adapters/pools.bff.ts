import { PoolsHttpAdapter } from './pools.http';

// BFF = Backend For Frontend：直连你的后端网关
export class PoolsBffAdapter extends PoolsHttpAdapter {
  constructor() {
    // 从环境变量注入 baseUrl，例如 http://localhost:8080
    const base = import.meta.env.VITE_API_BASE_URL;
    super(base && base.length > 0 ? base : undefined);
  }
}
