import { http } from '@/infrastructure/http/client';
import type { FaucetPort, FaucetRequest, FaucetRequestInput } from '@/domain/ports/faucet-port';

const BASE_PATH = '/api/faucet';

type FaucetResponse = { request: FaucetRequest };

export class FaucetHttpAdapter implements FaucetPort {
  constructor(private readonly baseUrl?: string) {}

  async request(input: FaucetRequestInput): Promise<FaucetRequest> {
    const res = await http<FaucetResponse>(`${BASE_PATH}/request`, {
      method: 'POST',
      body: JSON.stringify(input),
      baseUrl: this.baseUrl,
    });
    return res.request;
  }
}
