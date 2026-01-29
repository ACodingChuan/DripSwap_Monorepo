import { http } from '@/infrastructure/http/client';

export type CcipBridgeRecordCreateRequest = {
  messageId: string;
  userAddress: string;
  tokenSymbol: string;
  tokenAddress: string;
  fromChainId: number;
  toChainId: number;
  sourceTxHash?: string | null;
};

export type CcipBridgeRecordResponse = {
  id: string;
  messageId: string;
  userAddress: string;
  tokenSymbol: string;
  tokenAddress: string;
  fromChainId: number;
  toChainId: number;
  sourceTxHash?: string | null;
  createdAt: string;
};

export function postCcipBridgeRecord(body: CcipBridgeRecordCreateRequest) {
  return http<CcipBridgeRecordResponse>(`/api/bridge/ccip/records`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function fetchCcipBridgeRecordsByUser(userAddress: string, limit = 20) {
  const qs = new URLSearchParams({ userAddress, limit: String(limit) });
  return http<CcipBridgeRecordResponse[]>(`/api/bridge/ccip/records/by-user?${qs.toString()}`);
}

export function fetchCcipBridgeRecordByMessageId(messageId: string) {
  const qs = new URLSearchParams({ messageId });
  return http<CcipBridgeRecordResponse | null>(`/api/bridge/ccip/records/by-message?${qs.toString()}`);
}

export function searchCcipBridgeRecords(q: string, limit = 20) {
  const qs = new URLSearchParams({ q, limit: String(limit) });
  return http<CcipBridgeRecordResponse[]>(`/api/bridge/ccip/records/search?${qs.toString()}`);
}
