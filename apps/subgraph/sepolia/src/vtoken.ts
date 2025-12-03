import { Address, BigInt } from '@graphprotocol/graph-ts'
import {
  Transfer,
  Minted,
  Burned,
  Approval,
  Initialized,
  EIP712DomainChanged,
  Paused,
  Unpaused,
  OwnershipTransferred,
  RoleAdminChanged,
  RoleGranted,
  RoleRevoked,
  VToken as VTokenContract
} from '../generated/VToken_vETH/VToken'
import { VToken as VTokenEntity, VTokenTransfer, VTokenMint, VTokenBurn, ApprovalEvent, RoleEvent, ConfigEvent } from '../generated/schema'

function getOrCreateVToken(address: Address): VTokenEntity {
  let token = VTokenEntity.load(address.toHexString())
  if (token == null) {
    token = new VTokenEntity(address.toHexString())
    token.symbol = ''
    token.name = ''
    token.decimals = 18
    token.totalSupply = BigInt.fromI32(0)
    token.totalMinted = BigInt.fromI32(0)
    token.totalBurned = BigInt.fromI32(0)
    token = populateMetadata(address, token)
    token.save()
  }
  return token as VTokenEntity
}

function populateMetadata(address: Address, token: VTokenEntity): VTokenEntity {
  let contract = VTokenContract.bind(address)

  let symbolCall = contract.try_symbol()
  if (!symbolCall.reverted) {
    token.symbol = symbolCall.value
  }

  let nameCall = contract.try_name()
  if (!nameCall.reverted) {
    token.name = nameCall.value
  }

  let decimalsCall = contract.try_decimals()
  if (!decimalsCall.reverted) {
    token.decimals = decimalsCall.value
  }

  return token
}

export function handleVTokenTransfer(event: Transfer): void {
  let token = getOrCreateVToken(event.address)
  token = populateMetadata(event.address, token)
  token.save()
  
  let transfer = new VTokenTransfer(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  transfer.token = token.id
  transfer.from = event.params.from
  transfer.to = event.params.to
  transfer.value = event.params.value
  transfer.blockNumber = event.block.number
  transfer.timestamp = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.save()
}

export function handleVTokenMint(event: Minted): void {
  let token = getOrCreateVToken(event.address)
  token = populateMetadata(event.address, token)
  token.totalMinted = token.totalMinted.plus(event.params.amount)
  token.totalSupply = token.totalSupply.plus(event.params.amount)
  token.save()

  let mint = new VTokenMint(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  mint.token = token.id
  mint.to = event.params.to
  mint.amount = event.params.amount
  mint.blockNumber = event.block.number
  mint.timestamp = event.block.timestamp
  mint.transactionHash = event.transaction.hash
  mint.save()
}

export function handleVTokenBurn(event: Burned): void {
  let token = getOrCreateVToken(event.address)
  token = populateMetadata(event.address, token)
  token.totalBurned = token.totalBurned.plus(event.params.amount)
  token.totalSupply = token.totalSupply.minus(event.params.amount)
  token.save()

  let burn = new VTokenBurn(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  burn.token = token.id
  burn.from = event.params.caller
  burn.amount = event.params.amount
  burn.blockNumber = event.block.number
  burn.timestamp = event.block.timestamp
  burn.transactionHash = event.transaction.hash
  burn.save()
}

export function handleVTokenApproval(event: Approval): void {
  let approval = new ApprovalEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  approval.token = event.address
  approval.owner = event.params.owner
  approval.spender = event.params.spender
  approval.value = event.params.value
  approval.blockNumber = event.block.number
  approval.timestamp = event.block.timestamp
  approval.transactionHash = event.transaction.hash
  approval.save()
}

export function handleVTokenInitialized(event: Initialized): void {
  populateMetadata(event.address, getOrCreateVToken(event.address)).save()
  let config = new ConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'Initialized'
  config.contract = event.address
  config.params = event.params.version.toString()
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.logIndex = event.logIndex
  config.save()
}

export function handleVTokenPaused(event: Paused): void {
  populateMetadata(event.address, getOrCreateVToken(event.address)).save()
  let config = new ConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'Paused'
  config.contract = event.address
  config.params = event.params.account.toHexString()
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.logIndex = event.logIndex
  config.save()
}

export function handleVTokenUnpaused(event: Unpaused): void {
  populateMetadata(event.address, getOrCreateVToken(event.address)).save()
  let config = new ConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'Unpaused'
  config.contract = event.address
  config.params = event.params.account.toHexString()
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.logIndex = event.logIndex
  config.save()
}

export function handleVTokenOwnershipTransferred(event: OwnershipTransferred): void {
  populateMetadata(event.address, getOrCreateVToken(event.address)).save()
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

export function handleVTokenRoleAdminChanged(event: RoleAdminChanged): void {
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

export function handleVTokenRoleGranted(event: RoleGranted): void {
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

export function handleVTokenRoleRevoked(event: RoleRevoked): void {
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

export function handleEIP712DomainChanged(event: EIP712DomainChanged): void {
  let config = new ConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  config.eventName = 'EIP712DomainChanged'
  config.contract = event.address
  config.params = ''
  config.blockNumber = event.block.number
  config.timestamp = event.block.timestamp
  config.transactionHash = event.transaction.hash
  config.logIndex = event.logIndex
  config.save()
}
