import { BigInt, BigDecimal, store, Address } from '@graphprotocol/graph-ts'
import {
  Mint as MintEvent,
  Burn as BurnEvent,
  Swap as SwapEvent,
  Transfer,
  Sync as SyncEvent,
  Approval as ApprovalEvent
} from '../generated/templates/UniswapV2Pair/UniswapV2Pair'
import { Pair, Token, Mint, Burn, Swap, Sync, Transaction, Bundle, ApprovalEvent as ApprovalEntity } from '../generated/schema'
import { UniswapV2Pair as PairContract } from '../generated/templates/UniswapV2Pair/UniswapV2Pair'

let ZERO_BD = BigDecimal.fromString('0')
let ONE_BD = BigDecimal.fromString('1')

function exponentToBigDecimal(decimals: BigInt): BigDecimal {
  let bd = BigDecimal.fromString('1')
  for (let i = 0; i < decimals.toI32(); i++) {
    bd = bd.times(BigDecimal.fromString('10'))
  }
  return bd
}

function convertTokenToDecimal(tokenAmount: BigInt, exchangeDecimals: BigInt): BigDecimal {
  if (exchangeDecimals == BigInt.fromI32(0)) {
    return tokenAmount.toBigDecimal()
  }
  return tokenAmount.toBigDecimal().div(exponentToBigDecimal(exchangeDecimals))
}

export function handleMint(event: MintEvent): void {
  let transaction = Transaction.load(event.transaction.hash.toHexString())
  if (transaction === null) {
    transaction = new Transaction(event.transaction.hash.toHexString())
    transaction.blockNumber = event.block.number
    transaction.timestamp = event.block.timestamp
    transaction.save()
  }

  let pair = Pair.load(event.address.toHexString())
  if (pair === null) {
    return
  }

  let token0 = Token.load(pair.token0)
  let token1 = Token.load(pair.token1)
  let decimals0 = token0 ? token0.decimals : BigInt.fromI32(18)
  let decimals1 = token1 ? token1.decimals : BigInt.fromI32(18)

  let mint = new Mint(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  mint.transaction = transaction.id
  mint.timestamp = transaction.timestamp
  mint.pair = pair.id
  mint.to = event.params.sender
  mint.sender = event.params.sender
  mint.amount0 = convertTokenToDecimal(event.params.amount0, decimals0)
  mint.amount1 = convertTokenToDecimal(event.params.amount1, decimals1)
  mint.logIndex = event.logIndex
  mint.amountUSD = ZERO_BD
  mint.liquidity = ZERO_BD
  mint.save()

  pair.txCount = pair.txCount.plus(BigInt.fromI32(1))
  pair.save()
}

export function handleBurn(event: BurnEvent): void {
  let transaction = Transaction.load(event.transaction.hash.toHexString())
  if (transaction === null) {
    transaction = new Transaction(event.transaction.hash.toHexString())
    transaction.blockNumber = event.block.number
    transaction.timestamp = event.block.timestamp
    transaction.save()
  }

  let pair = Pair.load(event.address.toHexString())
  if (pair === null) {
    return
  }

  let token0 = Token.load(pair.token0)
  let token1 = Token.load(pair.token1)
  let decimals0 = token0 ? token0.decimals : BigInt.fromI32(18)
  let decimals1 = token1 ? token1.decimals : BigInt.fromI32(18)

  let burn = new Burn(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  burn.transaction = transaction.id
  burn.timestamp = transaction.timestamp
  burn.pair = pair.id
  burn.sender = event.params.sender
  burn.amount0 = convertTokenToDecimal(event.params.amount0, decimals0)
  burn.amount1 = convertTokenToDecimal(event.params.amount1, decimals1)
  burn.to = event.params.to
  burn.logIndex = event.logIndex
  burn.amountUSD = ZERO_BD
  burn.liquidity = ZERO_BD
  burn.needsComplete = false
  burn.save()

  pair.txCount = pair.txCount.plus(BigInt.fromI32(1))
  pair.save()
}

export function handleSwap(event: SwapEvent): void {
  let pair = Pair.load(event.address.toHexString())
  if (pair === null) {
    return
  }

  let token0 = Token.load(pair.token0)
  let token1 = Token.load(pair.token1)
  let decimals0 = token0 ? token0.decimals : BigInt.fromI32(18)
  let decimals1 = token1 ? token1.decimals : BigInt.fromI32(18)

  let transaction = Transaction.load(event.transaction.hash.toHexString())
  if (transaction === null) {
    transaction = new Transaction(event.transaction.hash.toHexString())
    transaction.blockNumber = event.block.number
    transaction.timestamp = event.block.timestamp
    transaction.save()
  }

  let swap = new Swap(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  swap.transaction = transaction.id
  swap.timestamp = transaction.timestamp
  swap.pair = pair.id
  swap.sender = event.params.sender
  swap.amount0In = convertTokenToDecimal(event.params.amount0In, decimals0)
  swap.amount1In = convertTokenToDecimal(event.params.amount1In, decimals1)
  swap.amount0Out = convertTokenToDecimal(event.params.amount0Out, decimals0)
  swap.amount1Out = convertTokenToDecimal(event.params.amount1Out, decimals1)
  swap.to = event.params.to
  swap.logIndex = event.logIndex
  swap.amountUSD = ZERO_BD
  swap.save()

  pair.volumeToken0 = pair.volumeToken0.plus(swap.amount0In.plus(swap.amount0Out))
  pair.volumeToken1 = pair.volumeToken1.plus(swap.amount1In.plus(swap.amount1Out))
  pair.txCount = pair.txCount.plus(BigInt.fromI32(1))
  pair.save()
}

export function handleSync(event: SyncEvent): void {
  let pair = Pair.load(event.address.toHexString())
  if (pair === null) {
    return
  }

  let token0 = Token.load(pair.token0)
  let token1 = Token.load(pair.token1)
  let decimals0 = token0 ? token0.decimals : BigInt.fromI32(18)
  let decimals1 = token1 ? token1.decimals : BigInt.fromI32(18)

  let transaction = Transaction.load(event.transaction.hash.toHexString())
  if (transaction === null) {
    transaction = new Transaction(event.transaction.hash.toHexString())
    transaction.blockNumber = event.block.number
    transaction.timestamp = event.block.timestamp
    transaction.save()
  }

  pair.reserve0 = convertTokenToDecimal(event.params.reserve0, decimals0)
  pair.reserve1 = convertTokenToDecimal(event.params.reserve1, decimals1)

  if (pair.reserve1.notEqual(ZERO_BD)) {
    pair.token0Price = pair.reserve0.div(pair.reserve1)
  }
  if (pair.reserve0.notEqual(ZERO_BD)) {
    pair.token1Price = pair.reserve1.div(pair.reserve0)
  }

  pair.save()

  let sync = new Sync(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  sync.transaction = transaction.id
  sync.timestamp = transaction.timestamp
  sync.pair = pair.id
  sync.reserve0 = pair.reserve0
  sync.reserve1 = pair.reserve1
  sync.logIndex = event.logIndex
  sync.save()
}

export function handleTransfer(event: Transfer): void {
  // Handle LP token transfers if needed
}

export function handleApproval(event: ApprovalEvent): void {
  let approval = new ApprovalEntity(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  approval.token = event.address
  approval.owner = event.params.owner
  approval.spender = event.params.spender
  approval.value = event.params.value
  approval.blockNumber = event.block.number
  approval.timestamp = event.block.timestamp
  approval.transactionHash = event.transaction.hash
  approval.save()
}
