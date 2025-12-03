import { USDFeedUpdated } from '../generated/ChainlinkOracle/ChainlinkOracle'
import { ConfigEvent } from '../generated/schema'

export function handleUSDFeedUpdated(event: USDFeedUpdated): void {
  let cfg = new ConfigEvent(event.transaction.hash.toHexString() + '-' + event.logIndex.toString())
  cfg.eventName = 'USDFeedUpdated'
  cfg.contract = event.address
  cfg.params =
    event.params.token.toHexString() +
    ',' +
    event.params.aggregator.toHexString() +
    ',' +
    event.params.aggDecimals.toString() +
    ',' +
    event.params.fixedUsdE18.toString()
  cfg.blockNumber = event.block.number
  cfg.timestamp = event.block.timestamp
  cfg.transactionHash = event.transaction.hash
  cfg.logIndex = event.logIndex
  cfg.save()
}
