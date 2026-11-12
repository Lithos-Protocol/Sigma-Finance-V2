package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.app.Protocol
import org.ergoplatform.appkit._
import org.scalatest.propspec.AnyPropSpec
import work.lithos.mutations.{InputUTXO, Token, UTXO}

/** ZC_Standard_Order, ZC_Standard_Bond, ZC_Standard_BondVault and the two liquidation expressions. */
class ZcStandardBondSpec extends AnyPropSpec with ZcStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  property("standard contracts compile at the harness version") {
    withCtx { ctx =>
      val s = scripts(ctx)
      Seq(s.vault, s.ergBond, s.tokenBond, s.ergOrder, s.tokenOrder, s.fractionalVault, s.fractionalMint,
        s.maturity, s.ergOracle, s.tokenOracle)
        .foreach(_.ergoTree.version shouldBe 3)
    }
  }

  property("each repayment asset has its own bond and order address, under one shared template") {
    withCtx { ctx =>
      val s = scripts(ctx)
      // An indexed node lists one market by ErgoTree and every market by template hash. That only
      // holds if the compiler did not fold the repayment-token constant into the tree's shape.
      Seq(s.ergBond -> s.tokenBond, s.ergOrder -> s.tokenOrder).foreach { case (erg, token) =>
        erg.ergoTree.bytes should not be token.ergoTree.bytes
        erg.ergoTree.template shouldBe token.ergoTree.template
        erg.ergoTree.constants.size shouldBe token.ergoTree.constants.size
      }
    }
  }

  // ═══ ORDER ════════════════════════════════════════════════════════════════

  for (kind <- Seq[Kind](ErgBond, TokenBond); style <- Seq[OrderStyle](FixedHeight, OnClose)) {
    val label = s"$style $kind"

    property(s"$label: fill mints 3 units, copies every borrower term and refunds the expiry fee") {
      withCtx { ctx =>
        val f = flow(ctx, kind, style, extra = Seq(15000L, 30L, Long.MaxValue, Long.MinValue, 123L))
        val tx = filled(f)
        val bond = tx.getOutputsToSpend.get(1)
        bond.getRegisters.get(0) shouldBe bytesValue(f.asset.getBytes)
        bond.getRegisters.get(2) shouldBe f.order.registers.head
        bond.getRegisters.get(5) shouldBe longsValue(f.fee, f.start, f.maturity)
        bond.getValue shouldBe f.collateralValue + f.fee
        bond.getTokens.get(0).getValue.longValue shouldBe 2L
        tx.getOutputsToSpend.get(0).getTokens.get(0).getValue.longValue shouldBe 1L
        tx.getOutputsToSpend.get(2).getValue shouldBe f.data(1) + f.expiryFee

        rejectsAtSigning(f.holder, standardFillTx(f, principal = f.principal.subValue(1L)))
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.subValue(1L)))
        rejectsAtSigning(f.holder, standardFillTx(f, unit = f.unit.withRegNum(6, utf8("9"))))
        f.data.indices.foreach { i =>
          val changed = f.data.updated(i, if (f.data(i) == Long.MaxValue) f.data(i) - 1L else f.data(i) + 1L)
          rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.withRegNum(6, longsValue(changed: _*))))
        }
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.withRegNum(6, longsValue(f.data.dropRight(1): _*))))
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.withRegNum(6, longsValue((f.data :+ 99L): _*))))
        val otherKind = if (kind == ErgBond) TokenBond else ErgBond
        // The order names one bond tree, and with it one repayment asset.
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.setContract(bondOf(ctx, otherKind))))
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.withRegNum(4, bytesValue(originOrderId))))
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.withRegNum(5, bytesValue(hashOf(otherTree(ctx))))))
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.withRegNum(7,
          Protocol.tokensValue(Seq(Token(SigUSD, 1000000000L))))))
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.withRegNum(8, bytesValue(alwaysTrue(ctx).hashedValueBytes))))
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.setContract(otherTree(ctx))))
      }
    }

    property(s"$label: the mint is exactly 2 in the bond and 1 circulating") {
      withCtx { ctx =>
        val f = flow(ctx, kind, style)
        def split(unit: Long, reserve: Long): UnsignedTransaction = standardFillTx(f,
          unit = f.unit.setTokens(Token(f.asset, unit)),
          bond = f.bond.setTokens(Token(f.asset, reserve) +: f.collateral: _*))
        accepts(f.holder, split(1L, 2L))
        Seq((2L, 1L), (1L, 3L), (2L, 2L), (1L, 1L)).foreach { case (unit, reserve) =>
          rejectsAtSigning(f.holder, split(unit, reserve))
        }
        // The lender's fee reserve is funded above the collateral, never out of it.
        rejectsAtSigning(f.holder, standardFillTx(f,
          bond = f.bond.withRegNum(9, longsValue(f.fee + 1L, f.start, f.maturity))))
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.setTokens(Token(f.asset, 2L))))
      }
    }

    property(s"$label: expiry boundary stops fills and enables a complete, tagged borrower refund") {
      withCtx { ctx =>
        val f = flow(ctx, kind, style)
        accepts(f.holder, standardFillTx(moveFill(f, f.expiry.toInt - 1)))
        rejectsAtSigning(f.holder, standardFillTx(moveFill(f, f.expiry.toInt)))

        val refundIn = inputAt(f.order, ctx, 0).withCtxVar(0.toByte, ErgoValue.of(0))
        val refund = tagged(borrowerTree(ctx), f.collateralValue, f.asset, f.collateral)
        def expire(out: UTXO = refund, in: InputUTXO = refundIn, height: Int = f.expiry.toInt): UnsignedTransaction =
          buildAt(ctx, height, Seq(in), Seq(out), executor(ctx).getAddress)
        rejectsAtSigning(executor(ctx), expire(height = f.expiry.toInt - 1))
        accepts(executor(ctx), expire())
        rejectsAtSigning(executor(ctx), expire(out = refund.subValue(1L)))
        rejectsAtSigning(executor(ctx), expire(out = refund.setContract(otherTree(ctx))))
        rejectsAtSigning(executor(ctx), expire(out = refund.withRegNum(4, bytesValue(originOrderId))))
        rejectsAtSigning(executor(ctx), expire(out = refund.setTokens()))
        rejectsAtSigning(executor(ctx), expire(in = inputAt(f.order, ctx, 0)))
      }
    }
  }

  property("order: only a borrower co-spend cancels, at any height and even with malformed terms") {
    withCtx { ctx =>
      val f = flow(ctx)
      def cancel(order: UTXO, by: ErgoProver, height: Int): UnsignedTransaction =
        buildAt(ctx, height, Seq(inputAt(order, ctx, 0), funding(ctx, by, 1)),
          Seq(UTXO(contractOf(by), order.value, order.tokens)), by.getAddress)
      Seq(f.height, f.expiry.toInt).foreach(h => accepts(f.owner, cancel(f.order, f.owner, h)))
      rejectsAtSigning(other(ctx), cancel(f.order, other(ctx), f.height))
      accepts(f.owner, cancel(f.order.withRegNum(4, utf8("not Longs")), f.owner, f.height))
    }
  }

  property("order: the start height must lie within 8 blocks of the fill and be the bond's creation height") {
    withCtx { ctx =>
      val f = flow(ctx)
      def startAt(start: Long): UnsignedTransaction = standardFillTx(f, bond = startingAt(f, start))
      accepts(f.holder, startAt(f.height.toLong))
      accepts(f.holder, startAt(f.height - 8L))
      rejectsAtSigning(f.holder, startAt(f.height - 9L))
      rejectsAtSigning(f.holder, startAt(f.height + 1L))
      // The bond counts its recollateralize wait from its creation height: a later stamp would lock
      // the borrower out, an earlier one would let the first wait be skipped.
      Seq(f.height - 1, f.height + 1, f.height + 1000, 0).foreach { stamp =>
        rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.setCreationHeight(stamp)))
      }
    }
  }

  property("order: fixed maturity is the borrower's height and leaves a full block to repay") {
    withCtx { ctx =>
      val f = flow(ctx)
      rejectsAtSigning(f.holder, standardFillTx(f,
        bond = f.bond.withRegNum(9, longsValue(f.fee, f.start, f.maturity - 1L))))
      val height = ctx.getHeight + 10
      accepts(lender(ctx), standardFillTx(flow(ctx, fixedMaturity = height + 2L)))
      val tight = flow(ctx, fixedMaturity = height + 1L)
      rejectsAtSigning(tight.holder, standardFillTx(tight))
    }
  }

  property("order: on-close maturity is start plus a duration of at least 30 blocks") {
    withCtx { ctx =>
      val f = flow(ctx, style = OnClose, duration = 30L)
      accepts(f.holder, standardFillTx(f))
      val early = f.height - 8L
      accepts(f.holder, standardFillTx(f, bond = startingAt(f, early, early + 30L)))
      // A start in the past cannot keep the later maturity: the two move together.
      rejectsAtSigning(f.holder, standardFillTx(f, bond = startingAt(f, early, f.maturity)))
      rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.withRegNum(9, longsValue(f.fee, f.start, f.maturity + 1L))))
      val short = flow(ctx, style = OnClose, duration = 29L)
      rejectsAtSigning(short.holder, standardFillTx(short))
      val overflow = flow(ctx, style = OnClose, duration = Long.MaxValue)
      rejectsAtSigning(overflow.holder, standardFillTx(overflow))
    }
  }

  property("order: expiry fee minimum, funded collateral and payload bounds") {
    withCtx { ctx =>
      val low = flow(ctx, expiryFee = 1999999L)
      rejectsAtSigning(low.holder, standardFillTx(low))
      val underfunded = flow(ctx, collateralValue = minBox - 1L)
      rejectsAtSigning(underfunded.holder, standardFillTx(underfunded))
      val max = flow(ctx, extra = Seq.fill(57)(17L))
      accepts(max.holder, standardFillTx(max))
      val over = flow(ctx, extra = Seq.fill(58)(17L))
      rejectsAtSigning(over.holder, standardFillTx(over))
    }
  }

  // ═══ ONE ASSET ════════════════════════════════════════════════════════════

  property("one asset: an ERG order lends nanoERG alone, with a floor that never binds") {
    withCtx { ctx =>
      Seq(flow(ctx, ErgBond), flow(ctx, ErgBond, ergFloor = repaymentErg - 1L), flow(ctx, ErgBond, principalValue = 1L))
        .foreach(f => accepts(f.holder, standardFillTx(f)))
      Seq(
        flow(ctx, ErgBond, ergFloor = repaymentErg + 1L),
        flow(ctx, ErgBond, principalAssets = Seq(Token(SigUSD, 600L))),
        flow(ctx, ErgBond, principalAssets = Seq(Token(stableToken, 1L))),
        flow(ctx, ErgBond, principalValue = 0L)
      ).foreach(f => rejectsAtSigning(f.holder, standardFillTx(f)))
    }
  }

  property("one asset: a token order lends one repayment token, with at most a box minimum beside it") {
    withCtx { ctx =>
      Seq(flow(ctx, TokenBond), flow(ctx, TokenBond, principalValue = 0L), flow(ctx, TokenBond, ergFloor = 1L),
        flow(ctx, TokenBond, principalAssets = Seq(Token(SigUSD, 1L))))
        .foreach(f => accepts(f.holder, standardFillTx(f)))
      // A principal token of amount 0 cannot be built into a box at all, so it is not listed here.
      Seq(
        flow(ctx, TokenBond, principalValue = minBox + 1L),
        flow(ctx, TokenBond, ergFloor = minBox + 1L),
        flow(ctx, TokenBond, principalAssets = Seq.empty[Token]),
        flow(ctx, TokenBond, principalAssets = Seq(Token(stableToken, 600L))),
        flow(ctx, TokenBond, principalAssets = Seq(Token(SigUSD, 600L), Token(stableToken, 1L))),
        flow(ctx, TokenBond, principalAssets = Seq(Token(stableToken, 1L), Token(SigUSD, 600L)))
      ).foreach(f => rejectsAtSigning(f.holder, standardFillTx(f)))
    }
  }

  property("one asset: an order that breaks the rule can still be cancelled or refunded after expiry") {
    withCtx { ctx =>
      val f = flow(ctx, TokenBond, principalValue = 2L * Parameters.OneErg)
      rejectsAtSigning(f.holder, standardFillTx(f))
      accepts(f.owner, buildAt(ctx, f.height, Seq(f.orderIn, funding(ctx, f.owner, 1)),
        Seq(UTXO(borrowerTree(ctx), f.order.value, f.order.tokens)), f.owner.getAddress))
      accepts(executor(ctx), buildAt(ctx, f.expiry.toInt, Seq(inputAt(f.order, ctx, 0).withCtxVar(0.toByte, ErgoValue.of(0))),
        Seq(tagged(borrowerTree(ctx), f.collateralValue, f.asset, f.collateral)), executor(ctx).getAddress))
    }
  }

  // ═══ REPAY AND CLAIM ══════════════════════════════════════════════════════

  for (kind <- Seq[Kind](ErgBond, TokenBond); style <- Seq[OrderStyle](FixedHeight, OnClose)) {
    val label = s"$style $kind"

    property(s"$label: repayment moves the reserve to the vault and the holder claims by burning all 3 units") {
      withCtx { ctx =>
        val f = flow(ctx, kind, style)
        val fill = filled(f)
        val bond = repayInput(spendOutput(fill, 1))
        val repaid = accepts(f.owner, standardRepayTx(f, bond))
        val vault = repaid.getOutputsToSpend.get(0)
        vault.getTokens.get(0).getId shouldBe f.asset
        vault.getTokens.get(0).getValue.longValue shouldBe 2L
        vault.getRegisters.get(0) shouldBe bytesValue(bond.id.getBytes)
        vault.getRegisters.get(1) shouldBe ErgoValue.of(0.toByte)
        repaid.getOutputsToSpend.get(1).getValue shouldBe f.collateralValue

        val vaultIn = spendOutput(repaid, 0)
        val unit = spendOutput(fill, 0)
        accepts(f.holder, standardClaimTx(f, vaultIn, unit))
        // Some of the asset surviving, or the circulating unit missing, both fail.
        rejectsAtSigning(f.holder, standardClaimTx(f, vaultIn, unit, burn = 2L))
        rejects(f.holder, build(ctx, Seq(vaultIn, funding(ctx, f.holder, 8)),
          Seq(UTXO(lenderTree(ctx), vaultIn.value, vaultIn.tokens.drop(1))), f.holder.getAddress,
          burn = Seq(Token(f.asset, 2L))))
      }
    }

    property(s"$label: repayment needs the full amount, the reserve, both tags and a height before maturity") {
      withCtx { ctx =>
        val f = flow(ctx, kind, style)
        val bond = repayInput(spendOutput(filled(f), 1))
        val settle = repaidBox(f, bond)
        val returned = returnedBox(f, bond)
        accepts(f.owner, standardRepayTx(f, bond))
        rejectsAtSigning(f.owner, standardRepayTx(f, bond, height = f.maturity.toInt))
        val short = if (kind == ErgBond) settle.subValue(1L)
          else settle.setTokens(Token(f.asset, 2L), Token(SigUSD, f.data(0) - 1L))
        val bad = Seq(short, settle.setContract(otherTree(ctx)),
          settle.withRegNum(4, bytesValue(originOrderId)), settle.withRegNum(5, ErgoValue.of(1.toByte)),
          settle.setTokens(Token(f.asset, 1L) +: settle.tokens.drop(1): _*),
          settle.setTokens(settle.tokens :+ Token(secondCollateral, 1L): _*))
        bad.foreach(out => rejectsAtSigning(f.owner, standardRepayTx(f, bond, settle = out)))
        // A third party repays: only the borrower's co-spend lets the return keep anything back.
        accepts(other(ctx), standardRepayTx(f, bond, payer = other(ctx)))
        Seq(returned.subValue(1L), returned.setContract(otherTree(ctx)),
          returned.withRegNum(4, bytesValue(originOrderId)), returned.setTokens()).foreach { out =>
          rejectsAtSigning(other(ctx), standardRepayTx(f, bond, returned = out, payer = other(ctx)))
        }
      }
    }
  }

  property("vault: one transaction sweeps several settled bonds, each by its own unit") {
    withCtx { ctx =>
      val a = flow(ctx, kind = ErgBond)
      val b = flow(ctx, kind = ErgBond, collateralValue = collateralErg + 1L)
      val settled = Seq(a, b).map { f =>
        val fill = filled(f)
        (f, spendOutput(accepts(f.owner, standardRepayTx(f, repayInput(spendOutput(fill, 1)))), 0), spendOutput(fill, 0))
      }
      val inputs = settled.flatMap { case (_, vault, unit) => Seq(vault, unit) } :+ funding(ctx, lender(ctx), 8)
      val total = settled.map(_._2.value).sum
      def sweep(burn: Seq[Token]): UnsignedTransaction =
        build(ctx, inputs, Seq(UTXO(lenderTree(ctx), total)), lender(ctx).getAddress, burn = burn)
      accepts(lender(ctx), sweep(Seq(Token(a.asset, 3L), Token(b.asset, 3L))))
      // One bond's unit cannot open the other bond's vault.
      rejects(lender(ctx), build(ctx, Seq(settled.head._2, settled(1)._3, funding(ctx, lender(ctx), 8)),
        Seq(UTXO(lenderTree(ctx), settled.head._2.value)), lender(ctx).getAddress,
        burn = Seq(Token(b.asset, 1L), Token(a.asset, 2L))))
    }
  }

  // ═══ RECOLLATERALIZE ══════════════════════════════════════════════════════

  property("recollateralize: the borrower adds ERG or a held token once the bond has rested 60 blocks") {
    withCtx { ctx =>
      val f = flow(ctx)
      val bond = spendOutput(filled(f), 1)
      val same = bond.toUTXO
      val open = f.height + recollateralizeWait
      def tx(out: UTXO, by: ErgoProver = f.owner, height: Int = open, stamp: Int = -1): UnsignedTransaction =
        recollateralizeTx(f, bond, out, height, by, stamp, Seq(Token(collateralToken, 50L)))
      val moreTokens = same.setTokens(Token(f.asset, 2L), Token(collateralToken, 101L))

      // One unit of anything is enough: the wait, not the amount, stops a borrower dodging a liquidation.
      accepts(f.owner, tx(same.addValue(1L)))
      accepts(f.owner, tx(moreTokens))
      rejectsAtSigning(f.owner, tx(same))
      // Open to nobody else: a stranger could otherwise re-spend the bond and void pending repayments.
      rejectsAtSigning(other(ctx), tx(same.addValue(1L), by = other(ctx)))
      rejectsAtSigning(f.owner, tx(same.addValue(1L), height = open - 1))
      rejectsAtSigning(f.owner, tx(same.addValue(1L), height = f.maturity.toInt))
      // The successor's stamp restarts the wait: at most 15 blocks old, and never ahead of inclusion.
      accepts(f.owner, tx(same.addValue(1L), stamp = open - stampSlack))
      rejectsAtSigning(f.owner, tx(same.addValue(1L), stamp = open - stampSlack - 1))
      rejectsAtSigning(f.owner, tx(same.addValue(1L), stamp = open + 1))
      val changed = Seq(
        same.withRegNum(9, longsValue(f.fee, f.start, f.maturity + 1L)),
        same.withRegNum(9, longsValue(0L, f.start, f.maturity)),
        same.withRegNum(6, longsValue(f.data.updated(0, 1L): _*)),
        same.withRegNum(5, bytesValue(hashOf(otherTree(ctx)))),
        same.withRegNum(8, bytesValue(alwaysTrue(ctx).hashedValueBytes)),
        same.setContract(otherTree(ctx)))
      changed.foreach(out => rejectsAtSigning(f.owner, tx(out.addValue(1L))))
      // More ERG cannot pay for taking a collateral token out.
      rejectsAtSigning(f.owner, tx(same.addValue(1L).setTokens(Token(f.asset, 2L), Token(collateralToken, 99L))))
    }
  }

  property("recollateralize: moves land at least 45 blocks apart, 60 after a fresh stamp") {
    withCtx { ctx =>
      val f = flow(ctx, kind = ErgBond)
      val bond = spendOutput(filled(f), 1)
      val first = f.height + recollateralizeWait
      def moved(stamp: Int): InputUTXO =
        spendOutput(accepts(f.owner, recollateralizeTx(f, bond, bond.toUTXO.addValue(1L), first, stamp = stamp)), 0)
      def again(from: InputUTXO, height: Int): UnsignedTransaction =
        recollateralizeTx(f, from, from.toUTXO.addValue(1L), height)
      // The oldest stamp allowed opens the next move 45 blocks after this one lands.
      val stale = moved(first - stampSlack)
      accepts(f.owner, again(stale, first + 45))
      rejectsAtSigning(f.owner, again(stale, first + 44))
      val fresh = moved(first)
      accepts(f.owner, again(fresh, first + 60))
      rejectsAtSigning(f.owner, again(fresh, first + 59))
    }
  }

  property("recollateralize: a bond maturing within 60 blocks of its start never can") {
    withCtx { ctx =>
      Seq(60L -> false, 61L -> true).foreach { case (duration, possible) =>
        val f = flow(ctx, kind = ErgBond, style = OnClose, duration = duration)
        val bond = spendOutput(filled(f), 1)
        def at(height: Int): UnsignedTransaction = recollateralizeTx(f, bond, bond.toUTXO.addValue(1L), height)
        rejectsAtSigning(f.owner, at(f.height + recollateralizeWait - 1))
        if (possible) accepts(f.owner, at(f.height + recollateralizeWait))
        else rejectsAtSigning(f.owner, at(f.height + recollateralizeWait))
      }
    }
  }

  property("recollateralize: a token bond may take on its repayment token, appended last, and nothing else new") {
    withCtx { ctx =>
      val f = flow(ctx, TokenBond)
      val bond = spendOutput(filled(f), 1)
      val same = bond.toUTXO
      val open = f.height + recollateralizeWait
      val funds = Seq(Token(SigUSD, 1000L), Token(stableToken, 10L))
      def tx(out: UTXO): UnsignedTransaction = recollateralizeTx(f, bond, out, open, funds = funds)
      accepts(f.owner, tx(same.setTokens(same.tokens :+ Token(SigUSD, 1L): _*)))
      accepts(f.owner, tx(same.setTokens(same.tokens :+ Token(SigUSD, 1000L): _*).addValue(1L)))
      Seq(
        same.setTokens(same.tokens :+ Token(stableToken, 1L): _*),
        same.setTokens(Token(f.asset, 2L), Token(SigUSD, 1L), Token(collateralToken, 100L)),
        same.setTokens(same.tokens ++ Seq(Token(SigUSD, 1L), Token(stableToken, 1L)): _*)
      ).foreach(out => rejectsAtSigning(f.owner, tx(out)))

      // An ERG bond's repayment asset is ERG, which it always holds: it takes on no token at all.
      val e = flow(ctx, ErgBond)
      val ergBond = spendOutput(filled(e), 1)
      rejectsAtSigning(e.owner, recollateralizeTx(e, ergBond,
        ergBond.toUTXO.setTokens(ergBond.tokens :+ Token(SigUSD, 1L): _*), e.height + recollateralizeWait, funds = funds))
      // A bond that already holds its repayment token grows that entry instead.
      val held = flow(ctx, TokenBond, collateral = Seq(Token(SigUSD, 100L)))
      val heldBond = spendOutput(filled(held), 1)
      // A second entry of the held token cannot be tested here: the signer refuses the duplicate id
      // before the script runs, so the bond's own check against it is defensive only.
      accepts(held.owner, recollateralizeTx(held, heldBond,
        heldBond.toUTXO.setTokens(Token(held.asset, 2L), Token(SigUSD, 101L)), held.height + recollateralizeWait,
        funds = funds))
    }
  }

  property("recollateralize: the new bond box repays and returns the larger collateral") {
    withCtx { ctx =>
      val f = flow(ctx, kind = ErgBond)
      val bond = spendOutput(filled(f), 1)
      val added = f.collateralValue / 2L
      val grown = accepts(f.owner, recollateralizeTx(f, bond, bond.toUTXO.addValue(added), f.height + recollateralizeWait))
      val next = repayInput(spendOutput(grown, 0))
      next.id should not be bond.id
      val repaid = accepts(f.owner, standardRepayTx(f, next))
      repaid.getOutputsToSpend.get(1).getValue shouldBe f.collateralValue + added
      repaid.getOutputsToSpend.get(0).getRegisters.get(0) shouldBe bytesValue(next.id.getBytes)
    }
  }

  property("recollateralize: a token bond appends its repayment token, then repays from it alone") {
    withCtx { ctx =>
      val f = flow(ctx, TokenBond)
      val bond = spendOutput(filled(f), 1)
      val owed = Token(SigUSD, f.data(0))
      val grown = accepts(f.owner, recollateralizeTx(f, bond, bond.toUTXO.setTokens(bond.tokens :+ owed: _*),
        f.height + recollateralizeWait, funds = Seq(owed)))
      val next = repayInput(spendOutput(grown, 0))
      // The borrower brings no SigUSD: the appended collateral pays the vault, the rest comes back.
      val repaid = accepts(f.owner, standardRepayTx(f, next,
        returned = returnedBox(f, next).setTokens(f.collateral: _*), funds = Seq.empty))
      repaid.getOutputsToSpend.get(0).getTokens.get(1).getValue.longValue shouldBe f.data(0)
    }
  }

  // ═══ REPAY FROM COLLATERAL ════════════════════════════════════════════════

  property("repay from collateral: an ERG bond's borrower keeps back up to the repayment in nanoERG") {
    withCtx { ctx =>
      val f = flow(ctx, ErgBond)
      val bond = repayInput(spendOutput(filled(f), 1))
      val netted = returnedBox(f, bond).subValue(f.data(0))
      // The vault is paid exactly as before; the borrower's own box only pays the miner.
      val repaid = accepts(f.owner, standardRepayTx(f, bond, returned = netted))
      repaid.getOutputsToSpend.get(0).getValue shouldBe repaymentValue(f)
      rejectsAtSigning(f.owner, standardRepayTx(f, bond, returned = netted.subValue(1L)))
      rejectsAtSigning(f.owner, standardRepayTx(f, bond, settle = repaidBox(f, bond).subValue(1L), returned = netted))
      // Only the borrower: a third party who repays returns every nanoERG.
      rejectsAtSigning(other(ctx), standardRepayTx(f, bond, returned = netted, payer = other(ctx)))
    }
  }

  property("repay from collateral: a token bond's borrower keeps back up to the repayment in its token") {
    withCtx { ctx =>
      // 1000 SigUSD of collateral against a 700 repayment: 700 pay the vault and 300 come back.
      val f = flow(ctx, TokenBond, collateral = Seq(Token(collateralToken, 100L), Token(SigUSD, 1000L)))
      val bond = repayInput(spendOutput(filled(f), 1))
      val returned = returnedBox(f, bond)
      val netted = returned.setTokens(Token(collateralToken, 100L), Token(SigUSD, 300L))
      def repay(out: UTXO, payer: ErgoProver = f.owner): UnsignedTransaction =
        standardRepayTx(f, bond, returned = out, payer = payer, funds = Seq.empty)
      accepts(f.owner, repay(netted))
      Seq(
        returned.setTokens(Token(collateralToken, 100L), Token(SigUSD, 299L)),
        // Only the repayment token, never another collateral token or the collateral ERG.
        returned.setTokens(Token(collateralToken, 99L), Token(SigUSD, 300L)),
        netted.subValue(1L)
      ).foreach(out => rejectsAtSigning(f.owner, repay(out)))
      rejectsAtSigning(other(ctx), repay(netted, payer = other(ctx)))

      // Less held than owed: the token leaves the return entirely and the borrower brings the rest.
      val short = flow(ctx, TokenBond, collateral = Seq(Token(collateralToken, 100L), Token(SigUSD, 400L)))
      val shortBond = repayInput(spendOutput(filled(short), 1))
      accepts(short.owner, standardRepayTx(short, shortBond,
        returned = returnedBox(short, shortBond).setTokens(Token(collateralToken, 100L)),
        funds = Seq(Token(SigUSD, 300L))))
    }
  }

  // ═══ LIQUIDATION ══════════════════════════════════════════════════════════

  for (kind <- Seq[Kind](ErgBond, TokenBond)) {
    property(s"$kind: maturity liquidation carries the reserve and all collateral to the vault") {
      withCtx { ctx =>
        val f = flow(ctx, kind)
        val fill = filled(f)
        val bond = liquidationInput(f, spendOutput(fill, 1))
        rejectsAtSigning(executor(ctx), standardLiquidationTx(f, bond, height = f.maturity.toInt - 1))
        val settled = accepts(executor(ctx), standardLiquidationTx(f, bond))
        val vault = settled.getOutputsToSpend.get(0)
        vault.getTokens.get(0).getValue.longValue shouldBe 2L
        vault.getRegisters.get(1) shouldBe ErgoValue.of(1.toByte)
        vault.getValue shouldBe f.collateralValue
        accepts(f.holder, standardClaimTx(f, spendOutput(settled, 0), spendOutput(fill, 0)))

        val out = proceedsBox(f, bond)
        val bad = Seq(out.subValue(1L), out.setTokens(Token(f.asset, 2L)), out.setContract(otherTree(ctx)),
          out.withRegNum(4, bytesValue(originOrderId)), out.withRegNum(5, ErgoValue.of(0.toByte)))
        bad.foreach(o => rejectsAtSigning(executor(ctx), standardLiquidationTx(f, bond, out = o)))
        // An expression the borrower never agreed to, however permissive, is not run.
        rejectsAtSigning(executor(ctx), standardLiquidationTx(f, bond.setCtxVars(
          ContextVar.of(0.toByte, liqVar(alwaysTrue(ctx))), ContextVar.of(1.toByte, ErgoValue.of(0)))))
      }
    }
  }

  private def oracleFlow(ctx: BlockchainContext, collateralValue: Long, collateral: Seq[Token] = Seq.empty,
                         principalValue: Long = 0L, principalAssets: Seq[Token] = Seq(Token(SigUSD, 800L)),
                         kind: Kind = TokenBond, repayment: Long = 800L,
                         extra: Seq[Long] = Seq(15000L, 30L)): Flow =
    flow(ctx, kind, collateralValue = collateralValue, collateral = collateral,
      principalValue = principalValue, principalAssets = principalAssets, repayment = repayment,
      extra = extra, liq = oracleRuleOf(ctx, kind))

  private def oracleBond(f: Flow): InputUTXO = liquidationInput(f, spendOutput(filled(f), 1))

  property("oracle: liquidates strictly below the borrower ratio, at any data-input position") {
    withCtx { ctx =>
      // The debt is the repayment: 800 raw SigUSD is $8; at 0.5 ERG per USD, 150% is exactly 6 ERG.
      val f = oracleFlow(ctx, 6000000000L - 1L)
      val bond = oracleBond(f)
      val oracle = oracleBox(f)
      val decoy = inputAt(UTXO(work.lithos.mutations.Contract.SIGMA_TRUE, minBox), ctx, 21)
      Seq(Seq(oracle), Seq(decoy, oracle), Seq(oracle, decoy)).foreach { data =>
        accepts(executor(ctx), standardLiquidationTx(f, bond, data = data, height = f.height))
      }
      Seq(6000000000L, 6000000001L).foreach { collateralERG =>
        val healthy = oracleFlow(ctx, collateralERG)
        rejectsAtSigning(executor(ctx), standardLiquidationTx(healthy, oracleBond(healthy),
          data = Seq(oracleBox(healthy)), height = healthy.height))
      }
    }
  }

  property("oracle: mixed ERG and SigUSD collateral is valued against the repayment at one price") {
    withCtx { ctx =>
      // A token loan carries no more than a box minimum of nanoERG beside its one principal token.
      val equal = oracleFlow(ctx, 4000000000L, Seq(Token(SigUSD, 400L)), 0L, Seq(Token(SigUSD, 400L)))
      rejectsAtSigning(executor(ctx), standardLiquidationTx(equal, oracleBond(equal),
        data = Seq(oracleBox(equal)), height = equal.height))
      val under = oracleFlow(ctx, 4000000000L, Seq(Token(SigUSD, 399L)), 0L, Seq(Token(SigUSD, 400L)))
      accepts(executor(ctx), standardLiquidationTx(under, oracleBond(under),
        data = Seq(oracleBox(under)), height = under.height))
      val ergLoan = oracleFlow(ctx, 2000000000L, Seq(Token(SigUSD, 799L)), 4000000000L, Seq.empty,
        kind = ErgBond, repayment = 4000000000L)
      accepts(executor(ctx), standardLiquidationTx(ergLoan, oracleBond(ergLoan),
        data = Seq(oracleBox(ergLoan)), height = ergLoan.height))
    }
  }

  property("oracle: stale, future, missing, ambiguous or malformed data never liquidates before maturity") {
    withCtx { ctx =>
      val f = oracleFlow(ctx, 6000000000L - 1L, extra = Seq(40000L, 30L))
      val bond = oracleBond(f)
      def at(data: Seq[InputUTXO]): UnsignedTransaction = standardLiquidationTx(f, bond, data = data, height = f.height)
      accepts(executor(ctx), at(Seq(oracleBox(f, created = f.height - 30))))
      rejectsAtSigning(executor(ctx), at(Seq(oracleBox(f, created = f.height - 31))))
      rejectsAtSigning(executor(ctx), at(Seq(oracleBox(f, created = f.height + 1))))
      rejectsAtSigning(executor(ctx), at(Seq.empty))
      rejectsAtSigning(executor(ctx), at(Seq(oracleBox(f, nft = otherBondAsset))))
      rejectsAtSigning(executor(ctx), at(Seq(oracleBox(f, amount = 2L))))
      rejectsAtSigning(executor(ctx), at(Seq(oracleBox(f), inputAt(oracleBox(f).toUTXO, ctx, 21))))
      Seq(0L, -1L).foreach(price => rejectsAtSigning(executor(ctx), at(Seq(oracleBox(f, price = price)))))
      rejectsAtSigning(executor(ctx), at(Seq(oracleBox(f, epoch = -1))))
    }
  }

  property("oracle: missing or invalid ratio parameters block early liquidation but not maturity") {
    withCtx { ctx =>
      Seq(Seq.empty[Long], Seq(40000L), Seq(0L, 30L), Seq(-1L, 30L), Seq(40000L, 0L),
        Seq(40000L, 100001L)).foreach { extra =>
        val f = oracleFlow(ctx, 6000000000L - 1L, extra = extra)
        val bond = oracleBond(f)
        rejectsAtSigning(executor(ctx), standardLiquidationTx(f, bond, data = Seq(oracleBox(f)), height = f.height))
        rejectsAtSigning(executor(ctx), standardLiquidationTx(f, bond, height = f.maturity.toInt - 1))
        accepts(executor(ctx), standardLiquidationTx(f, bond))
      }
    }
  }

  property("oracle: recollateralizing up to the ratio stops a liquidation that was open") {
    withCtx { ctx =>
      val f = oracleFlow(ctx, 6000000000L - 1L)
      val bond = spendOutput(filled(f), 1)
      val at = f.height + recollateralizeWait
      val oracle = Seq(oracleBox(f, created = at))
      accepts(executor(ctx), standardLiquidationTx(f, liquidationInput(f, bond), data = oracle, height = at))
      // One nanoERG lifts it to exactly the ratio, which is not below it.
      val grown = accepts(f.owner, recollateralizeTx(f, bond, bond.toUTXO.addValue(1L), at))
      rejectsAtSigning(executor(ctx), standardLiquidationTx(f, liquidationInput(f, spendOutput(grown, 0)),
        data = oracle, height = at))
    }
  }

  property("oracle: the wait leaves a borrower one dodged liquidation, not one per block") {
    withCtx { ctx =>
      val f = oracleFlow(ctx, 6000000000L - 2L)
      val bond = spendOutput(filled(f), 1)
      val at = f.height + recollateralizeWait
      val oracle = Seq(oracleBox(f, created = at))
      // One nanoERG leaves it below the ratio but moves the box, voiding the pending liquidation.
      val dodged = spendOutput(accepts(f.owner, recollateralizeTx(f, bond, bond.toUTXO.addValue(1L), at)), 0)
      // The liquidator rebuilds against the new box, and the borrower cannot move it again in time.
      rejectsAtSigning(f.owner, recollateralizeTx(f, dodged, dodged.toUTXO.addValue(1L), at + 1))
      accepts(executor(ctx), standardLiquidationTx(f, liquidationInput(f, dodged), data = oracle, height = at + 1))
    }
  }
}
