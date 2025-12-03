export type BridgeTransferInput = {
  fromNetwork: string;
  toNetwork: string;
  token: string;
  amount?: string;
};

export type BridgeTransfer = {
  id: string;
  status: string;
};

export interface BridgePort {
  transfer(input: BridgeTransferInput): Promise<BridgeTransfer>;
}
