import { resolveBridgeAdapter } from '@/infrastructure/adapters';
import type { BridgeTransferInput, BridgeTransfer } from '@/domain/ports/bridge-port';

const adapter = resolveBridgeAdapter();

export function requestBridgeTransfer(input: BridgeTransferInput): Promise<BridgeTransfer> {
  return adapter.transfer(input);
}
