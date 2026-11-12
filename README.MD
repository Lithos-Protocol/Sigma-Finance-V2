# Sigma Finance V2

Sigma Finance V2 is an open standard for decentralized P2P contracts on Ergo.

## SigmaBonds: the ZC Standard

[SigmaBonds.MD](./SigmaBonds.MD) describes the ZC Standard: P2P zero-coupon bonds on any collateral,
with a tradeable bond asset, pluggable liquidation, fractional shares, a Dutch auction for liquidated
collateral, and an exchange where a lender's yield bid buys new and existing bonds. It is not
deployed and has no off-chain code yet. Every contract has offline specs.

| Folder (`contracts/bonds/`) | Contracts (`ZC_Standard_*`) |
|---|---|
| `core/` | `Bond`, `BondVault`: the bond and the vault its settlement pays |
| `P2P/` | `Order`, `LendOffer`, `SaleOrder`, `NavAsk`: borrowing, lending and trading |
| `lender-scripts/` | `FractionalMint`, `FractionalVault`, `DutchAuctionMint`, `DutchAuctionVault`: shares |
| `liquidations/` | `MaturityLiquidation`, `ERG_SigUSD_Liquidation`: when a bond may be liquidated |
| `policies/` | `ERG_SigUSD_CollateralPolicy`, `AccruedValue_Pricing`: an offer's risk rule, an ask's price |

How it works, in brief:

- A fill mints exactly 3 units of the bond asset: 2 stay with the bond, then its vault, and 1
  circulates. The box holding 2 units is always the bond's state box, so one lookup by token id finds
  it, and one check that the asset's minting box was a standard order proves the asset genuine.
- A loan is lent and repaid in one asset, so every bond's rate and accrued value are defined.
- Each market (ERG, SigUSD, ...) has one address per contract, and all markets share one template
  hash, so the Ergo indexed node lists them directly.
- Nobody has to come back online: mints, pulls, liquidations and expiry refunds are run by anyone for
  a fee the user committed when posting.

## First generation (`contracts/old/`)

The CLI below builds the first-generation contracts, now in `contracts/old/`:
`ZC_Order`, `ZC_OrderOnClose`, `ZC_ERG_Bond`, `ZC_Token_Bond`, `BondAssetVault` and
`MaturityLiquidation`. [docs/SIGMABONDS_OLD.md](./docs/SIGMABONDS_OLD.md) describes them.

## CLI
`SigmaFiCli` runs the first-generation SigmaBonds from the command line against a single Ergo node.
It needs no explorer and holds no keys. Boxes come from the node's extra index
(`ergo.node.extraIndex = true`), and the node's wallet signs. Run it from the repository root:

```bash
sbt "runMain ksingh.sigmabonds.app.SigmaFiCli --help"
```

```bash
sbt "runMain ksingh.sigmabonds.app.SigmaFiCli orders --node http://127.0.0.1:9053"
```

```bash
sbt "runMain ksingh.sigmabonds.app.SigmaFiCli fill-order <order box id> --batching-fee 0.05 --api-key <key> --wallet-pass <pass>"
```

List commands (`orders`, `bonds`, `claims`) find boxes and check their scripts and shapes. Actions take
the box ids they print:
- Lender: `fill-order`, `claim`
- Borrower: `create-order`, `cancel-order`, `repay`
- Executor: `liquidate`

Every action is a dry run unless you pass `--send`: it is built, signed by the node wallet, and checked
by the node (`/transactions/check`), but never submitted. `--node`, `--api-key` and `--wallet-pass` can
also come from `SIGMAFI_NODE`, `SIGMAFI_API_KEY` and `SIGMAFI_WALLET_PASS`. If the CLI unlocks the
wallet, it locks it again when it is done.

The CLI writes an optional order R4 hint that no contract reads: the borrower's ErgoTree, whose hash
is an order term (see [SIGMABONDS_OLD.md](./docs/SIGMABONDS_OLD.md)). It lets any lender fill an order without asking
the borrower for that tree. Filling mints two bond-asset units, one in the bond and one for the lender.
The lender chooses and funds an extra liquidation reserve on fill (`--batching-fee`, CLI minimum and default 0.05 ERG).
Repayment refunds the reserve into the lender's vault and returns the borrower collateral in full.
Bonds store agreed principal ERG/tokens and a Long maturity height for liquidation expressions.
Settlement R6 is a Byte: 0 repaid, 1 liquidated, enforced by the bond.
The lender's unit is named `SigmaFi-ZC-Bond-<first6>`, using the first six token-ID characters. Its EIP-4
description is generated JSON containing the loan terms and identifiers available when building the fill;
there are no manual `--name` or `--description` options.
Bond recognition does not establish mint provenance; the complete on-chain verification mechanism
remains to be designed. The CLI currently builds `MaturityLiquidation`; contracts permit custom
liquidation expressions to control both the vault's ERG and its tokens.

## Development
- [Lithos integration reference](./docs/LITHOS_SIGMAFI_V2_REFERENCE.md): generated ErgoTrees, template hex,
  hashes, constant registries, box/transaction layouts and the Lithos migration map, for the first
  generation.
- [TESTING.md](./TESTING.md): the offline contract specs, how to write them, and how to measure
  transaction cost.
- `sbt "runMain ksingh.sigmabonds.client.ContractClient"` prints the first generation's addresses,
  script hashes, liquidation scripts and order templates.
