import {
  LimitsUpdated,
  PayMethodUpdated,
  ServiceFeeUpdated,
  TokenPoolRegistered,
  TokenPoolRemoved,
  TransferInitiated,
} from '../../../generated/Bridge/Bridge'
import { BridgeTransfer, BridgeConfigEvent, Token } from '../../../generated/schema'
import { convertTokenToDecimal } from '../../common/helpers'
import { getReceiverChainName } from '../../common/chain'
import { BigInt, BigDecimal, log } from '@graphprotocol/graph-ts'

const ETHER_DECIMALS = BigInt.fromI32(18)

function toDecimal(value: BigInt, decimals: BigInt): BigDecimal {
  return convertTokenToDecimal(value, decimals)
}

function configEntityId(eventId: string): string {
  return eventId
}

export function handleTransferInitiated(event: TransferInitiated): void {
  const entityId = event.transaction.hash.toHexString().concat('-').concat(event.logIndex.toString())
  const transfer = new BridgeTransfer(entityId)
  log.debug('handleTransferInitiated message {} token {} amount {} payInLink {} dst {}', [
    event.params.messageId.toHexString(),
    event.params.token.toHexString(),
    event.params.amount.toString(),
    event.params.payInLink ? 'true' : 'false',
    event.params.dstSelector.toString(),
  ])

  const tokenId = event.params.token.toHexString()
  const token = Token.load(tokenId)
  const decimals = token ? (token.decimals as BigInt) : BigInt.fromI32(18)

  transfer.txHash = event.transaction.hash
  transfer.blockNumber = event.block.number
  transfer.timestamp = event.block.timestamp
  transfer.messageId = event.params.messageId
  transfer.sender = event.params.sender
  transfer.token = event.params.token
  transfer.pool = event.params.pool
  transfer.dstSelector = event.params.dstSelector
  transfer.receiverChainName = getReceiverChainName(transfer.dstSelector)
  transfer.receiver = event.params.receiver
  transfer.amount = toDecimal(event.params.amount, decimals)
  transfer.payInLink = event.params.payInLink
  transfer.ccipFee = toDecimal(event.params.ccipFee, ETHER_DECIMALS)
  transfer.serviceFeePaid = toDecimal(event.params.serviceFeePaid, ETHER_DECIMALS)

  transfer.save()
}

export function handleTokenPoolRegistered(event: TokenPoolRegistered): void {
  const config = new BridgeConfigEvent(
    configEntityId(event.transaction.hash.toHexString().concat('-').concat(event.logIndex.toString()))
  )
  config.eventName = 'TokenPoolRegistered'
  config.token = event.params.token
  config.pool = event.params.pool
  log.debug('handleTokenPoolRegistered token {} pool {}', [
    event.params.token.toHexString(),
    event.params.pool.toHexString(),
  ])
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleTokenPoolRemoved(event: TokenPoolRemoved): void {
  const config = new BridgeConfigEvent(
    configEntityId(event.transaction.hash.toHexString().concat('-').concat(event.logIndex.toString()))
  )
  config.eventName = 'TokenPoolRemoved'
  config.token = event.params.token
  log.debug('handleTokenPoolRemoved token {}', [event.params.token.toHexString()])
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleLimitsUpdated(event: LimitsUpdated): void {
  const config = new BridgeConfigEvent(
    configEntityId(event.transaction.hash.toHexString().concat('-').concat(event.logIndex.toString()))
  )
  config.eventName = 'LimitsUpdated'
  config.minAmount = event.params.minAmount
  config.maxAmount = event.params.maxAmount
  log.debug('handleLimitsUpdated min {} max {}', [event.params.minAmount.toString(), event.params.maxAmount.toString()])
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handlePayMethodUpdated(event: PayMethodUpdated): void {
  const config = new BridgeConfigEvent(
    configEntityId(event.transaction.hash.toHexString().concat('-').concat(event.logIndex.toString()))
  )
  config.eventName = 'PayMethodUpdated'
  config.nativeAllowed = event.params.nativeAllowed
  config.linkAllowed = event.params.linkAllowed
  log.debug('handlePayMethodUpdated native {} link {}', [
    event.params.nativeAllowed ? 'true' : 'false',
    event.params.linkAllowed ? 'true' : 'false',
  ])
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleServiceFeeUpdated(event: ServiceFeeUpdated): void {
  const config = new BridgeConfigEvent(
    configEntityId(event.transaction.hash.toHexString().concat('-').concat(event.logIndex.toString()))
  )
  config.eventName = 'ServiceFeeUpdated'
  config.newFee = event.params.newFee
  config.newCollector = event.params.newCollector
  log.debug('handleServiceFeeUpdated fee {} collector {}', [
    event.params.newFee.toString(),
    event.params.newCollector.toHexString(),
  ])
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}
