# Sigma Finance V2

Sigma Finance V2 is an open standard for decentralized P2P contracts on Ergo.

## SigmaFi Contracts
- [SigmaBonds](./SigmaBonds.MD): P2P zero-coupon bonds on any collateral, with tradeable bond assets
  and pluggable liquidation.

## Development
- [TESTING.md](./TESTING.md): the offline contract specs, and how to write them.
- `sbt "runMain ksingh.sigmabonds.client.ContractClient"` prints the current V2 addresses, script hashes,
  liquidation scripts and order/offer templates.
