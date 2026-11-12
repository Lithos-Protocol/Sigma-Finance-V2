package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * ZC_Standard_LendOffer and ZC_Standard_ERG_SigUSD_CollateralPolicy. The lender owns the offer, the
 * executor is the batcher, `other` sells. Most cases run an always-true policy so that they test the
 * offer alone; the policy has its own section.
 */
class ZcStandardLendOfferSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  private val kinds: Seq[Kind] = Seq(ErgBond, TokenBond)
  private val year: BigInt = BigInt(2628000000L)
  private val ergCapital: Long = 20L * Parameters.OneErg
  private val tokenCapital: Long = 100000L
  private val pullFee: Long = 2000000L
  private val mintFee: Long = 2000000L
  private val metadata: Seq[String] = Seq("Bond shares", "Shares of one bond", "2")

  /** The offer's R5. `expiry` < 0 means 5000 blocks above the mocked height. */
  private case class Terms(yieldBps: Long = 0L, minBlocks: Long = 1L, maxBlocks: Long = 100000L,
                           batcherFee: Long = batchingFee, reserve: Long = batchingFee, floorBps: Long = 13000L,
                           expiry: Long = -1L, minFill: Long = 1L, maxAge: Long = 30L,
                           auctionFloor: Long = 0L, auctionLength: Long = 1000000L)

  private def offerBox(ctx: BlockchainContext, kind: Kind, t: Terms = Terms(), policy: Contract = null,
                       fractional: Seq[Long] = Seq.empty, mint: Array[Byte] = Array.empty[Byte]): UTXO = {
    val expiry = if (t.expiry < 0L) ctx.getHeight + 5000L else t.expiry
    val regs = Seq(bytesValue(hashOf(lenderTree(ctx))),
      longsValue(t.yieldBps, t.minBlocks, t.maxBlocks, t.batcherFee, t.reserve, t.floorBps, expiry, t.minFill,
        t.maxAge, t.auctionFloor, t.auctionLength),
      bytesValue((if (policy == null) alwaysTrue(ctx) else policy).hashedValueBytes),
      longsValue(fractional: _*), if (fractional.isEmpty) textsValue() else textsValue(metadata: _*), bytesValue(mint))
    if (kind == ErgBond) UTXO(marketOf(ctx, kind).offer, ergCapital, Seq.empty[Token], regs)
    else UTXO(marketOf(ctx, kind).offer, Parameters.OneErg, Seq(Token(SigUSD, tokenCapital)), regs)
  }

  private def termsOf(offer: UTXO): Seq[Long] = offer.parseCollReg[Long](1).toSeq
  private def policyOf(ctx: BlockchainContext, policy: Contract): Contract = if (policy == null) alwaysTrue(ctx) else policy

  /** What a fill leaves: `ergOut` nanoERG and `price` raw repayment units out of the box. */
  private def continuation(kind: Kind, offer: UTXO, ergOut: Long, price: Long): UTXO = {
    val next = offer.setValue(offer.value - ergOut)
    if (kind == ErgBond) next
    else {
      val left = offer.tokens.head.amount - price
      if (left == 0L) next.setTokens() else next.setTokens(Token(SigUSD, left))
    }
  }

  /** Fee, delivery funding, reserve, and the ERG price or the nanoERG beside a token principal. */
  private def ergOut(kind: Kind, offer: UTXO, delivery: Long, reserve: Long, price: Long, beside: Long): Long =
    termsOf(offer)(3) + delivery + reserve + (if (kind == ErgBond) price else beside)

  // ═══ NEW BOND ═════════════════════════════════════════════════════════════

  private def priceOf(f: Flow): Long = if (f.kind == ErgBond) f.data(1) else 600L

  /** Outputs: 0 delivery, 1 bond, 2 principal, 3 continuation. Vars: 0 continuation index, 1 policy. */
  private def newBondTx(f: Flow, offer: UTXO, policy: Contract = null, delivery: UTXO = null, next: UTXO = null,
                        bond: UTXO = null, offerIn: InputUTXO = null, extra: Seq[InputUTXO] = Seq.empty,
                        data: Seq[InputUTXO] = Seq.empty, height: Int = -1): UnsignedTransaction = {
    val ctx = f.ctx
    val in = if (offerIn != null) offerIn else inputAt(offer, ctx, 1)
      .withCtxVar(0.toByte, ErgoValue.of(3)).withCtxVar(1.toByte, liqVar(policyOf(ctx, policy)))
    val deliveryOut = if (delivery == null) f.unit else delivery
    val funded = if (termsOf(offer).isEmpty || offer.parseCollReg[Long](3).isEmpty) minBox
      else 2L * minBox + pullFee + mintFee
    val nextOut = if (next != null) next else continuation(f.kind, offer,
      ergOut(f.kind, offer, funded, f.fee, priceOf(f), f.data(1)), priceOf(f))
    buildAt(ctx, if (height < 0) f.height else height, Seq(f.orderIn, in) ++ extra,
      Seq(deliveryOut, if (bond == null) f.bond else bond, f.principal, nextOut), executor(ctx).getAddress,
      dataInputs = data)
  }

  /** The highest yield the offer can demand and still pay `cost` for `units` of `whole` of a bond. */
  private def maxYield(repayment: Long, cost: Long, blocks: Long, units: Long = 1L, whole: Long = 1L): Long =
    ((BigInt(repayment) * units * year - BigInt(cost) * whole * year) / (BigInt(cost) * whole * blocks)).toLong

  for (kind <- kinds) {
    property(s"$kind new bond: an offer fills a standard order and the bond unit reaches its owner") {
      withCtx { ctx =>
        val f = flow(ctx, kind)
        val offer = offerBox(ctx, kind)
        val tx = accepts(executor(ctx), newBondTx(f, offer))
        tx.getOutputsToSpend.get(0).getErgoTree shouldBe lenderTree(ctx).ergoTree
        tx.getOutputsToSpend.get(0).getTokens.get(0).getId shouldBe f.asset
        tx.getOutputsToSpend.get(3).getErgoTree shouldBe marketOf(ctx, kind).offer.ergoTree
      }
    }

    property(s"$kind new bond: the yield test is exact, and the ERG batcher fee counts against it") {
      withCtx { ctx =>
        val f = flow(ctx, kind)
        val cost = if (kind == ErgBond) priceOf(f) + batchingFee else priceOf(f)
        val y = maxYield(f.data(0), cost, f.maturity - f.height)
        accepts(executor(ctx), newBondTx(f, offerBox(ctx, kind, Terms(yieldBps = y))))
        rejectsAtSigning(executor(ctx), newBondTx(f, offerBox(ctx, kind, Terms(yieldBps = y + 1L))))
      }
    }
  }

  property("new bond: maturity window, minimum fill, exact reserve and the order's own checks") {
    withCtx { ctx =>
      val f = flow(ctx, ErgBond)
      val blocks = f.maturity - f.height
      accepts(executor(ctx), newBondTx(f, offerBox(ctx, ErgBond, Terms(minBlocks = blocks, maxBlocks = blocks))))
      Seq(Terms(minBlocks = blocks + 1L), Terms(maxBlocks = blocks - 1L), Terms(minFill = priceOf(f) + 1L),
        Terms(reserve = batchingFee + 1L), Terms(yieldBps = -1L), Terms(batcherFee = -1L)).foreach { t =>
        // The executor's box keeps every variant buildable, so only the offer can refuse it.
        rejectsAtSigning(executor(ctx), newBondTx(f, offerBox(ctx, ErgBond, t), extra = Seq(funding(ctx, executor(ctx), 9))))
      }
      // The offer never fills an order that is not the standard order for its asset. The executor
      // brings the SigUSD principal so the transaction builds and only the offer can refuse it.
      val tokenFlow = flow(ctx, TokenBond)
      val ergOffer = offerBox(ctx, ErgBond)
      rejectsAtSigning(executor(ctx), newBondTx(tokenFlow, ergOffer, next = ergOffer.subValue(10L * minBox),
        extra = Seq(funding(ctx, executor(ctx), 9, slack, Seq(Token(SigUSD, 600L))))))
    }
  }

  property("new bond: only the fee, delivery, reserve and price leave, to a continuation that keeps every term") {
    withCtx { ctx =>
      kinds.foreach { kind =>
        val f = flow(ctx, kind)
        val offer = offerBox(ctx, kind)
        val next = continuation(kind, offer, ergOut(kind, offer, minBox, f.fee, priceOf(f), f.data(1)), priceOf(f))
        accepts(executor(ctx), newBondTx(f, offer, next = next))
        val keptTokens = if (kind == TokenBond) Seq(next.setTokens(Token(SigUSD, tokenCapital - priceOf(f) - 1L))) else Nil
        (Seq(next.subValue(1L), next.setContract(otherTree(ctx)), next.withRegNum(4, bytesValue(hashOf(otherTree(ctx)))),
          next.withRegNum(5, longsValue(1L, 1L, 100000L, batchingFee, batchingFee, 13000L, ctx.getHeight + 5000L, 1L, 30L, 0L, 1000000L)),
          next.withRegNum(6, bytesValue(neverTrue(ctx).hashedValueBytes)), next.withRegNum(7, longsValue(1000L, pullFee, mintFee)),
          next.withRegNum(9, bytesValue(hashOf(otherTree(ctx))))) ++ keptTokens).foreach { bad =>
          rejectsAtSigning(executor(ctx), newBondTx(f, offer, next = bad))
        }
        // The unit itself, with its box minimum, to the owner only.
        Seq(f.unit.setContract(otherTree(ctx)), f.unit.subValue(1L)).foreach { bad =>
          rejectsAtSigning(executor(ctx), newBondTx(f, offer, delivery = bad))
        }
      }
    }
  }

  property("new bond: the policy expression is the one in R6, and it runs") {
    withCtx { ctx =>
      val f = flow(ctx, ErgBond)
      rejectsAtSigning(executor(ctx), newBondTx(f, offerBox(ctx, ErgBond), policy = neverTrue(ctx)))
      rejectsAtSigning(executor(ctx), newBondTx(f, offerBox(ctx, ErgBond, policy = neverTrue(ctx)), policy = neverTrue(ctx)))
    }
  }

  property("new bond: only one offer may take part in a fill") {
    withCtx { ctx =>
      val f = flow(ctx, ErgBond)
      val offer = offerBox(ctx, ErgBond)
      val second = inputAt(offer.addValue(1L), ctx, 5)
        .withCtxVar(0.toByte, ErgoValue.of(3)).withCtxVar(1.toByte, liqVar(alwaysTrue(ctx)))
      rejectsAtSigning(executor(ctx), newBondTx(f, offer, extra = Seq(second)))
    }
  }

  // ═══ DELIVERY INTO A MINT ═════════════════════════════════════════════════

  /** A mint request as the offer must deliver it: R4-R6 the bond asset's EIP-4, R7 owner, R8 offer R7, R9 offer R8. */
  private def requestBox(ctx: BlockchainContext, mint: Contract, asset: ErgoId, longs: Seq[Long],
                         owner: Contract = null, value: Long = 2L * minBox + pullFee + mintFee): UTXO =
    UTXO(mint, value, Seq(Token(asset, 1L)),
      eip4() ++ Seq(bytesValue(hashOf(if (owner == null) lenderTree(ctx) else owner)), longsValue(longs: _*),
        textsValue(metadata: _*)))

  property("delivery: a fill can put the unit into the standard or the Dutch mint the lender names, and a batcher mints") {
    withCtx { ctx =>
      val standard = Seq(1000L, pullFee, mintFee)
      val dutch = Seq(1000L, pullFee, mintFee, 15000L, 11000L, 500L)
      Seq(scripts(ctx).fractionalMint -> standard, marketOf(ctx, ErgBond).dutchMint -> dutch).foreach { case (mint, longs) =>
        val f = flow(ctx, ErgBond)
        val offer = offerBox(ctx, ErgBond, fractional = longs, mint = hashOf(mint))
        val request = requestBox(ctx, mint, f.asset, longs)
        val tx = accepts(executor(ctx), newBondTx(f, offer, delivery = request))
        val delivered = spendOutput(tx, 0)
        // Any batcher now runs the mint for the mint fee, with the bond's state box as data for the Dutch mint.
        val shares = UTXO(lenderTree(ctx), minBox, Seq(Token(delivered.id, 1000L)),
          Seq(textsValueItem(0), textsValueItem(1), textsValueItem(2)))
        val isDutch = longs.size == 6
        // Registers are dense, so a Dutch vault carrying R7 and R8 also carries an R6. The mint does not
        // read it and the vault ignores it until the pull sets the state.
        val vault = if (isDutch)
          UTXO(exchange(ctx).dutchVault, delivered.value - mintFee - minBox,
            Seq(Token(delivered.id, 1001L), Token(f.asset, 1L)),
            Seq(bytesValue(f.asset.getBytes), longsValue(1000L, 1000L, pullFee), ErgoValue.of(0.toByte),
              longsValue(f.data(0), 15000L, 11000L, 500L, 0L), bytesValue(Array.empty[Byte])))
        else UTXO(scripts(ctx).fractionalVault, delivered.value - mintFee - minBox,
            Seq(Token(delivered.id, 1001L), Token(f.asset, 1L)),
            Seq(bytesValue(f.asset.getBytes), longsValue(1000L, 1000L, pullFee)))
        val in = if (isDutch) delivered.withCtxVar(0.toByte, ErgoValue.of(0)) else delivered
        accepts(executor(ctx), build(ctx, Seq(in), Seq(shares, vault), executor(ctx).getAddress,
          dataInputs = if (isDutch) Seq(spendOutput(tx, 1)) else Seq.empty))
      }
    }
  }

  private def textsValueItem(i: Int): ErgoValue[_] = utf8(metadata(i))

  property("delivery: the request must name the owner, both term sets and the mint script, and be funded") {
    withCtx { ctx =>
      val longs = Seq(1000L, pullFee, mintFee)
      val mint = scripts(ctx).fractionalMint
      val f = flow(ctx, ErgBond)
      val offer = offerBox(ctx, ErgBond, fractional = longs, mint = hashOf(mint))
      val request = requestBox(ctx, mint, f.asset, longs)
      accepts(executor(ctx), newBondTx(f, offer, delivery = request))
      Seq(request.setContract(marketOf(ctx, ErgBond).dutchMint), request.subValue(1L),
        request.withRegNum(7, bytesValue(hashOf(otherTree(ctx)))), request.withRegNum(8, longsValue(999L, pullFee, mintFee)),
        request.withRegNum(9, textsValue("Renamed", metadata(1), metadata(2)))).foreach { bad =>
        rejectsAtSigning(executor(ctx), newBondTx(f, offer, delivery = bad))
      }
      // A plain delivery from an offer that asked for shares is refused too.
      rejectsAtSigning(executor(ctx), newBondTx(f, offer, delivery = f.unit.addValue(2L * minBox + pullFee + mintFee)))
    }
  }

  // ═══ EXISTING BOND OR SHARES, FROM AN ASK ═════════════════════════════════

  /** A live bond, 300 blocks into its term, and the box that minted its asset. */
  private case class Live(f: Flow, state: InputUTXO, height: Int, fill: SignedTransaction) {
    def blocks: Long = f.maturity - height
  }

  private def live(ctx: BlockchainContext, kind: Kind, unit: UTXO = null): Live = {
    val f = flow(ctx, kind)
    val fill = accepts(f.holder, standardFillTx(f, unit = unit))
    Live(f, spendOutput(fill, 1), f.height + 300, fill)
  }

  private def saleBox(ctx: BlockchainContext, kind: Kind, token: ErgoId, amount: Long, price: Long,
                      per: Long = 1L): UTXO =
    UTXO(marketOf(ctx, kind).saleOrder, minBox, Seq(Token(token, amount)),
      Seq(bytesValue(hashOf(otherTree(ctx))), longsValue(price, per, 1L, ctx.getHeight + 5000L)))

  private def payoutBox(ctx: BlockchainContext, kind: Kind, askId: ErgoId, units: Long, paid: Long): UTXO = {
    val regs = Seq(bytesValue(askId.getBytes), ErgoValue.of(units))
    if (kind == ErgBond) UTXO(otherTree(ctx), paid, Seq.empty[Token], regs)
    else UTXO(otherTree(ctx), minBox, Seq(Token(SigUSD, paid)), regs)
  }

  /**
   * The offer buys `units` of the ask's token at `price` per unit. Inputs: 0 the ask, 1 the offer.
   * Outputs: 0 payout, 1 delivery, 2 offer continuation, then the ask's continuation if any is left.
   */
  private def buyTx(l: Live, offer: UTXO, ask: UTXO, units: Long, paid: Long, request: InputUTXO = null,
                    origin: Array[Byte] = null, state: InputUTXO = null, delivery: UTXO = null,
                    payout: UTXO = null, askNext: UTXO = null, policy: Contract = null,
                    data: Seq[InputUTXO] = Seq.empty, height: Int = -1): UnsignedTransaction = {
    val ctx = l.f.ctx
    val kind = l.f.kind
    val askIn = inputAt(ask, ctx, 2)
    val token = ask.tokens.head.id
    val left = ask.tokens.head.amount - units
    val nextAsk = if (askNext != null) askNext else if (left > 0L) ask.setTokens(Token(token, left)) else null
    val askWithVars = if (nextAsk == null) askIn.withCtxVar(0.toByte, ErgoValue.of(0))
      else askIn.withCtxVar(0.toByte, ErgoValue.of(0)).withCtxVar(1.toByte, ErgoValue.of(3))
    val base = inputAt(offer, ctx, 1)
      .withCtxVar(0.toByte, ErgoValue.of(2)).withCtxVar(1.toByte, liqVar(policyOf(ctx, policy)))
      .withCtxVar(2.toByte, ErgoValue.of(0)).withCtxVar(3.toByte, ErgoValue.of(0))
      .withCtxVar(4.toByte, bytesValue(if (origin == null) l.f.orderIn.bytes else origin))
      .withCtxVar(5.toByte, ErgoValue.of(1)).withCtxVar(7.toByte, ErgoValue.of(0))
    val offerIn = if (request == null) base else base.withCtxVar(6.toByte, bytesValue(request.bytes))
    val pay = if (payout == null) payoutBox(ctx, kind, askIn.id, units, paid) else payout
    val deliver = if (delivery == null) UTXO(lenderTree(ctx), minBox, Seq(Token(token, units))) else delivery
    val next = continuation(kind, offer, ergOut(kind, offer, minBox, 0L, paid, minBox), paid)
    buildAt(ctx, if (height < 0) l.height else height, Seq(askWithVars, offerIn),
      Seq(pay, deliver, next) ++ Option(nextAsk).toSeq, executor(ctx).getAddress,
      dataInputs = Seq(if (state == null) l.state else state) ++ data)
  }

  /** A sale price that clears a 0 bps offer: just under the repayment, less the ERG fee. */
  private def unitPrice(kind: Kind): Long = if (kind == ErgBond) 6500000000L else 650L

  for (kind <- kinds) {
    property(s"$kind existing bond: an offer buys a verified bond unit from a sale order, with an exact yield") {
      withCtx { ctx =>
        val l = live(ctx, kind)
        val price = unitPrice(kind)
        val ask = saleBox(ctx, kind, l.f.asset, 1L, price)
        accepts(executor(ctx), buyTx(l, offerBox(ctx, kind), ask, 1L, price))
        val cost = if (kind == ErgBond) price + batchingFee else price
        val y = maxYield(l.f.data(0), cost, l.blocks)
        accepts(executor(ctx), buyTx(l, offerBox(ctx, kind, Terms(yieldBps = y)), ask, 1L, price))
        rejectsAtSigning(executor(ctx), buyTx(l, offerBox(ctx, kind, Terms(yieldBps = y + 1L)), ask, 1L, price))
      }
    }
  }

  property("existing bond: the origin must hash to the asset and be a standard order, and the bond must be live") {
    withCtx { ctx =>
      val l = live(ctx, ErgBond)
      val price = unitPrice(ErgBond)
      val offer = offerBox(ctx, ErgBond)
      val ask = saleBox(ctx, ErgBond, l.f.asset, 1L, price)
      accepts(executor(ctx), buyTx(l, offer, ask, 1L, price))
      // Bytes of some other box: they do not hash to the asset id.
      rejectsAtSigning(executor(ctx), buyTx(l, offer, ask, 1L, price, origin = l.state.bytes))
      // A settled bond is not bought: its 2 units sit in the vault.
      val vault = spendOutput(accepts(l.f.owner, standardRepayTx(l.f, repayInput(l.state))), 0)
      rejectsAtSigning(executor(ctx), buyTx(l, offer, ask, 1L, price, state = vault))
      // A hand-made asset: minted by a box that is not an order, with a hand-made "bond" holding 2 units.
      val fakeOrigin = inputAt(UTXO(otherTree(ctx), minBox), ctx, 11)
      val fake = fakeOrigin.id
      val fakeState = inputAt(l.state.toUTXO.setTokens(Token(fake, 2L) +: l.state.tokens.drop(1): _*), ctx, 12)
      val fakeAsk = saleBox(ctx, ErgBond, fake, 1L, price)
      rejectsAtSigning(executor(ctx), buyTx(l, offer, fakeAsk, 1L, price, origin = fakeOrigin.bytes, state = fakeState))
      // Past the offer's maturity window.
      rejectsAtSigning(executor(ctx), buyTx(l, offerBox(ctx, ErgBond, Terms(maxBlocks = l.blocks - 1L)), ask, 1L, price))
    }
  }

  property("existing bond: the ask convention, with the payout paying for exactly the units delivered") {
    withCtx { ctx =>
      val l = live(ctx, ErgBond)
      val price = unitPrice(ErgBond)
      val offer = offerBox(ctx, ErgBond)
      val ask = saleBox(ctx, ErgBond, l.f.asset, 1L, price)
      val askId = inputAt(ask, ctx, 2).id
      val pay = payoutBox(ctx, ErgBond, askId, 1L, price)
      Seq(pay.setContract(lenderTree(ctx)), pay.withRegNum(4, bytesValue(originOrderId)),
        pay.withRegNum(5, ErgoValue.of(2L))).foreach { bad =>
        rejectsAtSigning(executor(ctx), buyTx(l, offer, ask, 1L, price, payout = bad))
      }
      // The delivery goes to the owner.
      rejectsAtSigning(executor(ctx), buyTx(l, offer, ask, 1L, price,
        delivery = UTXO(otherTree(ctx), minBox, Seq(Token(l.f.asset, 1L)))))
    }
  }

  // ─── shares ───────────────────────────────────────────────────────────────

  private case class Wrapped(l: Live, request: InputUTXO, shares: Long) {
    def shareId: ErgoId = request.id
  }

  /** A bond whose unit went into a mint request at the fill; the request's id is the share id. */
  private def wrapped(ctx: BlockchainContext, kind: Kind, mint: Contract, longs: Seq[Long]): Wrapped = {
    val probe = flow(ctx, kind)
    val l = live(ctx, kind, unit = requestBox(ctx, mint, probe.asset, longs))
    Wrapped(l, spendOutput(l.fill, 0), longs.head)
  }

  private def shareBuy(w: Wrapped, offer: UTXO, units: Long, paid: Long, askAmount: Long = 100L,
                       delivered: Long = -1L): UnsignedTransaction = {
    val ctx = w.l.f.ctx
    // The ask's own limit is tiny, so the offer's yield decides each case.
    val ask = saleBox(ctx, w.l.f.kind, w.shareId, askAmount, 1L, per = 1000L)
    val give = if (delivered < 0L) units else delivered
    buyTx(w.l, offer, ask, units, paid, request = w.request,
      delivery = UTXO(lenderTree(ctx), minBox, Seq(Token(w.shareId, give))))
  }

  for (kind <- kinds) {
    property(s"$kind shares: standard shares are bought pro rata to the repayment, with an exact yield") {
      withCtx { ctx =>
        val w = wrapped(ctx, kind, scripts(ctx).fractionalMint, Seq(1000L, pullFee, mintFee))
        val units = 30L
        val paid = if (kind == ErgBond) 195000000L else 20L
        accepts(executor(ctx), shareBuy(w, offerBox(ctx, kind), units, paid))
        val cost = if (kind == ErgBond) paid + batchingFee else paid
        val y = maxYield(w.l.f.data(0), cost, w.l.blocks, units, 1000L)
        accepts(executor(ctx), shareBuy(w, offerBox(ctx, kind, Terms(yieldBps = y)), units, paid))
        rejectsAtSigning(executor(ctx), shareBuy(w, offerBox(ctx, kind, Terms(yieldBps = y + 1L)), units, paid))
      }
    }
  }

  property("shares: an offer is never charged for shares it did not receive") {
    withCtx { ctx =>
      val w = wrapped(ctx, ErgBond, scripts(ctx).fractionalMint, Seq(1000L, pullFee, mintFee))
      val offer = offerBox(ctx, ErgBond)
      accepts(executor(ctx), shareBuy(w, offer, 30L, 195000000L))
      // The ask sells 30 and states 30, but only 29 reach the offer.
      rejectsAtSigning(executor(ctx), shareBuy(w, offer, 30L, 195000000L, delivered = 29L))
      // More shares than the bond has.
      val all = wrapped(ctx, ErgBond, scripts(ctx).fractionalMint, Seq(10L, pullFee, mintFee))
      rejectsAtSigning(executor(ctx), shareBuy(all, offer, 11L, 7700000000L, askAmount = 11L))
    }
  }

  property("shares: Dutch auction shares are bought only within the lender's floor and length bounds") {
    withCtx { ctx =>
      val dutch = marketOf(ctx, ErgBond).dutchMint
      val w = wrapped(ctx, ErgBond, dutch, Seq(1000L, pullFee, mintFee, 15000L, 10000L, 500L))
      def offer(floor: Long, length: Long): UTXO = offerBox(ctx, ErgBond, Terms(auctionFloor = floor, auctionLength = length))
      accepts(executor(ctx), shareBuy(w, offer(10000L, 500L), 30L, 195000000L))
      accepts(executor(ctx), shareBuy(w, offer(0L, 1000000L), 30L, 195000000L))
      Seq(offer(10001L, 500L), offer(10000L, 499L), offer(0L, 0L)).foreach { o =>
        rejectsAtSigning(executor(ctx), shareBuy(w, o, 30L, 195000000L))
      }
      // A maximum length of 0 refuses Dutch shares but not standard ones.
      val standard = wrapped(ctx, ErgBond, scripts(ctx).fractionalMint, Seq(1000L, pullFee, mintFee))
      accepts(executor(ctx), shareBuy(standard, offer(1000000L, 0L), 30L, 195000000L))
      // A request under a script that is neither mint.
      val neither = wrapped(ctx, ErgBond, otherTree(ctx), Seq(1000L, pullFee, mintFee, 15000L, 10000L, 500L))
      rejectsAtSigning(executor(ctx), shareBuy(neither, offer(0L, 1000000L), 30L, 195000000L))
      // The Dutch mint compiled for the other repayment asset is not this offer's.
      val tokenDutch = wrapped(ctx, ErgBond, marketOf(ctx, TokenBond).dutchMint, Seq(1000L, pullFee, mintFee, 15000L, 10000L, 500L))
      rejectsAtSigning(executor(ctx), shareBuy(tokenDutch, offer(0L, 1000000L), 30L, 195000000L))
    }
  }

  // ═══ COLLATERAL POLICY ════════════════════════════════════════════════════

  /** A new-bond fill under the real policy, with the bond on the oracle rule and one fresh oracle. */
  private def policyTx(ctx: BlockchainContext, kind: Kind, floor: Long = 13000L, maxAge: Long = 30L,
                       extra: Seq[Long] = Seq(15000L, 30L), liq: Contract = null, created: Int = -1,
                       oracle: Boolean = true): UnsignedTransaction = {
    val f = flow(ctx, kind, extra = extra, liq = if (liq == null) oracleRuleOf(ctx, kind) else liq)
    val policy = marketOf(ctx, kind).policy
    newBondTx(f, offerBox(ctx, kind, Terms(floorBps = floor, maxAge = maxAge), policy = policy), policy = policy,
      data = if (oracle) Seq(oracleBox(f, created = if (created < 0) f.height else created)) else Seq.empty)
  }

  property("policy: collateral must cover the lender's floor now, measured against the repayment") {
    withCtx { ctx =>
      // ERG: 10 ERG of collateral against a 7 ERG repayment is 142.857%.
      accepts(executor(ctx), policyTx(ctx, ErgBond, floor = 14285L))
      rejectsAtSigning(executor(ctx), policyTx(ctx, ErgBond, floor = 14286L))
      // SigUSD: 10 ERG at 0.5 ERG per USD is $20 against 700 raw SigUSD ($7): 285.714%.
      accepts(executor(ctx), policyTx(ctx, TokenBond, floor = 28571L, extra = Seq(30000L, 30L)))
      rejectsAtSigning(executor(ctx), policyTx(ctx, TokenBond, floor = 28572L, extra = Seq(30000L, 30L)))
    }
  }

  property("policy: the bond's own rule must be the oracle rule, fire at the floor, and accept the lender's oracle age") {
    withCtx { ctx =>
      accepts(executor(ctx), policyTx(ctx, ErgBond, floor = 13000L, extra = Seq(13000L, 30L)))
      Seq(Seq(12999L, 30L), Seq(15000L, 29L), Seq(15000L, 100001L), Seq(15000L)).foreach { extra =>
        rejectsAtSigning(executor(ctx), policyTx(ctx, ErgBond, extra = extra))
      }
      accepts(executor(ctx), policyTx(ctx, ErgBond, extra = Seq(15000L, 100000L)))
      rejectsAtSigning(executor(ctx), policyTx(ctx, ErgBond, liq = scripts(ctx).maturity))
      rejectsAtSigning(executor(ctx), policyTx(ctx, ErgBond, liq = oracleRuleOf(ctx, TokenBond)))
    }
  }

  property("policy: the oracle must be present, unique and within the lender's age") {
    withCtx { ctx =>
      val f = flow(ctx, ErgBond)
      accepts(executor(ctx), policyTx(ctx, ErgBond, created = f.height - 30))
      rejectsAtSigning(executor(ctx), policyTx(ctx, ErgBond, created = f.height - 31))
      rejectsAtSigning(executor(ctx), policyTx(ctx, ErgBond, created = f.height + 1))
      rejectsAtSigning(executor(ctx), policyTx(ctx, ErgBond, oracle = false))
      Seq(0L, 100001L).foreach(age => rejectsAtSigning(executor(ctx), policyTx(ctx, ErgBond, maxAge = age, extra = Seq(15000L, 100000L))))
    }
  }

  property("policy: an existing bond is judged from its state box") {
    withCtx { ctx =>
      val f = flow(ctx, ErgBond, extra = Seq(15000L, 30L), liq = oracleRuleOf(ctx, ErgBond))
      val fill = accepts(f.holder, standardFillTx(f))
      val l = Live(f, spendOutput(fill, 1), f.height + 5, fill)
      val policy = marketOf(ctx, ErgBond).policy
      val ask = saleBox(ctx, ErgBond, f.asset, 1L, unitPrice(ErgBond))
      def buy(floor: Long): UnsignedTransaction = buyTx(l, offerBox(ctx, ErgBond, Terms(floorBps = floor), policy = policy),
        ask, 1L, unitPrice(ErgBond), policy = policy, data = Seq(oracleBox(f, created = l.height)))
      accepts(executor(ctx), buy(14285L))
      rejectsAtSigning(executor(ctx), buy(14286L))
    }
  }

  // ═══ EXPIRY REFUND AND CANCEL ═════════════════════════════════════════════

  private def refundTx(ctx: BlockchainContext, offers: Seq[(UTXO, Int)], outs: Seq[UTXO], height: Int,
                       indices: Seq[Int] = null): UnsignedTransaction = {
    val ins = offers.zipWithIndex.map { case ((box, slot), i) =>
      inputAt(box, ctx, slot).withCtxVar(0.toByte, ErgoValue.of(if (indices == null) i else indices(i)))
    }
    buildAt(ctx, height, ins :+ funding(ctx, executor(ctx), 9), outs, executor(ctx).getAddress)
  }

  property("refund: from expiry on anyone returns an offer to its owner, tagged, less the batcher fee") {
    withCtx { ctx =>
      val expiry = ctx.getHeight + 50
      kinds.foreach { kind =>
        val offer = offerBox(ctx, kind, Terms(expiry = expiry))
        val id = inputAt(offer, ctx, 1).id
        val refund = tagged(lenderTree(ctx), offer.value - batchingFee, id, offer.tokens)
        accepts(executor(ctx), refundTx(ctx, Seq(offer -> 1), Seq(refund), expiry))
        rejectsAtSigning(executor(ctx), refundTx(ctx, Seq(offer -> 1), Seq(refund), expiry - 1))
        val keptToken = if (kind == TokenBond) Seq(refund.setTokens(Token(SigUSD, tokenCapital - 1L))) else Nil
        (Seq(refund.subValue(1L), refund.setContract(otherTree(ctx)), refund.withRegNum(4, bytesValue(originOrderId)),
          refund.setRegs()) ++ keptToken).foreach { bad =>
          rejectsAtSigning(executor(ctx), refundTx(ctx, Seq(offer -> 1), Seq(bad), expiry))
        }
      }
      // Two offers in one transaction, each to its own tagged output; one output cannot serve both.
      val a = offerBox(ctx, ErgBond, Terms(expiry = expiry))
      val b = offerBox(ctx, ErgBond, Terms(expiry = expiry)).addValue(Parameters.OneErg)
      val ra = tagged(lenderTree(ctx), a.value - batchingFee, inputAt(a, ctx, 1).id)
      val rb = tagged(lenderTree(ctx), b.value - batchingFee, inputAt(b, ctx, 2).id)
      accepts(executor(ctx), refundTx(ctx, Seq(a -> 1, b -> 2), Seq(ra, rb), expiry))
      rejectsAtSigning(executor(ctx), refundTx(ctx, Seq(a -> 1, b -> 2), Seq(rb), expiry, indices = Seq(0, 0)))
      // A fill at expiry is refused: the box can only be refunded.
      val f = flow(ctx, ErgBond)
      rejectsAtSigning(executor(ctx), newBondTx(f, offerBox(ctx, ErgBond, Terms(expiry = f.height)), height = f.height))
    }
  }

  property("cancel: the owner takes an offer back at any height and nobody else can") {
    withCtx { ctx =>
      val offer = offerBox(ctx, TokenBond)
      def cancel(by: ErgoProver): UnsignedTransaction =
        build(ctx, Seq(inputAt(offer, ctx, 1), funding(ctx, by, 9)),
          Seq(UTXO(contractOf(by), offer.value, offer.tokens)), by.getAddress)
      accepts(lender(ctx), cancel(lender(ctx)))
      rejectsAtSigning(executor(ctx), cancel(executor(ctx)))
    }
  }
}
