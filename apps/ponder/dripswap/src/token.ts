import { hexToString, isHex } from "viem";

import { ERC20Abi } from "../abis/ERC20Abi";
import { ERC20NameBytesAbi } from "../abis/ERC20NameBytesAbi";
import { ERC20SymbolBytesAbi } from "../abis/ERC20SymbolBytesAbi";
import { SKIP_TOTAL_SUPPLY, STATIC_TOKEN_DEFINITIONS } from "./constants";

const NULL_ETH_VALUE =
  "0x0000000000000000000000000000000000000000000000000000000000000001";

export type TokenMetadata = {
  symbol: string;
  name: string;
  decimals: bigint;
  totalSupply: bigint;
};

function normalizeAddress(address: string): string {
  return address.toLowerCase();
}

function bytesToString(value: string): string | null {
  if (!isHex(value)) {
    return null;
  }
  if (value.toLowerCase() === NULL_ETH_VALUE) {
    return null;
  }
  return hexToString(value, { size: 32 }).replace(/\u0000/g, "");
}

function getStaticDefinition(address: string) {
  const normalized = normalizeAddress(address);
  return STATIC_TOKEN_DEFINITIONS.find((token) => token.address === normalized);
}

async function readContractSafe<T>(client: any, request: any): Promise<T | null> {
  try {
    return await client.readContract(request);
  } catch {
    return null;
  }
}

export async function fetchTokenMetadata(
  client: any,
  address: string
): Promise<TokenMetadata> {
  const staticDef = getStaticDefinition(address);
  const normalized = normalizeAddress(address);
  let symbol = staticDef?.symbol;
  let name = staticDef?.name;
  let decimals = staticDef?.decimals;

  if (!symbol) {
    const symbolResult = await readContractSafe<string>(client, {
      address: normalized,
      abi: ERC20Abi,
      functionName: "symbol",
    });
    symbol = symbolResult ?? "unknown";
    if (!symbolResult) {
      const symbolBytes = await readContractSafe<string>(client, {
        address: normalized,
        abi: ERC20SymbolBytesAbi,
        functionName: "symbol",
      });
      const decoded = symbolBytes ? bytesToString(symbolBytes) : null;
      if (decoded) {
        symbol = decoded;
      }
    }
  }

  if (!name) {
    const nameResult = await readContractSafe<string>(client, {
      address: normalized,
      abi: ERC20Abi,
      functionName: "name",
    });
    name = nameResult ?? "unknown";
    if (!nameResult) {
      const nameBytes = await readContractSafe<string>(client, {
        address: normalized,
        abi: ERC20NameBytesAbi,
        functionName: "name",
      });
      const decoded = nameBytes ? bytesToString(nameBytes) : null;
      if (decoded) {
        name = decoded;
      }
    }
  }

  if (!decimals) {
    const decimalsResult = await readContractSafe<number>(client, {
      address: normalized,
      abi: ERC20Abi,
      functionName: "decimals",
    });
    decimals = decimalsResult !== null ? BigInt(decimalsResult) : 18n;
  }

  let totalSupply = 0n;
  if (!SKIP_TOTAL_SUPPLY.includes(normalized)) {
    const totalSupplyResult = await readContractSafe<bigint>(client, {
      address: normalized,
      abi: ERC20Abi,
      functionName: "totalSupply",
    });
    if (totalSupplyResult !== null) {
      totalSupply = totalSupplyResult;
    }
  }

  return {
    symbol: symbol ?? "unknown",
    name: name ?? "unknown",
    decimals: decimals ?? 18n,
    totalSupply,
  };
}
