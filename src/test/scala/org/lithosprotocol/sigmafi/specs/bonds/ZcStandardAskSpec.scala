package org.lithosprotocol.sigmafi
package specs.bonds

import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import org.lithosprotocol.sigmafi.mutations.{Contract, InputUTXO, Token, UTXO}

/**
 * ZC_Standard_SaleOrder, ZC_Standard_PricedAsk with ZC_Standard_YieldAsk_Pricing, and the shared
 * templates of every contract compiled per asset. The seller is `other`; buyers are the executor.
 */
class ZcStandardAskSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  /** Nothing in either ask verifies the token for sale; a buyer does. */
  private val forSale: ErgoId = ErgoId.create("5a" * 32)
  private val kinds: Seq[Kind] = Seq(ErgBond, TokenBond)

  private def sellerHash(ctx: BlockchainContext): ErgoValue[_] = bytesValue(hashOf(otherTree(ctx)))

  /** The buyer's funds: ERG only, plus SigUSD when the ask is paid in SigUSD. */
  private def buyer(ctx: BlockchainContext, kind: Kind): InputUTXO =
    funding(ctx, executor(ctx), 9, slack, if (kind == ErgBond) Seq.empty[Token] else Seq(Token(SigUSD, 1000000L)))

  /** The ask convention's payout: at the seller script, R4 the ask's id, R5 the units it pays for. */
  private def payout(ctx: BlockchainContext, kind: Kind, askId: ErgoId, units: Long, paid: Long): UTXO = {
    val regs = Seq(bytesValue(askId.getBytes), ErgoValue.of(units))
    if (kind == ErgBond) UTXO(otherTree(ctx), paid, Seq.empty[Token], regs)
    else UTXO(otherTree(ctx), minBox, Seq(Token(SigUSD, paid)), regs)
  }

  private def askFillTx(ctx: BlockchainContext, kind: Kind, askIn: InputUTXO, outs: Seq[UTXO], height: Int,
                     data: Seq[InputUTXO] = Seq.empty): UnsignedTransaction =
    buildAt(ctx, height, Seq(askIn, buyer(ctx, kind)), outs, executor(ctx).getAddress, dataInputs = data)

  // ═══ TEMPLATES ════════════════════════════════════════════════════════════

  property("exchange contracts compile at the harness version, with one template per contract across assets") {
    withCtx { ctx =>
      val e = exchange(ctx)
      e.dutchVault.ergoTree.version shouldBe 3
      Seq(e.erg, e.token).foreach { m =>
        Seq(m.offer, m.saleOrder, m.pricedAsk, m.yieldPricing, m.policy, m.dutchMint, m.lotAuction).foreach(_.ergoTree.version shouldBe 3)
      }
      // An indexed node lists every market of a contract by template hash. The expressions run through
      // executeFromVar are not trees anyone lists, so only the box scripts are checked.
      Seq(e.erg.offer -> e.token.offer, e.erg.saleOrder -> e.token.saleOrder, e.erg.pricedAsk -> e.token.pricedAsk,
        e.erg.dutchMint -> e.token.dutchMint, e.erg.lotAuction -> e.token.lotAuction).foreach { case (erg, token) =>
        erg.ergoTree.bytes should not be token.ergoTree.bytes
        erg.ergoTree.template shouldBe token.ergoTree.template
        erg.ergoTree.constants.size shouldBe token.ergoTree.constants.size
      }
    }
  }

  // ═══ SALE ORDER ═══════════════════════════════════════════════════════════

  /** A price per raw unit that keeps every ERG payout above the box minimum. */
  private def priceOf(kind: Kind): (Long, Long) = if (kind == ErgBond) (1000003L, 2L) else (7L, 3L)

  private def saleBox(ctx: BlockchainContext, kind: Kind, amount: Long = 10L, minFill: Long = 1L,
                      expiry: Long = -1L, value: Long = minBox): UTXO = {
    val (num, den) = priceOf(kind)
    UTXO(marketOf(ctx, kind).saleOrder, value, Seq(Token(forSale, amount)),
      Seq(sellerHash(ctx), longsValue(num, den, minFill, if (expiry < 0L) ctx.getHeight + 100L else expiry)))
  }

  private def owed(kind: Kind, units: Long): Long = {
    val (num, den) = priceOf(kind)
    ceilDiv(BigInt(units) * num, den)
  }

  /** Outputs: 0 the payout, 1 the continuation when something is left. */
  private def saleTx(ctx: BlockchainContext, kind: Kind, ask: UTXO, sold: Long, paid: Long = -1L,
                     pay: UTXO = null, next: UTXO = null, height: Int = -1): UnsignedTransaction = {
    val askIn = inputAt(ask, ctx, 0)
    val left = ask.tokens.head.amount - sold
    val payOut = if (pay == null) payout(ctx, kind, askIn.id, sold, if (paid < 0L) owed(kind, sold) else paid) else pay
    val nextOut = if (next != null) next else if (left > 0L) ask.setTokens(Token(forSale, left))
      .withRegNum(6, bytesValue(askIn.id.getBytes)) else null
    val in = if (nextOut == null) askIn.withCtxVar(0.toByte, ErgoValue.of(0))
      else askIn.withCtxVar(0.toByte, ErgoValue.of(0)).withCtxVar(1.toByte, ErgoValue.of(1))
    askFillTx(ctx, kind, in, if (nextOut == null) Seq(payOut) else Seq(payOut, nextOut),
      if (height < 0) ctx.getHeight + 10 else height)
  }

  for (kind <- kinds) {
    property(s"sale order, $kind: fills pay at least ceil(units * numerator / denominator)") {
      withCtx { ctx =>
        val ask = saleBox(ctx, kind)
        Seq(1L, 3L, 4L, 10L).foreach { sold =>
          accepts(executor(ctx), saleTx(ctx, kind, ask, sold))
          rejectsAtSigning(executor(ctx), saleTx(ctx, kind, ask, sold, paid = owed(kind, sold) - 1L))
        }
        // Paying more is the buyer's choice and reaches the seller.
        accepts(executor(ctx), saleTx(ctx, kind, ask, 3L, paid = owed(kind, 3L) + 5L))
      }
    }
  }

  property("sale order: the payout names the ask, states the units sold and goes to the seller") {
    withCtx { ctx =>
      kinds.foreach { kind =>
        val ask = saleBox(ctx, kind)
        val id = inputAt(ask, ctx, 0).id
        val good = payout(ctx, kind, id, 3L, owed(kind, 3L))
        accepts(executor(ctx), saleTx(ctx, kind, ask, 3L, pay = good))
        Seq(good.withRegNum(5, ErgoValue.of(2L)), good.withRegNum(5, ErgoValue.of(4L)),
          good.withRegNum(4, bytesValue(originOrderId)), good.setContract(lenderTree(ctx)),
          good.setRegs(bytesValue(id.getBytes))).foreach { bad =>
          rejectsAtSigning(executor(ctx), saleTx(ctx, kind, ask, 3L, pay = bad))
        }
      }
      // A SigUSD ask is paid in SigUSD, not in the box's nanoERG.
      val ask = saleBox(ctx, TokenBond)
      val id = inputAt(ask, ctx, 0).id
      rejectsAtSigning(executor(ctx), saleTx(ctx, TokenBond, ask, 3L,
        pay = payout(ctx, ErgBond, id, 3L, Parameters.OneErg)))
    }
  }

  property("sale order: a continuation keeps script, terms and value, and a fill meets the minimum unless it takes the rest") {
    withCtx { ctx =>
      val kind = ErgBond
      val ask = saleBox(ctx, kind, minFill = 3L)
      accepts(executor(ctx), saleTx(ctx, kind, ask, 3L))
      rejectsAtSigning(executor(ctx), saleTx(ctx, kind, ask, 2L))
      val last = saleBox(ctx, kind, amount = 2L, minFill = 3L)
      accepts(executor(ctx), saleTx(ctx, kind, last, 2L))

      val next = ask.setTokens(Token(forSale, 7L)).withRegNum(6, bytesValue(inputAt(ask, ctx, 0).id.getBytes))
      Seq(next.subValue(1L), next.setContract(otherTree(ctx)), next.withRegNum(4, bytesValue(originOrderId)),
        next.withRegNum(5, longsValue(1000003L, 2L, 1L, ctx.getHeight + 100L))).foreach { bad =>
        rejectsAtSigning(executor(ctx), saleTx(ctx, kind, ask, 3L, next = bad))
      }
      // Selling nothing is not a fill.
      rejectsAtSigning(executor(ctx), saleTx(ctx, kind, ask, 0L, next = ask, pay = payout(ctx, kind,
        inputAt(ask, ctx, 0).id, 0L, minBox)))
    }
  }

  // ═══ YIELD ASK ════════════════════════════════════════════════════════════

  /** A yield line: the start yield up to `start`, the end yield from `end`, a straight line between. */
  private case class Line(start: Long, y0: Long, end: Long, y1: Long) {
    def longs: Seq[Long] = Seq(start, y0, end, y1)
    /** The yield at `h`, exactly rate / span bps. */
    def at(h: Long): (BigInt, BigInt) = {
      val c = math.min(math.max(h, start), end)
      (BigInt(y0) * (end - start) + (BigInt(y1) - y0) * (c - start), BigInt(end - start))
    }
  }

  private case class Live(f: Flow, state: InputUTXO, height: Int) {
    def repayment: Long = f.data(0)
    /** The least a fill of `sold` raw units may pay at `h`, from the pricing expression's own inequality. */
    def owed(sold: Long, wholeUnits: Long, line: Line, h: Int = height): Long = {
      val (rate, span) = line.at(h)
      val year = BigInt(2628000000L)
      ceilDiv(BigInt(sold) * repayment * year * span, BigInt(wholeUnits) * (year * span + rate * (f.maturity - h)))
    }
  }

  /** A live bond, filled, and a height 300 blocks into its term. */
  private def live(ctx: BlockchainContext, kind: Kind): Live = {
    val f = flow(ctx, kind)
    Live(f, spendOutput(filled(f), 1), f.height + 300)
  }

  /** Rising from 10% to 30% across the live height, so the yield there is 20%. */
  private def rising(n: Live): Line = Line(n.height - 100L, 1000L, n.height + 100L, 3000L)

  private def yieldBox(n: Live, token: ErgoId, amount: Long, wholeUnits: Long, line: Line,
                       floor: (Long, Long) = (1L, 1000000000L), minFill: Long = 1L, expiry: Long = -1L,
                       value: Long = minBox, pricing: Contract = null): UTXO = {
    val ctx = n.f.ctx
    UTXO(marketOf(ctx, n.f.kind).pricedAsk, value, Seq(Token(token, amount)),
      Seq(sellerHash(ctx),
        longsValue((Seq(floor._1, floor._2, minFill, if (expiry < 0L) n.f.maturity + 100L else expiry, wholeUnits) ++
          line.longs): _*),
        bytesValue((if (pricing == null) marketOf(ctx, n.f.kind).yieldPricing else pricing).hashedValueBytes),
        bytesValue(n.f.asset.getBytes)))
  }

  /** Vars: 0 payout, 1 continuation (when anything is left), 2 the expression, 3 the state data input. */
  private def yieldTx(n: Live, ask: UTXO, sold: Long, paid: Long, next: UTXO = null, state: InputUTXO = null,
                      expression: Contract = null, height: Int = -1): UnsignedTransaction = {
    val ctx = n.f.ctx
    val kind = n.f.kind
    val askIn = inputAt(ask, ctx, 0)
    val left = ask.tokens.head.amount - sold
    val nextOut = if (next != null) next else if (left > 0L) ask.setTokens(Token(ask.tokens.head.id, left))
      .withRegNum(8, bytesValue(askIn.id.getBytes)) else null
    val base = askIn.withCtxVar(0.toByte, ErgoValue.of(0))
      .withCtxVar(2.toByte, liqVar(if (expression == null) marketOf(ctx, kind).yieldPricing else expression))
      .withCtxVar(3.toByte, ErgoValue.of(0))
    val in = if (nextOut == null) base else base.withCtxVar(1.toByte, ErgoValue.of(1))
    val pay = payout(ctx, kind, askIn.id, sold, paid)
    askFillTx(ctx, kind, in, if (nextOut == null) Seq(pay) else Seq(pay, nextOut),
      if (height < 0) n.height else height, Seq(if (state == null) n.state else state))
  }

  for (kind <- kinds) {
    property(s"yield ask, $kind: a bond unit and shares are priced at the yield of the line, to one raw unit") {
      withCtx { ctx =>
        val n = live(ctx, kind)
        val flat = Line(n.height - 100L, 2000L, n.height + 100L, 2000L)
        val falling = Line(n.height - 100L, 3000L, n.height + 100L, 1000L)
        Seq(flat, rising(n), falling).foreach { line =>
          val unit = yieldBox(n, n.f.asset, 1L, 1L, line)
          accepts(executor(ctx), yieldTx(n, unit, 1L, n.owed(1L, 1L, line)))
          rejectsAtSigning(executor(ctx), yieldTx(n, unit, 1L, n.owed(1L, 1L, line) - 1L))
          // 7 of 1000 shares, leaving the rest for sale.
          val shares = yieldBox(n, forSale, 1000L, 1000L, line)
          accepts(executor(ctx), yieldTx(n, shares, 7L, n.owed(7L, 1000L, line)))
          rejectsAtSigning(executor(ctx), yieldTx(n, shares, 7L, n.owed(7L, 1000L, line) - 1L))
        }
        // Before the start the start yield holds; after the end, the end yield.
        val line = rising(n)
        val unit = yieldBox(n, n.f.asset, 1L, 1L, line)
        Seq(n.height - 150, n.height + 150).foreach { h =>
          accepts(executor(ctx), yieldTx(n, unit, 1L, n.owed(1L, 1L, line, h), height = h))
          rejectsAtSigning(executor(ctx), yieldTx(n, unit, 1L, n.owed(1L, 1L, line, h) - 1L, height = h))
        }
        // A falling yield raises the price: a payment for one height is short at a later one. The fall is steep so
        // that it moves a small SigUSD price by more than one raw unit.
        val down = Line(n.height - 100L, 1000000L, n.height + 100L, 0L)
        n.owed(1L, 1L, down, n.height + 50) should be > n.owed(1L, 1L, down)
        rejectsAtSigning(executor(ctx), yieldTx(n, yieldBox(n, n.f.asset, 1L, 1L, down), 1L, n.owed(1L, 1L, down),
          height = n.height + 50))
      }
    }
  }

  property("yield ask: it fills only against the live bond, before maturity, with valid yields") {
    withCtx { ctx =>
      val n = live(ctx, ErgBond)
      val unit = yieldBox(n, n.f.asset, 1L, 1L, rising(n))
      val price = n.owed(1L, 1L, rising(n))
      accepts(executor(ctx), yieldTx(n, unit, 1L, price))
      val bond = n.state.toUTXO
      val collateral = bond.tokens.drop(1)
      Seq(bond.setTokens(Token(otherBondAsset, 2L) +: collateral: _*),
        bond.setTokens(Token(n.f.asset, 1L) +: collateral: _*),
        bond.setContract(otherTree(ctx)),
        bond.setContract(bondOf(ctx, TokenBond))).foreach { fake =>
        rejectsAtSigning(executor(ctx), yieldTx(n, unit, 1L, price, state = inputAt(fake, ctx, 30)))
      }
      // Settled: the 2 units now sit in the vault.
      val vault = spendOutput(accepts(n.f.owner, standardRepayTx(n.f, repayInput(n.state))), 0)
      rejectsAtSigning(executor(ctx), yieldTx(n, unit, 1L, price, state = vault))
      // At maturity the expression stops, whatever is paid.
      rejectsAtSigning(executor(ctx), yieldTx(n, unit, 1L, 2L * price, height = n.f.maturity.toInt))
      // A negative yield, or an end not after the start, never fills.
      Seq(Line(n.height - 100L, -1L, n.height + 100L, 1000L), Line(n.height - 100L, 1000L, n.height + 100L, -1L),
        Line(n.height + 100L, 1000L, n.height + 100L, 1000L), Line(n.height + 100L, 1000L, n.height - 100L, 1000L)).foreach { bad =>
        rejectsAtSigning(executor(ctx), yieldTx(n, yieldBox(n, n.f.asset, 1L, 1L, bad), 1L, price))
      }
    }
  }

  property("yield ask: the fixed floor binds, the expression is pinned, and a continuation keeps R4 to R7") {
    withCtx { ctx =>
      val n = live(ctx, ErgBond)
      val line = rising(n)
      val price = n.owed(7L, 1000L, line)
      // A floor of 2x the yield price: the expression passes but the floor does not.
      val floored = yieldBox(n, forSale, 1000L, 1000L, line, floor = (2L * price, 7L))
      rejectsAtSigning(executor(ctx), yieldTx(n, floored, 7L, price))
      accepts(executor(ctx), yieldTx(n, floored, 7L, 2L * price))

      val ask = yieldBox(n, forSale, 1000L, 1000L, line)
      rejectsAtSigning(executor(ctx), yieldTx(n, ask, 7L, price, expression = alwaysTrue(ctx)))
      val next = ask.setTokens(Token(forSale, 993L)).withRegNum(8, bytesValue(inputAt(ask, ctx, 0).id.getBytes))
      accepts(executor(ctx), yieldTx(n, ask, 7L, price, next = next))
      Seq(next.withRegNum(6, bytesValue(alwaysTrue(ctx).hashedValueBytes)),
        next.withRegNum(7, bytesValue(otherBondAsset.getBytes)),
        next.withRegNum(5, longsValue((Seq(1L, 1000000000L, 1L, n.f.maturity + 100L, 1000L) ++
          Line(n.height - 100L, 9000L, n.height + 100L, 9000L).longs): _*)),
        next.subValue(1L)).foreach { bad =>
        rejectsAtSigning(executor(ctx), yieldTx(n, ask, 7L, price, next = bad))
      }
    }
  }

  // ═══ EXPIRY REFUNDS, BOTH ASKS ════════════════════════════════════════════

  property("asks: fills stop at expiry, and from expiry on anyone refunds the tokens to the seller") {
    withCtx { ctx =>
      val n = live(ctx, ErgBond)
      val expiry = n.height + 20
      val line = rising(n)
      val price = n.owed(1L, 1L, line)
      def yieldAt(value: Long): UTXO = yieldBox(n, n.f.asset, 1L, 1L, line, expiry = expiry, value = value)
      def saleAt(value: Long): UTXO = saleBox(ctx, ErgBond, expiry = expiry, value = value)
      accepts(executor(ctx), yieldTx(n, yieldAt(minBox), 1L, n.owed(1L, 1L, line, expiry - 1), height = expiry - 1))
      rejectsAtSigning(executor(ctx), yieldTx(n, yieldAt(minBox), 1L, 2L * price, height = expiry))
      accepts(executor(ctx), saleTx(ctx, ErgBond, saleAt(minBox), 3L, height = expiry - 1))
      rejectsAtSigning(executor(ctx), saleTx(ctx, ErgBond, saleAt(minBox), 3L, height = expiry))

      def refundTx(ask: UTXO, out: UTXO, height: Int = expiry): UnsignedTransaction =
        buildAt(ctx, height, Seq(inputAt(ask, ctx, 0).withCtxVar(0.toByte, ErgoValue.of(0)),
          funding(ctx, executor(ctx), 9)), Seq(out), executor(ctx).getAddress)
      Seq[Long => UTXO](yieldAt, saleAt).foreach { make =>
        val ask = make(3L * minBox)
        val id = inputAt(ask, ctx, 0).id
        val refund = tagged(otherTree(ctx), minBox, id, ask.tokens)
        accepts(executor(ctx), refundTx(ask, refund))
        rejectsAtSigning(executor(ctx), refundTx(ask, refund, height = expiry - 1))
        Seq(refund.subValue(1L), refund.setContract(lenderTree(ctx)), refund.withRegNum(4, bytesValue(originOrderId)),
          refund.setRegs()).foreach(bad => rejectsAtSigning(executor(ctx), refundTx(ask, bad)))
        // A box holding less than 1000000 nanoERG pays no fee: all of it goes back.
        val small = make(500000L)
        val smallRefund = tagged(otherTree(ctx), 500000L, inputAt(small, ctx, 0).id, small.tokens)
        accepts(executor(ctx), refundTx(small, smallRefund))
        rejectsAtSigning(executor(ctx), refundTx(small, smallRefund.subValue(1L)))
      }
      // Every token goes back: a partial refund leaves the rest with the batcher.
      val many = saleBox(ctx, ErgBond, expiry = expiry, value = 3L * minBox)
      rejectsAtSigning(executor(ctx), refundTx(many, tagged(otherTree(ctx), minBox, inputAt(many, ctx, 0).id,
        Seq(Token(forSale, 9L)))))
    }
  }

  property("asks: the seller cancels at any height and nobody else can") {
    withCtx { ctx =>
      val n = live(ctx, ErgBond)
      Seq(saleBox(ctx, ErgBond), yieldBox(n, n.f.asset, 1L, 1L, rising(n))).foreach { ask =>
        def cancel(by: ErgoProver, height: Int): UnsignedTransaction =
          buildAt(ctx, height, Seq(inputAt(ask, ctx, 0), funding(ctx, by, 9)),
            Seq(UTXO(contractOf(by), ask.value, ask.tokens)), by.getAddress)
        Seq(ctx.getHeight + 10, n.f.maturity.toInt + 200).foreach(h => accepts(other(ctx), cancel(other(ctx), h)))
        rejectsAtSigning(executor(ctx), cancel(executor(ctx), ctx.getHeight + 10))
      }
    }
  }
}
