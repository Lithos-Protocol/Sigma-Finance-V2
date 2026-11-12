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

  property("order: the start height must lie within 8 blocks of the fill") {
    withCtx { ctx =>
      val f = flow(ctx)
      def startAt(start: Long): UnsignedTransaction =
        standardFillTx(f, bond = f.bond.withRegNum(9, longsValue(f.fee, start, f.maturity)))
      accepts(f.holder, startAt(f.height.toLong))
      accepts(f.holder, startAt(f.height - 8L))
      rejectsAtSigning(f.holder, startAt(f.height - 9L))
      rejectsAtSigning(f.holder, startAt(f.height + 1L))
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
      accepts(f.holder, standardFillTx(f, bond = f.bond.withRegNum(9, longsValue(f.fee, early, early + 30L))))
      // A start in the past cannot keep the later maturity: the two move together.
      rejectsAtSigning(f.holder, standardFillTx(f, bond = f.bond.withRegNum(9, longsValue(f.fee, early, f.maturity))))
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
        Seq(returned.subValue(1L), returned.setContract(otherTree(ctx)),
          returned.withRegNum(4, bytesValue(originOrderId)), returned.setTokens()).foreach { out =>
          rejectsAtSigning(f.owner, standardRepayTx(f, bond, returned = out))
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

  property("recollateralize: the borrower adds at least 1% of one collateral asset before maturity") {
    withCtx { ctx =>
      val f = flow(ctx)
      val bond = spendOutput(filled(f), 1).withCtxVar(3.toByte, ErgoValue.of(0))
      val same = bond.toUTXO
      val onePercent = f.collateralValue / 100L
      def tx(out: UTXO, by: ErgoProver = f.owner, height: Int = f.height + 1): UnsignedTransaction =
        buildAt(ctx, height, Seq(bond, funding(ctx, by, 6, slack, Seq(Token(collateralToken, 50L)))),
          Seq(out), by.getAddress)
      val moreTokens = same.setTokens(Token(f.asset, 2L), Token(collateralToken, 101L))

      accepts(f.owner, tx(same.addValue(onePercent)))
      accepts(f.owner, tx(moreTokens))
      rejectsAtSigning(f.owner, tx(same.addValue(onePercent - 1L)))
      rejectsAtSigning(f.owner, tx(same))
      // Open to nobody else: a stranger could otherwise re-spend the bond and void pending repayments.
      rejectsAtSigning(other(ctx), tx(same.addValue(onePercent), by = other(ctx)))
      rejectsAtSigning(f.owner, tx(same.addValue(onePercent), height = f.maturity.toInt))
      val changed = Seq(
        same.withRegNum(9, longsValue(f.fee, f.start, f.maturity + 1L)),
        same.withRegNum(9, longsValue(0L, f.start, f.maturity)),
        same.withRegNum(6, longsValue(f.data.updated(0, 1L): _*)),
        same.withRegNum(5, bytesValue(hashOf(otherTree(ctx)))),
        same.withRegNum(8, bytesValue(alwaysTrue(ctx).hashedValueBytes)),
        same.setContract(otherTree(ctx)))
      changed.foreach(out => rejectsAtSigning(f.owner, tx(out.addValue(onePercent))))
      // More ERG cannot pay for taking a collateral token out.
      rejectsAtSigning(f.owner, tx(same.addValue(onePercent).setTokens(Token(f.asset, 2L), Token(collateralToken, 99L))))
    }
  }

  property("recollateralize: the new bond box repays and returns the larger collateral") {
    withCtx { ctx =>
      val f = flow(ctx, kind = ErgBond)
      val bond = spendOutput(filled(f), 1).withCtxVar(3.toByte, ErgoValue.of(0))
      val added = f.collateralValue / 2L
      val grown = accepts(f.owner, buildAt(ctx, f.height + 1, Seq(bond, funding(ctx, f.owner, 6)),
        Seq(bond.toUTXO.addValue(added)), f.owner.getAddress))
      val next = repayInput(spendOutput(grown, 0))
      next.id should not be bond.id
      val repaid = accepts(f.owner, standardRepayTx(f, next))
      repaid.getOutputsToSpend.get(1).getValue shouldBe f.collateralValue + added
      repaid.getOutputsToSpend.get(0).getRegisters.get(0) shouldBe bytesValue(next.id.getBytes)
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
      val equal = oracleFlow(ctx, 4000000000L, Seq(Token(SigUSD, 400L)), 2000000000L, Seq(Token(SigUSD, 400L)))
      rejectsAtSigning(executor(ctx), standardLiquidationTx(equal, oracleBond(equal),
        data = Seq(oracleBox(equal)), height = equal.height))
      val under = oracleFlow(ctx, 4000000000L, Seq(Token(SigUSD, 399L)), 2000000000L, Seq(Token(SigUSD, 400L)))
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

  property("oracle: recollateralizing above the ratio stops a liquidation that was open") {
    withCtx { ctx =>
      val f = oracleFlow(ctx, 6000000000L - 1L)
      val bond = spendOutput(filled(f), 1)
      accepts(executor(ctx), standardLiquidationTx(f, liquidationInput(f, bond),
        data = Seq(oracleBox(f)), height = f.height))
      val grown = accepts(f.owner, buildAt(ctx, f.height, Seq(bond.withCtxVar(3.toByte, ErgoValue.of(0)),
        funding(ctx, f.owner, 6)), Seq(bond.toUTXO.addValue(f.collateralValue / 100L)), f.owner.getAddress))
      rejectsAtSigning(executor(ctx), standardLiquidationTx(f, liquidationInput(f, spendOutput(grown, 0)),
        data = Seq(oracleBox(f)), height = f.height))
    }
  }
}
