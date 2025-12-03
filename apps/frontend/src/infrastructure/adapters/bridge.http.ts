import { http } from '@/infrastructure/http/client';
import type { BridgePort, BridgeTransfer, BridgeTransferInput } from '@/domain/ports/bridge-port';

const BASE_PATH = '/api/bridge';

type TransferResponse = { transfer: BridgeTransfer };

export class BridgeHttpAdapter implements BridgePort {
  constructor(private readonly baseUrl?: string) {}

  async transfer(input: BridgeTransferInput): Promise<BridgeTransfer> {
    const res = await http<TransferResponse>(`${BASE_PATH}/transfer`, {
      method: 'POST',
      body: JSON.stringify(input),
      baseUrl: this.baseUrl,
    });
    return res.transfer;
  }
}
