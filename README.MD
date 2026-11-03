# Sigma Finance V2

Sigma Finance V2 is an open standard for decentralized P2P contracts on Ergo.

## SigmaFi Contracts
- [SigmaBonds](./SigmaBonds.MD): P2P zero-coupon bonds on any collateral, with tradeable bond assets
  and pluggable liquidation.

## CLI
`SigmaFiCli` runs SigmaBonds from the command line against a single Ergo node. It needs no explorer and
holds no keys. Boxes come from the node's extra index (`ergo.node.extraIndex = true`), and the node's
wallet signs. Run it from the repository root:

```bash
sbt "runMain ksingh.sigmabonds.app.SigmaFiCli --help"
```

```bash
sbt "runMain ksingh.sigmabonds.app.SigmaFiCli orders --node http://127.0.0.1:9053"
```

```bash
sbt "runMain ksingh.sigmabonds.app.SigmaFiCli fill-order <order box id> --api-key <key> --wallet-pass <pass>"
```

List commands (`orders`, `offers`, `bonds`, `claims`) find and verify boxes. Actions take the box ids
they print:
- Lender: `create-offer`, `cancel-offer`, `fill-order`, `claim`
- Borrower: `create-order`, `cancel-order`, `close-offer`, `repay`
- Executor: `liquidate`

Every action is a dry run unless you pass `--send`: it is built, signed by the node wallet, and checked
by the node (`/transactions/check`), but never submitted. `--node`, `--api-key` and `--wallet-pass` can
also come from `SIGMAFI_NODE`, `SIGMAFI_API_KEY` and `SIGMAFI_WALLET_PASS`. If the CLI unlocks the
wallet, it locks it again when it is done.

The CLI writes optional hint registers that no contract reads: an order's R4 holds the borrower's
ErgoTree, and an offer's R4 the lender's (see [SigmaBonds](./SigmaBonds.MD)). They let any lender fill an
order, and any borrower close an offer, without asking the other party for those trees.

## Development
- [TESTING.md](./TESTING.md): the offline contract specs, and how to write them.
- `sbt "runMain ksingh.sigmabonds.client.ContractClient"` prints the current V2 addresses, script hashes,
  liquidation scripts and order/offer templates.
