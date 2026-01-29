export function getV2SubgraphUrl(chainId: number): string {
  // The Graph Studio endpoints (one subgraph per chain).
  switch (chainId) {
    case 11155111: // Sepolia
      return 'https://api.studio.thegraph.com/query/1718761/dripswap-v-2-sepolia/version/latest';
    case 534351: // Scroll Sepolia
      return 'https://api.studio.thegraph.com/query/1716244/dripswap_v2_scroll_sepolia/version/latest';
    default:
      throw new Error(`No V2 subgraph configured for chainId ${chainId}`);
  }
}

