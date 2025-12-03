const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

export const authService = {
  async getNonce(address: string): Promise<string> {
    const response = await fetch(`${API_BASE_URL}/session/nonce?address=${address}`);
    if (!response.ok) {
      throw new Error('Failed to get nonce');
    }
    return response.text();
  },

  async login(address: string, nonce: string, signature: string): Promise<{ sessionId: string }> {
    const response = await fetch(`${API_BASE_URL}/session/login`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ address, nonce, signature }),
    });

    if (!response.ok) {
      throw new Error('Login failed');
    }
    return response.json();
  },
};
