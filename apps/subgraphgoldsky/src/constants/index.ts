import { Address, BigDecimal, ByteArray, Bytes, crypto } from '@graphprotocol/graph-ts'


export function getCreate2Address(from: Bytes, salt: Bytes, initCodeHash: Bytes): Bytes {
  return Bytes.fromHexString(
    Bytes.fromByteArray(
      crypto.keccak256(
        Bytes.fromHexString(
          '0xff' + from.toHexString().slice(2) + salt.toHexString().slice(2) + initCodeHash.toHexString().slice(2)
        )
      )
    )
      .toHexString()
      .slice(26)
  ) as Bytes
}

export function generatePoolAddress(token0: string, token1: string, factoryAddress: string): string {
  const tokens = [token0, token1].sort()
  const address = getCreate2Address(
    Bytes.fromByteArray(Bytes.fromHexString(factoryAddress)),
    Bytes.fromByteArray(crypto.keccak256(ByteArray.fromHexString('0x' + tokens[0].slice(2) + tokens[1].slice(2)))),
    Bytes.fromByteArray(Bytes.fromHexString('0x0d793e0bc737382e20c7a174911671209b6e833da3cb64b5c75e1940da1f1c21'))
  ).toHex()
  return Address.fromString(address).toHex().toLowerCase()
}

export const NATIVE_ADDRESS = Address.fromHexString('0xe91d02e66a9152fee1bc79c1830121f6507a4f6d')
export const WHITELIST: string[] = '0xe91d02e66a9152fee1bc79c1830121f6507a4f6d,0x46a906fca4487c87f0d89d2d0824ec57bdaa947d,0xbacdbe38df8421d0aa90262beb1c20d32a634fe7,0x0c156e2f45a812ad743760a88d73fb22879bc299,0xaea8c2f08b10fe1853300df4332e462b449e19d6,0x1a95d5d1930b807b62b20f3ca6b2451ffc75b454,0x4911fb3923f6da0cd4920f914991b0a742d88bfd'.toLowerCase().split(',')

export const STABLE0_ADDRESS = Address.fromHexString('0x46a906fca4487c87f0d89d2d0824ec57bdaa947d')
export const STABLE1_ADDRESS = Address.fromHexString('0xbacdbe38df8421d0aa90262beb1c20d32a634fe7')
export const STABLE2_ADDRESS = Address.fromHexString('0x0c156e2f45a812ad743760a88d73fb22879bc299')

export const STABLE0_NATIVE_PAIR = generatePoolAddress('0x46a906fca4487c87f0d89d2d0824ec57bdaa947d'.toLowerCase(), NATIVE_ADDRESS.toHexString(), '0x6c9258026a9272368e49bbb7d0a78c17bbe284bf')
export const STABLE1_NATIVE_PAIR = generatePoolAddress('0xbacdbe38df8421d0aa90262beb1c20d32a634fe7'.toLowerCase(), NATIVE_ADDRESS.toHexString(), '0x6c9258026a9272368e49bbb7d0a78c17bbe284bf')
export const STABLE2_NATIVE_PAIR = generatePoolAddress('0x0c156e2f45a812ad743760a88d73fb22879bc299'.toLowerCase(), NATIVE_ADDRESS.toHexString(), '0x6c9258026a9272368e49bbb7d0a78c17bbe284bf')

export const NETWORK = 'sepolia'

export const MINIMUM_LIQUIDITY_THRESHOLD_ETH = BigDecimal.fromString('0.001')

export const MINIMUM_USD_THRESHOLD_NEW_PAIRS = BigDecimal.fromString('1000')

export const FACTORY_ADDRESS = Address.fromString('0x6c9258026a9272368e49bbb7d0a78c17bbe284bf').toHex().toLowerCase()

export const ORACLE_ADDRESS = '0x694aa1769357215de4fac081bf1f309adc325306'
