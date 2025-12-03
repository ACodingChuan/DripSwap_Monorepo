import {
  TransferInitiated,
  TokenPoolRegistered,
  TokenPoolRemoved,
  LimitsUpdated,
  PayMethodUpdated,
  ServiceFeeUpdated,
  Paused,
  Unpaused,
  RoleAdminChanged,
  RoleGranted,
  RoleRevoked
} from '../generated/Bridge/Bridge'
import { BridgeTransfer, BridgeMessage, BridgeConfigEvent, RoleEvent } from '../generated/schema'

export function handleTransferInitiated(event: TransferInitiated): void {
  let transfer = new BridgeTransfer(event.params.messageId.toHexString())
  transfer.messageId = event.params.messageId
  transfer.transferId = event.params.messageId
  transfer.sender = event.params.sender
  transfer.receiver = event.params.receiver
  transfer.token = event.params.token
  transfer.amount = event.params.amount
  transfer.pool = event.params.pool
  transfer.payInLink = event.params.payInLink
  transfer.ccipFee = event.params.ccipFee
  transfer.serviceFeePaid = event.params.serviceFeePaid
  transfer.destinationChainSelector = event.params.dstSelector
  transfer.status = 'Initiated'
  transfer.blockNumber = event.block.number
  transfer.timestamp = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.save()

  let message = new BridgeMessage(event.params.messageId.toHexString())
  message.messageId = event.params.messageId
  message.sender = event.params.sender
  message.receiver = event.params.receiver
  message.token = event.params.token
  message.amount = event.params.amount
  message.destinationChainSelector = event.params.dstSelector
  message.blockNumber = event.block.number
  message.timestamp = event.block.timestamp
  message.save()
}

export function handleTokenPoolRegistered(event: TokenPoolRegistered): void {
  let config = new BridgeConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'TokenPoolRegistered'
  config.token = event.params.token
  config.pool = event.params.pool
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleTokenPoolRemoved(event: TokenPoolRemoved): void {
  let config = new BridgeConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'TokenPoolRemoved'
  config.token = event.params.token
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleLimitsUpdated(event: LimitsUpdated): void {
  let config = new BridgeConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'LimitsUpdated'
  config.minAmount = event.params.minAmount
  config.maxAmount = event.params.maxAmount
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handlePayMethodUpdated(event: PayMethodUpdated): void {
  let config = new BridgeConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'PayMethodUpdated'
  config.nativeAllowed = event.params.nativeAllowed
  config.linkAllowed = event.params.linkAllowed
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleServiceFeeUpdated(event: ServiceFeeUpdated): void {
  let config = new BridgeConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'ServiceFeeUpdated'
  config.newFee = event.params.newFee
  config.newCollector = event.params.newCollector
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleBridgePaused(event: Paused): void {
  let config = new BridgeConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'Paused'
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleBridgeUnpaused(event: Unpaused): void {
  let config = new BridgeConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'Unpaused'
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.save()
}

export function handleBridgeRoleAdminChanged(event: RoleAdminChanged): void {
  let roleEvent = new RoleEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  roleEvent.eventName = 'RoleAdminChanged'
  roleEvent.contract = event.address
  roleEvent.role = event.params.role
  roleEvent.account = event.params.newAdminRole
  roleEvent.blockNumber = event.block.number
  roleEvent.timestamp = event.block.timestamp
  roleEvent.transactionHash = event.transaction.hash
  roleEvent.save()
}

export function handleBridgeRoleGranted(event: RoleGranted): void {
  let roleEvent = new RoleEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  roleEvent.eventName = 'RoleGranted'
  roleEvent.contract = event.address
  roleEvent.role = event.params.role
  roleEvent.account = event.params.account
  roleEvent.sender = event.params.sender
  roleEvent.blockNumber = event.block.number
  roleEvent.timestamp = event.block.timestamp
  roleEvent.transactionHash = event.transaction.hash
  roleEvent.save()
}

export function handleBridgeRoleRevoked(event: RoleRevoked): void {
  let roleEvent = new RoleEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  roleEvent.eventName = 'RoleRevoked'
  roleEvent.contract = event.address
  roleEvent.role = event.params.role
  roleEvent.account = event.params.account
  roleEvent.sender = event.params.sender
  roleEvent.blockNumber = event.block.number
  roleEvent.timestamp = event.block.timestamp
  roleEvent.transactionHash = event.transaction.hash
  roleEvent.save()
}
