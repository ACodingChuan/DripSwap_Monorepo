import { resolvePoolsAdapter } from '@/infrastructure/adapters';
import type { PoolsSummary } from '@/domain/ports/pools-port';

// 解析出一个“当前环境下”的适配器实例
const adapter = resolvePoolsAdapter();

/**
 * 给页面用的函数：获取 Pools 汇总
 * - UI 只用这个函数，不关心 HTTP/URL/错误模型
 */
export function fetchPoolsSummary(): Promise<PoolsSummary> {
  return adapter.getSummary();
}
