import { http } from '@/infrastructure/http/client';
import type { SwapPort, SwapQuote, SwapQuoteInput, TokenSummary } from '@/domain/ports/swap-port';

const BASE_PATH = '/api/swap';

type TokensResponse = {
  tokens: TokenSummary[];
};

type QuoteResponse = {
  quote: SwapQuote;
};

export class SwapHttpAdapter implements SwapPort {
  constructor(private readonly baseUrl?: string) {}

  async getTokens(): Promise<TokenSummary[]> {
    const response = await http<TokensResponse>(`${BASE_PATH}/tokens`, {
      baseUrl: this.baseUrl,
    });

    return response.tokens;
  }

  async getQuote(input: SwapQuoteInput): Promise<SwapQuote> {
    const response = await http<QuoteResponse>(`${BASE_PATH}/quote`, {
      method: 'POST',
      body: JSON.stringify(input),
      baseUrl: this.baseUrl,
    });

    return response.quote;
  }
}
