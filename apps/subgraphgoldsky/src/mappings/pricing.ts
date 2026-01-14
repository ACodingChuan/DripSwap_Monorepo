/* eslint-disable prefer-const */
import { Address, BigDecimal, BigInt, ethereum, Bytes } from '@graphprotocol/graph-ts'

import { Bundle, Pair, Token } from '../../generated/schema'
import {
  MINIMUM_LIQUIDITY_THRESHOLD_ETH,
  MINIMUM_USD_THRESHOLD_NEW_PAIRS,
  NATIVE_ADDRESS,
  STABLE0_ADDRESS,
  STABLE1_ADDRESS,
  STABLE2_ADDRESS,
  WHITELIST,
  ORACLE_ADDRESS,
  FACTORY_ADDRESS
} from '../constants'
import { ONE_BD, ZERO_BD, ZERO_BI } from './helpers'
import { exponentToBigDecimal } from './helpers'
import { Oracle } from '../../generated/Factory/Oracle'
import { generatePoolAddress } from '../constants'

/**
 * Get ETH price in USD from Oracle and create/update Bundle entity
 * Only creates a new Bundle if roundId has changed
 */
export function getEthPriceInUSD(event: ethereum.Event): BigDecimal {
  // Get Oracle address from constants
  const oracleAddress = ORACLE_ADDRESS
  
  if (oracleAddress == '0x0000000000000000000000000000000000000000') {
    return ZERO_BD
  }

  const oracle = Oracle.bind(Address.fromString(oracleAddress))
  const round = oracle.try_latestRoundData()
  if (round.reverted) {
    // If oracle call fails, try to load the latest Bundle
    let latestBundle = Bundle.load('latest')
    if (latestBundle) {
      return latestBundle.ethPrice
    }
    return ZERO_BD
  }

  const roundId = round.value.value0
  const answer = round.value.value1
  
  // Check if answer is valid (positive)
  if (answer.le(BigInt.zero())) {
    let latestBundle = Bundle.load('latest')
    if (latestBundle) {
      return latestBundle.ethPrice
    }
    return ZERO_BD
  }

  // Get decimals from oracle
  const decimalsResult = oracle.try_decimals()
  let decimals = decimalsResult.reverted ? 8 : decimalsResult.value
  if (decimals == 0) {
    decimals = 8 // Default to Chainlink standard
  }

  // Calculate price
  const answerBD = BigDecimal.fromString(answer.toString())
  const factor = exponentToBigDecimal(BigInt.fromI32(decimals))
  if (factor.equals(ZERO_BD)) {
    let latestBundle = Bundle.load('latest')
    if (latestBundle) {
      return latestBundle.ethPrice
    }
    return ZERO_BD
  }
  const ethPrice = answerBD.div(factor)

  // Check if this roundId already exists
  let bundleId = roundId.toString()
  let bundle = Bundle.load(bundleId)
  
  if (!bundle) {
    // Create new Bundle for this roundId
    bundle = new Bundle(bundleId)
    bundle.ethPrice = ethPrice
    bundle.roundId = roundId
    bundle.timestamp = event.block.timestamp
    bundle.save()
  }
  
  // Always update 'latest' pointer to point to the current roundId
  let latestBundle = Bundle.load('latest')
  if (!latestBundle || latestBundle.roundId.notEqual(roundId)) {
    // Only update if roundId changed to avoid duplicate writes
    if (!latestBundle) {
      latestBundle = new Bundle('latest')
    }
    latestBundle.ethPrice = ethPrice
    latestBundle.roundId = roundId
    latestBundle.timestamp = event.block.timestamp
    latestBundle.save()
  }

  return ethPrice
}

// return 0 if denominator is 0 in division
export function safeDiv(amount0: BigDecimal, amount1: BigDecimal): BigDecimal {
  if (amount1.equals(ZERO_BD)) {
    return ZERO_BD
  } else {
    return amount0.div(amount1)
  }
}

/**
 * Search through graph to find derived Eth per token.
 * Uses a recursive price discovery algorithm:
 * 1. If token is WETH, return 1
 * 2. If token is a stablecoin paired with WETH, return 1 / ethPrice
 * 3. Search for pairs where token is paired with whitelist tokens (WETH, USDC, etc.)
 * 4. Calculate price using: tokenPrice = whitelistTokenPrice * (whitelistReserve / tokenReserve)
 * 
 * Uses CREATE2 to deterministically calculate pair addresses.
 */
export function findEthPerToken(token: Token): BigDecimal {
  if (token.id == NATIVE_ADDRESS) {
    return ONE_BD
  }

  // If token is one of our configured stables, anchor it to ~$1 via oracle ETH/USD.
  // Do not require the stable/native pair to already exist, otherwise early pairs can get stuck with derivedETH = 0.
  if (token.id == STABLE0_ADDRESS || token.id == STABLE1_ADDRESS || token.id == STABLE2_ADDRESS) {
    let bundle = Bundle.load('latest')
    if (!bundle) {
      return ZERO_BD
    }
    return safeDiv(ONE_BD, bundle.ethPrice)
  }

  // Loop through whitelist and check if paired with any
  // Priority: Use WETH first, then skip tokens with derivedETH = 0
  for (let i = 0; i < WHITELIST.length; ++i) {
    let whitelistAddress = WHITELIST[i]
    
    // Use CREATE2 to calculate the pair address deterministically
    let pairAddress = generatePoolAddress(
      token.id.toHexString(),
      whitelistAddress,
      FACTORY_ADDRESS
    )
    
    let pair = Pair.load(Bytes.fromHexString(pairAddress))
    if (pair) {
      // Check if pair has minimum liquidity using totalSupply instead of reserveETH
      // to avoid circular dependency (reserveETH depends on derivedETH)
      if (pair.totalSupply.gt(ZERO_BD)) {
        let whitelistToken = Token.load(Bytes.fromHexString(whitelistAddress))
        if (whitelistToken) {
          // Special handling for WETH: directly use 1 instead of reading from database
          // This avoids the issue where WETH.derivedETH might not be saved yet
          let whitelistDerivedETH: BigDecimal
          if (whitelistAddress == NATIVE_ADDRESS.toHexString()) {
            whitelistDerivedETH = ONE_BD
          } else {
            whitelistDerivedETH = whitelistToken.derivedETH as BigDecimal
          }
          
          // Skip this whitelist token if its derivedETH is 0 (not calculated yet)
          // This prevents using uninitialized prices
          if (whitelistDerivedETH.equals(ZERO_BD)) {
            continue
          }
          
          // Calculate price based on which token is token0/token1
          if (pair.token0 == token.id) {
            // our token is token0, whitelist is token1
            // tokenPrice = token1Price * token1.derivedETH
            return pair.token1Price.times(whitelistDerivedETH)
          }
          if (pair.token1 == token.id) {
            // our token is token1, whitelist is token0
            // tokenPrice = token0Price * token0.derivedETH
            return pair.token0Price.times(whitelistDerivedETH)
          }
        }
      }
    }
  }

  return ZERO_BD // nothing was found return 0
}

/**
 * Accepts tokens and amounts, return tracked amount based on token whitelist
 * If one token on whitelist, return amount in that token converted to USD.
 * If both are, return average of two amounts
 * If neither is, return 0
 */
export function getTrackedVolumeUSD(
  tokenAmount0: BigDecimal,
  token0: Token,
  tokenAmount1: BigDecimal,
  token1: Token,
  pair: Pair
): BigDecimal {
  let bundle = Bundle.load('latest')
  if (!bundle) {
    return ZERO_BD
  }
  
  let price0 = token0.derivedETH.times(bundle.ethPrice)
  let price1 = token1.derivedETH.times(bundle.ethPrice)

  // if less than 5 LPs, require high minimum reserve amount or return 0
  if (pair.liquidityProviderCount.lt(BigInt.fromI32(5))) {
    let reserve0USD = pair.reserve0.times(price0)
    let reserve1USD = pair.reserve1.times(price1)
    if (WHITELIST.includes(token0.id.toHexString()) && WHITELIST.includes(token1.id.toHexString())) {
      if (reserve0USD.plus(reserve1USD).lt(MINIMUM_USD_THRESHOLD_NEW_PAIRS)) {
        return ZERO_BD
      }
    }
    if (WHITELIST.includes(token0.id.toHexString()) && !WHITELIST.includes(token1.id.toHexString())) {
      if (reserve0USD.times(BigDecimal.fromString('2')).lt(MINIMUM_USD_THRESHOLD_NEW_PAIRS)) {
        return ZERO_BD
      }
    }
    if (!WHITELIST.includes(token0.id.toHexString()) && WHITELIST.includes(token1.id.toHexString())) {
      if (reserve1USD.times(BigDecimal.fromString('2')).lt(MINIMUM_USD_THRESHOLD_NEW_PAIRS)) {
        return ZERO_BD
      }
    }
  }

  // both are whitelist tokens, take average of both amounts
  if (WHITELIST.includes(token0.id.toHexString()) && WHITELIST.includes(token1.id.toHexString())) {
    return tokenAmount0.times(price0).plus(tokenAmount1.times(price1)).div(BigDecimal.fromString('2'))
  }

  // take full value of the whitelisted token amount
  if (WHITELIST.includes(token0.id.toHexString()) && !WHITELIST.includes(token1.id.toHexString())) {
    return tokenAmount0.times(price0)
  }

  // take full value of the whitelisted token amount
  if (!WHITELIST.includes(token0.id.toHexString()) && WHITELIST.includes(token1.id.toHexString())) {
    return tokenAmount1.times(price1)
  }

  // neither token is on white list, tracked volume is 0
  return ZERO_BD
}

/**
 * Accepts tokens and amounts, return tracked amount based on token whitelist
 * If one token on whitelist, return amount in that token converted to USD * 2.
 * If both are, return sum of two amounts
 * If neither is, return 0
 */
export function getTrackedLiquidityUSD(
  tokenAmount0: BigDecimal,
  token0: Token,
  tokenAmount1: BigDecimal,
  token1: Token
): BigDecimal {
  let bundle = Bundle.load('latest')
  if (!bundle) {
    return ZERO_BD
  }
  
  let price0 = token0.derivedETH.times(bundle.ethPrice)
  let price1 = token1.derivedETH.times(bundle.ethPrice)

  // both are whitelist tokens, take sum of both amounts
  if (WHITELIST.includes(token0.id.toHexString()) && WHITELIST.includes(token1.id.toHexString())) {
    return tokenAmount0.times(price0).plus(tokenAmount1.times(price1))
  }

  // take double value of the whitelisted token amount
  if (WHITELIST.includes(token0.id.toHexString()) && !WHITELIST.includes(token1.id.toHexString())) {
    return tokenAmount0.times(price0).times(BigDecimal.fromString('2'))
  }

  // take double value of the whitelisted token amount
  if (!WHITELIST.includes(token0.id.toHexString()) && WHITELIST.includes(token1.id.toHexString())) {
    return tokenAmount1.times(price1).times(BigDecimal.fromString('2'))
  }

  // neither token is on white list, tracked liquidity is 0
  return ZERO_BD
}
