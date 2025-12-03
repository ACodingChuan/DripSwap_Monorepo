import { http } from '@/infrastructure/http/client';
import type { PoolsPort, PoolsSummary } from '@/domain/ports/pools-port';

const BASE_PATH = '/api/pools';
// 协议层返回体（Wire format）：描述“服务端返回的完整 JSON 形状”
type SummaryResponse = { summary: PoolsSummary };

// 通过 HTTP 实现 PoolsPort
export class PoolsHttpAdapter implements PoolsPort {
  // baseUrl 由外部注入：BFF 模式注入环境变量，或保持 undefined 用相对路径
  constructor(private readonly baseUrl?: string) {}

  // 实现端口：发起 GET /api/pools/summary
  async getSummary(): Promise<PoolsSummary> {
    // <SummaryResponse> 泛型：获得强类型智能提示与编译期校验
    const res = await http<SummaryResponse>(`${BASE_PATH}/summary`, {
      baseUrl: this.baseUrl,
    });
    // 只返回“领域数据”，把协议外壳（summary）剥掉
    return res.summary;
  }
}
