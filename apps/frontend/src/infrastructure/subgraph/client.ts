import { getV2SubgraphUrl } from './endpoints';

export type SubgraphError = {
  message: string;
  locations?: Array<{ line: number; column: number }>;
  path?: Array<string | number>;
};

export class SubgraphRequestError extends Error {
  constructor(
    message: string,
    readonly errors: SubgraphError[]
  ) {
    super(message);
    this.name = 'SubgraphRequestError';
  }
}

type SubgraphResponse<T> = {
  data?: T;
  errors?: SubgraphError[];
};

export async function subgraphGql<TData, TVariables extends Record<string, unknown>>(
  chainId: number,
  query: string,
  variables: TVariables
): Promise<TData> {
  const url = getV2SubgraphUrl(chainId);
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ query, variables }),
  });

  if (!res.ok) {
    throw new SubgraphRequestError(`Subgraph request failed (${res.status})`, []);
  }

  const json = (await res.json()) as SubgraphResponse<TData>;
  if (json.errors && json.errors.length > 0) {
    throw new SubgraphRequestError('Subgraph GraphQL returned errors', json.errors);
  }
  if (!json.data) {
    throw new SubgraphRequestError('Subgraph response missing data', json.errors ?? []);
  }
  return json.data;
}

