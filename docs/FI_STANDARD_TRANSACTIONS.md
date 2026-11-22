# FI Standard: mainnet transaction ledger

Every row is a real mainnet transaction, witnessed by the chain: a transaction counts
as settled only when the node shows its distinguished input box spent by that
transaction's id (not a tx-index lookup, not the runner's own output). Amounts are human units: ERG, and SigUSD where 100 raw units = 1.00 SigUSD.

- **Contracts:** the ten FI Standard contracts in `contracts/bonds/fi`, as committed with this file.
- **Runner:** `src/test/scala/specs/bonds/FiLive.scala`, one run per lifecycle (cycle, sale, shares, liquidate).
- **Node:** mainnet, appVersion `6.1.5-…-9f7482dd`. Storage fee 1,250,000 nanoERG/byte,
  block version 4.
- **Oracle (ERG/USD pool) at planning:** ~5.71e9 nanoERG per USD (1 ERG ≈ 0.175 USD).

## Wallets

| Actor | Role | Address |
|---|---|---|
| Alice | Borrower | `9hgvrC28ALL1NYn2wpfoxLa96PK5ZWQE6uAqfnS8GifA3UfAh8s` |
| Bob | Lender | `9h5TPn6PwtAD281KQ3MYNBB1xZGWNpz3hHt5m4cnAtBi8oAbtUw` |
| Charlie | Keeper | `9gmqK6SXQ7j9K5B1QUMyrZntLD7uSVtx1MEuZL3NmDiKy19Dteo` |
| David | Share holder | `9hSQnAxeDTnL52kSWLncsDdKSyxusL1MfyZXA1KHHtZ9detXJbP` |
| Erin | Share holder / buyer | `9f6SPJRxkvzNXYbdzgH5AbxissSYJnHKr9eHBJ6kgsE9VVcEn3h` |

SigUSD token id: `03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04`.

---

## Part 1 — Funding transfers

Plain wallet-to-wallet sends to rebalance the five wallets so each covers the biggest
single run. Built, signed, and inspected before sending; each spends only clean boxes
(token-free for ERG, SigUSD-only for the SigUSD sends); change returns to the sender.

### F-1 · Bob → Alice, 8.000 ERG

- **What was sent:** 8.000 ERG, to top Alice up for the ~11.2 ERG collateral peak.
- **Transaction id:** `ea5b5b5b130e1e1b229786b7d099ef538291f7899ae9f945b68c532845380734`
- **Distinguished input:** `a0965666…4525b8` (Bob) → **witness: SETTLED**, block 1,922,093.
- **Inputs:** 2 boxes of Bob's, 7.9356 ERG + 1.4890 ERG = 9.4246 ERG, no tokens.
- **Outputs:**
  - `66d0b920…` → **Alice** 8.000 ERG
  - `1c6bf28c…` → miner fee 0.001 ERG
  - `4c8f63a0…` → **Bob** (change) 1.4236 ERG

### F-2 · Alice → Bob, 0.15 SigUSD

- **What was sent:** 15 raw SigUSD units (0.15 SigUSD), toward Bob's 1.00 principal.
- **Transaction id:** `1f651130c5dbdb32ab1f20e2514d58213980e3705b0bfd417f8fd39389210f16`
- **Distinguished input:** `9f2a413a…6869f` (Alice) → **witness: SETTLED**, block 1,922,093.
- **Inputs:** 1 box of Alice's, 0.101 ERG + 100 SigUSD units.
- **Outputs:**
  - `effe2490…` → **Bob** 0.001 ERG + 15 SigUSD units
  - `ff24c09a…` → miner fee 0.001 ERG
  - `7db8a285…` → **Alice** (change) 0.099 ERG + 85 SigUSD units

### F-3 · Alice → Bob, 0.50 SigUSD

- **What was sent:** 50 raw SigUSD units (0.50 SigUSD), margin so Bob stays above the
  1.00 principal through the shares run (which pays ~0.47 SigUSD out to David and Erin).
- **Transaction id:** `03b913a50663daf8fe2b98a09c9de9b099353811875626b4fef29d21067e5889`
- **Distinguished input:** `7db8a285…ae813` (Alice, the F-2 change box) → **witness: SETTLED**, block 1,922,095.
- **Outputs (as signed):**
  - → **Bob** 0.001 ERG + 50 SigUSD units
  - → miner fee 0.001 ERG
  - → **Alice** (change) 0.097 ERG + 35 SigUSD units

### F-4 · Erin → Bob, 0.40 SigUSD

- **What was sent:** 40 raw SigUSD units (0.40 SigUSD), so Bob stays above the 1.00
  principal through the shares run even with liquidate pulled forward to today. Erin
  keeps ~1.01 SigUSD, enough for her buyer role in the sale run.
- **Transaction id:** `1a1c7b52453d070ec18cf7eb9df6739c263a4fb58b0012f78243ce5edcb63df5`
- **Distinguished input:** `3a81d1f5…04daa` (Erin) → **witness: SETTLED**, block 1,922,121.
- **Outputs:** Bob 0.001 ERG + 40 SigUSD · fee 0.001 ERG · Erin (change) 0.0196 ERG + 81 SigUSD.

**Funding result (after F-1 to F-3):** Alice ≈ 12.01 ERG / 0.38 SigUSD,
Bob ≈ 3.12 ERG / 1.53 SigUSD; F-4 then added 0.40 SigUSD to Bob. Charlie, David, Erin already funded. Every wallet then
covers the peak of each run.

---

## Part 2 — Lifecycle runs

Four runs, submitted one at a time: cycle, sale, shares, liquidate. The last four
shares transactions (the pull and three claims) can only land 720 blocks after the
settlement box's creation height, about a day after the rest.


### Run order and what each proves

1. **cycle** — the happy path: Alice posts an order, it is filled into a bond, Alice pays
   3 coupons, then repays; collateral returns. Proves post / fill / pay / repay.
2. **shares** — the bond's unit is fractionalised into 1000 shares (Bob → David, Erin),
   coupons are absorbed into the share vault, Alice repays early, then after a ~720-block
   wait the settlement is pulled and David, Erin and Bob each claim their share. Proves
   mint / absorb / pull / claim.
3. **sale** — a bond unit is listed and bought through the lend offer's existing-bond
   path. Proves the sale/ask route.
4. **liquidate** — a bond misses a coupon, becomes liquidatable, Charlie liquidates it
   and the 3 units are burned for the settlement. Proves default / liquidate / burn.

### cycle — COMPLETE (9 tx, blocks 1,922,099–1,922,119)

| # | Card | Actor | What the transaction did | Transaction id | Block | Witness |
|---|---|---|---|---|---|---|
| 1 | 1 | Alice | posts the FI order (collateral + expiry fee) | `978aca36b2240bd55bb1025c0fc0844370c83f6b0e0a2e88fd0c7117841729ac` | 1922099 | SETTLED |
| 2 | 2a | Bob | fills the order directly: mints 3 units, creates the bond | `561edfabd61de97ecfad6e405cad9b920e292fbf25ea09cd89181903a23ab90a` | 1922100 | SETTLED |
| 3 | 3a | Alice | pays coupon 0 into a pool | `da1756f538aefef535e8a33f381e69b74d264426e60e56c7dc3a76c43b14ea46` | 1922102 | SETTLED |
| 4 | 3b | Alice | pays coupon 1, topping up the same pool | `cd3db57949dd0eed73906eaac4afb51d2590f75d2793f9144b14cb29018cdfa9` | 1922109 | SETTLED |
| 5 | 4 | Bob | collects the coupon pool with the bond unit (10 raw SigUSD) | `29777dbb80dcc8b3b1fbffb8d859214c364db4019aa62201933188879891004d` | 1922111 | SETTLED |
| 6 | 5 | Charlie | records a breach at checkpoint 0, keeping the record bounty | `9c06cccda408c53ac956171a7d10907da38ab1f2623441feb6a81c3230e15a89` | 1922113 | SETTLED |
| 7 | 6 | Alice | cures the breach, adding 5.4743 ERG of collateral | `4ceb6118a0a305a8ea7fdf5da87551a8d172240d731a5cdfbeb02a7f88433de5` | 1922116 | SETTLED |
| 8 | 8 | Alice | repays early; the bond settles | `c700c71608db1861c8ce2ebf911337a13549afddf7cf8e4abe398d1338f27dc0` | 1922117 | SETTLED |
| 9 | 9 | Bob | burns the 3 units for the settlement | `0d5cf9eaeedc76b72a99d03ff2c99fab60377fe9f56c780ed91ca308a7bf1163` | 1922119 | SETTLED |

All 9 witnessed SETTLED by spent-input. This run exercised the covenant breach, the
Record bounty and the Cure on mainnet.


### shares — steps 1-8 COMPLETE (blocks 1,922,136–1,922,147); pull + 3 claims trailing

| # | Card | Actor | What the transaction did | Transaction id | Block | Witness |
|---|---|---|---|---|---|---|
| 1 | 1 | Alice | posts the FI order | `5a637e43e226f2d4506c213d361ff6e9581328cf5d20e8274e90e93fcc09e95b` | 1922136 | SETTLED |
| 2 | 2a | Bob | fills the order directly | `aba5b4642be1167e2918eb373e0e3f867eca182a04ee9a3c9305a5e4588a1c19` | 1922137 | SETTLED |
| 3 | 12 | Bob | requests the FI share mint (1000 shares) | `b79352a1cc180f1636556f8a662597b41626daac32758a9a83570b955b2abf5e` | 1922139 | SETTLED |
| 4 | 12 | Charlie | runs the share mint | `bb027abaa0e033894ccd0fbdb1d513596cf1b7f151396677f4127a9a5018272b` | 1922140 | SETTLED |
| 5 | - | Bob | sends 300 shares to David and 200 to Erin | `8277e45d19d4e95232d004c8aa477ce96a9786ecd0b36e07c2374a93c272f925` | 1922141 | SETTLED |
| 6 | 3a | Alice | pays coupon 0 (5 raw SigUSD) | `34f991791c2c31db0fe0aab58f29215002be06490891435adb2e4a890b2a48b5` | 1922143 | SETTLED |
| 7 | 13 | Charlie | absorbs the coupon box into the share vault, keeping 1/200 (0 raw SigUSD, 5000 nanoERG) | `8f465db38331cd180b1085db340afc3792daff65d0ca2c47650f0ca460a9b4ae` | 1922145 | SETTLED |
| 8 | 8 | Alice | repays early; the bond settles | `8134c16ce02bc3fd11b6994ee82b6521f91824fbff22027478e91dfe1187fee9` | 1922147 | SETTLED |

All 8 witnessed SETTLED by spent-input. The settlement box carries creation height
1,922,145, so the pull opens 720 blocks later, at height 1,922,865. Still to land then:
the pull (card 14) and the David, Erin and Bob claims (card 15).

### sale — COMPLETE (8 tx, blocks 1,922,122–1,922,134)

| # | Card | Actor | What the transaction did | Transaction id | Block | Witness |
|---|---|---|---|---|---|---|
| 1 | 1 | Alice | posts the FI order | `c89222987dcec228359a520c02cf766f83b66560f2a61b1e7c45ce081ba95afb` | 1922122 | SETTLED |
| 2 | 2a | Erin | fills the order directly: a bond, and Erin holds the unit | `5b9fe22ec54b3813249817ef6eda10aa701000d5e5e2dd931ad16472b31f8f49` | 1922124 | SETTLED |
| 3 | - | Erin | lists her unit in a ZC sale order at the principal | `cc1a39b665290946211a56d8a7d8063d798ed1044dde60c48fff6b25f02bc4ca` | 1922125 | SETTLED |
| 4 | - | Bob | posts an FI lend offer for the principal | `dca428e52030fd5ad3f37ba0bc612ae4c91df918b70f49bb92f616a306327b3b` | 1922127 | SETTLED |
| 5 | 16 | Charlie | matches Bob's offer against Erin's sale order (existing-bond buy) | `493bf2aa96f1bd7804b90a4fd36a5b13bdc00614246df0a5a70903db97976f3b` | 1922129 | SETTLED |
| 6 | 8 | Alice | repays early; the bond settles | `3c3b8c1ff6ec630448dac565516fee60138fa8103445dde55e6b51db993e9fa7` | 1922130 | SETTLED |
| 7 | 9 | Bob | burns the 3 units for the settlement | `d347e982b6dd98baf19be14d30d9a5c8838214c0019b6b74a7760b34812349b8` | 1922132 | SETTLED |
| 8 | - | Bob | cancels the unfilled remainder of his offer | `129ca086740f5882475a2eff590688f079b56e071264250ef91b09e7d35c5a2e` | 1922134 | SETTLED |

All 8 witnessed SETTLED by spent-input. This run exercised the existing-bond purchase
through the lend offer and the offer cancel path on mainnet.


### liquidate — COMPLETE (6 tx, blocks 1,922,148–1,922,196)

| # | Card | Actor | What the transaction did | Transaction id | Block | Witness |
|---|---|---|---|---|---|---|
| 1 | - | Bob | posts an FI lend offer for the principal | `54c375afce84ee2c1c6419476ca5948b81f2a71ba083d811be543e55759f6989` | 1922148 | SETTLED |
| 2 | 1 | Alice | posts the FI order | `215c5ddb6842b38297358219d5730932e6f33f07b8eb29274be6fceb1b3fba45` | 1922151 | SETTLED |
| 3 | 2b | Charlie | fills the order from Bob's lend offer, keeping the fill fee | `f33fb3daf4428999f60decff9302ebdb645270cf87a07438ae8e8c01bb7c5356` | 1922152 | SETTLED |
| 4 | 7 | Charlie | liquidates from coupon 0's miss height (1,922,190), keeping the reserve | `414e6f4b99888b6da2f16b57280e4d9f7e44b0de5308f7bf855efa02705fd96f` | 1922192 | SETTLED |
| 5 | 9 | Bob | burns the 3 units for the settlement | `2d951d613c2997adb2a8729a3f2ace7f717e0134a13f0dfe7ba0cb5e9c0fd5d0` | 1922194 | SETTLED |
| 6 | - | Bob | cancels what is left of his offer | `698303f80e1b380d044763d16c7ea203bea33b54fbcd734822e79ff6e38a68cb` | 1922196 | SETTLED |

All 6 witnessed SETTLED by spent-input. Alice paid no coupon; the runner waited to
the miss height and Charlie liquidated. This run exercised the offer-filled bond (the
policy's cover test), the default and the liquidation on mainnet.

---

*Witness method: `GET /blockchain/box/byId/{inputBoxId}` → `spentTransactionId` equals our
txid = SETTLED; another id = SUPERSEDED; `null` + absent from mempool + node refuses
rebroadcast = DEAD. A bare 404 is "unknown", never "unspent".*
