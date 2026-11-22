package org.lithosprotocol.sigmafi
package specs.bonds

import ksingh.sigmabonds.client.ScriptGenerator
import org.ergoplatform.appkit._
import org.ergoplatform.sdk.ErgoId
import sigma.Colls
import work.lithos.mutations.{Contract, InputUTXO, Token, UTXO}

import java.math.BigInteger
import scala.collection.JavaConverters._

/**
 * FI_Standard_FractionalVault and the share mint that creates it. The mint is FI_Standard_FractionalMint:
 * ZC_Standard_FractionalMint with a 0.1 ERG minimum pull fee, compiled with the FI vault's hash as
 * CONST_FRACTIONAL_VAULT_HASH. `zcMintFor` is ZC's own mint bound to the FI vault, which the FI vault
 * also runs (smaller fee, no lock). The FI vault pins the coupon pool's hash (CONST_COUPON_VAULT_HASH) and
 * the repayment token (CONST_REPAYMENT_TOKEN), so there is one vault and one mint per repayment asset.
 */
object FiStandardSharesSpecBase {
  val VaultPath = "bonds/fi/FI_Standard_FractionalVault"
  val MintPath = "bonds/fi/FI_Standard_FractionalMint"

  /**
   * The FI fractional vault compiled from its file with `edits` applied, pinning `pool` as the coupon pool and
   * `token` as the repayment token (empty for ERG).
   */
  def vaultFor(ctx: BlockchainContext, pool: Contract, token: Array[Byte], edits: (String, String)*): Contract = synchronized {
    Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create()
      .item("CONST_COUPON_VAULT_HASH", Colls.fromArray(pool.hashedPropBytes))
      .item("CONST_REPAYMENT_TOKEN", Colls.fromArray(token)).build(),
      FiStandardSpecBase.edited(ScriptGenerator.mkScript(VaultPath), edits)))
  }

  /** FI_Standard_FractionalMint (or `path`), bound to `vault`. */
  def mintFor(ctx: BlockchainContext, vault: Contract, path: String = MintPath): Contract = synchronized {
    Contract.fromErgoContract(ctx.compileContract(ConstantsBuilder.create()
      .item("CONST_FRACTIONAL_VAULT_HASH", Colls.fromArray(vault.hashedPropBytes)).build(),
      ScriptGenerator.mkScript(path)))
  }

  /** ZC_Standard_FractionalMint's own source, bound to `vault`. */
  def zcMintFor(ctx: BlockchainContext, vault: Contract): Contract =
    mintFor(ctx, vault, "bonds/lender-scripts/ZC_Standard_FractionalMint")

  /** `vault` and `mint` for SigUSD repayment; `ergVault` and `ergMint` for ERG repayment. */
  case class Shares(vault: Contract, mint: Contract, ergVault: Contract, ergMint: Contract)

  private var cache: Option[Shares] = None

  def compiled(ctx: BlockchainContext): Shares = synchronized {
    cache.getOrElse {
      val pool = FiStandardSpecBase.compiled(ctx).couponVault
      val vault = vaultFor(ctx, pool, ZcStandardSpecBase.SigUSD.getBytes)
      val ergVault = vaultFor(ctx, pool, Array.emptyByteArray)
      val s = Shares(vault, mintFor(ctx, vault), ergVault, mintFor(ctx, ergVault))
      specs.harness.ContractSpecBase.nameScript(s.vault, "FI FractionalVault SigUSD")
      specs.harness.ContractSpecBase.nameScript(s.mint, "FI FractionalMint SigUSD")
      specs.harness.ContractSpecBase.nameScript(s.ergVault, "FI FractionalVault ERG")
      specs.harness.ContractSpecBase.nameScript(s.ergMint, "FI FractionalMint ERG")
      cache = Some(s)
      s
    }
  }
}

/**
 * Builders for an FI unit fractionalized into shares: the mint request, the mint, Absorb (the vault spent
 * beside coupon pools), Pull (with or without pools), and the claims. Every expected successor is computed
 * here from the rule the header states, so a spec compares the contract against the documented layout, not
 * against itself.
 */
trait FiStandardSharesSpecBase extends FiStandardSpecBase {
  import BondSpecBase._
  import ZcStandardSpecBase._

  protected def shares(ctx: BlockchainContext): FiStandardSharesSpecBase.Shares = FiStandardSharesSpecBase.compiled(ctx)
  /** The SigUSD-repayment vault. */
  protected def fv(ctx: BlockchainContext): Contract = shares(ctx).vault
  /** The vault and the mint for this world's repayment asset. */
  protected def fvOf(w: W): Contract = if (w.erg) shares(w.ctx).ergVault else shares(w.ctx).vault
  protected def mintOf(w: W): Contract = if (w.erg) shares(w.ctx).ergMint else shares(w.ctx).mint

  protected val N: Long = 1000L
  /** FI_Standard_FractionalMint's minimum pull fee, 0.1 ERG. */
  protected val pullFee: Long = 100000000L
  protected val mintFee: Long = 2000000L
  protected val shareMeta: Seq[String] = Seq("FI shares", "Shares of one FI bond", "0")

  protected def third(ctx: BlockchainContext): ErgoProver = proverWith(ctx, BigInteger.valueOf(5005L))

  // ─── the mint ─────────────────────────────────────────────────────────────

  /** The lender's request: the unit, the owner hash, (N, pull fee, mint fee), the share metadata. */
  protected def requestBox(w: W, asset: ErgoId = bondAsset, n: Long = N, mint: Contract = null, fee: Long = pullFee): UTXO =
    UTXO(if (mint == null) mintOf(w) else mint, minBox + fee + minBox + mintFee, Seq(Token(asset, 1L)),
      eip4() ++ Seq(bytesValue(hashOf(lenderTree(w.ctx))), longsValue(n, fee, mintFee), textsValue(shareMeta: _*)))

  protected def requestIn(w: W, created: Int, asset: ErgoId = bondAsset, index: Int = 40, mint: Contract = null,
                          fee: Long = pullFee): InputUTXO =
    inputAt(requestBox(w, asset, mint = mint, fee = fee).setCreationHeight(created), w.ctx, index)

  protected def shareBox(w: W, shareId: ErgoId, n: Long = N): UTXO =
    UTXO(lenderTree(w.ctx), minBox, Seq(Token(shareId, n)), shareMeta.map(utf8))

  protected def freshVault(w: W, req: InputUTXO, asset: ErgoId = bondAsset, n: Long = N, vault: Contract = null,
                           fee: Long = pullFee): UTXO =
    UTXO(if (vault == null) fvOf(w) else vault, req.value - mintFee - minBox,
      Seq(Token(req.id, n + 1L), Token(asset, 1L)), Seq(bytesValue(asset.getBytes), longsValue(n, n, fee)))

  /** The request is the only input: the share id is its box id. Outputs: 0 the shares, 1 the vault. */
  protected def mintTx(w: W, req: InputUTXO, h: Int, vault: UTXO = null): Tx =
    txAt(w.ctx, h, Seq(req), Seq(shareBox(w, req.id), if (vault == null) freshVault(w, req) else vault),
      executor(w.ctx).getAddress)

  protected case class Minted(shareId: ErgoId, vault: InputUTXO, held: InputUTXO, tx: SignedTransaction)

  protected def minted(w: W, created: Int, h: Int, asset: ErgoId = bondAsset, index: Int = 40): Minted = {
    val req = requestIn(w, created, asset, index)
    val s = ok(executor(w.ctx), mintTx(w, req, h))
    Minted(req.id, spendOutput(s, 1), spendOutput(s, 0), s)
  }

  // ─── absorb ───────────────────────────────────────────────────────────────

  protected def sumOf(ts: Seq[Token], id: ErgoId): Long = ts.filter(_.id == id).map(_.amount).sum

  /** The repayment token a vault script pins: SigUSD, or none for the ERG vault (read from its tree). */
  protected def couponIdOf(c: Contract): Option[ErgoId] =
    if (c.ergoTreeHex.contains(org.bouncycastle.util.encoders.Hex.toHexString(SigUSD.getBytes))) Some(SigUSD) else None

  /** The nanoERG the caller keeps out of `pools`. */
  protected def ergCut(pools: Seq[InputUTXO]): Long = cut(pools.map(_.value).sum)

  /**
   * The coupon entry: (repayment token, its sum over `ts` less the cut of `pooled`, its sum over the pools), when
   * above 0.
   */
  protected def couponEntry(id: Option[ErgoId], ts: Seq[Token], pooled: Seq[Token] = Seq.empty): Seq[Token] =
    id.map(i => i -> (sumOf(ts, i) - cut(sumOf(pooled, i)))).filter(_._2 > 0L).map { case (i, n) => Token(i, n) }.toSeq

  /** The reserve and the unit, then the coupon entry over the vault and the pools, less the cut of the pools. */
  protected def absorbedTokens(own: Seq[Token], pool: Seq[Token], id: Option[ErgoId] = Some(SigUSD)): Seq[Token] =
    own.take(2) ++ couponEntry(id, own ++ pool, pool)

  /** The absorb's successor: the vault plus the pools, less the caller's cut (and less `kept` more nanoERG). */
  protected def absorbedAll(vault: InputUTXO, pools: Seq[InputUTXO], c: Contract = null): UTXO =
    UTXO(if (c == null) vault.contract else c, vault.value + pools.map(_.value).sum - ergCut(pools),
      absorbedTokens(vault.tokens, pools.flatMap(_.tokens), couponIdOf(vault.contract)), vault.registers.take(2))

  protected def absorbed(vault: InputUTXO, pool: InputUTXO, c: Contract = null): UTXO = absorbedAll(vault, Seq(pool), c)

  /** Anyone: the vault (var 0 = output 0) and the pools in, the successor out; the caller pays the miner fee. */
  protected def absorbTx(w: W, vault: InputUTXO, pools: Seq[InputUTXO], h: Int, next: UTXO = null,
                         extraIns: Seq[InputUTXO] = Seq.empty, extraOuts: Seq[UTXO] = Seq.empty,
                         by: ErgoProver = null, index: Int = 0, burn: Seq[Token] = Seq.empty,
                         callerTokens: Seq[Token] = Seq.empty, walletCreated: Int = -1): Tx = {
    val p = if (by == null) executor(w.ctx) else by
    txAt(w.ctx, h, (vault.withCtxVar(0.toByte, int(index)) +: pools) ++ extraIns :+
      wallet(w.ctx, p, 7, if (walletCreated < 0) h else walletCreated, callerTokens),
      (if (next == null) absorbedAll(vault, pools) else next) +: extraOuts, p.getAddress, burn = burn)
  }

  // ─── pull ─────────────────────────────────────────────────────────────────

  /**
   * Reserve, then the coupon entry over the vault, the proceeds and the pools (less the cut of the pools), then the
   * proceeds' other tokens.
   */
  protected def pulledTokens(vault: InputUTXO, source: InputUTXO, pools: Seq[InputUTXO] = Seq.empty): Seq[Token] = {
    val id = couponIdOf(vault.contract)
    val proceeds = source.tokens.drop(1)
    val pooled = pools.flatMap(_.tokens)
    vault.tokens.head +: (couponEntry(id, vault.tokens ++ proceeds ++ pooled, pooled) ++
      proceeds.filterNot(u => id.contains(u.id)))
  }

  /** The pull's successor: everything in, less `keep` (default the pull fee) and the cut of the pools. */
  protected def pulledBox(vault: InputUTXO, source: InputUTXO, keep: Long = pullFee, pools: Seq[InputUTXO] = Seq.empty): UTXO =
    UTXO(vault.contract, vault.value + source.value + pools.map(_.value).sum - keep - ergCut(pools),
      pulledTokens(vault, source, pools), vault.registers.take(2) :+ source.registers(1))

  /** The pull opens this many blocks after the settlement's creation height. */
  protected val PullWait: Int = 720

  /**
   * The two vaults alone: the pull fee is the executor's miner fee and reward. All 3 units burn. With `pools`
   * the successor takes them too, less the cut, and the puller still keeps the pull fee; the executor's own box
   * pays the miner fee.
   */
  protected def pullTx(w: W, vault: InputUTXO, source: InputUTXO, h: Int, out: UTXO = null,
                       extraIns: Seq[InputUTXO] = Seq.empty, asset: ErgoId = bondAsset,
                       pools: Seq[InputUTXO] = Seq.empty, by: ErgoProver = null): Tx = {
    val p = if (by == null) executor(w.ctx) else by
    txAt(w.ctx, h, Seq(vault.withCtxVar(0.toByte, int(0)), source) ++ pools ++ extraIns ++
      (if (pools.nonEmpty) Seq(wallet(w.ctx, p, 7, h)) else Seq.empty),
      Seq(if (out != null) out else pulledBox(vault, source, pools = pools)),
      p.getAddress, burn = Seq(Token(asset, 3L)))
  }

  // ─── claims ───────────────────────────────────────────────────────────────

  protected def part(amount: Long, burned: Long, outstanding: Long): Long = (BigInt(amount) * burned / outstanding).toLong

  protected def outstandingOf(vault: InputUTXO): Long =
    vault.registers(1).getValue.asInstanceOf[sigma.Coll[Long]].toArray.apply(1)

  /** Outputs: 0 the payout (exactly the pro-rata part), 1 the continuation. Leftover shares are change. */
  protected def partialTx(w: W, vault: InputUTXO, held: InputUTXO, burned: Long, by: ErgoProver, h: Int,
                          payout: UTXO = null, next: UTXO = null, walletCreated: Int = -1): Tx = {
    val outstanding = outstandingOf(vault)
    val assets = vault.tokens.drop(1)
    val paidERG = part(vault.value, burned, outstanding)
    val paid = assets.map(t => t.id -> part(t.amount, burned, outstanding)).filter(_._2 > 0L).map { case (i, a) => Token(i, a) }
    val kept = assets.map(t => t.id -> (t.amount - part(t.amount, burned, outstanding))).filter(_._2 > 0L)
      .map { case (i, a) => Token(i, a) }
    val pay = tagged(contractOf(by), paidERG, vault.id, paid)
    val cont = UTXO(vault.contract, vault.value - paidERG, vault.tokens.head +: kept,
      Seq(vault.registers.head, longsValue(N, outstanding - burned, pullFee)) ++ vault.registers.drop(2))
    txAt(w.ctx, h, Seq(vault.withCtxVar(0.toByte, int(1)).withCtxVar(1.toByte, int(0)), held,
      wallet(w.ctx, by, 9, if (walletCreated < 0) h else walletCreated)),
      Seq(if (payout == null) pay else payout, if (next == null) cont else next), by.getAddress,
      burn = Seq(Token(vault.tokens.head.id, burned)))
  }

  /** Every outstanding share and the reserve burn; the payout takes the whole vault. */
  protected def finalTx(w: W, vault: InputUTXO, held: InputUTXO, by: ErgoProver, h: Int,
                        payout: UTXO = null, burn: Long = -1L): Tx =
    txAt(w.ctx, h, Seq(vault.withCtxVar(1.toByte, int(0)), held, wallet(w.ctx, by, 9, h)),
      Seq(if (payout == null) tagged(contractOf(by), vault.value, vault.id, vault.tokens.drop(1)) else payout),
      by.getAddress,
      burn = Seq(Token(vault.tokens.head.id, if (burn < 0L) outstandingOf(vault) + N + 1L else burn)))

  /** What one signed transaction paid to `to`'s planned outputs: nanoERG and each token. */
  protected def paidTo(s: SignedTransaction, planned: Int, to: Contract): (Long, Map[ErgoId, Long]) = {
    val outs = (0 until planned).map(s.getOutputsToSpend.get).filter(_.getErgoTree == to.ergoTree)
    (outs.map(_.getValue.longValue).sum,
      outs.flatMap(_.getTokens.asScala).groupBy(_.getId).map { case (id, ts) => id -> ts.map(_.getValue.longValue).sum })
  }

  /** Split the lender's shares box: `amounts` to each prover (the lender's own share last, as change). */
  protected def splitTx(w: W, held: InputUTXO, to: Seq[(ErgoProver, Long)], h: Int): Tx = {
    val id = held.tokens.head.id
    txAt(w.ctx, h, Seq(held, wallet(w.ctx, lender(w.ctx), 8, h)),
      to.map { case (p, a) => UTXO(contractOf(p), minBox, Seq(Token(id, a))) }, lender(w.ctx).getAddress)
  }

  // ─── boxes made directly, for the refresh and attack specs ───────────────

  protected val handShare: ErgoId = ErgoId.create("5a" * 32)

  /** A vault box in a given state, created at `created`; `path` adds R6 (after the pull). */
  protected def vaultBox(ctx: BlockchainContext, value: Long, tokens: Seq[Token], created: Int,
                         outstanding: Long = N, path: Option[Byte] = None, asset: ErgoId = bondAsset,
                         c: Contract = null, index: Int = 30): InputUTXO =
    inputAt(UTXO(if (c == null) fv(ctx) else c, value, tokens,
      Seq(bytesValue(asset.getBytes), longsValue(N, outstanding, pullFee)) ++ path.map(b => ErgoValue.of(b)).toSeq,
      Some(created)), ctx, index)

  /** A settled ZC_Standard_BondVault box for `asset`, created at `created`. */
  protected def settledIn(ctx: BlockchainContext, created: Int, tokens: Seq[Token], value: Long, path: Byte,
                          asset: ErgoId = bondAsset, index: Int = 31): InputUTXO =
    inputAt(UTXO(scripts(ctx).vault, value, Token(asset, 2L) +: tokens,
      Seq(bytesValue(ErgoId.create("77" * 32).getBytes), ErgoValue.of(path)), Some(created)), ctx, index)

  /** `k` distinct token ids nobody else uses. */
  protected def junk(k: Int, amount: Long = 1L, salt: Int = 0): Seq[Token] =
    (0 until k).map(i => Token(ErgoId.create(f"${0xe0 + salt}%02x" + f"$i%062x"), amount))
}
