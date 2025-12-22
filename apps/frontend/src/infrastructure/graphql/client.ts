import { http } from '@/infrastructure/http/client';

export type GraphQLError = {
  message: string;
  path?: Array<string | number>;
  extensions?: Record<string, unknown>;
};

export class GraphQLRequestError extends Error {
  constructor(
    message: string,
    readonly errors: GraphQLError[]
  ) {
    super(message);
    this.name = 'GraphQLRequestError';
  }
}

type GraphQLResponse<T> = {
  data?: T;
  errors?: GraphQLError[];
};

export async function gql<TData, TVariables extends Record<string, unknown>>(
  query: string,
  variables: TVariables
): Promise<TData> {
  const response = await http<GraphQLResponse<TData>>('/graphql', {
    method: 'POST',
    body: JSON.stringify({ query, variables }),
  });

  if (response.errors && response.errors.length > 0) {
    throw new GraphQLRequestError('GraphQL request failed', response.errors);
  }

  if (!response.data) {
    throw new GraphQLRequestError('GraphQL response missing data', response.errors ?? []);
  }

  return response.data;
}

