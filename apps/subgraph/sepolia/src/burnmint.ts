import { BigInt } from '@graphprotocol/graph-ts'
import {
  LockedOrBurned,
  ReleasedOrMinted,
  ChainAdded,
  ChainConfigured,
  ChainRemoved,
  RemotePoolAdded,
  RemotePoolRemoved,
  AllowListAdd,
  AllowListRemove,
  RouterUpdated,
  OwnershipTransferred,
  ConfigChanged,
  RateLimitAdminSet,
  OutboundRateLimitConsumed,
  InboundRateLimitConsumed,
  OwnershipTransferRequested
} from '../generated/BurnMintPool_vETH/BurnMintTokenPool'
import { BridgeTransfer, PoolConfigEvent } from '../generated/schema'

export function handleLockedOrBurned(event: LockedOrBurned): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString() + '-locked'
  let transfer = new BridgeTransfer(id)
  transfer.messageId = event.transaction.hash
  transfer.transferId = event.transaction.hash
  transfer.sender = event.params.sender
  transfer.receiver = null
  transfer.token = event.params.token
  transfer.amount = event.params.amount
  transfer.destinationChainSelector = event.params.remoteChainSelector
  transfer.status = 'LockedOrBurned'
  transfer.blockNumber = event.block.number
  transfer.timestamp = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.save()
}

export function handleReleasedOrMinted(event: ReleasedOrMinted): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString() + '-released'
  let transfer = new BridgeTransfer(id)
  transfer.messageId = event.transaction.hash
  transfer.transferId = event.transaction.hash
  transfer.sender = event.params.sender
  transfer.receiver = event.params.recipient
  transfer.token = event.params.token
  transfer.amount = event.params.amount
  transfer.sourceChainSelector = event.params.remoteChainSelector
  transfer.status = 'ReleasedOrMinted'
  transfer.blockNumber = event.block.number
  transfer.timestamp = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.save()
}

export function handleChainAdded(event: ChainAdded): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'ChainAdded'
  config.pool = event.address
  config.remoteChainSelector = event.params.remoteChainSelector
  config.allowed = true
  config.remoteTokenAddress = event.params.remoteToken
  config.outboundRateLimiterConfig = rateLimiterToJson(
    event.params.outboundRateLimiterConfig.isEnabled,
    event.params.outboundRateLimiterConfig.capacity,
    event.params.outboundRateLimiterConfig.rate
  )
  config.inboundRateLimiterConfig = rateLimiterToJson(
    event.params.inboundRateLimiterConfig.isEnabled,
    event.params.inboundRateLimiterConfig.capacity,
    event.params.inboundRateLimiterConfig.rate
  )
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleChainConfigured(event: ChainConfigured): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'ChainConfigured'
  config.pool = event.address
  config.remoteChainSelector = event.params.remoteChainSelector
  config.outboundRateLimiterConfig = rateLimiterToJson(
    event.params.outboundRateLimiterConfig.isEnabled,
    event.params.outboundRateLimiterConfig.capacity,
    event.params.outboundRateLimiterConfig.rate
  )
  config.inboundRateLimiterConfig = rateLimiterToJson(
    event.params.inboundRateLimiterConfig.isEnabled,
    event.params.inboundRateLimiterConfig.capacity,
    event.params.inboundRateLimiterConfig.rate
  )
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleChainRemoved(event: ChainRemoved): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'ChainRemoved'
  config.pool = event.address
  config.remoteChainSelector = event.params.remoteChainSelector
  config.allowed = false
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleRemotePoolAdded(event: RemotePoolAdded): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'RemotePoolAdded'
  config.pool = event.address
  config.remoteChainSelector = event.params.remoteChainSelector
  config.remotePoolAddress = event.params.remotePoolAddress
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleRemotePoolRemoved(event: RemotePoolRemoved): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'RemotePoolRemoved'
  config.pool = event.address
  config.remoteChainSelector = event.params.remoteChainSelector
  config.remotePoolAddress = event.params.remotePoolAddress
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleAllowListAdd(event: AllowListAdd): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'AllowListAdd'
  config.pool = event.address
  config.remoteTokenAddress = event.params.sender
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleAllowListRemove(event: AllowListRemove): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'AllowListRemove'
  config.pool = event.address
  config.remoteTokenAddress = event.params.sender
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleRouterUpdated(event: RouterUpdated): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'RouterUpdated'
  config.pool = event.address
  config.outboundRateLimiterConfig =
    '{"oldRouter":"' + event.params.oldRouter.toHexString() + '","newRouter":"' + event.params.newRouter.toHexString() + '"}'
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handlePoolOwnershipTransferred(event: OwnershipTransferred): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'OwnershipTransferred'
  config.pool = event.address
  config.remoteTokenAddress = event.params.to
  config.outboundRateLimiterConfig =
    '{"from":"' + event.params.from.toHexString() + '","to":"' + event.params.to.toHexString() + '"}'
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleConfigChanged(event: ConfigChanged): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'ConfigChanged'
  config.pool = event.address
  config.outboundRateLimiterConfig = rateLimiterToJson(
    event.params.config.isEnabled,
    event.params.config.capacity,
    event.params.config.rate
  )
  config.inboundRateLimiterConfig = config.outboundRateLimiterConfig
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleRateLimitAdminSet(event: RateLimitAdminSet): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'RateLimitAdminSet'
  config.pool = event.address
  config.remoteTokenAddress = event.params.rateLimitAdmin
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleOutboundRateLimitConsumed(event: OutboundRateLimitConsumed): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'OutboundRateLimitConsumed'
  config.pool = event.address
  config.remoteChainSelector = event.params.remoteChainSelector
  config.remoteTokenAddress = event.params.token
  config.outboundRateLimiterConfig =
    '{"token":"' + event.params.token.toHexString() + '","amount":"' + event.params.amount.toString() + '"}'
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleInboundRateLimitConsumed(event: InboundRateLimitConsumed): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'InboundRateLimitConsumed'
  config.pool = event.address
  config.remoteChainSelector = event.params.remoteChainSelector
  config.remoteTokenAddress = event.params.token
  config.inboundRateLimiterConfig =
    '{"token":"' + event.params.token.toHexString() + '","amount":"' + event.params.amount.toString() + '"}'
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handlePoolOwnershipTransferRequested(event: OwnershipTransferRequested): void {
  let config = new PoolConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'OwnershipTransferRequested'
  config.pool = event.address
  config.remoteTokenAddress = event.params.to
  config.outboundRateLimiterConfig =
    '{"from":"' + event.params.from.toHexString() + '","to":"' + event.params.to.toHexString() + '"}'
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

function rateLimiterToJson(isEnabled: boolean, capacity: BigInt, rate: BigInt): string {
  return (
    '{"isEnabled":' +
    (isEnabled ? 'true' : 'false') +
    ',"capacity":"' +
    capacity.toString() +
    '","rate":"' +
    rate.toString() +
    '"}'
  )
}
