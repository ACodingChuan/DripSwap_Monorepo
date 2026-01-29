export function getExplorerTxUrl(chainId: number, txHash: string): string | null {
  if (!txHash) return null;
  if (chainId === 11155111) return `https://sepolia.etherscan.io/tx/${txHash}`;
  if (chainId === 534351) return `https://sepolia.scrollscan.com/tx/${txHash}`;
  return null;
}

export function getExplorerAddressUrl(chainId: number, address: string): string | null {
  if (!address) return null;
  if (chainId === 11155111) return `https://sepolia.etherscan.io/address/${address}`;
  if (chainId === 534351) return `https://sepolia.scrollscan.com/address/${address}`;
  return null;
}

