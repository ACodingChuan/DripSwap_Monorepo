import {
  PairCfgUpdated,
  DefaultsUpdated,
  OracleUpdated,
  OwnershipTransferStarted,
  OwnershipTransferred
} from '../generated/GuardedRouter/GuardedRouter'
import { ConfigEvent } from '../generated/schema'

export function handlePairCfgUpdated(event: PairCfgUpdated): void {
  let config = new ConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'PairCfgUpdated'
  config.contract = event.address
  config.params = event.params.tokenA.toHexString() + ',' + event.params.tokenB.toHexString()
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.logIndex = event.logIndex
  config.save()
}

export function handleDefaultsUpdated(event: DefaultsUpdated): void {
  let config = new ConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'DefaultsUpdated'
  config.contract = event.address
  config.params = event.params.hardBps.toString() + ',' + event.params.hardBpsFixed.toString() + ',' + event.params.staleSec.toString()
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.logIndex = event.logIndex
  config.save()
}

export function handleOracleUpdated(event: OracleUpdated): void {
  let config = new ConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'OracleUpdated'
  config.contract = event.address
  config.params = event.params.newOracle.toHexString()
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.logIndex = event.logIndex
  config.save()
}

export function handleGuardOwnershipTransferStarted(event: OwnershipTransferStarted): void {
  let config = new ConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'OwnershipTransferStarted'
  config.contract = event.address
  config.params = event.params.previousOwner.toHexString() + ',' + event.params.newOwner.toHexString()
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.logIndex = event.logIndex
  config.save()
}

export function handleGuardOwnershipTransferred(event: OwnershipTransferred): void {
  let config = new ConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'OwnershipTransferred'
  config.contract = event.address
  config.params = event.params.previousOwner.toHexString() + ',' + event.params.newOwner.toHexString()
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.logIndex = event.logIndex
  config.save()
}
