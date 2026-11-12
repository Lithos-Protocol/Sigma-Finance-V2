package ksingh.sigmabonds
package standard

import ksingh.sigmabonds.app.Protocol._
import ksingh.sigmabonds.app._
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.sdk.ErgoId
import scorex.util.encode.Base16
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import scala.util.Try

/** A Dutch auction's terms: basis points of the repayment, and a length in blocks. */
case class AuctionTerms(openingBps: Long, floorBps: Long, length: Long) {
  def longs: Seq[Long] = Seq(openingBps, floorBps, length)
}

/**
 * What a share request commits to: N shares, the batching fees, the share token's EIP-4, and an auction. The
 * count defaults to zcs1's 1,000,000; a name, description or decimals left out is zcs1's (StandardMetadata).
 */
case class ShareTerms(shares: Long = StandardMetadata.ShareCount, pullFee: Long, mintFee: Long,
                      name: Option[String] = None, description: Option[String] = None, decimals: Option[String] = None,
                      auction: Option[AuctionTerms] = None) {
  def longs: Seq[Long] = Seq(shares, pullFee, mintFee) ++ auction.toSeq.flatMap(_.longs)

  /** The (name, description, decimals) to commit: `standard`, with any field given here in its place. */
  def metadata(standard: Seq[String]): Seq[String] =
    Seq(name.getOrElse(standard(0)), description.getOrElse(standard(1)), decimals.getOrElse(standard(2)))

  /** Where these terms depart from zcs1, for a command's notes. */
  def departures(standard: Seq[String]): Seq[String] =
    (if (shares != StandardMetadata.ShareCount)
      Seq(s"NONSTANDARD share count: zcs1 uses ${StandardMetadata.ShareCount}, so that 1.000000 is one bond") else Seq.empty) ++
      (if (metadata(standard) != standard) Seq(s"NONSTANDARD metadata: zcs1 writes '${standard(0)}', '${standard(1)}', " +
        s"${standard(2)} decimals") else Seq.empty)
}

/**
 * Fractional and Dutch auction shares: depositing a bond unit into a mint request, minting (anyone, for the
 * mint fee), pulling a settled bond into the share vault (anyone, for the pull fee), buying a Dutch
 * auction's lot (anyone), and claiming with shares.
 */
trait StandardShares extends AppBase { self: StandardApp =>

  import StandardProtocol._

  def shareRequests(): Seq[StdShareRequest] =
    (decoded(nodeApi.unspentByErgoTree(protocol.contracts.fractionalMint.ergoTreeHex)) ++
      decoded(nodeApi.unspentByTemplateHash(Base16.encode(protocol.dutchMintTemplateHash)))).flatMap(protocol.asShareRequest)

  def shareVaults(): Seq[StdShareVault] =
    (decoded(nodeApi.unspentByErgoTree(protocol.contracts.fractionalVault.ergoTreeHex)) ++
      decoded(nodeApi.unspentByErgoTree(protocol.contracts.dutchVault.ergoTreeHex))).flatMap(protocol.asShareVault)

  /** A share's vault: the one box holding N+1 of it. */
  def shareVault(shareId: ErgoId): Option[StdShareVault] =
    decoded(nodeApi.unspentByTokenId(shareId.toString)).flatMap(protocol.asShareVault).find(_.shareId == shareId)

  /** The market a bond asset belongs to, read from the order that minted it, which also proves it genuine. */
  def assetMarket(asset: ErgoId): Option[Market] =
    nodeApi.boxById(asset.toString).filter(j => NodeJson.spentBy(j).isDefined)
      .flatMap(j => Try(NodeJson.box(j)).toOption).flatMap(b => protocol.orderMarket(b.input.getErgoTree))

  /**
   * The box holding a bond asset's 2-unit reserve: the live bond, or the vault its settlement paid. A Dutch
   * mint reads the repayment from it; for a settled bond, from the bond box the vault names.
   */
  private def bondState(asset: ErgoId): (InputUTXO, Long, Option[Array[Byte]]) = {
    val holders = decoded(nodeApi.unspentByTokenId(asset.toString)).filter(_.tokens.headOption.contains(Token(asset, 2L)))
    holders.flatMap(protocol.asBond).headOption match {
      case Some(bond) => (bond.box, bond.repayment, None)
      case None =>
        val settled = holders.flatMap(protocol.asSettlement).headOption
          .getOrElse(fail(s"no live bond or bond vault holds the reserve of $asset"))
        val bondBox = nodeApi.boxById(Base16.encode(settled.bondId)).flatMap(j => Try(NodeJson.box(j)).toOption)
          .getOrElse(fail("the node's index has no record of the settled bond box"))
        val repayment = longsReg(bondBox, 6).filter(_.nonEmpty).map(_(0)).getOrElse(fail("the settled bond has no terms"))
        (settled.box, repayment, Some(bondBox.bytes))
    }
  }

  // ═══ REQUEST, MINT, CANCEL ════════════════════════════════════════════════

  /**
   * Deposit a bond unit this wallet holds into a mint request, owned by this wallet. Any batcher then mints
   * N shares to this wallet for the mint fee. With auction terms, the request is the market's Dutch mint.
   */
  def fractionalize(assetId: String, t: ShareTerms): Prepared = {
    val asset = Try(ErgoId.create(assetId)).getOrElse(fail(s"$assetId is not a token id"))
    if (!walletHolds(asset)) fail(s"this wallet does not hold bond asset $assetId")
    if (t.shares < 1L || t.shares > 1000000000L) fail("the share count must be 1 to 1000000000")
    if (t.pullFee < MinBatchingFee || t.mintFee < MinBatchingFee) fail(s"the pull and mint fees must each be at least ${Fmt.erg(MinBatchingFee)}")
    val market = assetMarket(asset).getOrElse(fail(s"$assetId was not minted by a standard order"))
    t.auction.foreach { a =>
      if (a.floorBps < 0L || a.floorBps > a.openingBps || a.openingBps > 1000000L) fail("the auction needs 0 <= floor <= opening <= 10000%")
      if (a.length < 1L || a.length > 1000000L) fail("the auction length must be 1 to 1000000 blocks")
    }
    val script = if (t.auction.isDefined) protocol.market(market).dutchMint else protocol.contracts.fractionalMint
    // zcs1 names the bond in the shares' metadata when the writer knows it, as here.
    val standard = StandardMetadata.shares(market, t.auction,
      bondRecord(asset).map(b => StandardMetadata.ShareBond(asset, b.repayment, b.maturity)))
    val metadata = t.metadata(standard)
    // Registers are dense: R4 to R6 carry a copy of the metadata so R7 to R9 can be set.
    val request = UTXO(script, 2L * MinBox + t.pullFee + t.mintFee, Seq(Token(asset, 1L)),
      metadata.map(utf8Value) ++ Seq(bytesValue(hashOf(myTree)), longsValue(t.longs: _*), textsValue(metadata: _*)))
    prepare(s"Fractionalize $assetId", plan(Seq.empty, Seq(request)), Seq(
      s"${t.shares} shares of bond $asset ($market), named '${metadata(0)}' with ${metadata(2)} decimals") ++
      t.departures(standard) ++ Seq(
      t.auction.fold("no auction: after a liquidation, holders are paid in kind")(a =>
        s"Dutch auction after a liquidation: from ${Units.percent(a.openingBps)} down to ${Units.percent(a.floorBps)} " +
          s"of the repayment over ${a.length} blocks"),
      s"fees committed: mint ${Fmt.erg(t.mintFee)}, pull ${Fmt.erg(t.pullFee)}",
      "Output 0 is the request; any batcher can mint it with `mint-shares <its box id>`."))
  }

  /**
   * How a share token's metadata departs from zcs1, read from the request that minted it: its R9 must be
   * the template with or without its bond, its count 1,000,000, and its issuing box (the mint's OUTPUTS(0))
   * must leave R7 to R9 empty. Empty when it matches.
   */
  def shareMetadataIssues(shareId: ErgoId): Seq[String] = {
    val request = nodeApi.boxById(shareId.toString).flatMap(j => Try(NodeJson.box(j)).toOption).flatMap(protocol.asShareRequest)
    request.fold(Seq("cannot read the request that minted it")) { req =>
      val issuing = nodeApi.boxById(shareId.toString).flatMap(NodeJson.spentBy).flatMap(nodeApi.transactionById)
        .flatMap(_.hcursor.downField("outputs").focus.flatMap(_.asArray).flatMap(_.headOption))
        .flatMap(j => Try(NodeJson.box(j)).toOption)
      requestMetadataIssues(req) ++ issuing.filter(_.registers.size > 3)
        .map(_ => "its issuing box sets R7 to R9, which zcs1 leaves empty")
    }
  }

  /** How a share request's committed count and metadata depart from zcs1. Empty when they match. */
  def requestMetadataIssues(req: StdShareRequest): Seq[String] = {
    val market = req.dutch.orElse(assetMarket(req.bondAsset))
    val auction = req.dutch.map(_ => AuctionTerms(req.auction(0), req.auction(1), req.auction(2)))
    val count = if (req.shares != StandardMetadata.ShareCount)
      Seq(s"its share count is ${req.shares}, not zcs1's ${StandardMetadata.ShareCount}") else Seq.empty
    count ++ market.fold(Seq("its bond asset was not minted by a standard order")) { m =>
      val generic = StandardMetadata.shares(m, auction, None)
      val named = bondRecord(req.bondAsset).map(b =>
        StandardMetadata.shares(m, auction, Some(StandardMetadata.ShareBond(req.bondAsset, b.repayment, b.maturity))))
      val found = req.metadata.map(StandardMetadata.text)
      if (found == generic || named.contains(found)) Seq.empty
      else Seq(s"its name is '${found.headOption.getOrElse("")}', not zcs1's '${named.getOrElse(generic).head}'" +
        (if (found.lift(2).exists(_ != StandardMetadata.ShareDecimals)) s" (and ${found(2)} decimals, not 6)" else ""))
    }
  }

  /** Mint a request's shares: N to its owner, N+1 locked in the vault with the bond unit. Earns the mint fee. */
  def mintShares(requestId: String): Prepared = {
    val req = protocol.asShareRequest(unspent(requestId)).getOrElse(fail(s"$requestId is not a share mint request"))
    if (req.shares < 1L || req.shares > 1000000000L || req.pullFee < MinBatchingFee || req.mintFee < MinBatchingFee ||
      req.pullFee > req.box.value || req.mintFee > req.box.value - req.pullFee)
      fail("the request's terms are outside its contract's bounds, so only its owner can cancel it")
    val owner = ownerTree(req.ownerHash, req.box, "share owner")
    val shareId = req.shareId
    val shares = UTXO(Contract(owner), MinBox, Seq(Token(shareId, req.shares)), req.metadata.map(bytesValue))
    val vaultValue = req.box.value - req.mintFee - MinBox
    val fixedRegs = Seq(bytesValue(req.bondAsset.getBytes), longsValue(req.shares, req.shares, req.pullFee))
    val (vault, input, data) = req.dutch match {
      case None =>
        (UTXO(protocol.contracts.fractionalVault, vaultValue, Seq(Token(shareId, req.shares + 1L), Token(req.bondAsset, 1L)),
          fixedRegs), req.box, Seq.empty[InputUTXO])
      case Some(m) =>
        val (state, repayment, settledBond) = bondState(req.bondAsset)
        val regs = fixedRegs ++ Seq(ErgoValue.of(0.toByte), longsValue(repayment +: req.auction :+ 0L: _*), bytesValue(m.tokenBytes))
        val in = settledBond.foldLeft(req.box.withCtxVar(0.toByte, intValue(0)))((b, bytes) => b.withCtxVar(1.toByte, bytesValue(bytes)))
        (UTXO(protocol.contracts.dutchVault, vaultValue, Seq(Token(shareId, req.shares + 1L), Token(req.bondAsset, 1L)), regs),
          in, Seq(state))
    }
    prepare(s"Mint shares from $requestId", plan(Seq(input), Seq(shares, vault), minted = Some(shareId), dataInputs = data),
      Seq(s"${req.shares} shares $shareId to the owner; ${req.shares + 1} locked in the vault with bond unit ${req.bondAsset}",
        s"you keep the ${Fmt.erg(req.mintFee)} mint fee, less the miner fee"))
  }

  def cancelShareRequest(requestId: String): Prepared = {
    val req = protocol.asShareRequest(unspent(requestId)).getOrElse(fail(s"$requestId is not a share mint request"))
    prepare(s"Cancel share request $requestId", sweep(Seq(req.box, walletBoxAt(req.ownerHash, "owner"))))
  }

  // ═══ PULL, SALE ═══════════════════════════════════════════════════════════

  /**
   * Pull a settled bond's vault into the share vault holding its unit; all 3 bond units burn. A liquidated
   * bond's lot opens a Dutch auction if the vault holds less currency than the floor. Earns the pull fee.
   */
  def pull(vaultId: String): Prepared = {
    val v = protocol.asShareVault(unspent(vaultId)).getOrElse(fail(s"$vaultId is not a share vault"))
    if (!v.holding) fail("the vault has already pulled its bond's settlement")
    val source = settlements().find(_.asset == v.bondAsset)
      .getOrElse(fail(s"bond ${v.bondAsset} has not been repaid or liquidated yet"))
    if (v.pullFee < 0L || v.pullFee > v.box.value) fail("the vault's pull fee is out of bounds")
    val value = v.box.value + source.box.value - v.pullFee
    val tokens = Token(v.shareId, v.n + 1L) +: source.proceeds
    val supply = Seq(bytesValue(v.bondAsset.getBytes), longsValue(v.n, v.outstanding, v.pullFee))
    val (regs, note) =
      if (!v.dutch) (supply :+ ErgoValue.of((if (source.liquidated) 1 else 0).toByte),
        if (source.liquidated) "liquidated: holders are paid in kind" else "repaid")
      else {
        val currency = v.currencyToken
        val held = currency.fold(value)(id => source.proceeds.filter(_.id == id).map(_.amount).sum)
        val hasLot = currency.isDefined || source.proceeds.nonEmpty
        val short = BigInt(held) * 10000 < BigInt(v.auction(0)) * v.auction(2)
        val state: Byte = if (!source.liquidated) 0 else if (hasLot && short) 1 else 2
        val began = if (state == 1) height.toLong else 0L
        (supply ++ Seq(ErgoValue.of(state), longsValue(v.auction.updated(4, began): _*), bytesValue(v.currency.get)),
          state match {
            case 0 => "repaid"
            case 1 => s"liquidated: a Dutch auction of the lot opens at height $began"
            case _ => "liquidated: the vault already holds the floor, or there is no lot; holders are paid in kind"
          })
      }
    val next = UTXO(Contract(v.box.input.getErgoTree), value, tokens, regs)
    prepare(s"Pull bond ${v.bondAsset} into $vaultId",
      plan(Seq(v.box.withCtxVar(0.toByte, intValue(0)), source.box), Seq(next), burn = Seq(Token(v.bondAsset, 3L))),
      Seq(note, s"you keep the ${Fmt.erg(v.pullFee)} pull fee, less the miner fee"))
  }

  /** The least currency a Dutch vault must hold after a sale at `at`, in its currency's raw units. */
  def lotTarget(v: StdShareVault, at: Long): Long = {
    val Seq(repayment, opening, floor, length, began) = v.auction
    val since = math.min(math.max(at - began, 0L), length)
    val num = BigInt(repayment) * (BigInt(opening) * length - BigInt(opening - floor) * since)
    val den = BigInt(10000) * length
    ((num + den - 1) / den).toLong
  }

  /** What a Dutch vault holds of its currency now. */
  def currencyHeld(v: StdShareVault): Long =
    v.currencyToken.fold(v.box.value)(id => v.assets.filter(_.id == id).map(_.amount).sum)

  /**
   * Buy a Dutch auction's lot: bring the vault's currency up to the current target and take everything else.
   * The target falls with height, so a purchase priced now stays valid later.
   */
  def buyLot(vaultId: String): Prepared = {
    val v = protocol.asShareVault(unspent(vaultId)).getOrElse(fail(s"$vaultId is not a share vault"))
    if (!v.auctionOpen) fail("the vault has no open auction")
    if (v.auction(3) < 1L) fail("the auction has no length")
    val before = currencyHeld(v)
    val after = math.max(lotTarget(v, height.toLong), before)
    val m = Market.of(v.currencyToken).getOrElse(fail("the vault's currency is not a market this client knows"))
    val regs = v.box.registers.updated(2, ErgoValue.of(2.toByte))
    val sold = v.currencyToken match {
      case None => UTXO(Contract(v.box.input.getErgoTree), after, Seq(v.box.tokens.head), regs)
      case Some(id) => UTXO(Contract(v.box.input.getErgoTree), MinBox, Seq(v.box.tokens.head, Token(id, after)), regs)
    }
    val lot = v.currencyToken.fold(v.assets)(id => v.assets.filterNot(_.id == id))
    prepare(s"Buy the lot of $vaultId", plan(Seq(v.box.withCtxVar(0.toByte, intValue(0))), Seq(sold)),
      Seq(s"pay ${Units.amount(after - before, m)} into the vault (it then holds ${Units.amount(after, m)})",
        s"receive the lot: ${Fmt.contents(v.currencyToken.fold(0L)(_ => v.box.value - MinBox), lot)}"))
  }

  // ═══ CLAIMS ═══════════════════════════════════════════════════════════════

  /**
   * Claim with shares this wallet holds. Holding every outstanding share is a final claim: the vault and its
   * reserve go too, before the pull taking the bond unit itself. Otherwise a partial claim pays the burned
   * fraction of each asset, rounded down, and waits for a Dutch auction to run its length.
   */
  def claimShares(shareId: String, amount: Option[Long]): Prepared = {
    val id = Try(ErgoId.create(shareId)).getOrElse(fail(s"$shareId is not a token id"))
    val v = shareVault(id).getOrElse(fail(s"no share vault holds the reserve of $shareId"))
    val boxes = walletBoxes.filter(_.tokens.exists(_.id == id))
    val held = boxes.flatMap(_.tokens).filter(_.id == id).map(_.amount).sum
    if (held == 0L) fail(s"this wallet holds no $shareId")
    val tag = Seq(bytesValue(v.box.id.getBytes))
    if (held >= v.outstanding && amount.forall(_ >= v.outstanding)) {
      val payout = UTXO(Contract(myTree), math.max(v.box.value, MinBox), v.assets, tag)
      prepare(s"Final claim on $shareId", plan(v.box.withCtxVar(1.toByte, intValue(0)) +: boxes, Seq(payout),
        burn = Seq(Token(id, v.outstanding + v.n + 1L))),
        Seq(s"burns all ${v.outstanding} outstanding shares and the vault's reserve; takes " +
          Fmt.contents(v.box.value, v.assets) + (if (v.holding) " (the bond unit itself: the bond is not settled yet)" else "")))
    } else {
      val burned = amount.getOrElse(held)
      if (burned < 1L || burned > held) fail(s"claim 1 to $held shares")
      if (burned >= v.outstanding) fail(s"$burned is every outstanding share: that is a final claim, which needs them all")
      if (v.holding) fail("the bond has not settled into the vault yet: pull it first, or make a final claim with every share")
      val ended = height.toLong + 1L >= v.auction.lift(4).getOrElse(0L) + v.auction.lift(3).getOrElse(0L)
      if (v.auctionOpen && !ended) fail(s"the auction runs until height ${v.auction(4) + v.auction(3)}; claims wait for it")
      def part(n: Long): Long = (BigInt(n) * burned / v.outstanding).toLong
      val paidErg = part(v.box.value)
      // A token's part can round to zero, and a token box cannot hold zero of one: drop it before building.
      val paid = v.assets.flatMap(t => Some(part(t.amount)).filter(_ > 0L).map(Token(t.id, _)))
      val kept = v.assets.flatMap(t => Some(t.amount - part(t.amount)).filter(_ > 0L).map(Token(t.id, _)))
      val state = if (v.auctionOpen) Some(3.toByte) else v.state
      val regs = v.box.registers.updated(1, longsValue(v.n, v.outstanding - burned, v.pullFee))
        .patch(2, state.map(s => ErgoValue.of(s)).toSeq, if (v.box.registers.size > 2) 1 else 0)
      val next = UTXO(Contract(v.box.input.getErgoTree), v.box.value - paidErg, Token(id, v.n + 1L) +: kept, regs)
      val payout = UTXO(Contract(myTree), math.max(paidErg, MinBox), paid, tag)
      // zcs1: warn when a holding rounds to nothing; it stays in the vault for later claimants.
      val rounded = v.assets.filter(t => part(t.amount) == 0L).map { t =>
        s"WARNING: this claim takes none of ${t.id} (${t.amount} held); claim at least " +
          s"${(BigInt(v.outstanding) + t.amount - 1) / t.amount} shares to receive some"
      }
      prepare(s"Claim $burned of $shareId",
        plan(v.box.withCtxVar(0.toByte, intValue(1)).withCtxVar(1.toByte, intValue(0)) +: boxes, Seq(payout, next),
          burn = Seq(Token(id, burned))),
        Seq(s"burns $burned of ${v.outstanding} outstanding shares; takes ${Fmt.contents(paidErg, paid)}" +
          (if (v.auctionOpen) "; this closes the unsold auction, so holders take the lot in kind" else "")) ++ rounded)
    }
  }
}
