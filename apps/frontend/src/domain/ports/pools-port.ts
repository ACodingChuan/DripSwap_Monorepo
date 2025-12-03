// 领域模型：页面真正消费的数据结构（与协议层解耦）
export type PoolsSummary = {
  tvlUsd: number; // 总锁仓（美元）
  volume24hUsd: number; // 24小时成交额（美元）
  updatedAt: string; // ISO 时间
};

// 端口接口：声明“我们需要的业务能力”而非“如何实现”
// UI/Service 只依赖这个接口，不依赖具体 HTTP/GraphQL 等实现
export interface PoolsPort {
  getSummary(): Promise<PoolsSummary>;
}
