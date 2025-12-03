import { resolveFaucetAdapter } from '@/infrastructure/adapters';
import type { FaucetRequestInput, FaucetRequest } from '@/domain/ports/faucet-port';

const adapter = resolveFaucetAdapter();

export function requestFaucet(input: FaucetRequestInput): Promise<FaucetRequest> {
  return adapter.request(input);
}
