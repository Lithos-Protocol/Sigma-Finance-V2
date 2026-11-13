# Sigma Finance V2

Sigma Finance V2 is an open standard for decentralized P2P contracts on Ergo.

## SigmaBonds: the ZC Standard

[SigmaBonds.MD](./SigmaBonds.MD) describes the ZC Standard: P2P zero-coupon bonds on any collateral,
with a tradeable bond asset, pluggable liquidation, fractional shares, a Dutch auction for liquidated
collateral, and an exchange where a lender's yield bid buys new and existing bonds. It is not
deployed. Every contract has offline specs, and the CLI below builds all of it: the bond lifecycle,
fractional and Dutch auction shares, asks and lend offers, plus a batch scan for the work that pays
whoever runs it. [docs/ZC_STANDARD_OFFCHAIN.md](./docs/ZC_STANDARD_OFFCHAIN.md) sets the off-chain rules
that clients and batchers follow: script disclosure, token names and descriptions, share count, the
maturity grid, batching and defaults.

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

The first-generation contracts are `ZC_Order`, `ZC_OrderOnClose`, `ZC_ERG_Bond`, `ZC_Token_Bond`,
`BondAssetVault` and `MaturityLiquidation`, in `contracts/old/`.
[docs/SIGMABONDS_OLD.md](./docs/SIGMABONDS_OLD.md) describes them. The CLI still serves boxes already on
chain under them through its `--old` commands, but new orders use the ZC Standard only.

## CLI
`SigmaFiCli` runs SigmaBonds against a single Ergo node. It needs no explorer and holds no keys. Boxes
come from the node's extra index (`ergo.node.extraIndex = true`), and the node's wallet signs. Run it
from the repository root. Started without a command it is a shell: the JVM and the node connection
start once, and you type one command per line until `exit`.

```bash
sbt "sigmafi --node http://127.0.0.1:9053 --api-key <key> --wallet-pass <pass>"
```

```text
sigmafi> orders
sigmafi> fill-order <order box id> --fee-reserve 0.05
sigmafi> fill-order <order box id> --fee-reserve 0.05 --send
sigmafi> bonds --old
sigmafi> exit
```

`sigmafi` is an sbt alias for `runMain ksingh.sigmabonds.app.SigmaFiCli`; given a command, it runs that
one command and exits, so scripts keep working:

```bash
sbt "runMain ksingh.sigmabonds.app.SigmaFiCli help"
```

**ZC Standard, the default.** Commands build from the pinned trees in
`src/main/resources/zc-standard/contracts.tsv`, never from a fresh compile.
- Look around: `info`, `orders`, `bonds` (oracle ratios, recollateralize timing, and whether each bond
  asset was really minted by a standard order), `claims`
- Borrower: `create-order` (ERG or SigUSD market; maturity or oracle-ratio liquidation; a fixed
  maturity at least 21,600 blocks out snaps to the grid unless `--exact-maturity`; an oracle order
  carries a fill guard that its contract enforces, at `--ratio` or `--fill-ratio`, unless
  `--no-fill-guard`), `cancel-order`, `repay [--from-collateral]`, `recollateralize`
- Lender: `fill-order [--fee-reserve <ERG>]` (CLI minimum and default 0.05 ERG), `claim`. A fill or
  purchase that would leave a bond under its own oracle ratio is refused unless `--allow-liquidatable`
- Anyone: `liquidate`, which keeps the bonds' fee reserves, `refund-order`, which returns an expired
  order to its borrower for its expiry fee, and `pack`, which refunds expired orders, offers and asks
  and liquidates bonds in one transaction with one miner fee
- Shares: `shares`, `fractionalize` (fractional, or `--dutch` with an auction of a liquidated bond's
  collateral), `mint-shares` and `pull` (anyone, for the fees the owner committed), `buy-lot` (anyone,
  at an open auction's current price), `claim-shares`, `cancel-share-request`
- Asks: `asks` (prices also as a percentage of face), `sell` (a fixed `--price`, or `--nav` at a bond's
  accrued value plus a margin; `--min-cover` adds a sale guard that stops sales while the bond's
  collateral is thin), `buy`, `cancel-ask`, `refund-ask`
- Lend offers: `offers`, `create-offer` (a yearly yield, a maturity window, a collateral floor, and
  optionally `--deliver-shares` into fractional or Dutch shares), `cancel-offer`, `refund-offer`, and
  `match-offer --order | --ask`, which anyone runs for the offer's batcher fee. An offer's policy buys
  only bonds with the oracle liquidation rule, since it measures their collateral at the oracle price.
- `batch` lists every mint, pull, expiry refund, liquidation and offer match that pays its runner, and
  any box it passes over because it does not disclose a script it names; `batch --run` does the work
  in rounds, packing refunds and liquidations, matching each offer to its best-yield source, and
  scanning again with mempool lookups so fills chain through an offer's continuation. `--send` submits.

Every transaction follows the zcs1 off-chain rules in
[docs/ZC_STANDARD_OFFCHAIN.md](./docs/ZC_STANDARD_OFFCHAIN.md). It discloses each script its boxes name
by hash in context variable 64, unless an input or output already carries it, so any batcher can pay
it. A fill names the lender's bond unit `ZCS-Bond-Unit-<first6>`, with a compact JSON description of
the bond's terms. Shares default to 100 with 6 decimals (1.0 shares is 1% of the bond) and are named
`ZCS-Bond-Shares-<first6>`, with or without a Dutch auction. The listings flag anything nonstandard.
`help` lists every option.

**First generation, with `--old`.** `info`, `orders`, `bonds`, `claims`, `fill-order [--batching-fee
<ERG>]`, `claim`, `cancel-order`, `repay` and `liquidate`, as before, for first-generation boxes. Those
bonds' units are named `SigmaFi-ZC-Bond-<first6>`; filling mints two units, one in the bond and one for
the lender.

Every action is a dry run unless you pass `--send`: it is built, signed by the node wallet, and checked
by the node (`/transactions/check`), but never submitted. In the shell `--send` applies only to the
command it is typed with; a `--send` given at launch is ignored. `--node`, `--api-key` and
`--wallet-pass` can also come from `SIGMAFI_NODE`, `SIGMAFI_API_KEY` and `SIGMAFI_WALLET_PASS`. If the
CLI unlocks the wallet for a command, it locks it again afterwards.

### Live test

`sbt livetest` walks the CLI through every ZC Standard command on mainnet. Your node wallet is borrower,
lender, seller, buyer and batcher throughout, so everything except miner fees comes back to it. It
needs about 4 ERG and no SigUSD: loans are 0.1 ERG, at most about 2.7 ERG sits in contracts at once,
and the miner fees come to about 0.07 ERG. A run takes about 70 blocks (roughly 2.5 hours), set by the
bond's 60-block recollateralize wait; `skip B3` saves about an hour.

```bash
sbt "livetest --node http://127.0.0.1:9053 --api-key <key> --wallet-pass <pass>"
```

```text
livetest> plan      the steps, by track
livetest> next      run the next ready step; the first transaction asks for a yes
livetest> auto      run steps as they become ready, waiting for confirmations and heights; Enter pauses
livetest> status
```

Each step is a CLI command line, run in one CLI session, with the box ids earlier steps found filled
in. A setup transaction mints a worthless test token and splits ERG into boxes. The token is
collateral the oracle does not price, which the Dutch auction's lot and the token-collateral paths
need. Where the CLI can, a step builds on transactions still in the mempool (`--mempool`). It waits for
confirmation where the CLI reads the chain's history: an offer or a share request checks a bond asset's
origin, which needs the order spent on chain. A step that fails while what it built on is unconfirmed
runs again once that confirms, and the final report lists those. A step whose transaction leaves the
mempool unconfirmed has the ids it found undone and runs again, and `retry failed` re-runs every failed
step.

Steps also run the listings and check what they show, and run commands the CLI must refuse: a claim
before settlement, recollateralizing within 60 blocks, an early maturity liquidation, a refund before
expiry, and an offer facing an undercollateralized order. Progress is saved to `sigmafi-livetest.json`,
so `exit` and starting again resumes. Everything printed also goes to `sigmafi-livetest.log`. `cleanup`
lists whatever of the wallet's is still open, and `cleanup run` closes it. Options: `--no-chain`,
`--maturity-blocks <n>` (default 40), `--state <file>`, `--log <file>`. SigUSD loans and offers are not
covered; a SigUSD order and ask are only created and cancelled.

## Development
- [ZC Standard reference](./docs/ZC_STANDARD_REFERENCE.md): every standard contract's tree hash, template
  hash and addresses, and each expression's hash, generated from the compiler alongside the pinned trees
  the CLI loads.
- [Lithos integration reference](./docs/LITHOS_SIGMAFI_V2_REFERENCE.md): generated ErgoTrees, template hex,
  hashes, constant registries, box/transaction layouts and the Lithos migration map, for the first
  generation.
- [TESTING.md](./TESTING.md): the offline contract specs, how to write them, and how to measure
  transaction cost.
- `sbt "runMain ksingh.sigmabonds.client.ContractClient"` prints the first generation's addresses,
  script hashes, liquidation scripts and order templates.
