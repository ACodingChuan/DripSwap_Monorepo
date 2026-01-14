import { BigDecimal, BigInt, ethereum, store, Bytes } from '@graphprotocol/graph-ts'

import { Bundle, Token, TokenMinuteData } from '../../generated/schema'
import { ZERO_BD, ZERO_BI } from './helpers'

export function updateTokenMinuteData(token: Token, event: ethereum.Event): TokenMinuteData {
  const bundle = Bundle.load('latest')
  const timestamp = event.block.timestamp.toI32()
  const minuteIndex = timestamp / 60 // get unique minute within unix history
  const minuteStartUnix = minuteIndex * 60 // want the rounded effect
  const tokenMinuteID = Bytes.fromUTF8(token.id.toHexString() + '-' + minuteIndex.toString())
  let tokenMinuteData = TokenMinuteData.load(tokenMinuteID)
  const ethPrice = bundle === null ? ZERO_BD : bundle.ethPrice
  const tokenPrice = token.derivedETH.times(ethPrice)
  let isNew = false
  
  if (!tokenMinuteData) {
    tokenMinuteData = new TokenMinuteData(tokenMinuteID)
    tokenMinuteData.periodStartUnix = minuteStartUnix
    tokenMinuteData.token = token.id
    tokenMinuteData.volume = ZERO_BD
    tokenMinuteData.volumeUSD = ZERO_BD
    tokenMinuteData.untrackedVolumeUSD = ZERO_BD
    tokenMinuteData.feesUSD = ZERO_BD
    tokenMinuteData.open = tokenPrice
    tokenMinuteData.high = tokenPrice
    tokenMinuteData.low = tokenPrice
    tokenMinuteData.close = tokenPrice
    
    const tokenMinuteArray = token.minuteArray
    tokenMinuteArray.push(BigInt.fromI32(minuteIndex))
    token.minuteArray = tokenMinuteArray
    token.save()
    isNew = true
  }

  // OHLC repair: if the bucket was created when price was unavailable (0), fix it once price becomes available.
  if (tokenPrice.gt(ZERO_BD)) {
    if (tokenMinuteData.open.equals(ZERO_BD)) tokenMinuteData.open = tokenPrice
    if (tokenMinuteData.high.equals(ZERO_BD)) tokenMinuteData.high = tokenPrice
    if (tokenMinuteData.low.equals(ZERO_BD)) tokenMinuteData.low = tokenPrice
  }

  // Always update price fields on every sync
  if (tokenPrice.gt(tokenMinuteData.high)) {
    tokenMinuteData.high = tokenPrice
  }

  if (tokenPrice.lt(tokenMinuteData.low)) {
    tokenMinuteData.low = tokenPrice
  }

  tokenMinuteData.close = tokenPrice
  tokenMinuteData.priceUSD = tokenPrice
  tokenMinuteData.totalValueLocked = token.totalLiquidity
  tokenMinuteData.totalValueLockedUSD = token.totalLiquidity.times(tokenPrice)
  tokenMinuteData.save()

  if (token.lastMinuteArchived.equals(ZERO_BI) && token.lastMinuteRecorded.equals(ZERO_BI)) {
    token.lastMinuteRecorded = BigInt.fromI32(minuteIndex)
    token.lastMinuteArchived = BigInt.fromI32(minuteIndex - 1)
  }
  if (isNew) {
    const lastMinuteArchived = token.lastMinuteArchived.toI32()
    const stop = minuteIndex - 1680 // Delete data older than 1680 minutes (28 hours)
    if (stop > lastMinuteArchived) {
      archiveMinuteData(token, stop)
    }

    token.lastMinuteRecorded = BigInt.fromI32(minuteIndex)
    token.save()
  }

  return tokenMinuteData as TokenMinuteData
}

function archiveMinuteData(token: Token, end: i32): void {
  let last = token.lastMinuteArchived.toI32()
  let removed = 0

  // token.minuteArray is append-only and ordered; remove from the head.
  while (token.minuteArray.length > 0) {
    let minute = token.minuteArray[0].toI32()
    if (minute > end) break

    const tokenMinuteID = Bytes.fromUTF8(token.id.toHexString() + '-' + token.minuteArray[0].toString())
    store.remove('TokenMinuteData', tokenMinuteID.toHexString())
    token.minuteArray.shift()
    last = minute

    removed += 1
    if (removed >= 1000) {
      break
    }
  }

  token.lastMinuteArchived = BigInt.fromI32(last - 1)
  token.save()
}
