/* eslint-disable prefer-const */
import { BigDecimal, BigInt, Bytes, ethereum, store } from '@graphprotocol/graph-ts'
import { Bundle, Pair, PairDayData, Token, TokenDayData, TokenHourData, UniswapDayData, UniswapFactory } from '../../generated/schema'
import { PairHourData } from '../../generated/schema'
import { ONE_BI, ZERO_BD, ZERO_BI } from './helpers'

import { FACTORY_ADDRESS } from '../constants'

export function updateUniswapDayData(event: ethereum.Event): UniswapDayData {
  let uniswap = UniswapFactory.load(Bytes.fromHexString(FACTORY_ADDRESS))!
  let dayID = event.block.timestamp.toI32() / 86400
  let id = Bytes.fromI32(dayID)
  let dayStartTimestamp = dayID * 86400

  let uniswapDayData = UniswapDayData.load(id)
  if (uniswapDayData === null) {
    uniswapDayData = new UniswapDayData(id)
    uniswapDayData.date = dayStartTimestamp
    uniswapDayData.dailyVolumeUSD = ZERO_BD
    uniswapDayData.dailyVolumeETH = ZERO_BD
    uniswapDayData.totalVolumeUSD = ZERO_BD
    uniswapDayData.totalVolumeETH = ZERO_BD
    uniswapDayData.dailyVolumeUntracked = ZERO_BD
  }

  uniswapDayData.totalLiquidityUSD = uniswap.totalLiquidityUSD
  uniswapDayData.totalLiquidityETH = uniswap.totalLiquidityETH
  uniswapDayData.totalVolumeUSD = uniswap.totalVolumeUSD
  uniswapDayData.totalVolumeETH = uniswap.totalVolumeETH
  uniswapDayData.txCount = uniswap.txCount
  uniswapDayData.save()

  return uniswapDayData as UniswapDayData
}

export function updatePairDayData(event: ethereum.Event): PairDayData {
  let dayID = event.block.timestamp.toI32() / 86400
  let dayStartTimestamp = dayID * 86400
  let dayPairID = event.address.concatI32(dayID)
  let pair = Pair.load(event.address)!
  let pairDayData = PairDayData.load(dayPairID)
  if (pairDayData === null) {
    pairDayData = new PairDayData(dayPairID)
    pairDayData.date = dayStartTimestamp
    pairDayData.token0 = pair.token0
    pairDayData.token1 = pair.token1
    pairDayData.pairAddress = event.address
    pairDayData.dailyVolumeToken0 = ZERO_BD
    pairDayData.dailyVolumeToken1 = ZERO_BD
    pairDayData.dailyVolumeUSD = ZERO_BD
    pairDayData.dailyTxns = ZERO_BI
  }

  pairDayData.totalSupply = pair.totalSupply
  pairDayData.reserve0 = pair.reserve0
  pairDayData.reserve1 = pair.reserve1
  pairDayData.reserveUSD = pair.reserveUSD
  pairDayData.dailyTxns = pairDayData.dailyTxns.plus(ONE_BI)
  pairDayData.save()

  return pairDayData as PairDayData
}

export function updatePairHourData(event: ethereum.Event): PairHourData {
  let timestamp = event.block.timestamp.toI32()
  let hourIndex = timestamp / 3600 // get unique hour within unix history
  let hourStartUnix = hourIndex * 3600 // want the rounded effect
    
  let hourPairID = event.address.concatI32(hourIndex)
  let pair = Pair.load(event.address)!
  let pairHourData = PairHourData.load(hourPairID)
  if (pairHourData === null) {
    pairHourData = new PairHourData(hourPairID)
    pairHourData.hourStartUnix = hourStartUnix
    pairHourData.pair = event.address
    pairHourData.hourlyVolumeToken0 = ZERO_BD
    pairHourData.hourlyVolumeToken1 = ZERO_BD
    pairHourData.hourlyVolumeUSD = ZERO_BD
    pairHourData.hourlyTxns = ZERO_BI
  }

  pairHourData.totalSupply = pair.totalSupply
  pairHourData.reserve0 = pair.reserve0
  pairHourData.reserve1 = pair.reserve1
  pairHourData.reserveUSD = pair.reserveUSD
  pairHourData.hourlyTxns = pairHourData.hourlyTxns.plus(ONE_BI)
  pairHourData.save()

  return pairHourData as PairHourData
}

export function updateTokenDayData(token: Token, event: ethereum.Event): TokenDayData {
  let bundle = Bundle.load('latest')
  let timestamp = event.block.timestamp.toI32()
  let dayID = timestamp / 86400
  let dayStartTimestamp = dayID * 86400
  let tokenDayID = token.id.concatI32(dayID)
  let ethPrice = bundle === null ? ZERO_BD : bundle.ethPrice
  let tokenPriceUSD = token.derivedETH.times(ethPrice)

  let tokenDayData = TokenDayData.load(tokenDayID)
  if (tokenDayData === null) {
    tokenDayData = new TokenDayData(tokenDayID)
    tokenDayData.date = dayStartTimestamp
    tokenDayData.token = token.id
    tokenDayData.priceUSD = tokenPriceUSD
    tokenDayData.dailyVolumeToken = ZERO_BD
    tokenDayData.dailyVolumeETH = ZERO_BD
    tokenDayData.dailyVolumeUSD = ZERO_BD
    tokenDayData.dailyTxns = ZERO_BI
    tokenDayData.totalLiquidityUSD = ZERO_BD
  }
  tokenDayData.priceUSD = tokenPriceUSD
  tokenDayData.totalLiquidityToken = token.totalLiquidity
  tokenDayData.totalLiquidityETH = token.totalLiquidity.times(token.derivedETH as BigDecimal)
  tokenDayData.totalLiquidityUSD = tokenDayData.totalLiquidityETH.times(ethPrice)
  tokenDayData.dailyTxns = tokenDayData.dailyTxns.plus(ONE_BI)
  tokenDayData.save()

  /**
   * @todo test if this speeds up sync
   */
  // updateStoredTokens(tokenDayData as TokenDayData, dayID)
  // updateStoredPairs(tokenDayData as TokenDayData, dayPairID)

  return tokenDayData as TokenDayData
}

export function updateTokenHourData(token: Token, event: ethereum.Event): TokenHourData {
  let bundle = Bundle.load('latest')
  let timestamp = event.block.timestamp.toI32()
  let hourIndex = timestamp / 3600 // get unique hour within unix history
  let hourStartUnix = hourIndex * 3600 // want the rounded effect
  let tokenHourID = Bytes.fromUTF8(token.id.toHexString() + '-' + hourIndex.toString())
  let tokenHourData = TokenHourData.load(tokenHourID)
  let ethPrice = bundle === null ? ZERO_BD : bundle.ethPrice
  let tokenPrice = token.derivedETH.times(ethPrice)
  let isNew = false
  
  if (!tokenHourData) {
    tokenHourData = new TokenHourData(tokenHourID)
    tokenHourData.periodStartUnix = hourStartUnix
    tokenHourData.token = token.id
    tokenHourData.volume = ZERO_BD
    tokenHourData.volumeUSD = ZERO_BD
    tokenHourData.untrackedVolumeUSD = ZERO_BD
    tokenHourData.feesUSD = ZERO_BD
    tokenHourData.open = tokenPrice
    tokenHourData.high = tokenPrice
    tokenHourData.low = tokenPrice
    tokenHourData.close = tokenPrice
    
    let tokenHourArray = token.hourArray
    tokenHourArray.push(BigInt.fromI32(hourIndex))
    token.hourArray = tokenHourArray
    token.save()
    isNew = true
  }

  // OHLC repair: if the bucket was created when price was unavailable (0), fix it once price becomes available.
  if (tokenPrice.gt(ZERO_BD)) {
    if (tokenHourData.open.equals(ZERO_BD)) tokenHourData.open = tokenPrice
    if (tokenHourData.high.equals(ZERO_BD)) tokenHourData.high = tokenPrice
    if (tokenHourData.low.equals(ZERO_BD)) tokenHourData.low = tokenPrice
  }

  // Always update price fields on every sync
  if (tokenPrice.gt(tokenHourData.high)) {
    tokenHourData.high = tokenPrice
  }

  if (tokenPrice.lt(tokenHourData.low)) {
    tokenHourData.low = tokenPrice
  }

  tokenHourData.close = tokenPrice
  tokenHourData.priceUSD = tokenPrice
  tokenHourData.totalValueLocked = token.totalLiquidity
  tokenHourData.totalValueLockedUSD = token.totalLiquidity.times(tokenPrice)
  tokenHourData.save()

  if (token.lastHourArchived.equals(ZERO_BI) && token.lastHourRecorded.equals(ZERO_BI)) {
    token.lastHourRecorded = BigInt.fromI32(hourIndex)
    token.lastHourArchived = BigInt.fromI32(hourIndex - 1)
  }

  if (isNew) {
    let lastHourArchived = token.lastHourArchived.toI32()
    let stop = hourIndex - 768 // Delete data older than 768 hours (32 days)
    if (stop > lastHourArchived) {
      archiveHourData(token, stop)
    }
    token.lastHourRecorded = BigInt.fromI32(hourIndex)
    token.save()
  }

  return tokenHourData as TokenHourData
}

function archiveHourData(token: Token, end: i32): void {
  let last = token.lastHourArchived.toI32()
  let removed = 0

  // token.hourArray is append-only and ordered; remove from the head.
  while (token.hourArray.length > 0) {
    let hour = token.hourArray[0].toI32()
    if (hour > end) break

    let tokenHourID = Bytes.fromUTF8(token.id.toHexString() + '-' + token.hourArray[0].toString())
    store.remove('TokenHourData', tokenHourID.toHexString())
    token.hourArray.shift()
    last = hour

    removed += 1
    if (removed >= 500) {
      break
    }
  }

  token.lastHourArchived = BigInt.fromI32(last - 1)
  token.save()
}
