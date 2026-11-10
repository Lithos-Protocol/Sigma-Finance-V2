package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import ksingh.sigmabonds.client.ScriptGenerator.{ContractTemplate, ContractTerms, OnCloseOrderTerms, OrderTerms}
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import org.scalatest.propspec.AnyPropSpec
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.collection.JavaConverters._

/** Test-only bindings and transactions. No production compiler, API, or builder supports EXP_*.
  * Templates are stamped just like production, so constant-folding mistakes cannot hide in tests.
  */
object ExperimentalBondSpec {
  val Scale: Long = 1000000000L
  val WholeTokenCap: Long = 1000000000L
  val MaxCirculating: Long = WholeTokenCap * Scale
  val Circulating: Long = 100L * Scale // default fixture: 100 raw collateral units
  val Locked: Long = Circulating + 1L
  val Supply: Long = Circulating + Locked

  case class Scripts(vault: Contract, ergBond: Contract, tokenBond: Contract,
                     fixed: ContractTemplate, onClose: ContractTemplate)
  private var cache: Option[Scripts] = None

  case class DynamicTerms(base: ContractTerms, repaymentErgFloor: Long) extends ContractTerms {
    def constants: Seq[(String, Any)] = base.constants :+
      ("CONST_REPAYMENT_ERG_FLOOR" -> repaymentErgFloor)
  }

  def scripts(ctx: BlockchainContext): Scripts = synchronized {
    cache.getOrElse {
      def compile(path: String, bindings: Seq[(String, Any)] = Seq.empty): Contract = {
        val constants = bindings.foldLeft(ConstantsBuilder.create()) {
          case (builder, (name, value)) => builder.item(name, value)
        }.build()
        Contract.fromErgoContract(ctx.compileContract(constants, ScriptGenerator.mkScript(path)))
      }
      val vault = compile("bonds/lender-scripts/EXP_BondAssetVault")
      val vaultHash = Colls.fromArray(vault.hashedPropBytes)
      val ergBond = compile("bonds/P2P/EXP_ZC_ERG_Bond", Seq("CONST_VAULT_HASH" -> vaultHash))
      val tokenBond = compile("bonds/P2P/EXP_ZC_Token_Bond", Seq(
        "CONST_VAULT_HASH" -> vaultHash, "CONST_TOKEN_ID" -> Colls.fromArray(BondSpecBase.stableToken.getBytes)))
      val sentinels = OrderTerms(Array.fill(32)(0x61.toByte), Array.fill(32)(0x62.toByte),
        8888888881L, Seq(Array.fill(32)(0x64.toByte) -> 8888888885L), 8888888882L,
        888884L, Array.fill(32)(0x63.toByte))
      def template(path: String, terms: ContractTerms): ContractTemplate = {
        val tree = compile(path, terms.constants).ergoTree
        val positions = terms.constants.map { case (name, value) =>
          val found = tree.constants.indices.filter(i => tree.constants(i).value == value)
          require(found.size == 1, s"$name is not a unique template term: $found")
          name -> found.head
        }.toMap
        ContractTemplate(tree, positions)
      }
      val onCloseTerms = OnCloseOrderTerms(sentinels.bondHash, sentinels.borrowerHash,
        sentinels.principalErg, sentinels.principalTokens, sentinels.repayment, 999994L, sentinels.liqScriptHash)
      val compiled = Scripts(vault, ergBond, tokenBond,
        template("bonds/P2P/EXP_ZC_Order", DynamicTerms(sentinels, 7777777771L)),
        template("bonds/P2P/EXP_ZC_OrderOnClose", DynamicTerms(onCloseTerms, 7777777771L)))
      cache = Some(compiled)
      compiled
    }
  }
}

class ExperimentalBondSpec extends AnyPropSpec with BondSpecBase {
  import BondSpecBase._
  import ExperimentalBondSpec._

  private def reserve(f: Fill): Long = f.bondOut.tokens.head.amount
  private def shares(f: Fill): Long = f.unitOut.tokens.head.amount
  private def expBond(ctx: BlockchainContext, kind: Kind): Contract =
    if (kind == ErgBond) scripts(ctx).ergBond else scripts(ctx).tokenBond

  private def expFill(ctx: BlockchainContext, kind: Kind, style: OrderStyle, fractional: Boolean,
                      duration: Int = defaultDuration, collateral: Seq[Token] = defaultCollateral,
                      repaymentOverride: Option[Long] = None, ergFloorOverride: Option[Long] = None,
                      feeOverride: Option[Long] = None, collateralValue: Long = collateralErg,
                      liquidation: Contract = null): Fill = {
    val f = fillScenario(ctx, kind, style = style, duration = duration,
      fee = feeOverride.getOrElse(batchingFee), liqScript = liquidation)
    val bond = expBond(ctx, kind)
    val repayment = repaymentOverride.getOrElse(repaymentOf(kind))
    val floor = ergFloorOverride.getOrElse(if (kind == ErgBond) repayment else minBox)
    val liq = if (liquidation == null) matured(ctx) else liquidation
    val tree = style match {
      case FixedHeight => scripts(ctx).fixed.withTerms(DynamicTerms(orderTerms(ctx, kind)
        .copy(bondHash = hashOf(bond), repayment = repayment, liqScriptHash = liq.hashedValueBytes), floor))
      case OnClose => scripts(ctx).onClose.withTerms(DynamicTerms(onCloseTerms(ctx, kind, duration)
        .copy(bondHash = hashOf(bond), repayment = repayment, liqScriptHash = liq.hashedValueBytes), floor))
    }
    val order = f.order.setContract(Contract(tree)).setValue(collateralValue).setTokens(collateral: _*)
    val input = inputAt(order, ctx, 0).withCtxVar(0.toByte, ErgoValue.of(f.fee))
      .withCtxVar(1.toByte, ErgoValue.of(fractional))
    val limits = Seq(BigInt(WholeTokenCap), BigInt(collateralValue), BigInt(repayment), BigInt(floor) + f.fee) ++
      collateral.map(t => BigInt(t.amount))
    // Invalid-term tests still construct a positive mint; the script must reject the disagreement.
    val n = if (fractional) limits.min.max(BigInt(1)).toLong else 1L
    val circulating = if (fractional) n * Scale else 1L
    val locked = if (fractional) circulating + 1L else 1L
    f.copy(order = order, orderIn = input, asset = input.id, repayment = repayment,
      unitOut = f.unitOut.setTokens(Token(input.id, circulating))
        .setRegs(eip4(decimals = if (fractional) "9" else "0"): _*),
      bondOut = f.bondOut.setContract(bond).withRegNum(4, bytesValue(input.id.getBytes))
        .setValue(collateralValue + f.fee)
        .withRegNum(6, longsValue(repayment, f.fee, principalOf(kind)._1, f.maturity.toLong))
        .withRegNum(9, longsValue(n, floor))
        .setTokens(Token(input.id, locked) +: collateral: _*))
  }

  private def expSettlement(ctx: BlockchainContext, value: Long, bondId: ErgoId, asset: ErgoId,
                            outstanding: Long, tokens: Seq[Token] = Seq.empty, path: Byte = 0): UTXO =
    settlement(scripts(ctx).vault, value, bondId, asset, tokens, path)
      .withRegNum(7, ErgoValue.of(outstanding)).withRegNum(8, bytesValue(bondId.getBytes))

  /** Payout floor is computed independently using Scala BigInt. Top-ups are external funding. */
  private def claimOutputs(ctx: BlockchainContext, in: InputUTXO, burned: Long): Seq[UTXO] = {
    val outstanding = in.registers(3).getValue.asInstanceOf[Long]
    def amount(v: Long): Long = (BigInt(v) * burned / outstanding).toLong
    val paid = in.tokens.flatMap { t =>
      val n = amount(t.amount)
      if (n > 0L) Some(Token(t.id, n)) else None
    }
    val payout = tagged(lenderTree(ctx), math.max(minBox, amount(in.value)), in.id, paid)
    if (burned == outstanding) Seq(payout)
    else {
      val retained = in.tokens.map(t => Token(t.id, t.amount - amount(t.amount))).filter(_.amount > 0L)
      val next = UTXO(scripts(ctx).vault, math.max(minBox, in.value - amount(in.value)), retained,
        in.registers.take(3) ++ Seq(ErgoValue.of(outstanding - burned), bytesValue(in.id.getBytes)))
      Seq(payout, next)
    }
  }

  private def claimInput(ctx: BlockchainContext, value: Long = 9000000001L,
                         tokens: Seq[Token] = Seq(Token(collateralToken, 101L), Token(stableToken, 203L)),
                         outstanding: Long = Circulating): InputUTXO =
    inputAt(expSettlement(ctx, value, ErgoId.create("ab" * 32), bondAsset, outstanding, tokens), ctx, 0)
      .withCtxVar(0.toByte, ErgoValue.of(0)).withCtxVar(1.toByte, ErgoValue.of(1))

  private def expClaimTx(ctx: BlockchainContext, in: InputUTXO, burned: Long,
                      outputs: Seq[UTXO] = null, available: Long = Circulating,
                      inputs: Seq[InputUTXO] = null, asset: ErgoId = bondAsset): UnsignedTransaction = {
    val holder = lender(ctx)
    val ins = if (inputs == null) Seq(in, funding(ctx, holder, 1, slack, Seq(Token(asset, available)))) else inputs
    build(ctx, ins, if (outputs == null) claimOutputs(ctx, in, burned) else outputs,
      holder.getAddress, burn = if (burned > 0L) Seq(Token(asset, burned)) else Seq.empty)
  }

  for (style <- Seq[OrderStyle](FixedHeight, OnClose); kind <- Seq[Kind](ErgBond, TokenBond);
       fractional <- Seq(false, true)) {
    val label = s"EXP $style $kind fractional=$fractional"

    property(s"$label: exact mint cap, reserve, circulating allocation, and decimals") {
      withCtx { ctx =>
        val f = expFill(ctx, kind, style, fractional)
        val signed = accepts(f.prover, fillTx(f)())
        signed.getOutputsToSpend.asScala.flatMap(_.getTokens.asScala).filter(_.getId == f.asset)
          .map(t => BigInt(t.getValue.longValue)).sum shouldBe BigInt(reserve(f)) + shares(f)
        signed.getOutputsToSpend.get(1).getTokens.get(0).getValue.longValue shouldBe reserve(f)
        val wrongDecimals = f.unitOut.withRegNum(6, utf8(if (fractional) "0" else "9"))
        rejectsAtSigning(f.prover, fillTx(f)(unitOut = wrongDecimals))
        rejectsAtSigning(f.prover, fillTx(f)(unitOut = f.unitOut.setTokens(Token(f.asset, shares(f) + 1L))))
        if (fractional) {
          rejectsAtSigning(f.prover, fillTx(f)(unitOut = f.unitOut.setTokens(Token(f.asset, Circulating - 1L))))
          // Supply remains correct but one raw reserve unit has escaped to the lender.
          rejectsAtSigning(f.prover, fillTx(f)(
            unitOut = f.unitOut.setTokens(Token(f.asset, Circulating + 1L)),
            bondOut = f.bondOut.setTokens(Token(f.asset, Locked - 1L) +: defaultCollateral: _*)))
        }
      }
    }

    property(s"$label: fill to settlement to proportional/final redemption uses real predecessor boxes") {
      withCtx { ctx =>
        val f = expFill(ctx, kind, style, fractional)
        val filled = accepts(f.prover, fillTx(f)())
        val bond = spendOutput(filled, 1).withCtxVar(1.toByte, ErgoValue.of(0))
          .withCtxVar(2.toByte, ErgoValue.of(1))
        val payer = borrower(ctx)
        val vaultOut = expSettlement(ctx, if (kind == ErgBond) repaymentErg + f.fee else minBox + f.fee,
          bond.id, f.asset, shares(f),
          if (kind == ErgBond) Seq.empty else Seq(Token(stableToken, repaymentTokens)))
        val returnOut = tagged(borrowerTree(ctx), bond.value - f.fee, bond.id, defaultCollateral)
        def repay(out: UTXO, extra: Seq[InputUTXO] = Seq.empty, burn: Long = reserve(f)) =
          buildAt(ctx, f.maturity - 1,
            Seq(bond, funding(ctx, payer, 1, slack, repaymentFunds(kind))) ++ extra,
            Seq(out, returnOut), payer.getAddress,
            burn = if (burn > 0L) Seq(Token(f.asset, burn)) else Seq.empty)
        val repaid = accepts(payer, repay(vaultOut))
        rejectsAtSigning(payer, repay(vaultOut, burn = reserve(f) - 1L))
        rejectsAtSigning(payer, repay(vaultOut.setContract(vault(ctx))))
        rejectsAtSigning(payer, repay(vaultOut.withRegNum(7, ErgoValue.of(shares(f) + 1L))))
        rejectsAtSigning(payer, repay(vaultOut.withRegNum(8, bytesValue(originOrderId))))
        // A share holder cannot destroy circulating shares at settlement and leave the original denominator.
        rejectsAtSigning(payer, repay(vaultOut,
          Seq(funding(ctx, payer, 3, minBox, Seq(Token(f.asset, 1L)))), reserve(f) + 1L))

        val settled = spendOutput(repaid, 0).withCtxVar(0.toByte, ErgoValue.of(0))
          .withCtxVar(1.toByte, ErgoValue.of(1))
        val burned = if (fractional) Circulating / 3L else 1L
        val claimed = accepts(f.prover, expClaimTx(ctx, settled, burned,
          inputs = Seq(settled, spendOutput(filled, 0), funding(ctx, f.prover, 4)), asset = f.asset))
        if (fractional) {
          val next = spendOutput(claimed, 1).withCtxVar(0.toByte, ErgoValue.of(0))
          val change = claimed.getOutputsToSpend.asScala.find(_.getTokens.asScala.exists(_.getId == f.asset)).get
          accepts(f.prover, expClaimTx(ctx, next, Circulating - burned,
            inputs = Seq(next, InputUTXO(change), funding(ctx, f.prover, 5)), asset = f.asset))
        }
      }
    }
  }

  for (style <- Seq[OrderStyle](FixedHeight, OnClose)) {
    property(s"EXP $style: fractional allocation may be split among lender-chosen scripts") {
      withCtx { ctx =>
        val f = expFill(ctx, ErgBond, style, fractional = true)
        val first = f.unitOut.setContract(otherTree(ctx)).setTokens(Token(f.asset, 1L))
        val rest = UTXO(lenderTree(ctx), minBox, Seq(Token(f.asset, Circulating - 1L)))
        accepts(f.prover, fillTx(f)(outputs = Seq(first, f.bondOut, f.principalOut, rest)))
        val hiddenMint = UTXO(lenderTree(ctx), minBox, Seq(Token(f.asset, 1L)))
        rejectsAtSigning(f.prover, fillTx(f)(outputs = Seq(f.unitOut, f.bondOut, f.principalOut, hiddenMint)))
      }
    }

    property(s"EXP $style: context mode is mandatory, typed, and not needed for authenticated cancel") {
      withCtx { ctx =>
        val f = expFill(ctx, ErgBond, style, fractional = true)
        val feeOnly = inputAt(f.order, ctx, 0).withCtxVar(0.toByte, ErgoValue.of(f.fee))
        rejectsAtSigning(f.prover, fillTx(f)(inputs = Seq(feeOnly, f.lenderIn)))
        rejectsAtSigning(f.prover, fillTx(f)(inputs = Seq(feeOnly.withCtxVar(1.toByte, ErgoValue.of(1)), f.lenderIn)))
        rejectsAtSigning(f.prover, fillTx(f)(inputs = Seq(feeOnly.withCtxVar(1.toByte, ErgoValue.of(false)), f.lenderIn)))
        val owner = borrower(ctx)
        val canceled = Seq(UTXO(borrowerTree(ctx), f.order.value, f.order.tokens))
        accepts(owner, build(ctx, Seq(inputAt(f.order, ctx, 0), funding(ctx, owner, 1)), canceled, owner.getAddress))
        rejectsAtSigning(f.prover, build(ctx, Seq(inputAt(f.order, ctx, 0), f.lenderIn), canceled, f.prover.getAddress))
      }
    }
  }

  for (style <- Seq[OrderStyle](FixedHeight, OnClose); kind <- Seq[Kind](ErgBond, TokenBond)) {
    property(s"EXP $style $kind: the lowest raw collateral amount dynamically limits whole token supply") {
      withCtx { ctx =>
        val highLimit = if (kind == ErgBond) WholeTokenCap else repaymentTokens
        val cases = Seq(
          Seq(Token(collateralToken, 1L)) -> 1L,
          Seq(Token(collateralToken, 2L)) -> 2L,
          Seq(Token(collateralToken, 99L)) -> 99L,
          Seq(Token(collateralToken, 100L)) -> 100L,
          Seq(Token(collateralToken, 101L)) -> 101L,
          Seq(Token(collateralToken, Long.MaxValue)) -> highLimit,
          Seq(Token(collateralToken, Long.MaxValue), Token(secondCollateral, 99L)) -> 99L,
          Seq(Token(secondCollateral, 1L), Token(collateralToken, 100L)) -> 1L,
          Seq.empty[Token] -> highLimit)
        cases.foreach { case (collateral, expectedN) =>
          val f = expFill(ctx, kind, style, fractional = true, collateral = collateral)
          val filled = accepts(f.prover, fillTx(f)())
          val bond = filled.getOutputsToSpend.get(1)
          bond.getRegisters.get(5).getValue.asInstanceOf[sigma.Coll[Long]].toArray.head shouldBe expectedN
          shares(f) shouldBe expectedN * Scale
          reserve(f) shouldBe expectedN * Scale + 1L
          rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(9,
            longsValue(expectedN + 1L, if (kind == ErgBond) repaymentErg else minBox))))
        }
      }
    }

    property(s"EXP $style $kind: low-unit collateral still permits whole fills and borrower cancellation") {
      withCtx { ctx =>
        Seq(1L, 99L).foreach { amount =>
          val f = expFill(ctx, kind, style, fractional = false,
            collateral = Seq(Token(collateralToken, amount)))
          accepts(f.prover, fillTx(f)())
          val owner = borrower(ctx)
          accepts(owner, build(ctx, Seq(inputAt(f.order, ctx, 0), funding(ctx, owner, 1)),
            Seq(UTXO(borrowerTree(ctx), f.order.value, f.order.tokens)), owner.getAddress))
        }
      }
    }
  }

  property("EXP templates: correct version, term positions, maturity boundaries, collateral and fee funding") {
    withCtx { ctx =>
      val s = scripts(ctx)
      Seq(s.vault.ergoTree, s.ergBond.ergoTree, s.tokenBond.ergoTree, s.fixed.tree, s.onClose.tree)
        .foreach(_.version shouldBe 3)
      Seq(s.fixed, s.onClose).foreach { t =>
        t.positions.values.toSet.size shouldBe 8
        t.matches(t.tree) shouldBe true
      }
      val fixed = expFill(ctx, ErgBond, FixedHeight, fractional = true)
      accepts(fixed.prover, fillTx(fixed)(height = fixed.maturity - 2))
      rejectsAtSigning(fixed.prover, fillTx(fixed)(height = fixed.maturity - 1))
      rejectsAtSigning(fixed.prover, fillTx(fixed)(bondOut = fixed.bondOut.subValue(1L)))
      rejectsAtSigning(fixed.prover, fillTx(fixed)(principalOut = fixed.principalOut.subValue(1L)))
      rejectsAtSigning(fixed.prover, fillTx(fixed)(bondOut = fixed.bondOut.setContract(s.tokenBond)))
      val close = expFill(ctx, ErgBond, OnClose, fractional = true, duration = 30)
      accepts(close.prover, fillTx(close)())
      accepts(close.prover, fillTx(close)(height = close.height + 8))
      rejectsAtSigning(close.prover, fillTx(close)(height = close.height + 9))
      val short = expFill(ctx, ErgBond, OnClose, fractional = true, duration = 29)
      rejectsAtSigning(short.prover, fillTx(short)())
    }
  }

  for (style <- Seq[OrderStyle](FixedHeight, OnClose)) {
    property(s"EXP $style: repayment amount, ERG floor, collateral ERG, and issuance cap each limit N") {
      withCtx { ctx =>
        val repaymentLimited = expFill(ctx, TokenBond, style, fractional = true,
          collateral = Seq.empty, repaymentOverride = Some(7L))
        val ergLimited = expFill(ctx, TokenBond, style, fractional = true,
          collateral = Seq.empty, repaymentOverride = Some(2000000000L), feeOverride = Some(0L))
        val collateralLimited = expFill(ctx, ErgBond, style, fractional = true,
          collateral = Seq.empty, collateralValue = minBox)
        val capped = expFill(ctx, ErgBond, style, fractional = true, collateral = Seq.empty)
        Seq(repaymentLimited -> 7L, ergLimited -> minBox,
          collateralLimited -> minBox, capped -> WholeTokenCap).foreach { case (f, expected) =>
          shares(f) shouldBe expected * Scale
          accepts(f.prover, fillTx(f)())
          rejectsAtSigning(f.prover, fillTx(f)(unitOut = f.unitOut.setTokens(Token(f.asset, shares(f) + 1L))))
        }
        val refundedFee = expFill(ctx, TokenBond, style, fractional = true,
          collateral = Seq.empty, repaymentOverride = Some(2000000000L))
        shares(refundedFee) shouldBe (minBox + batchingFee) * Scale
        accepts(refundedFee.prover, fillTx(refundedFee)())
        // Clamping the minimum avoids overflowing floor+fee, without overflowing issuance either.
        val hugeFloor = expFill(ctx, TokenBond, style, fractional = true, collateral = Seq.empty,
          repaymentOverride = Some(Long.MaxValue), ergFloorOverride = Some(Long.MaxValue))
        shares(hugeFloor) shouldBe MaxCirculating
        accepts(hugeFloor.prover, fillTx(hugeFloor)())
        Seq(0L, -1L).foreach { invalid =>
          val f = expFill(ctx, TokenBond, style, fractional = true, ergFloorOverride = Some(invalid))
          rejectsAtSigning(f.prover, fillTx(f)())
        }
        val noRepayment = expFill(ctx, TokenBond, style, fractional = true, repaymentOverride = Some(0L))
        rejectsAtSigning(noRepayment.prover, fillTx(noRepayment)())
      }
    }
  }

  for (kind <- Seq[Kind](ErgBond, TokenBond)) {
    property(s"EXP $kind: the explicit repayment ERG floor is pinned and enforced") {
      withCtx { ctx =>
        val floor = if (kind == ErgBond) repaymentErg + minBox else 2L * minBox
        val f = expFill(ctx, kind, FixedHeight, fractional = true, ergFloorOverride = Some(floor))
        rejectsAtSigning(f.prover, fillTx(f)(bondOut = f.bondOut.withRegNum(9, longsValue(100L, floor - 1L))))
        val filled = accepts(f.prover, fillTx(f)())
        val bond = spendOutput(filled, 1).withCtxVar(1.toByte, ErgoValue.of(0))
          .withCtxVar(2.toByte, ErgoValue.of(1))
        val owner = borrower(ctx)
        val out = expSettlement(ctx, floor + f.fee, bond.id, f.asset, shares(f),
          if (kind == ErgBond) Seq.empty else Seq(Token(stableToken, repaymentTokens)))
        val returned = tagged(borrowerTree(ctx), bond.value - f.fee, bond.id, defaultCollateral)
        def repay(to: UTXO, extra: Seq[InputUTXO] = Seq.empty) = buildAt(ctx, f.maturity - 1,
          Seq(bond, funding(ctx, owner, 1, slack, repaymentFunds(kind))) ++ extra,
          Seq(to, returned), owner.getAddress, burn = Seq(Token(f.asset, reserve(f))))
        accepts(owner, repay(out))
        rejectsAtSigning(owner, repay(out.subValue(1L)))
        if (kind == ErgBond) {
          // Full ERG repayment plus an extra NFT would violate the per-whole-token guarantee.
          rejectsAtSigning(owner, repay(out.setTokens(Token(secondCollateral, 1L)),
            Seq(funding(ctx, owner, 2, minBox, Seq(Token(secondCollateral, 1L))))))
        }
      }
    }

    property(s"EXP $kind: custom liquidation rejects too few token units despite an agreed true expression") {
      withCtx { ctx =>
        val f = expFill(ctx, kind, FixedHeight, fractional = true, liquidation = alwaysTrue(ctx))
        val filled = accepts(f.prover, fillTx(f)())
        val bond = spendOutput(filled, 1).withCtxVar(0.toByte, liqVar(alwaysTrue(ctx)))
          .withCtxVar(1.toByte, ErgoValue.of(0))
        val runner = executor(ctx)
        val out = expSettlement(ctx, minBox, bond.id, f.asset, shares(f),
          Seq(Token(secondCollateral, 100L)), 1.toByte)
        def liquidate(to: UTXO) = build(ctx,
          Seq(bond, funding(ctx, runner, 1, slack, Seq(Token(secondCollateral, 100L)))),
          Seq(to), runner.getAddress, burn = Seq(Token(f.asset, reserve(f))))
        accepts(runner, liquidate(out))
        rejectsAtSigning(runner, liquidate(out.setTokens(Token(secondCollateral, 99L))))
        rejectsAtSigning(runner, liquidate(out.setTokens(Token(secondCollateral, 1L))))
      }
    }

    property(s"EXP $kind: custom liquidation rejects too few nanoERG despite an agreed true expression") {
      withCtx { ctx =>
        val f = expFill(ctx, kind, FixedHeight, fractional = true, collateral = Seq.empty,
          repaymentOverride = Some(2000000000L), ergFloorOverride = Some(2000000000L),
          liquidation = alwaysTrue(ctx))
        shares(f) shouldBe MaxCirculating
        val filled = accepts(f.prover, fillTx(f)())
        val bond = spendOutput(filled, 1).withCtxVar(0.toByte, liqVar(alwaysTrue(ctx)))
          .withCtxVar(1.toByte, ErgoValue.of(0))
        val runner = executor(ctx)
        val out = expSettlement(ctx, WholeTokenCap, bond.id, f.asset, shares(f), path = 1.toByte)
        def liquidate(to: UTXO) = build(ctx, Seq(bond), Seq(to), runner.getAddress,
          burn = Seq(Token(f.asset, reserve(f))))
        accepts(runner, liquidate(out))
        rejectsAtSigning(runner, liquidate(out.subValue(1L)))
      }
    }
  }

  for (kind <- Seq[Kind](ErgBond, TokenBond); fractional <- Seq(false, true)) {
    property(s"EXP liquidation $kind fractional=$fractional: reserve burns, vault denominator is pinned") {
      withCtx { ctx =>
        val f = expFill(ctx, kind, FixedHeight, fractional)
        val filled = accepts(f.prover, fillTx(f)())
        val bond = spendOutput(filled, 1).withCtxVar(0.toByte, liqVar(matured(ctx)))
          .withCtxVar(1.toByte, ErgoValue.of(0))
        val out = expSettlement(ctx, bond.value - f.fee, bond.id, f.asset,
          shares(f), defaultCollateral, 1.toByte)
        val runner = executor(ctx)
        def liquidate(height: Int, settlementOut: UTXO = out) = buildAt(ctx, height, Seq(bond),
          Seq(settlementOut), runner.getAddress, burn = Seq(Token(f.asset, reserve(f))))
        rejectsAtSigning(runner, liquidate(f.maturity - 1))
        val liquidated = accepts(runner, liquidate(f.maturity))
        rejectsAtSigning(runner, liquidate(f.maturity, out.withRegNum(7, ErgoValue.of(1L + shares(f)))))
        val vaultIn = spendOutput(liquidated, 0).withCtxVar(0.toByte, ErgoValue.of(0))
        accepts(f.prover, expClaimTx(ctx, vaultIn, shares(f),
          inputs = Seq(vaultIn, spendOutput(filled, 0), funding(ctx, f.prover, 4)), asset = f.asset))
      }
    }
  }

  property("EXP vault: partial claim pays the exact floor for ERG and every token, and preserves the denominator") {
    withCtx { ctx =>
      val in = claimInput(ctx)
      val burned = Circulating / 3L
      val outs = claimOutputs(ctx, in, burned)
      accepts(lender(ctx), expClaimTx(ctx, in, burned))
      val mutations = Seq(
        Seq(outs.head.subValue(1L), outs(1)),
        Seq(outs.head, outs(1).subValue(1L)),
        Seq(outs.head.setTokens(outs.head.tokens.head), outs(1)),
        Seq(outs.head, outs(1).setTokens(outs(1).tokens.head)),
        Seq(outs.head, outs(1).withRegNum(7, ErgoValue.of(Circulating - burned + 1L))),
        Seq(outs.head, outs(1).withRegNum(8, bytesValue(originOrderId))),
        Seq(outs.head, outs(1).withRegNum(4, bytesValue(originOrderId))),
        Seq(outs.head, outs(1).withRegNum(5, bytesValue(otherBondAsset.getBytes))),
        Seq(outs.head, outs(1).withRegNum(6, ErgoValue.of(1.toByte))),
        Seq(outs.head, outs(1).setContract(otherTree(ctx))),
        Seq(outs.head.withRegNum(4, bytesValue(originOrderId)), outs(1)))
      mutations.foreach(out => rejectsAtSigning(lender(ctx), expClaimTx(ctx, in, burned, out)))
    }
  }

  property("EXP vault: no burn, retained shares, and burning a different asset do not claim anything") {
    withCtx { ctx =>
      val in = claimInput(ctx)
      val outs = claimOutputs(ctx, in, Circulating / 2L)
      rejectsAtSigning(lender(ctx), expClaimTx(ctx, in, 0L, outs))
      rejectsAtSigning(lender(ctx), expClaimTx(ctx, in, Circulating / 2L, outs,
        inputs = Seq(in, funding(ctx, lender(ctx), 1, slack, Seq(Token(otherBondAsset, Circulating)))),
        asset = otherBondAsset))
    }
  }

  property("EXP vault: malformed context, shared roles, and malformed denominators fail closed") {
    withCtx { ctx =>
      val in = claimInput(ctx)
      val burned = Circulating / 2L
      val outs = claimOutputs(ctx, in, burned)
      accepts(lender(ctx), expClaimTx(ctx, in, burned))
      val contexts = Seq(
        Seq.empty[ContextVar],
        Seq(ContextVar.of(0.toByte, ErgoValue.of(-1))),
        Seq(ContextVar.of(0.toByte, ErgoValue.of(Int.MaxValue))),
        Seq(ContextVar.of(0.toByte, ErgoValue.of(0L))),
        Seq(ContextVar.of(0.toByte, ErgoValue.of(0))),
        Seq(ContextVar.of(0.toByte, ErgoValue.of(0)), ContextVar.of(1.toByte, ErgoValue.of(0))),
        Seq(ContextVar.of(0.toByte, ErgoValue.of(0)), ContextVar.of(1.toByte, ErgoValue.of(Int.MaxValue))))
      contexts.foreach { vars =>
        rejectsAtSigning(lender(ctx), expClaimTx(ctx, in.setCtxVars(vars: _*), burned, outs))
      }
      Seq(0L, -1L, MaxCirculating + 1L).foreach { denominator =>
        val bad = inputAt(in.toUTXO.withRegNum(7, ErgoValue.of(denominator)), ctx, 0)
          .withCtxVar(0.toByte, ErgoValue.of(0)).withCtxVar(1.toByte, ErgoValue.of(1))
        // For an over-cap denominator every other output condition remains valid.
        val badOuts = if (denominator > 0L) claimOutputs(ctx, bad, burned) else
          Seq(outs.head.withRegNum(4, bytesValue(bad.id.getBytes)),
            outs(1).withRegNum(8, bytesValue(bad.id.getBytes)))
        rejectsAtSigning(lender(ctx), expClaimTx(ctx, bad, burned, badOuts))
      }
    }
  }

  property("EXP vault: one-raw-unit claims round down, and the final claimant gets all dust") {
    withCtx { ctx =>
      val in = claimInput(ctx)
      val outs = claimOutputs(ctx, in, 1L)
      outs.head.tokens shouldBe empty
      outs(1).tokens shouldBe in.tokens
      outs(1).value shouldBe in.value
      val claimed = accepts(lender(ctx), expClaimTx(ctx, in, 1L))
      val next = spendOutput(claimed, 1).withCtxVar(0.toByte, ErgoValue.of(0))
      val finalOut = claimOutputs(ctx, next, Circulating - 1L)
      finalOut.head.value shouldBe in.value
      finalOut.head.tokens shouldBe in.tokens
      accepts(lender(ctx), expClaimTx(ctx, next, Circulating - 1L, available = Circulating - 1L))
      rejectsAtSigning(lender(ctx), expClaimTx(ctx, next, Circulating - 1L,
        finalOut.map(_.subValue(1L)), available = Circulating - 1L))
    }
  }

  property("EXP vault: each whole displayed token still pays raw units after arbitrary sub-token burns") {
    withCtx { ctx =>
      Seq(1L, Scale - 1L, 2L * Scale + 7L, Circulating - Scale).foreach { earlierBurn =>
        val in = claimInput(ctx)
        val first = accepts(lender(ctx), expClaimTx(ctx, in, earlierBurn))
        val next = spendOutput(first, 1).withCtxVar(0.toByte, ErgoValue.of(0))
          .withCtxVar(1.toByte, ErgoValue.of(1))
        val outs = claimOutputs(ctx, next, Scale)
        outs.head.tokens.size shouldBe 2
        all(outs.head.tokens.map(_.amount)) should be >= 1L
        val remaining = Circulating - earlierBurn
        (BigInt(next.value) * Scale / remaining).toLong should be >= 1L
        accepts(lender(ctx), expClaimTx(ctx, next, Scale, available = remaining))
      }
      // One displayed token may be the entire circulating claim for an NFT-backed bond.
      val nft = claimInput(ctx, tokens = Seq(Token(collateralToken, 1L)), outstanding = Scale)
      accepts(lender(ctx), expClaimTx(ctx, nft, Scale, available = Scale))
    }
  }

  property("EXP vault: undersized token or ERG balances fail the whole-token guarantee before redemption") {
    withCtx { ctx =>
      val tooFewTokens = claimInput(ctx, tokens = Seq(Token(collateralToken, 99L)))
      rejectsAtSigning(lender(ctx), expClaimTx(ctx, tooFewTokens, Scale))
      val tooLittleERG = claimInput(ctx, value = WholeTokenCap - 1L,
        tokens = Seq(Token(collateralToken, WholeTokenCap)), outstanding = MaxCirculating)
      rejectsAtSigning(lender(ctx), expClaimTx(ctx, tooLittleERG, Scale, available = MaxCirculating))
    }
  }

  property("EXP vault: redemption remains possible after an eligible token balance falls below 100 raw units") {
    withCtx { ctx =>
      val in = claimInput(ctx, tokens = Seq(Token(collateralToken, 100L)))
      val burned = Circulating / 100L
      val first = accepts(lender(ctx), expClaimTx(ctx, in, burned))
      val next = spendOutput(first, 1).withCtxVar(0.toByte, ErgoValue.of(0))
        .withCtxVar(1.toByte, ErgoValue.of(1))
      next.tokens shouldBe Seq(Token(collateralToken, 99L))
      val second = accepts(lender(ctx), expClaimTx(ctx, next, burned, available = Circulating - burned))
      val last = spendOutput(second, 1).withCtxVar(0.toByte, ErgoValue.of(0))
      last.tokens shouldBe Seq(Token(collateralToken, 98L))
      accepts(lender(ctx), expClaimTx(ctx, last, Circulating - 2L * burned,
        available = Circulating - 2L * burned))
    }
  }

  property("EXP vault: a locked-majority balance is too large to redeem the circulating denominator") {
    withCtx { ctx =>
      val in = claimInput(ctx)
      val out = tagged(lenderTree(ctx), in.value, in.id, in.tokens)
      rejectsAtSigning(lender(ctx), expClaimTx(ctx, in, Locked, Seq(out), available = Locked))
    }
  }

  property("EXP vault: shares inside the vault cannot fund a public redemption") {
    withCtx { ctx =>
      val in = claimInput(ctx, tokens = Seq(Token(bondAsset, 1L)), outstanding = 1L)
      val out = tagged(lenderTree(ctx), in.value, in.id)
      rejectsAtSigning(lender(ctx), expClaimTx(ctx, in, 1L, Seq(out),
        inputs = Seq(in, funding(ctx, lender(ctx), 1))))
    }
  }

  property("EXP vault: two vault inputs cannot share a burn or continuation") {
    withCtx { ctx =>
      val first = claimInput(ctx)
      val second = inputAt(first.toUTXO, ctx, 2).withCtxVar(0.toByte, ErgoValue.of(0))
        .withCtxVar(1.toByte, ErgoValue.of(1))
      rejectsAtSigning(lender(ctx), expClaimTx(ctx, first, Circulating / 2L,
        inputs = Seq(first, second, funding(ctx, lender(ctx), 3, slack, Seq(Token(bondAsset, Circulating))))))
    }
  }

  property("EXP vault: BigInt products handle maximum Long token balances") {
    withCtx { ctx =>
      val in = claimInput(ctx, tokens = Seq(Token(collateralToken, Long.MaxValue)))
      accepts(lender(ctx), expClaimTx(ctx, in, Circulating / 2L))
    }
  }
}
