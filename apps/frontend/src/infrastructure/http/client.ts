export type ApiError = {
  code: string;
  message: string;
  details?: Record<string, unknown>;
  traceId?: string;
  retryable?: boolean;
};

type RequestOptions = RequestInit & { baseUrl?: string };

const DEFAULT_HEADERS: HeadersInit = {
  'Content-Type': 'application/json',
};

export async function http<T>(input: RequestInfo | URL, init: RequestOptions = {}) {
  const baseUrl = init.baseUrl ?? import.meta.env.VITE_API_BASE_URL ?? '';
  const url = typeof input === 'string' ? `${baseUrl}${input}` : input;

  const response = await fetch(url, {
    ...init,
    headers: {
      ...DEFAULT_HEADERS,
      ...init.headers,
    },
  });

  if (!response.ok) {
    const errorBody = await parseError(response);
    throw errorBody;
  }

  if (response.status === 204) {
    return null as T;
  }

  return (await response.json()) as T;
}

async function parseError(response: Response): Promise<ApiError> {
  try {
    const data = (await response.json()) as Partial<ApiError>;
    return {
      code: data.code ?? 'UNKNOWN_ERROR',
      message: data.message ?? response.statusText,
      details: data.details,
      traceId: data.traceId,
      retryable: data.retryable,
    } satisfies ApiError;
  } catch {
    return {
      code: 'UNKNOWN_ERROR',
      message: response.statusText || 'Request failed',
    } satisfies ApiError;
  }
}
