import { http } from '@/infrastructure/http/client';

export type FaucetV2ClaimRequest = {
  chainId: number;
  user: string;
  idempotencyKey: string;
  deviceId?: string;
  captchaId?: string;
  captchaAnswer?: string;
  token?: string;
};

export type FaucetV2ClaimResponse = {
  requestId: string;
  status: string;
  message?: string | null;
  txHash?: string | null;
};

export function postFaucetV2Claim(body: FaucetV2ClaimRequest) {
  return http<FaucetV2ClaimResponse>(`/api/faucet/v2/claim`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function postFaucetV2ClaimV2(body: FaucetV2ClaimRequest) {
  return http<FaucetV2ClaimResponse>(`/api/faucet/v2/claim2`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export type FaucetV2CaptchaResponse = {
  enabled: boolean;
  captchaId: string;
  imageData: string;
  expiresInSeconds: number;
};

export function fetchFaucetV2Captcha() {
  return http<FaucetV2CaptchaResponse>(`/api/faucet/v2/captcha`);
}
