export function getV2SubgraphUrl(chainId: number): string {
  // Public Goldsky endpoints.
  switch (chainId) {
    case 11155111: // Sepolia
      return 'https://api.goldsky.com/api/public/project_cmke483ckgziz01w9gr6cb0we/subgraphs/dripswap-v2-sepolia/1.0.5/gn';
    case 534351: // Scroll Sepolia
      return 'https://api.goldsky.com/api/public/project_cmjbktp0056ic01yj30ya4t7q/subgraphs/dripswap-v2-scroll-sepolia/1.0.5/gn';
    default:
      throw new Error(`No V2 subgraph configured for chainId ${chainId}`);
  }
}
