import { PairCreated } from '../generated/UniswapV2Factory/UniswapV2Factory'
import { Pair, Token, Factory } from '../generated/schema'
import { UniswapV2Pair as PairTemplate } from '../generated/templates'
import { UniswapV2Pair } from '../generated/UniswapV2Factory/UniswapV2Pair'
import { VToken } from '../generated/UniswapV2Factory/VToken'
import { BigInt, BigDecimal, Address } from '@graphprotocol/graph-ts'

const FACTORY_ADDRESS = '0x6C9258026A9272368e49bBB7D0A78c17BBe284BF'
export function handlePairCreated(event: PairCreated): void {
  let ZERO_BD = BigDecimal.fromString('0')
  let ZERO_BI = BigInt.fromI32(0)

  // ---- Factory ----
  let factory = Factory.load(FACTORY_ADDRESS)
  if (factory == null) {
    factory = new Factory(FACTORY_ADDRESS)
    factory.pairCount = 0
    factory.totalVolumeUSD = ZERO_BD
    factory.totalLiquidityUSD = ZERO_BD
    factory.txCount = ZERO_BI
  }
  factory.pairCount = factory.pairCount + 1
  factory.save()

  // ---- Token0 ----
  let token0Id = event.params.token0.toHexString()
  let token0 = Token.load(token0Id)
  if (token0 == null) {
    token0 = new Token(token0Id)
    token0.symbol = token0Id // Fallback
    token0.name = token0Id   // Fallback
    token0.decimals = BigInt.fromI32(18) // Fallback

    let tokenContract = VToken.bind(event.params.token0)
    
    let symbolCall = tokenContract.try_symbol()
    if (!symbolCall.reverted) token0.symbol = symbolCall.value
    
    let nameCall = tokenContract.try_name()
    if (!nameCall.reverted) token0.name = nameCall.value
    
    let decimalsCall = tokenContract.try_decimals()
    if (!decimalsCall.reverted) token0.decimals = BigInt.fromI32(decimalsCall.value)

    token0.totalSupply = ZERO_BI
    token0.tradeVolume = ZERO_BD
    token0.tradeVolumeUSD = ZERO_BD
    token0.txCount = ZERO_BI
    token0.totalLiquidity = ZERO_BD
    token0.save()
  }

  // ---- Token1 ----
  let token1Id = event.params.token1.toHexString()
  let token1 = Token.load(token1Id)
  if (token1 == null) {
    token1 = new Token(token1Id)
    token1.symbol = token1Id // Fallback
    token1.name = token1Id   // Fallback
    token1.decimals = BigInt.fromI32(18) // Fallback

    let tokenContract = VToken.bind(event.params.token1)
    
    let symbolCall = tokenContract.try_symbol()
    if (!symbolCall.reverted) token1.symbol = symbolCall.value
    
    let nameCall = tokenContract.try_name()
    if (!nameCall.reverted) token1.name = nameCall.value
    
    let decimalsCall = tokenContract.try_decimals()
    if (!decimalsCall.reverted) token1.decimals = BigInt.fromI32(decimalsCall.value)

    token1.totalSupply = ZERO_BI
    token1.tradeVolume = ZERO_BD
    token1.tradeVolumeUSD = ZERO_BD
    token1.txCount = ZERO_BI
    token1.totalLiquidity = ZERO_BD
    token1.save()
  }

  // ---- Pair ----
  let pairId = event.params.pair.toHexString()
  let pair = new Pair(pairId)
  pair.token0 = token0Id          // 关系字段 = Token ID（字符串）
  pair.token1 = token1Id
  pair.reserve0 = ZERO_BD
  pair.reserve1 = ZERO_BD
  pair.totalSupply = ZERO_BD
  pair.reserveETH = ZERO_BD
  pair.reserveUSD = ZERO_BD
  pair.trackedReserveETH = ZERO_BD
  pair.token0Price = ZERO_BD
  pair.token1Price = ZERO_BD
  pair.volumeToken0 = ZERO_BD
  pair.volumeToken1 = ZERO_BD
  pair.volumeUSD = ZERO_BD
  pair.txCount = ZERO_BI
  pair.createdAtTimestamp = event.block.timestamp
  pair.createdAtBlockNumber = event.block.number
  pair.save()

  // ---- 动态模板 ----
  PairTemplate.create(event.params.pair)
}
