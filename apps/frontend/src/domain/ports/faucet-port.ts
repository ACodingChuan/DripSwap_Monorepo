export type FaucetRequestInput = {
  network: string;
  token: string;
  amount?: string;
  recipient: string;
};

export type FaucetRequest = {
  id: string;
  status: string;
};

export interface FaucetPort {
  request(input: FaucetRequestInput): Promise<FaucetRequest>;
}
