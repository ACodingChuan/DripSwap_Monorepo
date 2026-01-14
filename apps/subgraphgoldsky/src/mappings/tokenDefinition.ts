import {
  Address,
  BigInt,
} from "@graphprotocol/graph-ts"
import { NETWORK } from "../constants"

// Initialize a Token Definition with the attributes
export class TokenDefinition {
  address: Address
  symbol: string
  name: string
  decimals: BigInt

  // Get all tokens with a static defintion
  static getStaticDefinitions(): Array<TokenDefinition> {
    const staticDefinitions: Array<TokenDefinition> = [
      {
        address: Address.fromString('0xe91d02e66a9152fee1bc79c1830121f6507a4f6d'),
        symbol: 'vETH',
        name: 'DripSwap vETH',
        decimals: BigInt.fromI32(18)
      },
      {
        address: Address.fromString('0x46a906fca4487c87f0d89d2d0824ec57bdaa947d'),
        symbol: 'vUSDC',
        name: 'DripSwap vUSDC',
        decimals: BigInt.fromI32(6)
      },
      {
        address: Address.fromString('0xbacdbe38df8421d0aa90262beb1c20d32a634fe7'),
        symbol: 'vUSDT',
        name: 'DripSwap vUSDT',
        decimals: BigInt.fromI32(6)
      },
      {
        address: Address.fromString('0x0c156e2f45a812ad743760a88d73fb22879bc299'),
        symbol: 'vDAI',
        name: 'DripSwap vDAI',
        decimals: BigInt.fromI32(18)
      },
      {
        address: Address.fromString('0xaea8c2f08b10fe1853300df4332e462b449e19d6'),
        symbol: 'vBTC',
        name: 'DripSwap vBTC',
        decimals: BigInt.fromI32(8)
      },
      {
        address: Address.fromString('0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454'),
        symbol: 'vLINK',
        name: 'DripSwap vLINK',
        decimals: BigInt.fromI32(18)
      },
      {
        address: Address.fromString('0x4911fb3923f6da0cd4920f914991b0a742d88bfd'),
        symbol: 'vSCR',
        name: 'DripSwap vSCR',
        decimals: BigInt.fromI32(18)
      }
    ]
    return staticDefinitions
  }

  // Helper for hardcoded tokens
  static fromAddress(tokenAddress: Address): TokenDefinition | null {
    // Always return static definitions for DripSwap vTokens
    let staticDefinitions = this.getStaticDefinitions()
    let tokenAddressHex = tokenAddress.toHexString()

    // Search the definition using the address
    for (let i = 0; i < staticDefinitions.length; i++) {
      let staticDefinition = staticDefinitions[i]
      if (staticDefinition.address.toHexString() == tokenAddressHex) {
        return staticDefinition
      }
    }

    // If not found, return null
    return null
  }

}